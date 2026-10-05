package dev.nexoclient.nexomod.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An AES-256 key kept in the operating system's own credential store —
 * libsecret on Linux ({@code secret-tool}), Keychain on macOS
 * ({@code security}), Credential Locker on Windows (PowerShell) — instead of
 * being derived from hardware identifiers. It survives a GPU/board swap, which
 * the hardware key does not.
 *
 * <p>The keychain holds a key, not the account data itself: Windows caps a
 * stored credential at a couple of KB, and a Minecraft token plus a Microsoft
 * refresh token can exceed that.
 *
 * <p>Shells out rather than going through the native core, because that
 * library is only built for Linux today. Everything fails soft: no tool, no
 * running keyring daemon, a locked keychain — all yield {@code null} and the
 * caller keeps using the hardware key. Only the Linux path has been run;
 * macOS and Windows are written to each tool's documented behaviour.
 */
final class KeychainKey {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/auth");
	private static final String SERVICE = "nexomod";
	private static final String ACCOUNT = "accounts-key";
	private static final int TIMEOUT_SECONDS = 20;

	private static final String WIN_PREAMBLE = "[void][Windows.Security.Credentials.PasswordVault,Windows.Security.Credentials,ContentType=WindowsRuntime];"
			+ " $v = New-Object Windows.Security.Credentials.PasswordVault;";

	/** Off-thread because an unlock prompt can block until the user answers it. */
	private static final CompletableFuture<SecretKey> KEY =
			CompletableFuture.supplyAsync(KeychainKey::resolve, runnable -> daemon(runnable, "nexomod-keychain-key"))
					// Backstop so get() can never hang the caller: null = keychain unusable.
					.orTimeout(60, TimeUnit.SECONDS).exceptionally(t -> {
						LOGGER.warn("Keychain key unavailable ({})", t.toString());
						return null;
					});

	private static void daemon(Runnable runnable, String name) {
		Thread thread = new Thread(runnable, name);
		thread.setDaemon(true);
		thread.start();
	}

	private KeychainKey() {}

	/** Called at mod init purely to start the off-thread lookup early. */
	static void warmUp() {}

	/** The keychain-held key, created on first use; {@code null} if this system's keychain can't be used. */
	static SecretKey get() {
		return KEY.join();
	}

	private static SecretKey resolve() {
		try {
			String stored = lookup();
			if (stored != null) {
				byte[] raw = Base64.getDecoder().decode(stored);
				if (raw.length == 32) {
					return new SecretKeySpec(raw, "AES");
				}
				LOGGER.warn("Keychain entry has the wrong length; ignoring it");
				return null; // Don't overwrite an entry we don't understand.
			}
			if (java.nio.file.Files.exists(dev.nexoclient.nexomod.util.NexoPaths.sharedDataDir().resolve("accounts.kc.dat"))) {
				// A keychain copy exists, so the key does too: "not found" here is ambiguous. Never mint a replacement.
				LOGGER.warn("Keychain entry reported missing but accounts.kc.dat exists; not creating a new key");
				return null;
			}
			byte[] raw = new byte[32];
			new SecureRandom().nextBytes(raw);
			if (!store(Base64.getEncoder().encodeToString(raw))) {
				return null;
			}
			LOGGER.info("Created account key in the OS keychain");
			return new SecretKeySpec(raw, "AES");
		} catch (Exception e) {
			LOGGER.warn("OS keychain unavailable ({}); using the hardware key only", e.toString());
			return null;
		}
	}

