package dev.nexoclient.nexomod.cosmetics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import net.minecraft.client.User;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.net.MojangIdentityProof;

/**
 * Every proof-gated call to the cosmetics service: equip, purchase, submit,
 * claim a challenge, read the wallet. Each does its own challenge/Mojang-join
 * round trip through {@link MojangIdentityProof} — the mechanics are shared
 * with badge registration, but the body each endpoint wants differs enough
 * per call that the submission step stays here rather than in the shared
 * helper.
 *
 * <p>Runs off the render thread only. Every method here blocks on network I/O.
 */
public final class CosmeticsIdentity {
	/** {@link #purchase} outcomes. FAILED covers network/service trouble, distinct from a real 402. */
	public enum PurchaseResult { PURCHASED, ALREADY_OWNED, INSUFFICIENT_FUNDS, FAILED }

	/** {@link #claimChallenge} outcomes. */
	public enum ClaimResult { CLAIMED, ALREADY_CLAIMED, NOT_ELIGIBLE, FAILED }

	private final CosmeticsServiceClient service;
	private final MojangIdentityProof proof;

	CosmeticsIdentity(CosmeticsServiceClient service) {
		this.service = service;
		this.proof = new MojangIdentityProof(service.http(), CosmeticsServiceClient.TIMEOUT, CosmeticsServiceClient.USER_AGENT);
	}

	boolean equip(User user, String slot, int cosmeticId) {
		JsonObject extra = new JsonObject();
		extra.addProperty("slot", slot);
		extra.addProperty("cosmetic_id", cosmeticId);
		JsonObject response = exchange(user, "/equip", extra, "equip");
		return response != null && response.has("ok") && response.get("ok").getAsBoolean();
	}

	/** Clears `slot`, falling back to whatever vanilla shows on its own (the account's real cape, or none). */
	boolean unequip(User user, String slot) {
		JsonObject extra = new JsonObject();
		extra.addProperty("slot", slot);
		JsonObject response = exchange(user, "/unequip", extra, "unequip");
		return response != null && response.has("ok") && response.get("ok").getAsBoolean();
	}

	PurchaseResult purchase(User user, int cosmeticId) {
		JsonObject extra = new JsonObject();
		extra.addProperty("cosmetic_id", cosmeticId);
		ExchangeOutcome outcome = exchangeWithStatus(user, "/purchase", extra, "purchase");
		if (outcome == null) {
			return PurchaseResult.FAILED;
		}
		if (outcome.status == 402) {
			return PurchaseResult.INSUFFICIENT_FUNDS;
		}
		if (outcome.status != 200 || outcome.body == null) {
			return PurchaseResult.FAILED;
		}
		boolean alreadyOwned = outcome.body.has("already_owned") && outcome.body.get("already_owned").getAsBoolean();
		return alreadyOwned ? PurchaseResult.ALREADY_OWNED : PurchaseResult.PURCHASED;
	}

	/** The caller's balance, or null if the call failed (not "zero" — those are different facts). */
	Integer fetchWallet(User user) {
		JsonObject response = exchange(user, "/wallet", new JsonObject(), "wallet");
		if (response == null || !response.has("balance")) {
			return null;
		}
		return response.get("balance").getAsInt();
	}

	/** The caller's owned cosmetic ids, or null if the call failed. */
	Set<Integer> fetchOwned(User user) {
		JsonObject response = exchange(user, "/owned", new JsonObject(), "owned");
		if (response == null || !response.has("owned")) {
			return null;
		}
		Set<Integer> owned = new HashSet<>();
		for (JsonElement element : response.getAsJsonArray("owned")) {
			owned.add(element.getAsInt());
		}
		return owned;
	}

	ClaimResult claimChallenge(User user, String challengeId) {
		JsonObject extra = new JsonObject();
		extra.addProperty("challenge_id", challengeId);
		ExchangeOutcome outcome = exchangeWithStatus(user, "/challenge/claim", extra, "claim");
		if (outcome == null) {
			return ClaimResult.FAILED;
		}
		if (outcome.status == 403) {
			return ClaimResult.NOT_ELIGIBLE;
		}
		if (outcome.status == 409) {
			return ClaimResult.ALREADY_CLAIMED;
		}
		return outcome.status == 200 ? ClaimResult.CLAIMED : ClaimResult.FAILED;
	}

	/** A pending submission id, or null on failure — including a rejection by server-side validation. */
	Integer submit(User user, String type, String name, String contentType, byte[] assetData) {
		JsonObject extra = new JsonObject();
		extra.addProperty("type", type);
		extra.addProperty("name", name);
		extra.addProperty("content_type", contentType);
		extra.addProperty("asset_base64", Base64.getEncoder().encodeToString(assetData));
		JsonObject response = exchange(user, "/submit", extra, "submit");
		if (response == null || !response.has("submission_id")) {
			return null;
		}
		return response.get("submission_id").getAsInt();
	}

	/** Runs the challenge/proof/submit flow, returning the parsed body on a 200, else null. */
	private JsonObject exchange(User user, String path, JsonObject extra, String what) {
		ExchangeOutcome outcome = exchangeWithStatus(user, path, extra, what);
		return (outcome != null && outcome.status == 200) ? outcome.body : null;
	}

	private record ExchangeOutcome(int status, JsonObject body) {
	}

	private ExchangeOutcome exchangeWithStatus(User user, String path, JsonObject extra, String what) {
		try {
			String serverId = proof.requestChallenge(service.url("/challenge"));
			if (serverId == null) {
				return null;
			}
			if (!proof.joinWithMojang(user, serverId)) {
				NexoMod.LOGGER.debug("[nexomod] Cosmetics {}: the session server would not accept this session.", what);
				return null;
			}

			JsonObject payload = extra.deepCopy();
			payload.addProperty("username", user.getName());
			payload.addProperty("uuid", user.getProfileId().toString());
			payload.addProperty("server_id", serverId);

			HttpRequest request = HttpRequest.newBuilder(URI.create(service.url(path)))
					.timeout(CosmeticsServiceClient.TIMEOUT)
					.header("User-Agent", CosmeticsServiceClient.USER_AGENT)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
					.build();
			HttpResponse<String> response = service.http()
					.send(request, HttpResponse.BodyHandlers.ofString());
			JsonObject body = parse(response.body());
			if (response.statusCode() != 200) {
				NexoMod.LOGGER.debug("[nexomod] Cosmetics {} returned {}: {}", what, response.statusCode(), response.body());
			}
			return new ExchangeOutcome(response.statusCode(), body);
		} catch (IOException e) {
			NexoMod.LOGGER.debug("[nexomod] Cosmetics {} failed.", what, e);
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private static JsonObject parse(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			return JsonParser.parseString(body).getAsJsonObject();
		} catch (JsonSyntaxException | IllegalStateException e) {
			return null;
		}
	}
}
