package io.github.jhviw.hotreload;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code config/hotreload.json}.
 * <ul>
 * <li>{@code projects}: extra mod projects to watch, each either one Gradle project or a folder containing projects
 * (one level deep). Projects registered with {@code ./gradlew hotReload} need no entry here.</li>
 * <li>{@code chatMessages}: show results in chat (they always go to the log).</li>
 * </ul>
 */
public record HotReloadConfig(List<String> projects, Boolean chatMessages) {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HotReloadConfig DEFAULT = new HotReloadConfig(List.of(), true);

	static HotReloadConfig load(Path file) {
		try {
			if (Files.exists(file)) {
				HotReloadConfig config = GSON.fromJson(Files.readString(file), HotReloadConfig.class);
				if (config != null) {
					return new HotReloadConfig(config.projects() != null ? config.projects() : List.of(), config.chatMessages() != Boolean.FALSE);
				}
			}
			Files.writeString(file, GSON.toJson(DEFAULT));
		} catch (IOException | RuntimeException e) {
			Messages.LOG.warn("could not read {}, using defaults", file, e);
		}
		return DEFAULT;
	}

	boolean chat() {
		return chatMessages != Boolean.FALSE;
	}
}
