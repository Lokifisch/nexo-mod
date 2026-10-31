package dev.nexoclient.nexomod.emotes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The bytes of one emote message on the relay. No Minecraft imports, so
 * {@code tools/emote-wire-check} can run it against the real relay with javac.
 *
 * <p>Encrypted with a key derived from the server's address, so the relay sees a
 * room hash and noise, never who is emoting. That only protects against a relay
 * that doesn't guess the address, which it can for popular servers; the settings
 * tooltip says so.
 */
public final class NexoEmoteWire {
	public static final int PLAY = 1;
	public static final int STOP = 2;
	/** Older than this and a message is a replay or a badly wrong clock. */
	public static final long MAX_AGE_MILLIS = 120_000L;
	private static final int VERSION = 1;
	private static final int NONCE_BYTES = 12;
	private static final int MAX_TICKET = 256;
	private static final Pattern EMOTE_ID = Pattern.compile("[a-z0-9_]{1,64}");
	private static final SecureRandom RANDOM = new SecureRandom();

	public record Message(int action, UUID player, long sentMillis, float tick, String emote, byte[] ticket) {
	}

	private NexoEmoteWire() {
	}

	private static byte[] sha256(String text) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** One spelling per server: players typing it differently still meet if only case or the default port differ. */
	static String normalise(String address) {
		String text = address.strip().toLowerCase(Locale.ROOT);
		if (text.endsWith(".")) {
			text = text.substring(0, text.length() - 1);
		}
		return text.contains(":") ? text : text + ":25565";
	}

	public static byte[] room(String address) {
		return sha256("nexo-emote-room\0" + normalise(address));
	}

	public static byte[] key(String address) {
		return sha256("nexo-emote-key\0" + normalise(address));
	}

	public static byte[] encode(Message message, byte[] key) throws IOException, GeneralSecurityException {
		ByteArrayOutputStream plain = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(plain);
		out.writeByte(VERSION);
		out.writeByte(message.action());
		out.writeLong(message.player().getMostSignificantBits());
		out.writeLong(message.player().getLeastSignificantBits());
		out.writeLong(message.sentMillis());
		out.writeFloat(message.tick());
		out.writeUTF(message.emote());
		out.writeShort(message.ticket().length);
		out.write(message.ticket());
		byte[] nonce = new byte[NONCE_BYTES];
		RANDOM.nextBytes(nonce);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
		byte[] sealed = cipher.doFinal(plain.toByteArray());
		byte[] frame = new byte[NONCE_BYTES + sealed.length];
		System.arraycopy(nonce, 0, frame, 0, NONCE_BYTES);
		System.arraycopy(sealed, 0, frame, NONCE_BYTES, sealed.length);
		return frame;
	}

	/** The message, or null if it is anything other than well formed and from someone with the key. */
	public static Message decode(byte[] frame, byte[] key) {
		if (frame.length <= NONCE_BYTES) {
			return null;
		}
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, frame, 0, NONCE_BYTES));
			byte[] plain = cipher.doFinal(frame, NONCE_BYTES, frame.length - NONCE_BYTES);
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain));
			if (in.readByte() != VERSION) {
				return null;
			}
			int action = in.readByte();
			UUID player = new UUID(in.readLong(), in.readLong());
			long sent = in.readLong();
			float tick = in.readFloat();
			String emote = in.readUTF();
			int length = in.readUnsignedShort();
			if ((action != PLAY && action != STOP) || length > MAX_TICKET || !EMOTE_ID.matcher(emote).matches()
					|| Float.isNaN(tick) || tick < 0F || tick > 1200F * 20F) {
				return null;
			}
			byte[] ticket = in.readNBytes(length);
			return ticket.length == length ? new Message(action, player, sent, tick, emote, ticket) : null;
		} catch (GeneralSecurityException | IOException | RuntimeException e) {
			return null;
		}
	}
}
