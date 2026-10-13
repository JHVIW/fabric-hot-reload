package io.github.jhviw.hotreload.setup;

import java.nio.file.Path;

/**
 * Shared per-user folder {@code ~/.hotreload}, used by the mod, the Gradle script and the setup helper:
 * <ul>
 * <li>{@code projects.json}: mod projects registered by {@code ./gradlew hotReload}</li>
 * <li>{@code events.log}: what the running game did, read back by the Gradle task</li>
 * <li>{@code jbr/}: JetBrains Runtime downloaded by {@code /hotreload setup}</li>
 * </ul>
 * JDK classes only: also used outside the game by {@link SetupMain}.
 */
public final class HotReloadPaths {
	public static final Path HOME = Path.of(System.getProperty("user.home"), ".hotreload");
	public static final Path PROJECTS = HOME.resolve("projects.json");
	public static final Path EVENTS = HOME.resolve("events.log");
	public static final Path JBR = HOME.resolve("jbr");

	private HotReloadPaths() {
	}
}
