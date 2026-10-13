package io.github.jhviw.hotreload;

import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;

/**
 * Serves the {@code build/resources/...} folders of mod projects as required resource packs on top of everything
 * else: assets of hot-loaded mods become available, and edited lang files/textures of any watched mod override the
 * ones in its jar. Installed into the client's PackRepository on first use (its source set is immutable, so it is
 * swapped via reflection).
 */
final class LiveResourcePacks implements RepositorySource {
	/** Pack id to (title, resource folder); insertion order = load order. */
	private final Map<String, Entry> packs = new LinkedHashMap<>();
	private boolean installed;

	private record Entry(String title, Path root) {
	}

	/** Registers the folders that contain assets and reloads resources. Returns whether anything was registered. */
	boolean add(String modId, String title, List<Path> resourceDirs) {
		boolean any = false;
		for (Path dir : resourceDirs) {
			if (Files.isDirectory(dir.resolve("assets"))) {
				String id = "hotreload/" + modId + "/" + dir.getFileName();
				packs.putIfAbsent(id, new Entry(title, dir));
				any = true;
			}
		}
		if (any) {
			reload();
		}
		return any;
	}

	/** Reloads resources (like F3+T) after making sure our source is part of the repository. */
	void reload() {
		Minecraft mc = Minecraft.getInstance();
		PackRepository repository = mc.getResourcePackRepository();
		if (!installed) {
			try {
				Field sources = ModHotLoader.field(PackRepository.class, "sources");
				@SuppressWarnings("unchecked")
				Set<RepositorySource> current = (Set<RepositorySource>) sources.get(repository);
				sources.set(repository, ImmutableSet.<RepositorySource>builder().addAll(current).add(this).build());
				installed = true;
			} catch (ReflectiveOperationException e) {
				Messages.LOG.error("could not add live resource packs", e);
				return;
			}
		}
		repository.reload();
		mc.reloadResourcePacks();
	}

	@Override
	public void loadPacks(Consumer<Pack> result) {
		packs.forEach((id, entry) -> {
			PackLocationInfo location = new PackLocationInfo(id, Component.literal(entry.title() + " (live)"), PackSource.BUILT_IN, Optional.empty());
			Pack.Metadata metadata = new Pack.Metadata(Component.literal("Fabric Hot Reload: " + entry.root()), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
			result.accept(new Pack(location, new PathPackResources.PathResourcesSupplier(entry.root()), metadata,
				new PackSelectionConfig(true, Pack.Position.TOP, false)));
		});
	}
}
