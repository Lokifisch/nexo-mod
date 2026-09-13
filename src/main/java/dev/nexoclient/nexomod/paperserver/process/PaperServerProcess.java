package dev.nexoclient.nexomod.paperserver.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches and supervises a Paper server subprocess. Unlike
 * {@code auth.HardwareKey}'s short-lived {@code waitFor}-with-timeout
 * pattern, this process is meant to run for a long time, so exit is
 * observed via {@link Process#onExit()} rather than a blocking wait — the
 * caller stays free to do other things while the server runs.
 *
 * <p>Stopping is expected to go through RCON ({@code rcon.RconClient},
 * command {@code "stop"}) first, for a clean save-and-shutdown;
 * {@link #awaitExitOrKill} is the bounded fallback for when that doesn't
 * finish in time, not the primary path.
 */
public final class PaperServerProcess {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/paperserver");

	private final Process process;

	private PaperServerProcess(Process process) {
		this.process = process;
	}

	/** Starts {@code paper.jar} in {@code serverDir}, using this same JVM's own java binary. */
	public static PaperServerProcess start(Path serverDir, int heapMb) throws IOException {
		String javaBin = ProcessHandle.current().info().command().orElse("java");
		Path logFile = serverDir.resolve("logs").resolve("nexo-launch.log");
		Files.createDirectories(logFile.getParent());

		ProcessBuilder builder = new ProcessBuilder(javaBin, "-Xmx" + heapMb + "M", "-jar", "paper.jar", "--nogui")
				.directory(serverDir.toFile())
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

		Process proc = builder.start();
		LOGGER.info("[nexomod] Paper server starting in {} (pid {})", serverDir, proc.pid());
		return new PaperServerProcess(proc);
	}

	public long pid() {
		return process.pid();
	}

	public boolean isAlive() {
		return process.isAlive();
	}

	/** Runs {@code callback} once the process exits, whatever the reason. */
	public void onExit(Runnable callback) {
		process.onExit().thenRun(callback);
	}

	/** Waits up to {@code timeout} for a graceful exit (send RCON "stop" first); force-kills past that. */
	public void awaitExitOrKill(Duration timeout) throws InterruptedException {
		if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
			return;
		}
		LOGGER.warn("[nexomod] Paper server (pid {}) did not exit within {}, killing it", process.pid(), timeout);
		process.destroyForcibly();
	}

	/** Kills immediately, no grace period — for when RCON (the only clean-shutdown path) never came up at all. */
	public void kill() {
		LOGGER.warn("[nexomod] Killing Paper server (pid {})", process.pid());
		process.destroyForcibly();
	}
}
