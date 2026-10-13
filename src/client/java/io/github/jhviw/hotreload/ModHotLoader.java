package io.github.jhviw.hotreload;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * Loads a mod that was not there when the game started, the way Fabric would have at launch: class + resource
 * folders on the class path, mixin configs registered and applied (also to classes that are already loaded, which
 * get rebuilt and redefined), entrypoints run, key mappings added to the options, assets added as a resource pack.
 * <p>
 * Not possible: Fabric's mod list ({@code FabricLoader.getModContainer} stays empty for it) and dependency checks.
 */
final class ModHotLoader {
	private final Instrumentation inst;
	private final ClassLoader gameLoader;
	private final LiveResourcePacks packs;

	ModHotLoader(Instrumentation inst, ClassLoader gameLoader, LiveResourcePacks packs) {
		this.inst = inst;
		this.gameLoader = gameLoader;
		this.packs = packs;
	}

	record Result(List<String> done, List<String> errors) {
	}

	/** Must run on the render thread. */
	Result load(String modId, String name, JsonObject modJson, List<Path> classDirs, List<Path> resourceDirs) {
		List<String> done = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		for (Path dir : classDirs) {
			FabricLauncherBase.getLauncher().addToClassPath(dir);
		}
		for (Path dir : resourceDirs) {
			FabricLauncherBase.getLauncher().addToClassPath(dir);
		}

		List<String> configs = mixinConfigs(modJson);
		if (!configs.isEmpty()) {
			try {
				int targets = applyMixinConfigs(modId, configs, errors);
				done.add(configs.size() + " mixin config" + (configs.size() == 1 ? "" : "s") + " (" + targets + " already-loaded targets rebuilt)");
			} catch (Throwable e) {
				Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
				Messages.LOG.error("mixin setup for {} failed", modId, cause);
				errors.add("mixins: " + cause);
			}
		}

		int entrypoints = runEntrypoints(modJson, errors);
		if (entrypoints > 0) {
			done.add(entrypoints + " entrypoint" + (entrypoints == 1 ? "" : "s"));
		}

		if (packs.add(modId, name, resourceDirs)) {
			done.add("resources");
		}
		return new Result(done, errors);
	}

	// ---- mixins ----------------------------------------------------------------------------------------------------

	/**
	 * Gradle writes classes and the two resource folders in separate steps; loading half a build would e.g. miss the
	 * mixin config. Ready = every mixin config and every entrypoint class named in fabric.mod.json exists.
	 *
	 * @return the first missing file, or {@code null} when the build is complete
	 */
	static @Nullable String missing(JsonObject modJson, List<Path> classDirs, List<Path> resourceDirs) {
		for (String config : mixinConfigs(modJson)) {
			if (resourceDirs.stream().noneMatch(d -> Files.isRegularFile(d.resolve(config)))) {
				return config;
			}
		}
		if (modJson.has("entrypoints")) {
			for (var entry : modJson.getAsJsonObject("entrypoints").entrySet()) {
				for (JsonElement e : entry.getValue().getAsJsonArray()) {
					String value = e.isJsonPrimitive() ? e.getAsString() : e.getAsJsonObject().get("value").getAsString();
					String file = value.split("::")[0].replace('.', '/') + ".class";
					if (classDirs.stream().noneMatch(d -> Files.isRegularFile(d.resolve(file)))) {
						return file;
					}
				}
			}
		}
		return null;
	}

	private static List<String> mixinConfigs(JsonObject modJson) {
		List<String> result = new ArrayList<>();
		if (modJson.has("mixins")) {
			for (JsonElement e : modJson.getAsJsonArray("mixins")) {
				if (e.isJsonPrimitive()) {
					result.add(e.getAsString());
				} else {
					JsonObject o = e.getAsJsonObject();
					String env = o.has("environment") ? o.get("environment").getAsString() : "*";
					if (!env.equals("server")) {
						result.add(o.get("config").getAsString());
					}
				}
			}
		}
		return result;
	}

