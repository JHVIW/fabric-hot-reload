package io.github.jhviw.hotreload.setup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * JetBrains Runtime: a regular OpenJDK build whose HotSpot can redefine classes with added/removed methods and fields
 * ({@code -XX:+AllowEnhancedClassRedefinition}, G1 or Serial GC only). Downloaded from JetBrains' CDN, checked
 * against pinned SHA-512 sums and unpacked into {@code ~/.hotreload/jbr/}. JDK classes only.
 */
public final class Jbr {
	public static final String VERSION = "25.0.4.1";
	public static final String BUILD = "b635.72";
	public static final String HOTSWAP_FLAG = "-XX:+AllowEnhancedClassRedefinition";
	public static final String GC_FLAG = "-XX:+UseG1GC";

	/** SHA-512 of {@code jbr-<VERSION>-<platform>-<BUILD>.tar.gz}, from JetBrains' .checksum files. */
	private static final Map<String, String> SHA512 = Map.of(
		"windows-x64", "f1166f656a59bbf9166762c0ef161332eb8dce9ba7c29444d9f5ba2c3c296fd9ef9eaeb9f2f0c74ac799c7fea9fd4790702a48ceba5e31d2143a99863a38cb29",
		"windows-aarch64", "ac9e2e0d36d1c943ac958e546416e5836fba5e9fde773498494a604e9e8bf2c0e61438dea30e6cfc3d86a3d3fc66eff93f5a68a32c42ccf870072bfabe82c459",
		"linux-x64", "b7cff4d94f5e8240024834d966451c15275b9d5ed01b8a806b0d4ef84248abb53b0b58beb40a8518ab1d2bba8d36878e04424e2251e3b0be87098fed219cc47d",
		"linux-aarch64", "4e8f30fa5e71089d0ae4d96db4d5b409bf76d61bf3f0041b20ea21ae5498cda23aa3cf341f6848eddfaa4174575cb4142f44cd5eac4bd9237cbad81816e368fc",
		"osx-x64", "40f5fa75c6bdb4e569d3e10067e38a6a9358c28696a0f833a21eeaf6ec746016bfb8b52ea3d5c62e727c1cc17941b47412a62083c97542214edae27e10f9aa1f",
		"osx-aarch64", "b8fec9e52cb293ca355c0703517e2fee1d614a74e7a01a9b642f2c35c1b668de6431cf90e4ab5f7763d75aea0bcc058984ef497eb0541dee8fb2cd68aad83420");

	private Jbr() {
	}

	/** Whether this JVM runs with enhanced class redefinition (only possible on JBR). */
	public static boolean runningWithHotswap() {
		try {
			return ManagementFactory.getRuntimeMXBean().getInputArguments().contains(HOTSWAP_FLAG);
		} catch (Throwable t) {
			return false;
		}
	}

	public static String platform() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
		String o = os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "osx" : "linux";
		String a = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x64";
		return o + "-" + a;
	}

	public static Path home() {
		return HotReloadPaths.JBR.resolve(VERSION + "-" + BUILD + "-" + platform());
	}

	/**
	 * The Java executable inside a JBR folder. {@code gui} picks {@code javaw.exe} on Windows (what launchers want,
	 * no console window); otherwise {@code java}.
	 */
	public static Path executable(Path home, boolean gui) {
		String p = platform();
		if (p.startsWith("windows")) {
			return home.resolve("bin").resolve(gui ? "javaw.exe" : "java.exe");
		}
		if (p.startsWith("osx")) {
			return home.resolve("Contents/Home/bin/java");
		}
		return home.resolve("bin/java");
	}

	public static Optional<Path> installed() {
		Path exe = executable(home(), true);
		return Files.isRegularFile(exe) ? Optional.of(exe) : Optional.empty();
	}

	/** Downloads, verifies and unpacks JBR (if not there yet) and returns the launcher-facing executable. */
	public static Path install(Consumer<String> progress) throws IOException, InterruptedException {
		Optional<Path> existing = installed();
		if (existing.isPresent()) {
			return existing.get();
		}
		String platform = platform();
		String expected = SHA512.get(platform);
		if (expected == null) {
			throw new IOException("no JetBrains Runtime build for " + platform);
		}
		String file = "jbr-" + VERSION + "-" + platform + "-" + BUILD + ".tar.gz";
		URI uri = URI.create("https://cache-redirector.jetbrains.com/intellij-jbr/" + file);
		Files.createDirectories(HotReloadPaths.JBR);
		Path archive = HotReloadPaths.JBR.resolve(file + ".part");

		HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			throw new IOException("download failed: HTTP " + response.statusCode() + " for " + uri);
		}
		long total = response.headers().firstValueAsLong("content-length").orElse(-1);
		MessageDigest sha;
		try {
			sha = MessageDigest.getInstance("SHA-512");
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
		try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(archive)) {
			byte[] buf = new byte[1 << 16];
			long done = 0;
			int lastPercent = -10;
			for (int n; (n = in.read(buf)) > 0; ) {
				out.write(buf, 0, n);
				sha.update(buf, 0, n);
				done += n;
				if (total > 0) {
					int percent = (int) (done * 100 / total);
					if (percent >= lastPercent + 10) {
						lastPercent = percent - percent % 10;
						progress.accept(lastPercent + "% (" + done / (1 << 20) + " / " + total / (1 << 20) + " MB)");
					}
				}
			}
		}
		String actual = HexFormat.of().formatHex(sha.digest());
		if (!actual.equalsIgnoreCase(expected)) {
			Files.deleteIfExists(archive);
			throw new IOException("checksum mismatch for " + file + ", download discarded");
		}

		progress.accept("unpacking");
		Path unpack = HotReloadPaths.JBR.resolve("unpack-" + System.currentTimeMillis());
		Files.createDirectories(unpack);
		// tar ships with Windows 10+, macOS and Linux.
		Process tar = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", unpack.toString()).redirectErrorStream(true).start();
		String tarOutput = new String(tar.getInputStream().readAllBytes());
		if (tar.waitFor() != 0) {
			throw new IOException("unpacking failed: " + tarOutput.trim());
		}
		Path top;
		try (Stream<Path> children = Files.list(unpack)) {
			top = children.filter(Files::isDirectory).findFirst().orElseThrow(() -> new IOException("archive was empty"));
		}
		Files.move(top, home(), StandardCopyOption.ATOMIC_MOVE);
		Files.deleteIfExists(unpack);
		Files.deleteIfExists(archive);
		return executable(home(), true);
	}
}
