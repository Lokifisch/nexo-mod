package dev.nexoclient.nexomod.badge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.google.gson.JsonObject;

import net.minecraft.client.User;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.net.MojangIdentityProof;

/**
 * Proving to the badge service that we are who we say we are.
 *
 * <p>Uses the public join/hasJoined handshake that every third-party Minecraft
 * service uses, and the reason it is worth the extra round-trips is what it
 * avoids: the access token goes to Mojang and never to Nexo.
 *
 * <ol>
 *   <li>Ask the service for a one-shot {@code server_id}.</li>
 *   <li>POST it to Mojang's {@code session/minecraft/join} together with this
 *       session's access token, exactly as the game does when connecting to a
 *       server.</li>
 *   <li>Tell the service to look it up. Mojang answers with the profile that
 *       joined under that id, which is what proves ownership.</li>
 * </ol>
 *
 * <p>An offline or cracked session has no token Mojang will accept, so step 2
 * fails and nothing is registered. That is the point — a badge anyone could
 * claim without owning the account would mean nothing.
 *
 * <p>Runs off the render thread only. Every method here blocks on network I/O.
 */
final class BadgeIdentity {
	private final BadgeService service;
	private final MojangIdentityProof proof;

	BadgeIdentity(BadgeService service) {
		this.service = service;
		this.proof = new MojangIdentityProof(service.http(), BadgeService.TIMEOUT, BadgeService.USER_AGENT);
	}

	/** The signed-in session, or null when there is nothing to register. */
	static User currentUser() {
		return MojangIdentityProof.currentUser();
	}

	/** Adds this account to the roster. Returns whether it worked. */
	boolean register(User user) {
		return exchange(user, "/register", "register");
	}

	/**
	 * Removes this account from the roster.
	 *
	 * <p>Needs the same proof as registering, because otherwise anyone who knew
	 * a UUID could delete that player's badge.
	 */
	boolean unregister(User user) {
		return exchange(user, "/unregister", "unregister");
	}

	private boolean exchange(User user, String path, String what) {
		try {
			String serverId = proof.requestChallenge(service.url("/challenge"));
			if (serverId == null) {
				return false;
			}
			if (!proof.joinWithMojang(user, serverId)) {
				// Almost always an offline session or a stale token. Not an
				// error the player needs to see.
				NexoMod.LOGGER.debug("[nexomod] Badge {}: the session server would not accept this session.", what);
				return false;
			}
			return submit(user, serverId, path, what);
		} catch (IOException e) {
			NexoMod.LOGGER.debug("[nexomod] Badge {} failed.", what, e);
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private boolean submit(User user, String serverId, String path, String what)
			throws IOException, InterruptedException {
		JsonObject payload = new JsonObject();
		payload.addProperty("username", user.getName());
		payload.addProperty("uuid", user.getProfileId().toString());
		payload.addProperty("server_id", serverId);

		HttpRequest request = HttpRequest.newBuilder(URI.create(service.url(path)))
				.timeout(BadgeService.TIMEOUT)
				.header("User-Agent", BadgeService.USER_AGENT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
				.build();
		HttpResponse<String> response = service.http()
				.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 200) {
			return true;
		}
		NexoMod.LOGGER.debug("[nexomod] Badge {} returned {}: {}", what, response.statusCode(), response.body());
		return false;
	}
}
