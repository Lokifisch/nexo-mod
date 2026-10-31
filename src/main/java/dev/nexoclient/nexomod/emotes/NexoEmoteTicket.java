package dev.nexoclient.nexomod.emotes;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Checks the server's signed proof that a player owns an emote. The layout is
 * {@code EmoteService/src/nexo_emotes/tickets.py}; {@code tools/emote-wire-check}
 * has the service sign and this verify. No Minecraft imports for that reason.
 */
public final class NexoEmoteTicket {
	private static final int BODY = 1 + 1 + 32 + 32 + 8;
	private static final int SIGNATURE = 64;
	private static final int VERSION = 1;
	private static final byte[] ED25519_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

	private NexoEmoteTicket() {
	}

	/** {@code keys} maps key id to the raw 32-byte public key, as hex. */
	public static Map<Integer, PublicKey> keys(Map<Integer, String> keys) {
		Map<Integer, PublicKey> parsed = new HashMap<>();
		keys.forEach((id, hex) -> {
			try {
				byte[] raw = HexFormat.of().parseHex(hex);
				byte[] der = Arrays.copyOf(ED25519_PREFIX, ED25519_PREFIX.length + raw.length);
				System.arraycopy(raw, 0, der, ED25519_PREFIX.length, raw.length);
				parsed.put(id, KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der)));
			} catch (GeneralSecurityException | IllegalArgumentException e) {
				throw new IllegalStateException("bad emote ticket key " + id, e);
			}
		});
		return parsed;
	}

	public static boolean verify(byte[] ticket, String emoteSha256, UUID player, long nowSeconds, Map<Integer, PublicKey> keys) {
		if (ticket == null || ticket.length != BODY + SIGNATURE) {
			return false;
		}
		try {
			ByteBuffer in = ByteBuffer.wrap(ticket);
			if (in.get() != VERSION) {
				return false;
			}
			PublicKey key = keys.get((int) in.get());
			if (key == null) {
				return false;
			}
			byte[] emote = new byte[32];
			in.get(emote);
			byte[] owner = new byte[32];
			in.get(owner);
			long expires = in.getLong();
			byte[] wantedOwner = MessageDigest.getInstance("SHA-256").digest(player.toString().getBytes(StandardCharsets.US_ASCII));
			if (expires < nowSeconds || !MessageDigest.isEqual(emote, HexFormat.of().parseHex(emoteSha256))
					|| !MessageDigest.isEqual(owner, wantedOwner)) {
				return false;
			}
			Signature signature = Signature.getInstance("Ed25519");
			signature.initVerify(key);
			signature.update(ticket, 0, BODY);
			return signature.verify(ticket, BODY, SIGNATURE);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			return false;
		}
	}
}
