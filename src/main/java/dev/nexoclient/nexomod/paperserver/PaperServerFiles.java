package dev.nexoclient.nexomod.paperserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The small config files a Paper server directory needs beyond the world
 * and the jar: {@code eula.txt} and {@code server.properties}.
 *
 * <p>{@code online-mode} is a per-conversion choice made on
 * {@code NexoPaperHostSettingsScreen}, not hardcoded — it matters more than
 * it looks like it should: the owner's own singleplayer playerdata is keyed
 * by their real Microsoft-account UUID, but an offline-mode server computes
 * a *different*, deterministic UUID from the username instead of trusting
 * the client. Join an offline-mode server as the same person who played the
 * singleplayer world and the inventory/position/stats simply won't be
 * there — a fresh player, not a mismatch error. Online mode is why this
 * defaults to on: it keeps the client-provided (Mojang-verified) UUID, which
 * is the one the existing playerdata already expects. Off is still offered
 * for anyone who wants a server that needs no live Mojang connectivity —
 * friend-hosting through the LAN tunnel works either way.
 */
public final class PaperServerFiles {
	private PaperServerFiles() {}

	/**
	 * Only ever called after a real, explicit user click on a EULA consent
	 * screen (see {@code PaperServerRecord.Eula}) — never as a side effect of
	 * anything else.
	 */
	public static void writeEula(Path serverDir) throws IOException {
		Files.writeString(serverDir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
	}

	public static void writeServerProperties(Path serverDir, PaperServerRecord record, String gameMode, boolean onlineMode) throws IOException {
		String properties = """
				level-name=%s
				server-port=%d
				online-mode=%b
				gamemode=%s
				enable-rcon=true
				rcon.port=%d
				rcon.password=%s
				motd=%s
				""".formatted(record.levelName(), record.port(), onlineMode, gameMode, record.rcon().port(), record.rcon().password(), record.name());
		Files.writeString(serverDir.resolve("server.properties"), properties, StandardCharsets.UTF_8);
	}

	/** A fresh per-server RCON password — never reused across servers, never logged. */
	public static String randomRconPassword() {
		byte[] bytes = new byte[18];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