	private static String lookup() throws IOException, InterruptedException {
		Result r = switch (os()) {
			case WINDOWS -> run(null, "powershell", "-NoProfile", "-NonInteractive", "-Command",
					WIN_PREAMBLE + " try { $c = $v.Retrieve('" + SERVICE + "','" + ACCOUNT + "'); $c.RetrievePassword(); $c.Password } catch { if ($_.Exception.HResult -eq -2147023728) { exit 3 } else { exit 4 } }");
			case MAC -> run(null, "/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w");
			case LINUX -> run(null, "secret-tool", "lookup", "service", SERVICE, "account", ACCOUNT);
		};
		String out = r.out.trim();
		if (r.code == 0 && !out.isEmpty()) {
			return out;
		}
		// Only each tool's definite "no such entry" counts as not-found (the caller may then create a key).
		// Anything else — locked keychain, dead daemon, timeout — is ambiguous and must not lead to an overwrite.
		boolean notFound = switch (os()) {
			case WINDOWS -> r.code == 3;
			case MAC -> r.code == 44;
			// secret-tool prints nothing on a real miss; a dismissed prompt / dbus error / locked collection prints text.
			case LINUX -> r.code == 1 && out.isEmpty() && r.err.isBlank();
		};
		if (notFound) {
			return null;
		}
		throw new IOException("keychain lookup failed (exit " + r.code + ")");
	}

	private static boolean store(String value) throws IOException, InterruptedException {
		Result r = switch (os()) {
			case WINDOWS -> run(value, "powershell", "-NoProfile", "-NonInteractive", "-Command",
					WIN_PREAMBLE + " $p = [Console]::In.ReadLine();"
							+ " $v.Add((New-Object Windows.Security.Credentials.PasswordCredential('" + SERVICE + "','" + ACCOUNT + "',$p)))");
			// ponytail: security takes the value as an argument, so it is visible in the
			// process list for a few ms. It's a random key, not a token; the fix would be -X hex stdin.
			case MAC -> run(null, "/usr/bin/security", "add-generic-password", "-U", "-s", SERVICE, "-a", ACCOUNT, "-w", value);
			case LINUX -> run(value, "secret-tool", "store", "--label=Nexo Mod account key", "service", SERVICE, "account", ACCOUNT);
		};
		if (r.code != 0) {
			LOGGER.warn("Could not write to the OS keychain (exit {})", r.code);
		}
		return r.code == 0;
	}

	private enum Os { WINDOWS, MAC, LINUX }

	private static Os os() {
		String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		return name.contains("win") ? Os.WINDOWS : name.contains("mac") ? Os.MAC : Os.LINUX;
	}

	private record Result(int code, String out, String err) {}

	private static Result run(String stdin, String... command) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command)
				.start(); // stderr stays a separate pipe so it can never end up in the Base64
		try (OutputStream in = process.getOutputStream()) {
			if (stdin != null) {
				in.write((stdin + "\n").getBytes(StandardCharsets.UTF_8));
			}
		}
		// Drained off-thread: a blocking read before waitFor would make the timeout unreachable.
		CompletableFuture<String> out = drain(process.getInputStream(), 1 << 16);
		CompletableFuture<String> err = drain(process.getErrorStream(), 4096);
		if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			kill(process);
			return new Result(-1, "timed out", "timed out");
		}
		try {
			return new Result(process.exitValue(), out.get(2, TimeUnit.SECONDS), err.get(2, TimeUnit.SECONDS));
		} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
			// A descendant still holds a pipe open, so EOF never comes.
			kill(process);
			return new Result(-1, "output drain timed out", "output drain timed out");
		}
	}

	private static void kill(Process process) {
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
		try {
			process.getInputStream().close();
			process.getErrorStream().close();
		} catch (IOException ignored) {
			// already closed
		}
	}

	/** Reads at most {@code max} bytes (the rest is discarded so the child never blocks on a full pipe). */
	private static CompletableFuture<String> drain(java.io.InputStream stream, int max) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				byte[] head = stream.readNBytes(max);
				stream.transferTo(java.io.OutputStream.nullOutputStream());
				return new String(head, StandardCharsets.UTF_8);
			} catch (IOException e) {
				return "";
			}
		}, runnable -> daemon(runnable, "nexomod-keychain-drain"));
	}
}
