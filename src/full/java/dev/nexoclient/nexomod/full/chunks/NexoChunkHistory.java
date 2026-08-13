package dev.nexoclient.nexomod.full.chunks;

import java.nio.ByteBuffer;
import java.nio.file.Path;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.full.nativecore.NexoNativeChunks;
import dev.nexoclient.nexomod.nativecore.JobPayloadReader;
import dev.nexoclient.nexomod.nativecore.NexoNative;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Remembers the chunks the player has actually been sent, in the native store.
 *
 * <h2>Why this is full-only</h2>
 *
 * <p>A record of terrain the client once saw is terrain the client can be shown
 * again after it unloads — information the vanilla game deliberately drops. The
 * store, its Cargo feature, its JNI class and this package are all on the
 * {@code src/full} side of the split for that reason, and
 * {@link NexoNativeChunks#isAvailable()} is checked in addition to
 * {@link NexoNative#isAvailable()}: a full jar can be pointed at a light build
 * of the library with {@code -Dnexomod.nativecore.library}, and every call here
 * would then die with an {@link UnsatisfiedLinkError}, which is an
 * {@link Error} and slips past {@code catch (Exception)}.
 *
 * <h2>The payload</h2>
 *
 * <p>Opaque to Rust by contract, so its shape is entirely this class's business
 * — which is the point, since block-state ids and palette layout change between
 * Minecraft versions and the native side must not have to track that. What goes
 * in is a version byte and the 16×16 {@code WORLD_SURFACE} heightmap as signed
 * shorts: 513 bytes per chunk, enough to redraw a terrain silhouette, and
 * nothing about what the blocks are. The version byte is what lets a later
 * format be told apart from this one instead of being read as garbage.
 */
public final class NexoChunkHistory {
	/** Bumped when {@link #encode} changes shape. Read back in {@link #decodeVersion}. */
	public static final byte PAYLOAD_VERSION = 1;

	private static final int SECTION = 16;
	private static final int PAYLOAD_BYTES = 1 + (SECTION * SECTION * Short.BYTES);
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-chunks.db");

	private static long handle = NexoNative.INVALID_HANDLE;
	private static boolean openFailed;

	private static long queryJob = NexoNative.INVALID_HANDLE;
	private static int lastQueryCount = -1;

	private NexoChunkHistory() {
	}

	/** Registered from {@code NexoFullFeatures}; a no-op at runtime when the store is unavailable. */
	public static void register() {
		ClientChunkEvents.CHUNK_LOAD.register(NexoChunkHistory::onChunkLoad);
		ClientTickEvents.END_CLIENT_TICK.register(NexoChunkHistory::tick);
	}

	/** Whether anything here can work at all: full library, chunk-history feature present. */
	public static boolean isAvailable() {
		return NexoNativeChunks.isAvailable();
	}

	private static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		if (!isAvailable() || !NexoConfig.get().chunkHistoryEnabled()) {
			return;
		}
		long store = handle();
		if (store == NexoNative.INVALID_HANDLE) {
			return;
		}
		ChunkPos pos = chunk.getPos();
		if (!NexoNativeChunks.chunkSnapshot(store, dimensionOf(level), pos.x(), pos.z(),
				System.currentTimeMillis(), encode(chunk))) {
			// Debug, not warn: this runs per chunk load, and a store that has
			// started failing would otherwise fill the log at walking speed.
			NexoMod.LOGGER.debug("[nexomod] chunk snapshot failed: {}", NexoNative.lastErrorOrUnknown());
		}
	}

	/**
	 * Asks the store how many chunks it remembers in a square of
	 * {@code radiusChunks} around the player. Replaces any query still running,
	 * the way the chat search does — one owner, one in-flight job.
	 */
	public static void queryAround(int radiusChunks) {
		cancelQuery();
		if (!isAvailable()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) {
			return;
		}
		long store = handle();
		if (store == NexoNative.INVALID_HANDLE) {
			return;
		}
		ChunkPos center = client.player.chunkPosition();
		queryJob = NexoNativeChunks.chunkQueryAsync(store, dimensionOf(client.level),
				center.x() - radiusChunks, center.z() - radiusChunks,
				center.x() + radiusChunks, center.z() + radiusChunks);
		if (queryJob == NexoNative.INVALID_HANDLE) {
			lastQueryCount = -1;
			NexoMod.LOGGER.debug("[nexomod] chunk query rejected: {}", NexoNative.lastErrorOrUnknown());
		}
	}

	/** How many chunks the last completed query found, or -1 if none has completed. */
	public static int lastQueryCount() {
		return lastQueryCount;
	}

	public static boolean queryRunning() {
		return queryJob != NexoNative.INVALID_HANDLE;
	}

	/** Safe with nothing in flight — {@code jobCancel} is silent for an unknown id by design. */
	public static void cancelQuery() {
		if (queryJob == NexoNative.INVALID_HANDLE) {
			return;
		}
		NexoNative.jobCancel(queryJob);
		NexoNative.jobTake(queryJob);
		queryJob = NexoNative.INVALID_HANDLE;
	}

	/** Closed from {@code CLIENT_STOPPING}. Idempotent. */
	public static synchronized void close() {
		cancelQuery();
		if (handle == NexoNative.INVALID_HANDLE) {
			return;
		}
		NexoNativeChunks.chunkStoreClose(handle);
		handle = NexoNative.INVALID_HANDLE;
	}

	/**
	 * Polls the query from the client tick.
	 *
	 * <p>{@code jobStatus} rather than {@code jobIsReady}: the latter is
	 * {@code false} for a failed job as well as a running one, so anything with
	 * a spinner in front of it would never stop spinning.
	 */
	private static void tick(Minecraft client) {
		if (queryJob == NexoNative.INVALID_HANDLE) {
			return;
		}
		int status = NexoNative.jobStatus(queryJob);
		if (status == NexoNative.JOB_PENDING) {
			return;
		}
		byte[] payload = NexoNative.jobTake(queryJob);
		queryJob = NexoNative.INVALID_HANDLE;
		if (status != NexoNative.JOB_READY || payload == null) {
			lastQueryCount = -1;
			NexoMod.LOGGER.debug("[nexomod] chunk query failed: {}", NexoNative.lastErrorOrUnknown());
			return;
		}
		try {
			JobPayloadReader reader = new JobPayloadReader(payload);
			if (reader.kind() != JobPayloadReader.KIND_CHUNK_QUERY && reader.kind() != JobPayloadReader.KIND_EMPTY) {
				lastQueryCount = -1;
				NexoMod.LOGGER.warn("[nexomod] unexpected chunk query payload kind {}", reader.kind());
				return;
			}
			lastQueryCount = reader.recordCount();
		} catch (RuntimeException e) {
			// A malformed payload means the jar and the library disagree about
			// the layout, which is loud in the log and quiet on screen.
			NexoMod.LOGGER.warn("[nexomod] chunk query payload could not be decoded", e);
			lastQueryCount = -1;
		}
	}

	private static synchronized long handle() {
		if (handle != NexoNative.INVALID_HANDLE || openFailed || !isAvailable()) {
			return handle;
		}
		handle = NexoNativeChunks.chunkStoreOpen(PATH.toString());
		if (handle == NexoNative.INVALID_HANDLE) {
			// Sticky, so a bad path costs one WARN rather than one per chunk.
			openFailed = true;
			NexoMod.LOGGER.warn("[nexomod] Chunk history disabled: could not open {} ({})", PATH,
					NexoNative.lastErrorOrUnknown());
		}
		return handle;
	}

	/** {@code minecraft:overworld} and friends — the registry key, not the display name. */
	private static String dimensionOf(ClientLevel level) {
		return level.dimension().identifier().toString();
	}

	private static byte[] encode(LevelChunk chunk) {
		ByteBuffer buffer = ByteBuffer.allocate(PAYLOAD_BYTES);
		buffer.put(PAYLOAD_VERSION);
		for (int z = 0; z < SECTION; z++) {
			for (int x = 0; x < SECTION; x++) {
				// Clamped to a short: build heights fit comfortably, but a
				// datapack dimension with an absurd height would otherwise wrap
				// into a negative and read back as a hole in the terrain.
				int height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				buffer.putShort((short) Math.clamp(height, Short.MIN_VALUE, Short.MAX_VALUE));
			}
		}
		return buffer.array();
	}

	/** The version byte a stored payload starts with, or -1 if it is too short to have one. */
	public static int decodeVersion(byte[] payload) {
		return payload == null || payload.length == 0 ? -1 : payload[0];
	}
}
