package dev.nexoclient.nexomod.cosmetics;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import dev.nexoclient.nexomod.NexoMod;

/**
 * What every recently-seen player has equipped, in bulk.
 *
 * <p>A client rendering a crowd cannot afford one HTTP request per nearby
 * player per frame, so this mirrors the service's own reasoning for
 * {@code /roster}: {@link #noteVisible} is a cheap, allocation-light note
 * from the render thread ("ask about this player next time"), and a
 * background pass resolves the whole tracked set in one bulk lookup.
 *
 * <p>{@link #tracked} is capped at {@link #MAX_TRACKED} — matching the
 * service's own per-lookup cap — via a {@link LinkedHashMap} in
 * access-order, so a long session that passes through many players evicts
 * the ones asked about longest ago rather than growing without bound.
 */
public final class CosmeticsEquipped {
	private static final int MAX_TRACKED = 200;
	private static final Gson GSON = new Gson();

	private record Payload(Map<String, Map<String, Integer>> equipped) {
	}

	private static final Type PAYLOAD_TYPE = new TypeToken<Payload>() {
	}.getType();

	private final Object trackedLock = new Object();
	private final LinkedHashMap<UUID, Boolean> tracked = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
			return size() > MAX_TRACKED;
		}
	};

	private volatile Map<UUID, Map<String, Integer>> equipped = Map.of();

	/** Called from the render thread for every player drawn, so the next refresh knows to ask about them. */
	public void noteVisible(UUID id) {
		if (id == null) {
			return;
		}
		synchronized (trackedLock) {
			tracked.put(id, Boolean.TRUE);
		}
	}

	/** What `id` has equipped in `slot`, or null if nothing (or nothing known yet). */
	public Integer equippedCosmetic(UUID id, String slot) {
		Map<String, Integer> forPlayer = equipped.get(id);
		return forPlayer == null ? null : forPlayer.get(slot);
	}

	/**
	 * Reflects a just-confirmed equip immediately, rather than waiting out the
	 * next periodic {@link #refresh}. Copy-on-write so a concurrent reader of
	 * {@link #equipped} never sees a partially-updated map.
	 *
	 * <p>ponytail: this is the whole of the "instant feedback" story — there is
	 * no separate persisted local-override file. Good enough because the local
	 * account is always in {@link #tracked} (see {@code NexoCosmetics}'s
	 * reconcile pass), so a restart re-resolves it from the service within one
	 * refresh cycle instead of trusting a stale local copy. Add a persisted
	 * override if that cycle ever needs to be hidden too.
	 */
	void applyLocalEquip(UUID id, String slot, int cosmeticId) {
		Map<UUID, Map<String, Integer>> current = equipped;
		Map<String, Integer> forPlayer = new ConcurrentHashMap<>(current.getOrDefault(id, Map.of()));
		forPlayer.put(slot, cosmeticId);
		Map<UUID, Map<String, Integer>> next = new ConcurrentHashMap<>(current);
		next.put(id, forPlayer);
		equipped = next;
	}

	/** The unequip counterpart to {@link #applyLocalEquip} — same instant-feedback reasoning. */
	void applyLocalUnequip(UUID id, String slot) {
		Map<UUID, Map<String, Integer>> current = equipped;
		if (!current.containsKey(id)) {
			return;
		}
		Map<String, Integer> forPlayer = new ConcurrentHashMap<>(current.get(id));
		forPlayer.remove(slot);
		Map<UUID, Map<String, Integer>> next = new ConcurrentHashMap<>(current);
		next.put(id, forPlayer);
		equipped = next;
	}

	boolean refresh(CosmeticsServiceClient service) {
		List<UUID> ids;
		synchronized (trackedLock) {
			ids = List.copyOf(tracked.keySet());
		}
		if (ids.isEmpty()) {
			return true;
		}
		String joined = ids.stream().map(UUID::toString).collect(Collectors.joining(","));
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(
						service.url("/equipped?uuids=" + URLEncoder.encode(joined, StandardCharsets.UTF_8))))
					.timeout(CosmeticsServiceClient.TIMEOUT)
					.header("User-Agent", CosmeticsServiceClient.USER_AGENT)
					.GET()
					.build();
			HttpResponse<String> response = service.http().send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				NexoMod.LOGGER.debug("[nexomod] Cosmetics equipped-lookup returned {}.", response.statusCode());
				return false;
			}
			Payload payload = GSON.fromJson(response.body(), PAYLOAD_TYPE);
			if (payload == null || payload.equipped() == null) {
				return false;
			}
			Map<UUID, Map<String, Integer>> parsed = new ConcurrentHashMap<>();
			payload.equipped().forEach((uuidText, slots) -> {
				try {
					parsed.put(UUID.fromString(uuidText), slots);
				} catch (IllegalArgumentException ignored) {
					// Not a UUID we recognise the shape of; skip rather than fail the whole batch.
				}
			});
			equipped = parsed;
			return true;
		} catch (IOException e) {
			NexoMod.LOGGER.debug("[nexomod] Cosmetics equipped-lookup failed.", e);
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (JsonSyntaxException e) {
			return false;
		}
	}
}
