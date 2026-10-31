package dev.nexoclient.nexomod.net;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import dev.nexoclient.nexomod.NexoMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * The public join/hasJoined handshake every third-party Minecraft service
 * uses to prove a caller owns the account it claims, without ever seeing the
 * account's access token — that goes to Mojang and nowhere else.
 *
 * <ol>
 *   <li>Ask the service for a one-shot {@code server_id}.</li>
 *   <li>POST its hash to Mojang's {@code session/minecraft/join} together with this
 *       session's access token, exactly as the game does when connecting to a
 *       server.</li>
 *   <li>Tell the service to look it up. Mojang answers with the profile that
 *       joined under that id, which is what proves ownership.</li>
 * </ol>
 *
 * <p>Mojang refuses a {@code serverId} that isn't shaped like a real one, so
 * what it gets is {@link #joinId} of the challenge, and the service asks about
 * that same value. A hacked service that wanted to use this client to log in
 * to a real server would have to find a challenge whose hash collides with that
 * login's, about 2^80 work on SHA-1: not impossible in theory, out of reach here.
 *
 * <p>Extracted out of the badge feature so another feature needing the same
 * proof does not reimplement the Mojang handshake a second time.
 * What differs per feature — which endpoint gets the proof and what else the
 * request body carries — stays with the caller; only the challenge-then-join
 * mechanics live here.
 *
 * <p>Runs off the render thread only. Every method here blocks on network I/O.
 */
public final class MojangIdentityProof {
	private static final String JOIN_URL = "https://sessionserver.mojang.com/session/minecraft/join";

	private final HttpClient http;
	private final Duration timeout;
	private final String userAgent;

	public MojangIdentityProof(HttpClient http, Duration timeout, String userAgent) {
		this.http = http;
		this.timeout = timeout;
		this.userAgent = userAgent;
	}

	/**
	 * The signed-in session, or null when there is nothing to prove ownership
	 * of — no account, or one missing the fields an offline/cracked session
	 * never has.
	 */
	public static User currentUser() {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return null;
		}
		User user = client.getUser();
		if (user == null || user.getName() == null || user.getName().isBlank()
				|| user.getProfileId() == null || user.getAccessToken() == null
				|| user.getAccessToken().isBlank()) {
			return null;
		}
		return user;
	}

	private static final java.util.regex.Pattern SERVER_ID = java.util.regex.Pattern.compile("nexo-(badge|emote)-[0-9a-f]{32}");

	/** The one-shot {@code server_id} from `POST challengeUrl`, or null on failure. */
	public String requestChallenge(String challengeUrl) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(challengeUrl))
				.timeout(timeout)
				.header("User-Agent", userAgent)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			return null;
		}
		try {
			JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
			String serverId = body.has("server_id") ? body.get("server_id").getAsString() : null;
			return serverId == null || !SERVER_ID.matcher(serverId).matches() ? null : serverId;
		} catch (JsonSyntaxException | IllegalStateException e) {
			return null;
		}
	}

	/**
	 * The same call the game makes when joining a server, with the hash of our
	 * challenge in place of the server's id.
	 */
	public boolean joinWithMojang(User user, String serverId) throws IOException, InterruptedException {
		// Only our own namespaced challenges may be hashed into a join.
		if (serverId == null || !SERVER_ID.matcher(serverId).matches()) {
			return false;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("accessToken", user.getAccessToken());
		payload.addProperty("selectedProfile", undashed(user.getProfileId()));
		payload.addProperty("serverId", joinId(serverId));

		HttpRequest request = HttpRequest.newBuilder(URI.create(JOIN_URL))
				.timeout(timeout)
				.header("User-Agent", userAgent)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
				.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 204 || response.statusCode() == 200) {
			return true;
		}
		NexoMod.LOGGER.warn("[nexomod] Mojang refused this session: {} {}", response.statusCode(), refusal(response.body(), user.getAccessToken()));
		// An outage or rate limit says nothing about the session.
		if (response.statusCode() == 429 || response.statusCode() >= 500) {
			throw new IOException("Mojang session server: " + response.statusCode());
		}
		return false;
	}

	/** The challenge as a server id, in the signed-hex SHA-1 form the game itself sends. */
	private static String joinId(String challenge) {
		try {
			return new BigInteger(MessageDigest.getInstance("SHA-1").digest(challenge.getBytes(StandardCharsets.UTF_8))).toString(16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Only Mojang's named error fields, so nothing else in the reply can reach the log. */
	private static String refusal(String body, String token) {
		try {
			JsonObject reply = JsonParser.parseString(body).getAsJsonObject();
			String text = (reply.has("error") ? reply.get("error").getAsString() : "")
					+ (reply.has("errorMessage") ? ": " + reply.get("errorMessage").getAsString() : "");
			text = text.replace(token, "<token>").replaceAll("\\p{Cntrl}|\\p{Cf}", " ");
			return text.length() > 200 ? text.substring(0, 200) : text;
		} catch (RuntimeException e) {
			return "";
		}
	}

	/** Mojang's session API wants the dashless form. */
	public static String undashed(UUID id) {
		return id.toString().replace("-", "");
	}
}
