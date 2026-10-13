package io.github.jhviw.hotreload;

import io.github.jhviw.hotreload.Messages.Level;
import io.github.jhviw.hotreload.agent.HotReloadAgent;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Fabric Hot Reload: build a mod project while the game runs and the change is in the game within a second, new mods
 * included. See {@link Reloader} for what is swapped and how.
 */
public class HotReload implements ClientModInitializer {
	public static final String MOD_ID = "hotreload";

	private static @Nullable Reloader reloader;
	private static boolean greeted;

	@Override
	public void onInitializeClient() {
		Messages.startEventLog();
		HotReloadConfig config = HotReloadConfig.load(FabricLoader.getInstance().getConfigDir().resolve("hotreload.json"));
		Messages.chat = config.chat();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> HotReloadCommand.register(dispatcher));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> greet());

		Instrumentation inst = attachAgent();
		if (inst == null) {
			return;
		}
		if (!inst.isRedefineClassesSupported()) {
			Messages.LOG.error("this JVM does not support class redefinition, hot reload disabled");
			return;
		}
		reloader = new Reloader(inst, new Projects(config));
		reloader.start();
		Messages.LOG.info("active ({}), watching {} project(s)", reloader.fullHotswap() ? "full hotswap" : "basic mode",
			reloader.watched().size());
	}

	static Optional<Reloader> reloader() {
		return Optional.ofNullable(reloader);
	}

	/** One line on the first world join, so you know whether it works and how to get full hotswap. */
	private static void greet() {
		if (greeted) {
			return;
		}
		greeted = true;
		if (reloader == null) {
			Messages.report(null, Level.ERROR, "hotreload.inactive", "Not active: could not attach to the game, see the log");
		} else if (!reloader.fullHotswap()) {
			Messages.report(null, Level.INFO, "hotreload.greet_basic",
				"Active in basic mode (method bodies). Type /hotreload setup for full hotswap");
		}
	}

	static @Nullable Path modJar() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
			.flatMap(m -> m.getOrigin().getPaths().stream().filter(Files::isRegularFile).findFirst())
			.orElse(null);
	}

	private static @Nullable Instrumentation attachAgent() {
		try {
			Path jar = modJar();
			if (jar == null) {
				Messages.LOG.error("Fabric Hot Reload must run from its jar (put it in the mods folder), hot reload disabled");
				return null;
			}
			String java = ProcessHandle.current().info().command().orElse("java");
			Process p = new ProcessBuilder(java, "-cp", jar.toString(), HotReloadAgent.class.getName(),
				Long.toString(ProcessHandle.current().pid()), jar.toString())
				.redirectErrorStream(true)
				.start();
			if (!p.waitFor(30, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				Messages.LOG.error("agent attach timed out");
				return null;
			}
			if (p.exitValue() != 0) {
				Messages.LOG.error("agent attach failed: {}", new String(p.getInputStream().readAllBytes()).trim());
				return null;
			}
			// agentmain ran in the system class loader; Fabric's loader has its own copy of the class.
			Class<?> agent = Class.forName(HotReloadAgent.class.getName(), true, ClassLoader.getSystemClassLoader());
			return (Instrumentation) agent.getField("instrumentation").get(null);
		} catch (Exception e) {
			Messages.LOG.error("could not attach agent, hot reload disabled", e);
			return null;
		}
	}
}
