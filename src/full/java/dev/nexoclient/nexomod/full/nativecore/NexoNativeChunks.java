package dev.nexoclient.nexomod.full.nativecore;

import dev.nexoclient.nexomod.nativecore.JobPayloadReader;
import dev.nexoclient.nexomod.nativecore.NexoNative;

/**
 * The chunk-history half of the native surface — full builds only.
 *
 * <p>Everything in {@link NexoNative} applies here unchanged: nothing throws,
 * failures return a sentinel ({@link NexoNative#INVALID_HANDLE}, {@code false},
 * {@code null}) and leave a message for {@link NexoNative#nativeLastError()},
 * handles are opaque {@code long}s that must be closed, and long work returns a
 * job id to poll. {@code rust-core/FFI_CONTRACT.md} is normative.
 *
 * <h2>Why this is a separate class</h2>
 *
 * <p>JNI derives the exported symbol from the declaring class's package and
 * name, so these methods bind to
 * {@code Java_dev_nexoclient_nexomod_full_nativecore_NexoNativeChunks_…} rather
 * than to the {@code NexoNative} symbols the rest of the surface uses. That is
 * the point. The class lives in {@code src/full}, so it is absent from the
 * {@code nexomod-light} jar, whose {@code libnexo_core.so} is built without the
 * Cargo {@code full} feature and does not export these symbols either. Light
 * code physically cannot reference something its library cannot resolve.
 *
 * <h2>Before calling anything here</h2>
 *
 * <p>Check {@link #isAvailable()}, not just {@link NexoNative#isAvailable()}.
 * The two differ in exactly one situation, and it is a situation developers put
 * themselves in routinely: {@code -Dnexomod.nativecore.library=…} pointing this
 * jar at a light build of the library. Then the library is loaded and healthy,
 * and every method below still fails with an {@link UnsatisfiedLinkError} —
 * an {@link Error}, so a {@code catch (Exception)} around the call will not
 * save you.
 */
public final class NexoNativeChunks {
	private NexoNativeChunks() {
	}

	/**
	 * Whether the loaded library exports this surface. False means the whole
	 * class is off limits, exactly as {@code NexoNative.isAvailable() == false}
	 * means the rest of it is.
	 */
	public static boolean isAvailable() {
		return NexoNative.hasFeature(NexoNative.FEATURE_CHUNK_HISTORY);
	}

	/** @return a handle, or {@link NexoNative#INVALID_HANDLE} on failure */
	public static native long chunkStoreOpen(String path);

	public static native void chunkStoreClose(long h);

	/**
	 * @param payload opaque to the native side — it is stored and handed back
	 *        verbatim, so its encoding is entirely the Java side's business
	 */
	public static native boolean chunkSnapshot(long h, String dimension, int chunkX, int chunkZ, long tsMillis,
			byte[] payload);

	/**
	 * Bounds are inclusive chunk coordinates and are normalised natively, so
	 * passing them in either order works.
	 *
	 * @return a job id whose payload decodes as
	 *         {@link JobPayloadReader#KIND_CHUNK_QUERY}, or
	 *         {@link NexoNative#INVALID_HANDLE} on failure
	 */
	public static native long chunkQueryAsync(long h, String dimension, int minX, int minZ, int maxX, int maxZ);
}
