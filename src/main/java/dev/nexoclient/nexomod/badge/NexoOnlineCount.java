package dev.nexoclient.nexomod.badge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * How many Nexo players are online right now, for the title screen.
 *
 * <p>Read from the service's public {@code /stats} — the same aggregate the
 * website's players page shows, carrying nothing about this player, and
 * fetched only while badges are switched on: with them off the mod talks to
 * the service not at all, and this does not become the exception.
 */
public final class NexoOnlineCount {
	/** No more often than this, however often the title screen is opened. */
	private static final long REFRESH_MILLIS = 60_000L;
	private static final BadgeService SERVICE = new BadgeService();

	private static volatile int online = -1;
	private static volatile long fetchedAt;
	private static volatile boolean inFlight;

	private NexoOnlineCount() {
	}

	/** The last count, or -1 when there is none (badges off, offline, not fetched yet). Starts a refresh if stale. */
	public static int get() {
		if (!NexoConfig.get().badgeSyncEnabled()) {
			return -1;
		}
		long now = System.currentTimeMillis();
		if (!inFlight && now - fetchedAt > REFRESH_MILLIS) {
			inFlight = true;
			fetchedAt = now;
			HttpRequest request = HttpRequest.newBuilder(URI.create(SERVICE.url("/stats")))
					.timeout(BadgeService.TIMEOUT)
					.header("User-Agent", BadgeService.USER_AGENT)
					.GET()
					.build();
			SERVICE.http().sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
				try {
					if (error == null && response.statusCode() == 200) {
						JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
						online = body.has("online") ? Math.max(0, body.get("online").getAsInt()) : -1;
					}
				} catch (RuntimeException e) {
					// Malformed or unexpected: keep the last good number.
				} finally {
					inFlight = false;
				}
			});
		}
		return online;
	}
}
