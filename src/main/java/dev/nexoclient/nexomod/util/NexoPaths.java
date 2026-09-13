package dev.nexoclient.nexomod.util;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The launcher's platform data directory, resolved the same way its
 * {@code directories} crate does. These paths are a contract between the
 * Mod and Nexo Client, not a preference — see
 * {@code Client/crates/nexo-core/src/paths.rs} and
 * {@code Mod/docs/SHARED-ACCOUNT-STORE.md}.
 *
 * <p>Extracted out of {@code auth.AccountStore} (the first feature to need
 * it) so other shared-data-directory consumers, such as the Paper server
 * registry, resolve the exact same path rather than re-deriving it.
 */
public final class NexoPaths {
	private NexoPaths() {}

	public static Path sharedDataDir() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home", ".");

		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			Path base = appData != null && !appData.isBlank()
					? Path.of(appData)
					: Path.of(home, "AppData", "Roaming");
			return base.resolve("nexoclient").resolve("nexo").resolve("data");
		}
		if (os.contains("mac")) {
			return Path.of(home, "Library", "Application Support", "dev.nexoclient.nexo");
		}

		// Linux and the BSDs honour XDG_DATA_HOME when it is set.
		String xdg = System.getenv("XDG_DATA_HOME");
		Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".local", "share");
		return base.resolve("nexo");
	}
}