	/**
	 * Mixin only picks up newly added configs before the first class has been transformed, so its private
	 * {@code MixinProcessor.select} is called directly. Targets that are already loaded are then rebuilt from their
	 * pre-mixin bytes with every mixin (old and new) applied, and redefined.
	 */
	private int applyMixinConfigs(String modId, List<String> configs, List<String> errors) throws Exception {
		for (String config : configs) {
			Mixins.addConfiguration(config);
		}
		for (Config handle : Mixins.getConfigs()) {
			if (configs.contains(handle.getName())) {
				handle.getConfig().decorate(FabricUtil.KEY_MOD_ID, modId);
				// A required config refuses targets that are already loaded ("loaded too early"); we re-transform
				// those ourselves, and a failing live mod should warn instead of crashing the game.
				field(handle.getConfig().getClass(), "required").setBoolean(handle.getConfig(), false);
			}
		}

		IMixinTransformer transformer = (IMixinTransformer) MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
		Object processor = fieldOfType(transformer, "MixinProcessor");
		List<?> active = (List<?>) field(processor.getClass(), "configs").get(processor);
		Set<Object> before = new HashSet<>(active);
		Method select = processor.getClass().getDeclaredMethod("select", MixinEnvironment.class);
		select.setAccessible(true);
		select.invoke(processor, MixinEnvironment.getCurrentEnvironment());

		Set<String> targets = new HashSet<>();
		for (Object config : active) {
			if (!before.contains(config)) {
				for (String target : ((IMixinConfig) config).getTargets()) {
					targets.add(target.replace('/', '.'));
				}
			}
		}

		Map<String, Class<?>> loaded = new java.util.HashMap<>();
		for (Class<?> c : inst.getAllLoadedClasses()) {
			if (c.getClassLoader() == gameLoader && targets.contains(c.getName())) {
				loaded.put(c.getName(), c);
			}
		}
		List<ClassDefinition> definitions = new ArrayList<>();
		for (Class<?> cls : loaded.values()) {
			byte[] pre = FabricLauncherBase.getLauncher().getClassByteArray(cls.getName(), true);
			definitions.add(new ClassDefinition(cls, transformer.transformClassBytes(cls.getName(), cls.getName(), pre)));
		}
		int rebuilt = definitions.size();
		if (!definitions.isEmpty()) {
			try {
				inst.redefineClasses(definitions.toArray(ClassDefinition[]::new));
			} catch (Exception | LinkageError batch) {
				for (ClassDefinition def : definitions) {
					try {
						inst.redefineClasses(def);
					} catch (Exception | LinkageError e) {
						rebuilt--;
						errors.add("mixin into " + def.getDefinitionClass().getSimpleName() + ": "
							+ (e instanceof UnsupportedOperationException ? "needs full hotswap (/hotreload setup) or a restart" : e.toString()));
					}
				}
			}
		}
		return rebuilt;
	}

	// ---- entrypoints -----------------------------------------------------------------------------------------------

	private int runEntrypoints(JsonObject modJson, List<String> errors) {
		if (!modJson.has("entrypoints")) {
			return 0;
		}
		JsonObject entrypoints = modJson.getAsJsonObject("entrypoints");
		Minecraft mc = Minecraft.getInstance();
		int count = 0;
		List<KeyMapping> keysBefore = new ArrayList<>(List.of(mc.options.keyMappings));
		// Fabric only accepts key mappings before Options exists, which is how it is during a normal launch.
		Field optionsField = null;
		Options options = mc.options;
		try {
			optionsField = field(Minecraft.class, "options");
			optionsField.set(mc, null);
		} catch (ReflectiveOperationException e) {
			Messages.LOG.warn("could not hide Options during init; key mappings may fail to register", e);
		}
		try {
			for (String type : List.of("main", "client")) {
				if (!entrypoints.has(type)) {
					continue;
				}
				for (JsonElement e : entrypoints.getAsJsonArray(type)) {
					String value = e.isJsonPrimitive() ? e.getAsString() : e.getAsJsonObject().get("value").getAsString();
					try {
						if (value.contains("::")) {
							throw new UnsupportedOperationException("method/field entrypoints are not supported");
						}
						Object instance = Class.forName(value, true, gameLoader).getDeclaredConstructor().newInstance();
						if (type.equals("main") && instance instanceof ModInitializer init) {
							init.onInitialize();
						} else if (type.equals("client") && instance instanceof ClientModInitializer init) {
							init.onInitializeClient();
						}
						count++;
					} catch (Throwable t) {
						Messages.LOG.error("entrypoint {} failed", value, t);
						errors.add("entrypoint " + value + ": " + t);
					}
				}
			}
		} finally {
			if (optionsField != null) {
				try {
					optionsField.set(mc, options);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException("could not restore Minecraft.options", e);
				}
			}
		}
		addNewKeyMappings(options, keysBefore, errors);
		return count;
	}

	/** Same as Fabric's Options mixin does at launch: append the modded key mappings so the controls screen shows them. */
	private static void addNewKeyMappings(Options options, List<KeyMapping> before, List<String> errors) {
		KeyMapping[] all = KeyMappingRegistryImpl.process(options.keyMappings);
		if (all.length == before.size()) {
			return;
		}
		try {
			field(Options.class, "keyMappings").set(options, all);
			KeyMapping.resetMapping();
		} catch (ReflectiveOperationException e) {
			errors.add("key mappings not added to the controls screen: " + e);
		}
	}

	// ---- reflection ------------------------------------------------------------------------------------------------

	static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException ignored) {
				// look in the superclass
			}
		}
		throw new NoSuchFieldException(owner.getName() + "." + name);
	}

	private static Object fieldOfType(Object owner, String simpleTypeName) throws ReflectiveOperationException {
		for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (f.getType().getSimpleName().equals(simpleTypeName)) {
					f.setAccessible(true);
					return f.get(owner);
				}
			}
		}
		throw new NoSuchFieldException(simpleTypeName + " in " + owner.getClass().getName());
	}

	static boolean hasFiles(Path dir) {
		try (var s = Files.list(dir)) {
			return s.findAny().isPresent();
		} catch (Exception e) {
			return false;
		}
	}
}
