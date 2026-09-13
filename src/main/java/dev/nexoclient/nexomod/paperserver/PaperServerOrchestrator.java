package dev.nexoclient.nexomod.paperserver;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.nexoclient.nexomod.lantunnel.LanTunnel;
import dev.nexoclient.nexomod.lantunnel.LanTunnelSession;
import dev.nexoclient.nexomod.paperserver.convert.WorldConverter;
import dev.nexoclient.nexomod.paperserver.download.PaperApiClient;
import dev.nexoclient.nexomod.paperserver.process.PaperServerProcess;
import dev.nexoclient.nexomod.paperserver.rcon.RconClient;

/**
 * The convert -> host -> stop -> convert-back loop, shared by
 * {@link PaperServerDebug} and the real UI screens. Deliberately holds no
 * per-server state beyond what {@link PaperServerRegistry} already persists
 * to disk: the only thing kept in memory here is the {@link PaperServerProcess}
 * handle for a server *this* JVM just started, used solely to register an
 * exit callback. Stopping never depends on holding that handle — it goes
 * through RCON, so a server this process didn't start (left running from a
 * previous session, or started by the launcher) can still be stopped.
 */
public final class PaperServerOrchestrator {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/paperserver");

	private static final int DEFAULT_PORT = 25566;
	private static final int DEFAULT_RCON_PORT = 25576;
	private static final int DEFAULT_HEAP_MB = 2048;
	/**
	 * Generous on purpose: Paper does a mandatory, non-configurable one-time
	 * "world storage migration" pass (with its own hardcoded 30s warning
	 * delay) the first time it opens a save that wasn't already in its
	 * native format — which is every single conversion this feature does.
	 * Measured ~164s from process spawn to RCON actually answering on
	 * ordinary dev hardware; 120s cut it close enough to fail outright, and
	 * gave up without killing the process, which is what let a *second*
	 * conversion attempt fail on a port collision with the still-running
	 * first one.
	 */
	private static final Duration RCON_START_TIMEOUT = Duration.ofSeconds(300);
	private static final Duration RCON_POLL_INTERVAL = Duration.ofSeconds(2);
	private static final Duration RCON_CALL_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration STOP_POLL_TIMEOUT = Duration.ofSeconds(60);
	private static final Duration STOP_POLL_INTERVAL = Duration.ofSeconds(2);
	private static final Duration TUNNEL_DOMAIN_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration TUNNEL_POLL_INTERVAL = Duration.ofMillis(250);

	private PaperServerOrchestrator() {}

	public static String idFor(String worldName) {
		String id = worldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+|-+$", "");
		return id.isEmpty() ? "world" : id;
	}

	/**
	 * The choices {@code NexoPaperHostSettingsScreen} offers, mirroring
	 * vanilla's "Open to LAN" dialog (game mode, allow-cheats-equivalent)
	 * plus the online-mode tradeoff described on {@code PaperServerFiles}.
	 * {@code playerName} is only read when {@code grantOperator} is set.
	 */
	public record HostOptions(String gameMode, boolean grantOperator, boolean onlineMode, String playerName) {
		public static HostOptions defaults(String playerName) {
			return new HostOptions("survival", true, true, playerName);
		}
	}

