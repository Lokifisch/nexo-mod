package dev.nexoclient.nexomod.nativecore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Locale;

/**
 * Finds, extracts, and loads {@code libnexo_core}.
 *
 * <p>The library can't simply be {@code System.loadLibrary}'d: it ships inside
 * the mod jar, and {@code dlopen}/{@code LoadLibrary} only take filesystem
 * paths. So the matching platform's copy is unpacked to a fresh temp directory
 * and loaded by absolute path.
 *
 * <p><b>Every failure here is survivable.</b> Only Linux x86-64 can be built on
 * the current dev machine, so a jar with no Windows or macOS binary in it is the
 * normal case, not a broken build — and a Windows player must get a working mod
 * with the native features switched off rather than a crash on startup. Nothing
 * in this class throws past {@link NexoNative#bootstrap()}.
 */
final class NativeLoader {
	/**
	 * Matches {@code [lib].name} in {@code rust-core/Cargo.toml} and the
	 * {@code cargoBuild} task in {@code build.gradle}. All three have to agree.
	 */
	static final String LIB_STEM = "nexo_core";

	/**
	 * Escape hatch for developing the Rust side: point this at a
	 * {@code target/release/libnexo_core.so} and the jar's copy is ignored, so
	 * a {@code cargo build} is enough to test a change instead of a full
	 * {@code ./gradlew build} plus a game restart.
	 */
	private static final String OVERRIDE_PROPERTY = "nexomod.nativecore.library";

	private NativeLoader() {
	}

	/**
	 * @throws UnsatisfiedLinkError if the library is missing, unreadable, or
	 *         rejected by the OS loader — the single failure type
	 *         {@link NexoNative#bootstrap()} catches
	 */
	static void load() {
		String override = System.getProperty(OVERRIDE_PROPERTY);
		if (override != null && !override.isBlank()) {
			System.load(Path.of(override).toAbsolutePath().toString());
			return;
		}

		String platform = platformDirectory();
		if (platform == null) {
			throw new UnsatisfiedLinkError("unsupported platform: " + System.getProperty("os.name")
					+ " / " + System.getProperty("os.arch"));
		}

		String resource = "/assets/nexomod/natives/" + platform + "/" + libraryFileName();
		try {
			System.load(extract(resource).toString());
		} catch (IOException e) {
			// Rewrapped rather than propagated: the caller only distinguishes
			// "loaded" from "didn't", and a checked exception here would leak
			// that distinction into every caller of bootstrap().
			throw new UnsatisfiedLinkError("could not unpack " + resource + ": " + e);
		}
	}

	/**
	 * The {@code <os>-<arch>} directory name, or {@code null} on a platform the
	 * build has no naming convention for.
	 *
	 * <p>Kept identical to the mapping in {@code build.gradle}'s
	 * {@code cargoBuild}, which derives these from Rust target triples — a
	 * disagreement between the two shows up only at runtime, as a library that
	 * was built but can never be found.
	 */
	static String platformDirectory() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

		String osName;
		if (os.contains("win")) {
			osName = "windows";
		} else if (os.contains("mac") || os.contains("darwin")) {
			osName = "macos";
		} else if (os.contains("nux") || os.contains("nix")) {
			osName = "linux";
		} else {
			return null;
		}

		String archName = switch (arch) {
			// The JVM reports "amd64" on Linux/Windows and "x86_64" on macOS for
			// the same hardware; Rust calls both x86_64.
			case "amd64", "x86_64", "x64" -> "x86_64";
			case "aarch64", "arm64" -> "aarch64";
			default -> null;
		};
		if (archName == null) {
			return null;
		}
		return osName + "-" + archName;
	}

	static String libraryFileName() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			return LIB_STEM + ".dll";
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return "lib" + LIB_STEM + ".dylib";
		}
		return "lib" + LIB_STEM + ".so";
	}

	/**
	 * Copies the resource to a private temp directory and returns its absolute
	 * path.
	 *
	 * <p>A fresh directory per launch rather than a cached one keyed by hash:
	 * caching would have to defend against a half-written file left by a
	 * previous crash, and against a second Minecraft instance loading the same
	 * path while this one overwrites it. Re-copying a few hundred kilobytes once
	 * per launch is cheaper than getting that right.
	 */
	private static Path extract(String resource) throws IOException {
		Path dir = createPrivateTempDirectory();
		Path target = dir.resolve(libraryFileName());

		try (InputStream in = NativeLoader.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("not present in the jar");
			}
			Files.copy(in, target);
		}

		// Best-effort executable bit. On the platforms that need it the temp
		// directory is already 0700, so this only ever widens permissions for
		// the owner.
		//noinspection ResultOfMethodCallIgnored
		target.toFile().setExecutable(true, true);

		Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteQuietly(dir), "nexomod-native-cleanup"));
		return target;
	}

	private static Path createPrivateTempDirectory() throws IOException {
		try {
			FileAttribute<?> ownerOnly = PosixFilePermissions
					.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
			return Files.createTempDirectory("nexomod-native-", ownerOnly);
		} catch (UnsupportedOperationException e) {
			// Windows has no POSIX view. Its per-user temp directory
			// (%LOCALAPPDATA%\Temp) already inherits an owner-only ACL, so the
			// fallback is not a downgrade there.
			return Files.createTempDirectory("nexomod-native-");
		}
	}

	/**
	 * Deletes the extracted library at exit.
	 *
	 * <p>Expected to fail on Windows, where a loaded DLL stays mapped for the
	 * lifetime of the process and cannot be unlinked — hence "quietly". The
	 * leftover is one file in the temp directory, which the OS cleans up
	 * eventually; logging a warning about it every single shutdown would train
	 * people to ignore the log.
	 */
	private static void deleteQuietly(Path dir) {
		try (var walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				//noinspection ResultOfMethodCallIgnored
				p.toFile().delete();
			});
		} catch (IOException | RuntimeException ignored) {
			// See above.
		}
	}
}
