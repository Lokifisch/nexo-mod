package dev.nexoclient.nexomod.badge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The roster wire format: how a UUID is hashed, and how the blob is searched.
 *
 * <p>This is the protocol contract with the badge service, and the one place
 * where a disagreement is completely silent: hash the wrong string — uppercase,
 * dashless, a different digest, a different truncation — and every lookup
 * simply returns "not a member". No error, no log line, just a feature that
 * quietly never works.
 *
 * <p>Which is why it lives here on its own, depending on nothing but the JDK,
 * rather than inside {@link BadgeRoster}: that class reaches for the Fabric
 * config directory as it loads, so it cannot be exercised outside a running
 * game, and this can. See {@code BadgeService/tests} on the service side for
 * the matching Python.
 */
final class BadgeRosterFormat {
	/**
	 * Bytes kept from each SHA-256. Must equal the service's {@code HASH_BYTES};
	 * every roster response repeats it in {@code X-Nexo-Hash-Bytes} so a
	 * mismatch is at least visible when looking at the traffic.
	 */
	static final int BYTES = 8;

	private BadgeRosterFormat() {
	}

	/**
	 * The roster entry for a UUID: {@code SHA-256(uuid.toString())} truncated.
	 *
	 * <p>{@link UUID#toString()} is lowercase and dashed, which is exactly the
	 * form the service canonicalises to before hashing.
	 */
	static byte[] of(UUID id) {
		try {
			byte[] full = MessageDigest.getInstance("SHA-256")
					.digest(id.toString().getBytes(StandardCharsets.US_ASCII));
			byte[] truncated = new byte[BYTES];
			System.arraycopy(full, 0, truncated, 0, BYTES);
			return truncated;
		} catch (NoSuchAlgorithmException e) {
			// Every JRE is required to ship SHA-256.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
	/**
	 * Whether {@code roster} contains this UUID's entry.
	 *
	 * <p>Binary search over the blob in place: records are fixed width, so
	 * record <em>i</em> is an offset rather than an object, and a lookup costs a
	 * handful of comparisons and no allocation. That matters because the tab
	 * list asks this about every player on every frame.
	 *
	 * <p>Bytes are compared <em>unsigned</em>, because that is the order the
	 * service sorts them in — SQLite orders BLOBs by unsigned byte value, while
	 * Java's {@code byte} is signed. Comparing them signed would make everything
	 * from 0x80 upwards look unsorted and silently lose about half the roster.
	 */
	static boolean contains(byte[] roster, UUID id) {
		if (roster.length == 0) {
			return false;
		}
		byte[] needle = of(id);
		int low = 0;
		int high = roster.length / BYTES - 1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			int cmp = compare(roster, mid * BYTES, needle);
			if (cmp < 0) {
				low = mid + 1;
			} else if (cmp > 0) {
				high = mid - 1;
			} else {
				return true;
			}
		}
		return false;
	}

	private static int compare(byte[] roster, int offset, byte[] needle) {
		for (int i = 0; i < BYTES; i++) {
			int a = roster[offset + i] & 0xFF;
			int b = needle[i] & 0xFF;
			if (a != b) {
				return a - b;
			}
		}
		return 0;
	}
}