	/**
	 * Converts {@code save} into a fresh Paper server directory and starts it.
	 * Must only be called after real, explicit EULA consent — this does not
	 * ask, it assumes the caller already did (see {@code PaperServerRecord.Eula}).
	 */
	public static CompletableFuture<PaperServerRecord> convertAndHost(Path save, String worldName,
			String minecraftVersion, String acceptedBy, HostOptions options) {
		return CompletableFuture.supplyAsync(() -> {
			String id = idFor(worldName);
			PaperServerRecord.SourceWorld sourceWorld = new PaperServerRecord.SourceWorld(
					worldName, save.toAbsolutePath().normalize().toString(), Instant.now().getEpochSecond());
			PaperServerRecord.Rcon rcon = new PaperServerRecord.Rcon(DEFAULT_RCON_PORT, PaperServerFiles.randomRconPassword());
			PaperServerRecord record = PaperServerRecord.create(id, worldName, minecraftVersion, "world", DEFAULT_PORT, rcon, sourceWorld)
					.withEulaAccepted(acceptedBy);
			PaperServerRegistry.write(record);

			PaperServerProcess process = null;
			try {
				Path serverDir = PaperServerRegistry.serverDir(id);
				LOGGER.info("[nexomod] Converting {} -> {}", save, serverDir);
				WorldConverter.toServer(save, serverDir, record.levelName());

				PaperServerFiles.writeEula(serverDir);
				PaperServerFiles.writeServerProperties(serverDir, record, options.gameMode(), options.onlineMode());

				PaperApiClient paperApi = new PaperApiClient();
				PaperApiClient.Build build = paperApi.latestStableBuild(minecraftVersion);
				LOGGER.info("[nexomod] Downloading Paper build {} for {}", build.id(), minecraftVersion);
				paperApi.downloadJar(build, serverDir.resolve("paper.jar"));
				record = record.withPaperBuild(build.id());
				PaperServerRegistry.write(record);

				record = record.withStatus(PaperServerRecord.Status.STARTING);
				PaperServerRegistry.write(record);
				process = PaperServerProcess.start(serverDir, DEFAULT_HEAP_MB);
				PaperServerRecord started = record.withOwner(
						new PaperServerRecord.Owner("mod", process.pid(), hostname(), Instant.now().getEpochSecond()));
				PaperServerRegistry.write(started);
				process.onExit(() -> PaperServerRegistry.read(id).ifPresent(r ->
						PaperServerRegistry.write(r.withStatus(PaperServerRecord.Status.STOPPED).withOwner(PaperServerRecord.Owner.NONE))));

				waitForRcon(rcon.port(), rcon.password());

				if (options.grantOperator() && options.playerName() != null && !options.playerName().isBlank()) {
					try (RconClient opRcon = RconClient.connect("127.0.0.1", started.rcon().port(), started.rcon().password(), RCON_CALL_TIMEOUT)) {
						opRcon.command("op " + options.playerName());
						LOGGER.info("[nexomod] Granted \"{}\" operator on \"{}\"", options.playerName(), id);
					} catch (IOException e) {
						// Not fatal: the server is up either way, just without op granted.
						LOGGER.warn("[nexomod] Could not grant \"{}\" operator on \"{}\"", options.playerName(), id, e);
					}
				}

				PaperServerRecord running = started.withStatus(PaperServerRecord.Status.RUNNING);
				PaperServerRegistry.write(running);
				LOGGER.info("[nexomod] Paper server \"{}\" is up on localhost:{}", worldName, DEFAULT_PORT);
				return running;
			} catch (IOException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				if (process != null) {
					// RCON is the only stop mechanism this codebase has, and it's
					// exactly what just failed — a hard kill is the only option
					// left. Leaving the process running would squat on the
					// hardcoded ports and fail every future attempt with a
					// confusing "address already in use".
					process.kill();
				}
				PaperServerRegistry.read(id).ifPresent(r -> PaperServerRegistry.write(r.withStatus(PaperServerRecord.Status.ERROR)));
				throw new PaperServerException("Failed converting \"" + worldName + "\" to a Paper server", e);
			}
		});
	}

	/**
	 * Stops the server over RCON — regardless of whether this JVM is the one
	 * that started it — and converts its world back into the original save
	 * path recorded at conversion time ({@code record.sourceWorld()}).
	 *
	 * @return the path the pre-conversion save was backed up to
	 */
	public static CompletableFuture<Path> stopAndRevert(String id) {
		return CompletableFuture.supplyAsync(() -> {
			Optional<PaperServerRecord> maybeRecord = PaperServerRegistry.read(id);
			if (maybeRecord.isEmpty()) {
				throw new PaperServerException("No Paper server registered for \"" + id + "\"", null);
			}
			PaperServerRecord record = maybeRecord.get();
			Path save = Path.of(record.sourceWorld().originalSavePath());
			PaperServerRegistry.write(record.withStatus(PaperServerRecord.Status.STOPPING));

			// The tunnel is pointless once the server is going down, and would
			// otherwise leak its own thread/socket running forever.
			LanTunnel.stopForPaperServer(id);

			try {
				try (RconClient rcon = RconClient.connect("127.0.0.1", record.rcon().port(), record.rcon().password(), RCON_CALL_TIMEOUT)) {
					rcon.command("stop");
				}
				waitForRconRefusal(record.rcon().port(), record.rcon().password());

				Path backup = WorldConverter.toSingleplayer(PaperServerRegistry.serverDir(id), record.levelName(), save);
				LOGGER.info("[nexomod] Converted back to singleplayer. Pre-conversion backup kept at {}", backup);
				PaperServerRegistry.write(record.withStatus(PaperServerRecord.Status.STOPPED)
						.withOwner(PaperServerRecord.Owner.NONE)
						.withFriendHosting(PaperServerRecord.FriendHosting.INACTIVE));
				return backup;
			} catch (IOException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				PaperServerRegistry.read(id).ifPresent(r -> PaperServerRegistry.write(r.withStatus(PaperServerRecord.Status.ERROR)));
				throw new PaperServerException("Failed reverting \"" + id + "\" to singleplayer", e);
			}
		});
	}

