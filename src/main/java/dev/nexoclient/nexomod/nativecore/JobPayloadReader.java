package dev.nexoclient.nexomod.nativecore;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Decodes the {@code byte[]} that {@link NexoNative#jobTake} returns.
 *
 * <p>The format is defined in {@code rust-core/src/payload.rs} and documented in
 * {@code rust-core/FFI_CONTRACT.md}; this class is the only place the Java side
 * should reproduce it. Everything is big-endian, which is why this is a plain
 * {@link ByteBuffer} with no byte-order fiddling — that was the point of
 * choosing big-endian on the Rust side.
 *
 * <p>Usage is a cursor: check {@link #kind()}, then read {@link #recordCount()}
 * records, pulling each record's fields in the order the kind documents. Reading
 * fields in the wrong order or the wrong number of them produces garbage, not an
 * exception, so the field list per kind is worth following literally.
 *
 * <pre>{@code
 * byte[] raw = NexoNative.jobTake(jobId);
 * if (raw == null) { ... still running, or failed; check jobStatus ... }
 * JobPayloadReader r = new JobPayloadReader(raw);
 * for (int i = 0; i < r.recordCount(); i++) {
 *     long ts = r.readLong();
 *     String server = r.readString();
 *     String sender = r.readString();
 *     String message = r.readString();
 * }
 * }</pre>
 */
public final class JobPayloadReader {
	/** No records — a job that finished and legitimately found nothing. */
	public static final int KIND_EMPTY = 0;
	/** Records of {@code long tsMillis, String server, String sender, String message}. */
	public static final int KIND_CHAT_SEARCH = 1;
	/** Records of {@code String dimension, int chunkX, int chunkZ, long tsMillis, byte[] payload}. */
	public static final int KIND_CHUNK_QUERY = 2;

	private static final int MAGIC = 0x4E584A31; // "NXJ1"

	private final ByteBuffer buf;
	private final int kind;
	private final int recordCount;

	/**
	 * @throws IllegalArgumentException if the buffer isn't a payload this
	 *         version understands — which is a version mismatch between the jar
	 *         and the library, and is better as a loud failure than as records
	 *         decoded out of the wrong layout
	 */
	public JobPayloadReader(byte[] payload) {
		if (payload == null || payload.length < 9) {
			throw new IllegalArgumentException("job payload is too short to be valid");
		}
		// ByteBuffer is big-endian by default; stated rather than set, so nobody
		// "fixes" it to little-endian later.
		this.buf = ByteBuffer.wrap(payload);
		int magic = buf.getInt();
		if (magic != MAGIC) {
			throw new IllegalArgumentException("job payload has bad magic 0x" + Integer.toHexString(magic));
		}
		this.kind = Byte.toUnsignedInt(buf.get());
		int count = buf.getInt();
		if (count < 0) {
			// The count is a u32 on the wire. A value past 2^31 can't be a real
			// record count — it's corruption — and silently treating it as
			// negative would just produce an empty loop.
			throw new IllegalArgumentException("job payload declares an implausible record count");
		}
		this.recordCount = count;
	}

	/** One of the {@code KIND_*} constants. */
	public int kind() {
		return kind;
	}

	public int recordCount() {
		return recordCount;
	}

	public boolean hasRemaining() {
		return buf.hasRemaining();
	}

	public int readInt() {
		return guard(buf::getInt);
	}

	public long readLong() {
		return guard(buf::getLong);
	}

	/**
	 * Reads a length-prefixed UTF-8 string.
	 *
	 * <p>Note this is <em>not</em> {@link java.io.DataInput#readUTF()}: that
	 * reads modified UTF-8, which encodes NUL and astral-plane characters
	 * differently, and chat is full of emoji.
	 */
	public String readString() {
		int length = guard(() -> Short.toUnsignedInt(buf.getShort()));
		byte[] bytes = new byte[length];
		guardVoid(() -> buf.get(bytes));
		return new String(bytes, StandardCharsets.UTF_8);
	}

	public byte[] readBytes() {
		int length = guard(buf::getInt);
		if (length < 0 || length > buf.remaining()) {
			throw new IllegalStateException("job payload declares a " + length
					+ "-byte blob but only " + buf.remaining() + " bytes remain");
		}
		byte[] bytes = new byte[length];
		buf.get(bytes);
		return bytes;
	}

	/**
	 * Turns a short read into a message that says what actually went wrong. A
	 * bare {@link BufferUnderflowException} out of a reader like this reads as
	 * "the payload is corrupt" when the far likelier cause is a caller pulling
	 * fields in an order that doesn't match the kind.
	 */
	private static <T> T guard(java.util.function.Supplier<T> read) {
		try {
			return read.get();
		} catch (BufferUnderflowException e) {
			throw new IllegalStateException("job payload ran out early; fields read in the wrong order?", e);
		}
	}

	private static void guardVoid(Runnable read) {
		guard(() -> {
			read.run();
			return null;
		});
	}
}
