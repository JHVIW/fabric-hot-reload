package io.github.jhviw.hotreload.setup;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code java -jar fabric-hot-reload-<version>.jar <command>}: setup steps that must run while the game (and for
 * CurseForge, the launcher) is closed. The in-game {@code /hotreload setup} prints the exact command to run.
 * JDK classes only.
 */
public final class SetupMain {
	private SetupMain() {
	}

	public static void main(String[] args) {
		try {
			System.exit(run(args));
		} catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
			System.exit(1);
		}
	}

	private static int run(String[] args) throws Exception {
		String command = args.length > 0 ? args[0] : "help";
		switch (command) {
			case "install-jbr" -> {
				Path java = Jbr.install(p -> System.out.println("JetBrains Runtime: " + p));
				System.out.println("Java path: " + java);
				System.out.println("JVM arguments to add: " + Jbr.GC_FLAG + " " + Jbr.HOTSWAP_FLAG);
				return 0;
			}
			case "curseforge" -> {
				if (args.length < 2) {
					System.err.println("usage: curseforge <instance folder> [--undo]");
					return 2;
				}
				Path instance = Path.of(args[1]);
				List<String> done;
				if (List.of(args).contains("--undo")) {
					done = CurseForgeSetup.undo(instance);
				} else {
					CurseForgeSetup.checkClosed(); // before a 100 MB download, not after
					Path java = Jbr.install(p -> System.out.println("JetBrains Runtime: " + p));
					done = CurseForgeSetup.apply(instance, java);
				}
				done.forEach(System.out::println);
				System.out.println("Done. Start CurseForge and launch the instance.");
				return 0;
			}
			default -> {
				System.out.println("""
					Fabric Hot Reload setup helper

					  install-jbr                         download JetBrains Runtime into ~/.hotreload/jbr
					  curseforge <instance folder>        point a CurseForge instance at it (CurseForge must be closed)
					  curseforge <instance folder> --undo restore the CurseForge files from the backup

					Other launchers: run /hotreload setup in game, it shows what to paste where.""");
				return command.equals("help") ? 0 : 2;
			}
		}
	}
}
