package dev.nexoclient.nexomod.cosmetics;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

import dev.nexoclient.nexomod.NexoMod;

/**
 * The published cosmetics catalog: what exists, its type, its price. Not the
 * asset bytes themselves — those are fetched per item and separately cached,
 * see {@link CosmeticsAssetCache}, so opening the picker never has to
 * download every texture up front.
 *
 * <p>Same shape as {@code BadgeRoster}: fetched periodically, mirrored to
 * disk so a cold start or an unreachable service still has something to show,
 * swapped as a whole rather than mutated so a lookup never sees a torn list.
 * Unlike the roster this endpoint has no ETag, so refreshing is a plain GET
 * on a timer rather than a conditional one — the catalog is small JSON, not
 * a per-thousand-member blob, so the saved bandwidth would not be worth the
 * extra state.
 */
public final class CosmeticsCatalog {
	public record Item(int id, String type, String name, int price, String creator_uuid) {
	}

	private record Payload(List<Item> items) {
	}

	private static final Gson GSON = new Gson();
	private static final Type PAYLOAD_TYPE = new TypeToken<Payload>() {
	}.getType();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir()
			.resolve("nexomod-cosmetics-catalog.json");

	private volatile List<Item> items = List.of();
	private final Map<Integer, Item> byId = new ConcurrentHashMap<>();

	void loadFromDisk() {
		if (!Files.exists(PATH)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			Payload payload = GSON.fromJson(reader, PAYLOAD_TYPE);
			if (payload != null && payload.items() != null) {
				publish(payload.items());
				NexoMod.LOGGER.info("[nexomod] Cosmetics catalog: {} entries from cache.", items.size());
			}
		} catch (IOException e) {
			NexoMod.LOGGER.warn("[nexomod] Could not read the cached cosmetics catalog.", e);
		}
	}

	/** @return true if the catalog was fetched (even if unchanged); false on failure, leaving the cache in place. */
	boolean refresh(CosmeticsServiceClient service) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(service.url("/catalog")))
					.timeout(CosmeticsServiceClient.TIMEOUT)
					.header("User-Agent", CosmeticsServiceClient.USER_AGENT)
					.GET()
					.build();
			HttpResponse<String> response = service.http()
					.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				NexoMod.LOGGER.warn("[nexomod] Cosmetics catalog fetch returned {}.", response.statusCode());
				return false;
			}
			Payload payload = GSON.fromJson(response.body(), PAYLOAD_TYPE);
			if (payload == null || payload.items() == null) {
				return false;
			}
			publish(payload.items());
			persist(response.body());
			NexoMod.LOGGER.info("[nexomod] Cosmetics catalog: {} entries.", items.size());
			return true;
		} catch (IOException e) {
			NexoMod.LOGGER.debug("[nexomod] Cosmetics catalog fetch failed.", e);
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void publish(List<Item> next) {
		items = List.copyOf(next);
		byId.clear();
		for (Item item : items) {
			byId.put(item.id(), item);
		}
	}

	private void persist(String rawJson) {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				writer.write(rawJson);
			}
		} catch (IOException e) {
			NexoMod.LOGGER.warn("[nexomod] Could not cache the cosmetics catalog.", e);
		}
	}

	public List<Item> items() {
		return items;
	}

	Item byId(int id) {
		return byId.get(id);
	}
}
