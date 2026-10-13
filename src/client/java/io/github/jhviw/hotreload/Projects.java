package io.github.jhviw.hotreload;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jhviw.hotreload.setup.HotReloadPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Finds the Gradle mod projects to watch: the ones in {@code config/hotreload.json} plus the ones registered in
 * {@code ~/.hotreload/projects.json} by {@code ./gradlew hotReload}. A folder counts once it has been built
 * ({@code build/resources/main/fabric.mod.json} exists).
 */
final class Projects {
	private static final List<String> CLASS_DIRS = List.of("build/classes/java/main", "build/classes/java/client",
		"build/classes/kotlin/main", "build/classes/kotlin/client");
	private static final List<String> RESOURCE_DIRS = List.of("build/resources/main", "build/resources/client");

	/**
	 * @param requestedAt when {@code ./gradlew hotReload} last asked for this project (0 = never); the game answers
	 *                    every request, also when nothing changed
	 */
	record Project(String modId, String name, JsonObject modJson, Path root, List<Path> classDirs, List<Path> resourceDirs,
			boolean loadedAtLaunch, long requestedAt) {
		List<Path> allDirs() {
			List<Path> all = new ArrayList<>(classDirs);
			all.addAll(resourceDirs);
			return all;
		}
	}

	private final HotReloadConfig config;

	Projects(HotReloadConfig config) {
		this.config = config;
	}

	/** Keyed by mod id; the first project found for an id wins. */
	List<Project> find() {
		Map<String, Project> result = new LinkedHashMap<>();
		Registry registry = registry();
		List<String> roots = new ArrayList<>(config.projects());
		roots.addAll(registry.projects());
		for (String rootString : roots) {
			Path root;
			try {
				root = Path.of(rootString);
			} catch (RuntimeException e) {
				continue;
			}
			if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
				project(root, registry.requestedAt(root)).ifPresent(p -> result.putIfAbsent(p.modId(), p));
			} else if (Files.isDirectory(root)) {
				try (Stream<Path> children = Files.list(root)) {
					children.forEach(child -> project(child, 0).ifPresent(p -> result.putIfAbsent(p.modId(), p)));
				} catch (IOException ignored) {
					// folder vanished; nothing to watch
				}
			}
		}
		return new ArrayList<>(result.values());
	}

	/** Contents of {@code ~/.hotreload/projects.json}: registered project folders and their last request time. */
	private record Registry(List<String> projects, Map<Path, Long> requests) {
		long requestedAt(Path root) {
			return requests.getOrDefault(root.toAbsolutePath().normalize(), 0L);
		}
	}

	private static Registry registry() {
		if (!Files.isRegularFile(HotReloadPaths.PROJECTS)) {
			return new Registry(List.of(), Map.of());
		}
		try {
			JsonObject json = JsonParser.parseString(Files.readString(HotReloadPaths.PROJECTS)).getAsJsonObject();
			List<String> projects = new ArrayList<>();
			if (json.has("projects")) {
				for (JsonElement e : json.getAsJsonArray("projects")) {
					projects.add(e.getAsString());
				}
			}
			Map<Path, Long> requests = new HashMap<>();
			if (json.has("requests")) {
				for (var e : json.getAsJsonObject("requests").entrySet()) {
					requests.put(Path.of(e.getKey()).toAbsolutePath().normalize(), e.getValue().getAsLong());
				}
			}
			return new Registry(projects, requests);
		} catch (IOException | RuntimeException e) {
			return new Registry(List.of(), Map.of()); // being rewritten by Gradle right now; the next poll reads it
		}
	}

	private static Optional<Project> project(Path dir, long requestedAt) {
		Path modJson = dir.resolve("build/resources/main/fabric.mod.json");
		if (!Files.isRegularFile(modJson)) {
			return Optional.empty();
		}
		try {
			JsonObject json = JsonParser.parseString(Files.readString(modJson)).getAsJsonObject();
			String id = json.get("id").getAsString();
			if (id.equals(HotReload.MOD_ID)) {
				return Optional.empty();
			}
			String name = json.has("name") ? json.get("name").getAsString() : id;
			List<Path> classDirs = CLASS_DIRS.stream().map(dir::resolve).filter(Files::isDirectory).toList();
			List<Path> resourceDirs = RESOURCE_DIRS.stream().map(dir::resolve).filter(Files::isDirectory).toList();
			return Optional.of(new Project(id, name, json, dir.toAbsolutePath().normalize(), classDirs, resourceDirs,
				FabricLoader.getInstance().isModLoaded(id), requestedAt));
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}
}