	/**
	 * Tunnels the server's game port through the existing e4mc relay
	 * ({@code lantunnel/}), the same way vanilla's own "Open to LAN" is
	 * shared — see {@code LocalPortForward}. Never tunnels RCON.
	 */
	public static CompletableFuture<PaperServerRecord> startFriendHosting(String id) {
		return CompletableFuture.supplyAsync(() -> {
			PaperServerRecord record = PaperServerRegistry.read(id)
					.orElseThrow(() -> new PaperServerException("No Paper server registered for \"" + id + "\"", null));

			LanTunnelSession tunnelSession = LanTunnel.startForPaperServer(id, record.port());
			Instant deadline = Instant.now().plus(TUNNEL_DOMAIN_TIMEOUT);
			while (tunnelSession.domain == null && tunnelSession.state != LanTunnelSession.State.UNHEALTHY
					&& Instant.now().isBefore(deadline)) {
				try {
					Thread.sleep(TUNNEL_POLL_INTERVAL.toMillis());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new PaperServerException("Interrupted while starting friend-hosting for \"" + id + "\"", e);
				}
			}
			if (tunnelSession.domain == null) {
				LanTunnel.stopForPaperServer(id);
				throw new PaperServerException("Could not get a tunnel domain for \"" + id + "\"", tunnelSession.failureCause);
			}

			PaperServerRecord updated = record.withFriendHosting(new PaperServerRecord.FriendHosting(true, tunnelSession.domain));
			PaperServerRegistry.write(updated);
			return updated;
		});
	}

	public static void stopFriendHosting(String id) {
		LanTunnel.stopForPaperServer(id);
		PaperServerRegistry.read(id).ifPresent(r ->
				PaperServerRegistry.write(r.withFriendHosting(PaperServerRecord.FriendHosting.INACTIVE)));
	}

	private static void waitForRcon(int port, String password) throws IOException, InterruptedException {
		Instant deadline = Instant.now().plus(RCON_START_TIMEOUT);
		IOException lastFailure = null;
		while (Instant.now().isBefore(deadline)) {
			try (RconClient rcon = RconClient.connect("127.0.0.1", port, password, RCON_CALL_TIMEOUT)) {
				return;
			} catch (IOException e) {
				lastFailure = e;
				Thread.sleep(RCON_POLL_INTERVAL.toMillis());
			}
		}
		throw new IOException("Paper server did not accept RCON connections within " + RCON_START_TIMEOUT, lastFailure);
	}

	/** Waits for the server to actually go down after "stop", by polling until RCON refuses the connection. */
	private static void waitForRconRefusal(int port, String password) throws InterruptedException {
		Instant deadline = Instant.now().plus(STOP_POLL_TIMEOUT);
		while (Instant.now().isBefore(deadline)) {
			try (RconClient rcon = RconClient.connect("127.0.0.1", port, password, RCON_CALL_TIMEOUT)) {
				Thread.sleep(STOP_POLL_INTERVAL.toMillis());
			} catch (IOException e) {
				return; // Connection refused (or reset) - the server is down.
			}
		}
		LOGGER.warn("[nexomod] Paper server on port {} still accepted RCON after {}; converting its world back anyway", port, STOP_POLL_TIMEOUT);
	}

	private static String hostname() {
		return Optional.ofNullable(System.getenv("COMPUTERNAME"))
				.or(() -> Optional.ofNullable(System.getenv("HOSTNAME")))
				.orElse("localhost");
	}

	public static final class PaperServerException extends RuntimeException {
		public PaperServerException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
