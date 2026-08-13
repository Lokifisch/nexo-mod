/**
 * The Java half of the chunk-history feature: capturing chunk snapshots,
 * encoding the opaque payload, and querying a region back.
 *
 * <p>The storage half is Rust, in {@code rust-core/src/chunks.rs}, and is
 * compiled only into the full variant's native library (Cargo feature
 * {@code full}). Its JNI declarations are
 * {@link dev.nexoclient.nexomod.full.nativecore.NexoNativeChunks} — deliberately
 * in {@code src/full} as well, so the light jar cannot even name the methods
 * its own {@code libnexo_core.so} does not export. Read
 * {@code rust-core/FFI_CONTRACT.md} before touching either side.
 *
 * <p>Register whatever you add from
 * {@link dev.nexoclient.nexomod.full.NexoFullFeatures}.
 */
package dev.nexoclient.nexomod.full.chunks;
