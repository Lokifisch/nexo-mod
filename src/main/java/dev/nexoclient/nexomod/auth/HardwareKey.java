package dev.nexoclient.nexomod.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Derives the AES-256 key protecting the account store from this machine's
 * hardware identity — CPU, mainboard, and GPU(s), each by name plus serial
 * number where the platform actually exposes one. Nothing key-like is ever
 * written to disk, so a config folder that gets copied into a shared modpack
 * can't be decrypted anywhere except on the machine it came from.
 *
 * <p>Identifier availability is uneven across platforms and that's fine —
 * what matters is that the same machine produces the same set every run:
 * <ul>
 * <li>CPU serial: exposed on ARM boards; x86 hasn't shipped one since the
 * Pentium III, so there Windows' {@code ProcessorId} (a stable
 * capability-derived id) or nothing is used.</li>
 * <li>Board serial: real on Windows/macOS; root-only on most Linux distros,
 * so as a normal user it's consistently absent there.</li>
 * <li>GPU: no platform exposes a true serial, so the closest stable stand-ins
 * are used — PCI vendor/device ids on Linux (stable across driver updates,
 * unlike marketing names), name + PNP device path on Windows, chipset model
 * on macOS. Machines without any GPU simply contribute no GPU component.</li>
 * </ul>
 *
 * <p>Derivation runs once per game launch, off-thread, kicked off from mod
 * init so it's long finished before any screen touches the account store.
 *
 * <p>Threat model, honestly: this binds the file to the machine, it doesn't
 * beat an attacker who has both the file and knowledge of (or access to) the
 * victim's hardware identifiers — those are enumerable, not secret. The
 * attack it stops is exactly the accidental one: tokens riding along inside
 * a shared config folder and being readable wherever they land.
 */
public final class HardwareKey {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/auth");
	private static final int COMMAND_TIMEOUT_SECONDS = 20;

	private static final CompletableFuture<SecretKey> KEY =
			CompletableFuture.supplyAsync(HardwareKey::derive, runnable -> new Thread(runnable, "nexomod-hardware-key").start());

	private HardwareKey() {}

	/** Called at mod init purely to start the off-thread derivation early. */
	public static void warmUp() {
		// Class initialization already kicked off KEY; nothing else to do.
	}

	/**
	 * The machine-bound key, or {@code null} if not a single hardware
	 * identifier could be collected — in which case callers must fail safe
	 * (don't persist secrets, don't destroy files we couldn't verify).
	 */
	public static SecretKey await() {
		return KEY.join();
	}

