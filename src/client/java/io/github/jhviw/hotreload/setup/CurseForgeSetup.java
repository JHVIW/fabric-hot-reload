package io.github.jhviw.hotreload.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Points a CurseForge instance at JetBrains Runtime with full hotswap. CurseForge has no per-instance Java path, only
 * one path per Java version (Settings, "javaVersionsPaths" in {@code %APPDATA%/CurseForge/storage.json}); Minecraft
 * 26.x uses Java 25, so the "java25" path is set to JBR (affects every instance on Java 25, which is harmless: JBR
 * is a normal OpenJDK build). Only the given instance gets G1 + the hotswap flag in its JVM arguments.
 * <p>
 * Both files are edited as text so everything else stays byte-identical, and backed up first. CurseForge must be
 * closed: it rewrites both files when it exits. JDK classes only (runs via {@code java -jar}).
 */
public final class CurseForgeSetup {
	private static final String BACKUP_PREFIX = "hotreload-backup-";
	private static final Pattern ARGS = Pattern.compile("\"javaArgsOverride\":(null|\"((?:[^\"\\\\]|\\\\.)*)\")");
	private static final Pattern ENTRY = Pattern.compile("\"(java\\d+)\":\"((?:[^\"\\\\]|\\\\.)*)\"");
	/** Other collectors make the JVM refuse to start with the hotswap flag. */
	private static final List<String> OTHER_GC_FLAGS = List.of("-XX:+UseZGC", "-XX:+ZGenerational", "-XX:-ZGenerational",
		"-XX:+UseShenandoahGC", "-XX:+UseParallelGC", "-XX:+UseSerialGC", "-XX:+UseEpsilonGC", "-XX:+UseG1GC");

	private CurseForgeSetup() {
	}

	public static Path storageFile() {
		String appData = System.getenv("APPDATA");
		return Path.of(appData != null ? appData : System.getProperty("user.home"), "CurseForge", "storage.json");
	}

	public static boolean isInstance(Path gameDir) {
		return Files.isRegularFile(gameDir.resolve("minecraftinstance.json"));
	}

	/** Returns a list of what was done; throws with a user-facing message when it cannot proceed. */
	public static List<String> apply(Path instanceDir, Path java) throws IOException {
		Path storage = storageFile();
		Path instance = instanceDir.resolve("minecraftinstance.json");
		checkClosed();
		require(storage);
		require(instance);
		if (!Files.isRegularFile(java)) {
			throw new IOException("Java not found: " + java);
		}

		Path backup = instanceDir.resolve(BACKUP_PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
		Files.createDirectories(backup);
		Files.copy(storage, backup.resolve("storage.json"));
		Files.copy(instance, backup.resolve("minecraftinstance.json"));

		List<String> done = new ArrayList<>();
		done.add("backup: " + backup);
		write(storage, setJava25Path(read(storage), java.toString()));
		done.add("CurseForge Java 25 path -> " + java);
		String args = read(instance);
		String newArgs = withHotswapArgs(args);
		if (!newArgs.equals(args)) {
			write(instance, newArgs);
			done.add("instance JVM arguments -> " + currentArgs(newArgs).orElse(""));
		} else {
			done.add("instance JVM arguments already set");
		}
		return done;
	}

	/** Restores the files from the newest backup made by {@link #apply}. */
	public static List<String> undo(Path instanceDir) throws IOException {
		checkClosed();
		Path backup;
		try (Stream<Path> dirs = Files.list(instanceDir)) {
			backup = dirs.filter(d -> d.getFileName().toString().startsWith(BACKUP_PREFIX)).max(Path::compareTo)
				.orElseThrow(() -> new IOException("no " + BACKUP_PREFIX + "* folder in " + instanceDir));
		}
		Files.copy(backup.resolve("storage.json"), storageFile(), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(backup.resolve("minecraftinstance.json"), instanceDir.resolve("minecraftinstance.json"), StandardCopyOption.REPLACE_EXISTING);
		return List.of("restored from " + backup);
	}

	public static void checkClosed() throws IOException {
		List<String> running = ProcessHandle.allProcesses()
			.map(p -> p.info().command().map(c -> Path.of(c).getFileName().toString()).orElse(""))
			.filter(n -> n.equalsIgnoreCase("CurseForge.exe") || n.equalsIgnoreCase("Curse.Agent.Host.exe"))
			.distinct().toList();
		if (!running.isEmpty()) {
			throw new IOException("Close CurseForge completely first (tray icon > Exit); it rewrites its settings when it exits. Still running: "
				+ String.join(", ", running));
		}
	}

	// ---- storage.json --------------------------------------------------------------------------------------------

	/**
	 * storage.json holds the Minecraft settings as a JSON document inside a JSON string, so the
	 * {@code javaVersionsPaths} object appears escaped: {@code \"javaVersionsPaths\":{\"java8\":\"\"}}.
	 * Only that object is rewritten.
	 */
	static String setJava25Path(String raw, String javaPath) throws IOException {
		String marker = "\\\"javaVersionsPaths\\\":";
		int at = raw.indexOf(marker);
		if (at < 0) {
			throw new IOException("javaVersionsPaths not found in storage.json (open CurseForge settings once, then retry)");
		}
		int start = at + marker.length();
		int end;
		Map<String, String> entries = new LinkedHashMap<>();
		if (raw.startsWith("null", start)) {
			end = start + 4;
		} else if (raw.charAt(start) == '{') {
			end = raw.indexOf('}', start) + 1;
			Matcher m = ENTRY.matcher(unescapeJson(raw.substring(start, end)));
			while (m.find()) {
				entries.put(m.group(1), m.group(2));
			}
		} else {
			throw new IOException("unexpected javaVersionsPaths format in storage.json");
		}
		entries.put("java25", escapeJson(javaPath));
		StringBuilder inner = new StringBuilder("{");
		entries.forEach((k, v) -> inner.append(inner.length() > 1 ? "," : "").append('"').append(k).append("\":\"").append(v).append('"'));
		inner.append('}');
		return raw.substring(0, start) + escapeJson(inner.toString()) + raw.substring(end);
	}

	// ---- minecraftinstance.json ----------------------------------------------------------------------------------

	static String withHotswapArgs(String raw) throws IOException {
		Matcher m = ARGS.matcher(raw);
		if (!m.find()) {
			throw new IOException("javaArgsOverride not found in minecraftinstance.json");
		}
		List<String> args = new ArrayList<>();
		if (m.group(2) != null) {
			args.addAll(Arrays.stream(unescapeJson(m.group(2)).trim().split("\\s+")).filter(a -> !a.isEmpty()).toList());
		}
		args.removeIf(OTHER_GC_FLAGS::contains);
		args.removeIf(a -> a.equals(Jbr.HOTSWAP_FLAG));
		args.addAll(0, List.of(Jbr.GC_FLAG, Jbr.HOTSWAP_FLAG));
		String value = "\"javaArgsOverride\":\"" + escapeJson(String.join(" ", args)) + "\"";
		return raw.substring(0, m.start()) + value + raw.substring(m.end());
	}

	static Optional<String> currentArgs(String raw) {
		Matcher m = ARGS.matcher(raw);
		return m.find() && m.group(2) != null ? Optional.of(unescapeJson(m.group(2))) : Optional.empty();
	}

	// ---- helpers ---------------------------------------------------------------------------------------------------

	private static String escapeJson(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String unescapeJson(String s) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < s.length()) {
				out.append(s.charAt(++i));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static void require(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IOException("not found: " + file);
		}
	}

	private static String read(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
	}

	private static void write(Path file, String content) throws IOException {
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}
}
