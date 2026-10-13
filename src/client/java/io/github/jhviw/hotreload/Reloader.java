package io.github.jhviw.hotreload;

import io.github.jhviw.hotreload.Messages.Level;
import io.github.jhviw.hotreload.Projects.Project;
import io.github.jhviw.hotreload.setup.HotReloadPaths;
import io.github.jhviw.hotreload.setup.Jbr;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * Polls the build folders of the watched projects. When a compile finishes (no file changes for {@link #SETTLE_MS}),
 * on the render thread:
 * <ul>
 * <li>a project whose mod is not loaded, built after the game started, is loaded as a new mod ({@link ModHotLoader})</li>
 * <li>a changed, loaded class is redefined; objects keep their fields, methods run the new code from the next call</li>
 * <li>a changed mixin is re-read by Mixin, and every already-loaded target is rebuilt from its pre-mixin bytes with
 * all mixins (every mod's) re-applied, then redefined</li>
 * <li>a changed class that is not loaded yet gets the new bytes when it loads; brand-new classes are found via the
 * build folder, which is added to the class path</li>
 * <li>changed resources are served on top of the jar's as a resource pack, followed by a resource reload</li>
 * </ul>
 * A stock JVM only accepts changed method bodies; adding/removing methods, fields or lambdas needs JetBrains Runtime
 * with -XX:+AllowEnhancedClassRedefinition ({@code /hotreload setup}). Static initializers and constructors of
 * existing objects never re-run.
 */
final class Reloader {
	private static final long POLL_MS = 250;
	private static final long SETTLE_MS = 600;
	private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";

	private final Instrumentation inst;
	private final Projects projects;
	private final ClassLoader gameLoader = FabricLauncherBase.getLauncher().getTargetClassLoader();
	private final boolean fullHotswap = Jbr.runningWithHotswap();
	private final long startedAt = System.currentTimeMillis();
	private final LiveResourcePacks packs = new LiveResourcePacks();
	private final ModHotLoader modLoader;

	/** Binary class name to the bytes the game currently runs (filled lazily from the mod jar). */
	private final Map<String, byte[]> live = new HashMap<>();
	/** Binary class name to bytes that failed to apply, so the same failure is only reported once. */
	private final Map<String, byte[]> failed = new HashMap<>();
	/** Internal class name to new bytes for classes that are not loaded yet. */
	private final Map<String, byte[]> pendingLoad = new ConcurrentHashMap<>();
	private final Set<Path> addedToClassPath = new HashSet<>();
	/** Mod id to display name, for mods loaded by us after launch. */
	private final Map<String, String> loadedLive = new LinkedHashMap<>();
	/** Unloaded projects we won't load (e.g. server-only), reported once. */
	private final Set<String> refused = new HashSet<>();
	/** Mod id to a fingerprint of its resource folders, to notice lang/texture edits. */
	private final Map<String, Long> resourceStamps = new HashMap<>();

	/** Mod id to the newest request answered. */
	private final Map<String, Long> answered = new HashMap<>();

	private volatile List<Project> lastProjects = List.of();
	private Map<Path, Long> lastScan = Map.of();

	Reloader(Instrumentation inst, Projects projects) {
		this.inst = inst;
		this.projects = projects;
		this.modLoader = new ModHotLoader(inst, gameLoader, packs);
	}

	boolean fullHotswap() {
		return fullHotswap;
	}

	List<Project> watched() {
		return lastProjects;
	}

	Map<String, String> loadedLive() {
		return loadedLive;
	}

	void start() {
		inst.addTransformer(new PendingLoadTransformer(), false);
		List<Project> found = projects.find();
		lastProjects = found;
		for (Project project : found) {
			resourceStamps.put(project.modId(), stamp(project.resourceDirs()));
			if (project.loadedAtLaunch() && runsFromFolders(project.modId())) {
				try {
					snapshot(project); // dev environment: the "jar" is these very folders, so remember what was loaded
				} catch (IOException e) {
					Messages.LOG.warn("could not snapshot {}", project.root(), e);
				}
			}
		}
		lastScan = scan(found);
		Thread thread = new Thread(this::watchLoop, "Fabric Hot Reload watcher");
		thread.setDaemon(true);
		thread.start();
	}

	private void watchLoop() {
		long changedAt = 0;
		while (true) {
			try {
				Thread.sleep(POLL_MS);
				List<Project> found = projects.find();
				lastProjects = found;
				Map<Path, Long> now = scan(found);
				if (!now.equals(lastScan)) {
					lastScan = now;
					changedAt = System.currentTimeMillis();
				} else if (changedAt != 0 && System.currentTimeMillis() - changedAt >= SETTLE_MS) {
					changedAt = 0;
					Minecraft.getInstance().execute(this::reloadSafely);
				}
			} catch (InterruptedException e) {
				return;
			} catch (RuntimeException e) {
				Messages.LOG.error("watcher error", e);
			}
		}
	}

	/** Watched files to last-modified + size, so a rewrite with the same timestamp still counts. */
	private static Map<Path, Long> scan(List<Project> found) {
		Map<Path, Long> result = new HashMap<>();
		try {
			if (Files.isRegularFile(HotReloadPaths.PROJECTS)) {
				result.put(HotReloadPaths.PROJECTS, Files.getLastModifiedTime(HotReloadPaths.PROJECTS).toMillis());
			}
		} catch (IOException ignored) {
			// being replaced; the next poll sees it
		}
		for (Project project : found) {
			for (Path dir : project.allDirs()) {
				try (Stream<Path> files = Files.walk(dir)) {
					files.filter(Files::isRegularFile).forEach(f -> {
						try {
							result.put(f, Files.getLastModifiedTime(f).toMillis() * 31 + Files.size(f));
						} catch (IOException ignored) {
							// deleted mid-scan by Gradle; the next poll sees the settled state
						}
					});
				} catch (IOException ignored) {
					// same
				}
			}
		}
		return result;
	}

	private void reloadSafely() {
		try {
			reload();
		} catch (Throwable t) {
			Messages.LOG.error("reload crashed", t);
			Messages.report(null, Level.ERROR, "hotreload.crash", "Reload failed: %s", t.toString());
		}
	}

	private record Change(String modId, Path classDir, String binaryName, byte[] bytes, byte @Nullable [] previous) {
		boolean isNew() {
			return previous == null;
		}
	}

	/** Per-mod outcome of one reload round. */
	private static final class Outcome {
		final Set<String> applied = new LinkedHashSet<>();
		final List<String> errors = new ArrayList<>();
	}

	private void reload() throws IOException {
		List<Change> changes = new ArrayList<>();
		Set<String> requested = new HashSet<>();
		for (Project project : projects.find()) {
			if (project.requestedAt() > startedAt && project.requestedAt() > answered.getOrDefault(project.modId(), 0L)) {
				answered.put(project.modId(), project.requestedAt());
				requested.add(project.modId());
			}
			if (!project.loadedAtLaunch() && !loadedLive.containsKey(project.modId())) {
				// Only when built or requested during this session, so mods you removed don't come back by themselves.
				boolean wanted = requested.contains(project.modId()) || newest(project.allDirs()) > startedAt;
				if (!refused.contains(project.modId()) && wanted) {
					String missing = ModHotLoader.missing(project.modJson(), project.classDirs(), project.resourceDirs());
					if (missing == null) {
						loadNewMod(project);
					} else if (requested.contains(project.modId())) {
						Messages.report(project.modId(), Level.WARN, "hotreload.incomplete",
							"%s is not fully built (missing %s), not loaded", project.name(), missing);
					}
				}
				continue;
			}
			long resources = stamp(project.resourceDirs());
			Long previousResources = resourceStamps.put(project.modId(), resources);
			if (previousResources != null && previousResources != resources) {
				packs.add(project.modId(), project.name(), project.resourceDirs());
				Messages.report(project.modId(), Level.INFO, "hotreload.resources", "%s: resources reloaded", project.name());
			}
			for (Path dir : project.classDirs()) {
				for (Path file : classFiles(dir)) {
					String relative = dir.relativize(file).toString().replace('\\', '/');
					String name = binaryName(relative);
					byte[] bytes = Files.readAllBytes(file);
					byte[] current = live.computeIfAbsent(name, n -> readFromMod(project.modId(), relative));
					if (current != null && Arrays.equals(current, bytes)) {
						continue;
					}
					if (Arrays.equals(failed.get(name), bytes)) {
						continue; // already reported, still waiting for a restart or a different edit
					}
					changes.add(new Change(project.modId(), dir, name, bytes, current));
				}
			}
		}
		if (!changes.isEmpty()) {
			apply(changes);
		}
		// Answer requests that produced nothing else, so the Gradle task always gets a reply. The request is written
		// right after compiling, so anything this build caused was reported at most a moment before it.
		for (Project project : projects.find()) {
			if (requested.contains(project.modId()) && Messages.lastEventAt(project.modId()) < project.requestedAt() - 2_000) {
				Messages.report(project.modId(), Level.INFO, "hotreload.up_to_date", "%s is up to date", project.name());
			}
		}
	}

	private void apply(List<Change> changes) {
		Map<String, Class<?>> loaded = new HashMap<>();
		for (Class<?> c : inst.getAllLoadedClasses()) {
			if (c.getClassLoader() == gameLoader) {
				loaded.put(c.getName(), c);
			}
		}

		// Keyed by redefined class so a mixin and its target changing together end up as one definition.
		Map<Class<?>, ClassDefinition> definitions = new LinkedHashMap<>();
		Map<Class<?>, Change> causes = new HashMap<>();
		Map<String, Outcome> outcomes = new LinkedHashMap<>();
		List<Change> mixinChanges = new ArrayList<>();

		for (Change change : changes) {
			ClassNode node = new ClassNode();
			new ClassReader(change.bytes()).accept(node, ClassReader.SKIP_CODE);
			if (hasAnnotation(node, MIXIN_ANNOTATION)) {
				mixinChanges.add(change);
				continue;
			}
			Class<?> cls = loaded.get(change.binaryName());
			if (cls != null) {
				definitions.put(cls, new ClassDefinition(cls, change.bytes()));
				causes.put(cls, change);
			} else {
				if (change.isNew() && addedToClassPath.add(change.classDir())) {
					FabricLauncherBase.getLauncher().addToClassPath(change.classDir());
				}
				pendingLoad.put(change.binaryName().replace('.', '/'), change.bytes());
				live.put(change.binaryName(), change.bytes());
				outcome(outcomes, change).applied.add(simpleName(change.binaryName()));
			}
		}

		IMixinTransformer mixins = (IMixinTransformer) MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
		for (Change change : mixinChanges) {
			ClassNode node = new ClassNode();
			new ClassReader(change.bytes()).accept(node, 0);
			try {
				MixinClassInfoRefresh.beforeReload(change.previous(), node);
				for (String target : mixins.reload(change.binaryName(), node)) {
					String targetName = target.replace('/', '.');
					Class<?> cls = loaded.get(targetName);
					if (cls == null) {
						continue; // gets the new code when it loads
					}
					byte[] pre = FabricLauncherBase.getLauncher().getClassByteArray(targetName, true);
					definitions.put(cls, new ClassDefinition(cls, mixins.transformClassBytes(targetName, targetName, pre)));
					causes.put(cls, change);
				}
				live.put(change.binaryName(), change.bytes());
				outcome(outcomes, change).applied.add(simpleName(change.binaryName()) + " (mixin)");
			} catch (Exception e) {
				failed.put(change.binaryName(), change.bytes());
				outcome(outcomes, change).errors.add(simpleName(change.binaryName()) + ": " + e.getMessage());
			}
		}

		if (!definitions.isEmpty()) {
			try {
				inst.redefineClasses(definitions.values().toArray(ClassDefinition[]::new));
				causes.forEach((cls, change) -> {
					live.put(change.binaryName(), change.bytes());
					if (change.binaryName().equals(cls.getName())) {
						outcome(outcomes, change).applied.add(cls.getSimpleName());
					}
				});
			} catch (Exception | LinkageError batchError) {
				// Retry one by one to keep whatever is compatible and pinpoint what is not.
				for (ClassDefinition def : definitions.values()) {
					Class<?> cls = def.getDefinitionClass();
					Change cause = causes.get(cls);
					Outcome outcome = outcome(outcomes, cause);
					try {
						inst.redefineClasses(def);
						live.put(cause.binaryName(), cause.bytes());
						if (cause.binaryName().equals(cls.getName())) {
							outcome.applied.add(cls.getSimpleName());
						}
					} catch (Exception | LinkageError e) {
						failed.put(cause.binaryName(), cause.bytes());
						live.remove(cause.binaryName());
						String label = simpleName(cause.binaryName());
						outcome.applied.remove(label + " (mixin)");
						outcome.errors.add(label + (cause.binaryName().equals(cls.getName()) ? "" : " -> " + cls.getSimpleName()) + ": " + explain(e));
					}
				}
			}
		}

		outcomes.forEach((modId, outcome) -> {
			if (!outcome.applied.isEmpty()) {
				Messages.report(modId, Level.INFO, "hotreload.reloaded", "Reloaded: %s", String.join(", ", outcome.applied));
			}
			for (String error : outcome.errors) {
				Messages.report(modId, Level.ERROR, "hotreload.not_applied", "Not applied: %s", error);
			}
		});
	}

	private static Outcome outcome(Map<String, Outcome> outcomes, Change change) {
		return outcomes.computeIfAbsent(change.modId(), k -> new Outcome());
	}

	private void loadNewMod(Project project) throws IOException {
		String env = project.modJson().has("environment") ? project.modJson().get("environment").getAsString() : "*";
		if (env.equals("server")) {
			refused.add(project.modId());
			Messages.report(project.modId(), Level.WARN, "hotreload.server_only", "%s is server-only, not loaded", project.name());
			return;
		}
		loadedLive.put(project.modId(), project.name());
		addedToClassPath.addAll(project.classDirs());
		snapshot(project); // what the game runs from now on is exactly what is in the build folders
		resourceStamps.put(project.modId(), stamp(project.resourceDirs()));

		ModHotLoader.Result result = modLoader.load(project.modId(), project.name(), project.modJson(), project.classDirs(), project.resourceDirs());
		Messages.report(project.modId(), Level.INFO, "hotreload.loaded_mod", "Loaded new mod: %s (%s)",
			project.name(), result.done().isEmpty() ? "-" : String.join(", ", result.done()));
		for (String error : result.errors()) {
			Messages.report(project.modId(), Level.ERROR, "hotreload.load_error", "%s: %s", project.name(), error);
		}
	}

	private void snapshot(Project project) throws IOException {
		for (Path dir : project.classDirs()) {
			for (Path file : classFiles(dir)) {
				live.put(binaryName(dir.relativize(file).toString().replace('\\', '/')), Files.readAllBytes(file));
			}
		}
	}

	private String explain(Throwable e) {
		if (e instanceof UnsupportedOperationException) {
			return fullHotswap
				? "structure change not supported here"
				: "structure changed (method/field/lambda added or removed, or a signature changed); restart, or /hotreload setup for full hotswap";
		}
		return e.getClass().getSimpleName() + ": " + e.getMessage();
	}

	// ---- helpers ---------------------------------------------------------------------------------------------------

	private static List<Path> classFiles(Path dir) throws IOException {
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(f -> f.toString().endsWith(".class")).toList();
		}
	}

	private static String binaryName(String relativePath) {
		return relativePath.substring(0, relativePath.length() - ".class".length()).replace('/', '.');
	}

	/** True in a Loom dev environment, where the mod is loaded straight from build/classes. */
	private static boolean runsFromFolders(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
			.map(m -> m.getRootPaths().stream().anyMatch(p -> p.getFileSystem() == FileSystems.getDefault()))
			.orElse(false);
	}

	private static byte @Nullable [] readFromMod(String modId, String relativePath) {
		ModContainer mod = FabricLoader.getInstance().getModContainer(modId).orElse(null);
		if (mod == null) {
			return null;
		}
		for (Path root : mod.getRootPaths()) {
			Path file = root.resolve(relativePath);
			if (Files.isRegularFile(file)) {
				try {
					return Files.readAllBytes(file);
				} catch (IOException ignored) {
					return null;
				}
			}
		}
		return null;
	}

	private static boolean hasAnnotation(ClassNode node, String desc) {
		for (List<AnnotationNode> list : Arrays.asList(node.invisibleAnnotations, node.visibleAnnotations)) {
			if (list != null && list.stream().anyMatch(a -> a.desc.equals(desc))) {
				return true;
			}
		}
		return false;
	}

	private static String simpleName(String binaryName) {
		return binaryName.substring(binaryName.lastIndexOf('.') + 1);
	}

	/** Newest last-modified time of any file in the folders. */
	private static long newest(List<Path> dirs) {
		long newest = 0;
		for (Path dir : dirs) {
			try (Stream<Path> walk = Files.walk(dir)) {
				for (Path f : walk.filter(Files::isRegularFile).toList()) {
					newest = Math.max(newest, Files.getLastModifiedTime(f).toMillis());
				}
			} catch (IOException ignored) {
				// mid-build; the next scan catches it
			}
		}
		return newest;
	}

	/** Order-independent fingerprint of the files (path, time, size) in the folders. */
	private static long stamp(List<Path> dirs) {
		long stamp = 0;
		for (Path dir : dirs) {
			try (Stream<Path> walk = Files.walk(dir)) {
				for (Path f : walk.filter(Files::isRegularFile).toList()) {
					stamp += (f.toString().hashCode() * 31L + Files.getLastModifiedTime(f).toMillis()) * 31L + Files.size(f);
				}
			} catch (IOException ignored) {
				// mid-build; the next scan catches it
			}
		}
		return stamp;
	}

	/** Swaps in the latest compiled bytes for classes that changed before they were first loaded. */
	private final class PendingLoadTransformer implements ClassFileTransformer {
		@Override
		public byte @Nullable [] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
				ProtectionDomain protectionDomain, byte[] classfileBuffer) {
			if (classBeingRedefined != null || loader != gameLoader || className == null) {
				return null;
			}
			return pendingLoad.remove(className);
		}
	}
}