	private static SecretKey derive() {
		List<String> parts = new ArrayList<>();
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		try {
			if (os.contains("win")) {
				collectWindows(parts);
			} else if (os.contains("mac")) {
				collectMac(parts);
			} else {
				collectLinux(parts);
			}
		} catch (Exception e) {
			LOGGER.error("Hardware fingerprinting failed", e);
		}

		if (parts.isEmpty()) {
			LOGGER.error("No hardware identifiers available on this system — saved accounts will not be persisted this session");
			return null;
		}

		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update("nexomod-hwkey-v1".getBytes(StandardCharsets.UTF_8));
			for (String part : parts) {
				digest.update((byte) '\n');
				digest.update(part.getBytes(StandardCharsets.UTF_8));
			}
			LOGGER.info("Derived account-store key from {} hardware identifier(s)", parts.size());
			return new SecretKeySpec(digest.digest(), "AES");
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 is mandated by the JCA spec", e);
		}
	}

	// --- Linux: plain file reads, no subprocesses ---

	private static void collectLinux(List<String> parts) {
		String cpuName = null;
		String cpuSerial = null;
		try {
			for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
				int colon = line.indexOf(':');
				if (colon < 0) {
					continue;
				}
				String field = line.substring(0, colon).trim();
				String value = line.substring(colon + 1).trim();
				if (cpuName == null && ("model name".equals(field) || "Model".equals(field))) {
					cpuName = value;
				} else if (cpuSerial == null && "Serial".equals(field)) {
					cpuSerial = value;
				}
			}
		} catch (IOException ignored) {
			// No /proc (unlikely); the DMI and DRM probes below still apply.
		}
		add(parts, "cpu.name", cpuName);
		add(parts, "cpu.serial", cpuSerial);

		add(parts, "board.name", joinNonBlank(
				readFirstLine(Path.of("/sys/class/dmi/id/board_vendor")),
				readFirstLine(Path.of("/sys/class/dmi/id/board_name"))));
		add(parts, "board.serial", readFirstLine(Path.of("/sys/class/dmi/id/board_serial")));

		List<String> gpus = new ArrayList<>();
		try (DirectoryStream<Path> cards = Files.newDirectoryStream(Path.of("/sys/class/drm"),
				entry -> entry.getFileName().toString().matches("card\\d+"))) {
			for (Path card : cards) {
				Path device = card.resolve("device");
				String ids = joinNonBlank(
						readFirstLine(device.resolve("vendor")),
						readFirstLine(device.resolve("device")),
						readFirstLine(device.resolve("subsystem_vendor")),
						readFirstLine(device.resolve("subsystem_device")));
				if (!ids.isBlank()) {
					gpus.add(ids);
				}
			}
		} catch (IOException ignored) {
			// No DRM subsystem — headless box or exotic setup; GPU component is skipped consistently.
		}
		addGpus(parts, gpus);
	}

	// --- Windows: one PowerShell CIM query for everything ---

	private static void collectWindows(List<String> parts) {
		// Single-quoted PowerShell strings only, so no escaping fights with ProcessBuilder's argument quoting.
		List<String> lines = runCommand("powershell", "-NoProfile", "-NonInteractive", "-Command",
				"$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1;"
						+ " 'cpu.name=' + $cpu.Name;"
						+ " 'cpu.serial=' + $cpu.ProcessorId;"
						+ " $board = Get-CimInstance Win32_BaseBoard | Select-Object -First 1;"
						+ " 'board.name=' + $board.Manufacturer + ' ' + $board.Product;"
						+ " 'board.serial=' + $board.SerialNumber;"
						+ " Get-CimInstance Win32_VideoController | ForEach-Object { 'gpu=' + $_.Name + '/' + $_.PNPDeviceID }");
		List<String> gpus = new ArrayList<>();
		for (String line : lines) {
			int eq = line.indexOf('=');
			if (eq < 0) {
				continue;
			}
			String field = line.substring(0, eq).trim();
			String value = line.substring(eq + 1).trim();
			if ("gpu".equals(field)) {
				if (!value.isBlank()) {
					gpus.add(value);
				}
			} else {
				add(parts, field, value);
			}
		}
		addGpus(parts, gpus);
	}

	// --- macOS ---

	private static void collectMac(List<String> parts) {
		add(parts, "cpu.name", firstLineOf(runCommand("/usr/sbin/sysctl", "-n", "machdep.cpu.brand_string")));
		add(parts, "board.name", firstLineOf(runCommand("/usr/sbin/sysctl", "-n", "hw.model")));
		for (String line : runCommand("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice")) {
			if (line.contains("IOPlatformSerialNumber")) {
				String value = line.replaceAll(".*\"IOPlatformSerialNumber\"\\s*=\\s*\"([^\"]*)\".*", "$1");
				if (!value.equals(line)) {
					add(parts, "board.serial", value);
				}
				break;
			}
		}
		List<String> gpus = new ArrayList<>();
		for (String line : runCommand("/usr/sbin/system_profiler", "SPDisplaysDataType")) {
			String trimmed = line.trim();
			if (trimmed.startsWith("Chipset Model:")) {
				gpus.add(trimmed.substring("Chipset Model:".length()).trim());
			}
		}
		addGpus(parts, gpus);
	}

	// --- shared helpers ---

	private static void add(List<String> parts, String label, String value) {
		if (value != null && !value.isBlank()) {
			parts.add(label + "=" + value.trim());
		}
	}

	/** Sorted so multi-GPU enumeration order (which the OS doesn't guarantee) can't change the key. */
	private static void addGpus(List<String> parts, List<String> gpus) {
		gpus.stream().sorted().forEach(gpu -> add(parts, "gpu", gpu));
	}

	private static String readFirstLine(Path path) {
		try (BufferedReader reader = Files.newBufferedReader(path)) {
			return reader.readLine();
		} catch (IOException e) {
			return null; // Unreadable (missing, or root-only like board_serial) — consistently absent.
		}
	}

	private static String joinNonBlank(String... values) {
		StringBuilder joined = new StringBuilder();
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				if (joined.length() > 0) {
					joined.append(' ');
				}
				joined.append(value.trim());
			}
		}
		return joined.toString();
	}

	private static String firstLineOf(List<String> lines) {
		return lines.isEmpty() ? null : lines.get(0);
	}

	private static List<String> runCommand(String... command) {
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			List<String> lines = new ArrayList<>();
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					lines.add(line);
				}
			}
			if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				LOGGER.warn("Hardware probe timed out: {}", command[0]);
				return List.of();
			}
			return lines;
		} catch (IOException e) {
			LOGGER.warn("Hardware probe unavailable: {} ({})", command[0], e.toString());
			return List.of();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return List.of();
		}
	}
}
