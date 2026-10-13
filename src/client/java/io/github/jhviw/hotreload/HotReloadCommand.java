package io.github.jhviw.hotreload;

import com.mojang.brigadier.CommandDispatcher;
import io.github.jhviw.hotreload.Messages.Level;
import io.github.jhviw.hotreload.setup.CurseForgeSetup;
import io.github.jhviw.hotreload.setup.Jbr;
import io.github.jhviw.hotreload.setup.Launcher;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** {@code /hotreload} (status) and {@code /hotreload setup} (full hotswap). */
final class HotReloadCommand {
	private static volatile boolean installing;

	private HotReloadCommand() {
	}

	static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("hotreload")
			.executes(ctx -> {
				status();
				return 1;
			})
			.then(ClientCommands.literal("setup").executes(ctx -> {
				setup();
				return 1;
			})));
	}

	private static void status() {
		Reloader reloader = HotReload.reloader().orElse(null);
		if (reloader == null) {
			Messages.toChat(tr("hotreload.inactive", "Not active: could not attach to the game, see the log").withStyle(ChatFormatting.RED));
			return;
		}
		Messages.toChat(reloader.fullHotswap()
			? tr("hotreload.status_full", "Full hotswap (JetBrains Runtime)").withStyle(ChatFormatting.GREEN)
			: tr("hotreload.status_basic", "Basic mode: only changed method bodies. /hotreload setup for full hotswap").withStyle(ChatFormatting.YELLOW));
		List<Projects.Project> watched = reloader.watched();
		String names = watched.isEmpty() ? "-" : watched.stream().map(p -> p.name() + (p.loadedAtLaunch() || reloader.loadedLive().containsKey(p.modId()) ? "" : " (not loaded)"))
			.collect(Collectors.joining(", "));
		Messages.toChat(tr("hotreload.status_projects", "Watching %s project(s): %s", watched.size(), names));
		if (!reloader.loadedLive().isEmpty()) {
			Messages.toChat(tr("hotreload.status_live", "Loaded live: %s", String.join(", ", reloader.loadedLive().values())));
		}
		if (watched.isEmpty()) {
			Messages.toChat(tr("hotreload.status_hint", "Run ./gradlew hotReload in your mod project (see the README) to connect it").withStyle(ChatFormatting.GRAY));
		}
	}

	private static void setup() {
		if (Jbr.runningWithHotswap()) {
			Messages.toChat(tr("hotreload.setup_done", "Full hotswap is already active").withStyle(ChatFormatting.GREEN));
			return;
		}
		if (installing) {
			return;
		}
		installing = true;
		Messages.toChat(tr("hotreload.setup_download", "Downloading JetBrains Runtime %s (~100 MB, once)...", Jbr.VERSION));
		Thread thread = new Thread(() -> {
			try {
				Path java = Jbr.install(progress -> Messages.toChat(Component.literal("JetBrains Runtime: " + progress).withStyle(ChatFormatting.GRAY)));
				instructions(java);
			} catch (Exception e) {
				Messages.LOG.error("JetBrains Runtime install failed", e);
				Messages.report(null, Level.ERROR, "hotreload.setup_failed", "Setup failed: %s", String.valueOf(e.getMessage()));
			} finally {
				installing = false;
			}
		}, "Fabric Hot Reload setup");
		thread.setDaemon(true);
		thread.start();
	}

	/** Launcher-specific steps, with click-to-copy values. */
	private static void instructions(Path java) {
		Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		Launcher launcher = Launcher.detect(gameDir);
		String args = Jbr.GC_FLAG + " " + Jbr.HOTSWAP_FLAG;
		List<String> gcFlags = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
			.filter(a -> a.matches("-XX:[+-]Use\\w+GC|-XX:[+-]ZGenerational") && !a.equals(Jbr.GC_FLAG)).toList();

		Messages.toChat(tr("hotreload.setup_ready", "JetBrains Runtime is ready. Detected launcher: %s", launcher.displayName).withStyle(ChatFormatting.GREEN));
		switch (launcher) {
			case CURSEFORGE -> {
				Path jar = HotReload.modJar();
				Path console = Jbr.executable(Jbr.home(), false);
				String command = "\"" + console + "\" -jar \"" + jar + "\" curseforge \"" + gameDir + "\"";
				Messages.toChat(tr("hotreload.setup_cf", "CurseForge can be configured automatically. Quit the game, close CurseForge completely (tray icon > Exit), then run this in a terminal: "
					+ "%s. It backs up and edits the CurseForge settings (Java 25 path for all instances, JVM arguments for this one).", copy(command)));
			}
			case PRISM -> Messages.toChat(tr("hotreload.setup_prism",
				"In Prism Launcher / MultiMC: right-click the instance > Edit > Settings > Java. Tick Java installation and set the path to %s. Tick Java arguments and add %s",
				copy(java.toString()), copy(args)));
			case MODRINTH -> Messages.toChat(tr("hotreload.setup_modrinth",
				"In the Modrinth App: open the instance > ⋮ > Edit/Options > Java and memory. Custom Java installation: %s. Custom Java arguments: add %s",
				copy(java.toString()), copy(args)));
			case OFFICIAL -> Messages.toChat(tr("hotreload.setup_official",
				"In the Minecraft Launcher: Installations > your Fabric installation > Edit > More options. Java executable: %s. JVM arguments: add %s",
				copy(java.toString()), copy(args)));
			case OTHER -> Messages.toChat(tr("hotreload.setup_other",
				"In your launcher's instance settings set the Java executable to %s and add the JVM arguments %s",
				copy(java.toString()), copy(args)));
		}
		if (!gcFlags.isEmpty() && launcher != Launcher.CURSEFORGE) {
			Messages.toChat(tr("hotreload.setup_gc", "Also remove %s from the JVM arguments: full hotswap only works with the G1 collector",
				String.join(" ", gcFlags)).withStyle(ChatFormatting.YELLOW));
		}
		Messages.toChat(tr("hotreload.setup_restart", "Then restart the game. /hotreload shows whether full hotswap is active").withStyle(ChatFormatting.GRAY));
	}

	private static MutableComponent copy(String value) {
		return Component.literal("[" + value + "]").withStyle(style -> style
			.withColor(ChatFormatting.AQUA)
			.withUnderlined(true)
			.withClickEvent(new ClickEvent.CopyToClipboard(value))
			.withHoverEvent(new HoverEvent.ShowText(Component.translatableWithFallback("hotreload.click_to_copy", "Click to copy"))));
	}

	private static MutableComponent tr(String key, String english, Object... args) {
		return Component.translatableWithFallback(key, english, args);
	}
}
