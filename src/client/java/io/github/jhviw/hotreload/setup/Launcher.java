package io.github.jhviw.hotreload.setup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Which launcher started the game, guessed from the game folder layout. JDK classes only. */
public enum Launcher {
	CURSEFORGE("CurseForge"),
	/** Prism Launcher, MultiMC and their forks share the instance layout. */
	PRISM("Prism Launcher / MultiMC"),
	MODRINTH("Modrinth App"),
	OFFICIAL("Minecraft Launcher"),
	OTHER("your launcher");

	public final String displayName;

	Launcher(String displayName) {
		this.displayName = displayName;
	}

	public static Launcher detect(Path gameDir) {
		Path dir = gameDir.toAbsolutePath().normalize();
		String path = dir.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
		if (CurseForgeSetup.isInstance(dir)) {
			return CURSEFORGE;
		}
		Path parent = dir.getParent();
		if (parent != null && (Files.isRegularFile(parent.resolve("instance.cfg")) || Files.isRegularFile(parent.resolve("mmc-pack.json")))) {
			return PRISM;
		}
		if (path.contains("modrinthapp/profiles/") || path.contains("com.modrinth.theseus/profiles/")) {
			return MODRINTH;
		}
		if (Files.isRegularFile(dir.resolve("launcher_profiles.json")) || path.endsWith("/.minecraft")
			|| path.endsWith("/library/application support/minecraft")) {
			return OFFICIAL;
		}
		return OTHER;
	}
}
