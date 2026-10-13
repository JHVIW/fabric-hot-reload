package io.github.jhviw.hotreload;

import io.github.jhviw.hotreload.setup.HotReloadPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every result goes to three places: the game chat (translated), the game log, and {@code ~/.hotreload/events.log},
 * which {@code ./gradlew hotReload} reads to print the outcome in your terminal.
 */
public final class Messages {
	public static final Logger LOG = LoggerFactory.getLogger("Fabric Hot Reload");
	private static final long MAX_EVENTS_SIZE = 1 << 20;

	static volatile boolean chat = true;
	private static final Map<String, Long> LAST_EVENT = new ConcurrentHashMap<>();

	public enum Level {
		INFO(ChatFormatting.GREEN),
		WARN(ChatFormatting.YELLOW),
		ERROR(ChatFormatting.RED);

		final ChatFormatting color;

		Level(ChatFormatting color) {
			this.color = color;
		}
	}

	private Messages() {
	}

	/**
	 * @param modId   mod the message is about ({@code null} = general), lets the Gradle task pick its own lines
	 * @param key     translation key ({@code hotreload.*} in the lang files)
	 * @param english English text with {@code %s} placeholders, used for the log/events and as fallback
	 */
	public static void report(@Nullable String modId, Level level, String key, String english, Object... args) {
		String text = String.format(english, args);
		switch (level) {
			case INFO -> LOG.info(text);
			case WARN -> LOG.warn(text);
			case ERROR -> LOG.error(text);
		}
		appendEvent(modId, level, text);
		if (modId != null) {
			LAST_EVENT.put(modId, System.currentTimeMillis());
		}
		if (chat) {
			toChat(Component.translatableWithFallback(key, english, args).withStyle(level.color));
		}
	}

	/** Posts to chat from any thread, prefixed with "[Hot Reload]". */
	public static void toChat(Component message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.gui != null) {
				MutableComponent line = Component.translatableWithFallback("hotreload.prefix", "[Hot Reload] ").withStyle(ChatFormatting.LIGHT_PURPLE);
				mc.gui.hud.getChat().addClientSystemMessage(line.append(message));
			}
		});
	}

	/** When the last message about this mod was reported (0 = never). */
	static long lastEventAt(String modId) {
		return LAST_EVENT.getOrDefault(modId, 0L);
	}

	static void startEventLog() {
		try {
			Files.createDirectories(HotReloadPaths.HOME);
			if (Files.exists(HotReloadPaths.EVENTS) && Files.size(HotReloadPaths.EVENTS) > MAX_EVENTS_SIZE) {
				Files.delete(HotReloadPaths.EVENTS);
			}
		} catch (IOException e) {
			LOG.warn("cannot use {}", HotReloadPaths.EVENTS, e);
		}
	}

	/** One line per event: {@code <millis>\t<modId or *>\t<LEVEL>\t<text>}. */
	private static synchronized void appendEvent(@Nullable String modId, Level level, String text) {
		String line = System.currentTimeMillis() + "\t" + (modId != null ? modId : "*") + "\t" + level + "\t"
			+ text.replace('\n', ' ').replace('\t', ' ') + System.lineSeparator();
		try {
			Files.writeString(HotReloadPaths.EVENTS, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOG.debug("cannot write event", e);
		}
	}
}
