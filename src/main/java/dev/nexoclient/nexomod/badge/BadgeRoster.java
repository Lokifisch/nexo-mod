package dev.nexoclient.nexomod.badge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.loader.api.FabricLoader;

import dev.nexoclient.nexomod.NexoMod;

/**
 * The set of players known to use Nexo, and the lookup against it.
 *
 * <p>The roster arrives as one blob of fixed-width records: the first
 * {@value #HASH_BYTES} bytes of {@code SHA-256(uuid)}, sorted. Downloading the
 * whole set and answering "does this player have Nexo" locally is the entire
 * privacy design — the alternative, uploading the UUIDs of everyone standing
 * around you and asking the service, would hand a server the record of who you
 * play with, which is not a thing this mod should be building.
 *
 * <p>At 8 bytes a member the blob is 8 KB per thousand users, and it is
 * revalidated with an {@code ETag}, so the normal refresh is a 304 with no
 * body. It is also mirrored to disk: with the service unreachable — or simply
 * offline — the last known roster keeps working instead of every badge
 * vanishing.
 *
 * <p>Thread safety: {@link #contains(UUID)} runs on the render thread once per
 * nametag and once per tab row. The blob is swapped as a whole, never mutated,
 * so a lookup either sees the old roster or the new one and never a torn one.
 */
final class BadgeRoster {
	/** The record width. Owned by {@link BadgeRosterFormat}, the wire contract. */
	static final int HASH_BYTES = BadgeRosterFormat.BYTES;

	private static final Path BLOB_PATH = FabricLoader.getInstance().getConfigDir()
			.resolve("nexomod-badge-roster.bin");
	private static final Path ETAG_PATH = FabricLoader.getInstance().getConfigDir()
			.resolve("nexomod-badge-roster.etag");

	/**
	 * Sorted, {@value #HASH_BYTES}-byte records. Never mutated in place — a
	 * refresh publishes a new array, which is what makes the lookup lock-free.
	 */
	private volatile byte[] entries = new byte[0];
	private volatile String etag;

	/**
	 * Memoised answers, because the tab list asks about every player on every
	 * frame and hashing a UUID that often is pure waste. Cleared whenever a new
	 * roster lands, so a player who registers mid-session stops being a "no"
	 * as soon as the next refresh sees them.
	 */
	private final ConcurrentHashMap<UUID, Boolean> verdicts = new ConcurrentHashMap<>();

	/** Loads whatever the last session left behind. Never throws. */
	void loadFromDisk() {
		try {
			if (Files.exists(BLOB_PATH)) {
				byte[] blob = Files.readAllBytes(BLOB_PATH);
				if (blob.length % HASH_BYTES == 0) {
					publish(blob);
					if (Files.exists(ETAG_PATH)) {
						etag = Files.readString(ETAG_PATH, StandardCharsets.UTF_8).trim();
					}
					NexoMod.LOGGER.info("[nexomod] Badge roster: {} entries from cache.", size());
					return;
				}
				// A truncated blob would shift every record after the damage and
				// hand out badges at random, so it is discarded rather than used.
				NexoMod.LOGGER.warn("[nexomod] Cached badge roster is not a whole number of records, ignoring it.");
			}
		} catch (IOException e) {
			NexoMod.LOGGER.warn("[nexomod] Could not read the cached badge roster.", e);
		}
	}

	/**
	 * Fetches the roster, sending the stored ETag so an unchanged one costs a
	 * 304 and no body.
	 *
	 * @return true if anything was downloaded or confirmed; false on failure
	 */
	boolean refresh(BadgeService service) {
		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(service.url("/roster")))
					.timeout(BadgeService.TIMEOUT)
					.header("User-Agent", BadgeService.USER_AGENT)
					.GET();
			String known = etag;
			if (known != null && !known.isEmpty()) {
				request.header("If-None-Match", known);
			}

			HttpResponse<byte[]> response = service.http()
					.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());

			if (response.statusCode() == 304) {
				return true;
			}
			if (response.statusCode() != 200) {
				NexoMod.LOGGER.warn("[nexomod] Badge roster fetch returned {}.", response.statusCode());
				return false;
			}

			byte[] blob = response.body();
			if (blob.length % HASH_BYTES != 0) {
				NexoMod.LOGGER.warn("[nexomod] Badge roster is {} bytes, not a multiple of {}; ignoring it.",
						blob.length, HASH_BYTES);
				return false;
			}

			publish(blob);
			etag = response.headers().firstValue("ETag").orElse(null);
			persist(blob);
			NexoMod.LOGGER.info("[nexomod] Badge roster: {} entries.", size());
			return true;
		} catch (IOException e) {
			// Offline, or the service is down. The cached roster stays in use.
			NexoMod.LOGGER.debug("[nexomod] Badge roster fetch failed.", e);
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void publish(byte[] blob) {
		entries = blob;
		verdicts.clear();
	}

	private void persist(byte[] blob) {
		try {
			Files.createDirectories(BLOB_PATH.getParent());
			Files.write(BLOB_PATH, blob);
			String tag = etag;
			if (tag != null) {
				Files.writeString(ETAG_PATH, tag, StandardCharsets.UTF_8);
			} else {
				Files.deleteIfExists(ETAG_PATH);
			}
		} catch (IOException e) {
			NexoMod.LOGGER.warn("[nexomod] Could not cache the badge roster.", e);
		}
	}

	/** Forgets everything, on disk too — used when badge sync is switched off. */
	void clear() {
		publish(new byte[0]);
		etag = null;
		try {
			Files.deleteIfExists(BLOB_PATH);
			Files.deleteIfExists(ETAG_PATH);
		} catch (IOException e) {
			NexoMod.LOGGER.warn("[nexomod] Could not delete the cached badge roster.", e);
		}
	}

	int size() {
		return entries.length / HASH_BYTES;
	}

	/** Whether this player is in the roster. Called from the render thread. */
	boolean contains(UUID id) {
		if (id == null) {
			return false;
		}
		byte[] blob = entries;
		if (blob.length == 0) {
			return false;
		}
		return verdicts.computeIfAbsent(id, key -> BadgeRosterFormat.contains(blob, key));
	}
}
