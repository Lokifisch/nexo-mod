package dev.nexoclient.nexomod.badge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.HexFormat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import dev.nexoclient.nexomod.NexoMod;

/**
 * Telling the badge service that somebody is playing, without telling it who.
 *
 * <p>The id sent here is sixteen random bytes made when the game starts and
 * thrown away when it exits. It is not derived from the account, it is never
 * written to disk, and a new one is minted on every launch — so two sessions
 * by the same player cannot be tied together, and no session can be tied to a
 * player at all. The service's table has no column it could be joined to a
 * member on.
 *
 * <p>That is the whole reason this is separate from {@link BadgeIdentity}. The
 * obvious implementation is to heartbeat with the account hash the roster
 * already uses, which is one line shorter and turns the service into a record
 * of when each player is online and therefore who plays alongside whom. The
 * roster is downloaded rather than uploaded for exactly that reason; a
 * heartbeat that leaked it back would undo the design.
 *
 * <p>What it costs: the resulting count is unauthenticated, so it can be
 * inflated by anyone willing to post random ids. For a number on a public
 * page, that is the better half of the trade.
 *
 * <p>Runs off the render thread only — the send blocks.
 */
final class BadgePresence {
	/**
	 * Fallback cadence, used only until the service states its own. It answers
	 * with an interval so the rate can be retuned server-side rather than
	 * waiting for every player to update the mod.
	 */
	static final long DEFAULT_INTERVAL_SECONDS = 300;

	private final BadgeService service;
	private final String sessionId;

	BadgePresence(BadgeService service) {
		this.service = service;
		byte[] random = new byte[16];
		new SecureRandom().nextBytes(random);
		this.sessionId = HexFormat.of().formatHex(random);
	}

	/**
	 * Checks in once.
	 *
	 * @return the interval the service asks for, in seconds, or -1 if it could
	 *         not be reached — the caller keeps its current cadence either way,
	 *         since a service that is down is not a reason to beat faster.
	 */
	long beat() {
		JsonObject payload = new JsonObject();
		payload.addProperty("session", sessionId);

		HttpRequest request = HttpRequest.newBuilder(URI.create(service.url("/presence")))
				.timeout(BadgeService.TIMEOUT)
				.header("User-Agent", BadgeService.USER_AGENT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
				.build();

		try {
			HttpResponse<String> response = service.http()
					.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				// A 429 means this address is beating too often, which is not
				// something the player can act on and not worth a warning.
				NexoMod.LOGGER.debug("[nexomod] Badge presence returned {}.", response.statusCode());
				return -1;
			}
			JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
			return body.has("interval") ? body.get("interval").getAsLong() : -1;
		} catch (IOException | JsonSyntaxException | IllegalStateException e) {
			NexoMod.LOGGER.debug("[nexomod] Badge presence failed.", e);
			return -1;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return -1;
		}
	}
}
