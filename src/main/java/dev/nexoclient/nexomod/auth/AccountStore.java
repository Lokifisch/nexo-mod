package dev.nexoclient.nexomod.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Local storage for saved Minecraft accounts, so switching between them
 * doesn't require signing in again each time. Encrypted at rest with
 * AES-256-GCM under a key derived from this machine's hardware identity
 * (see {@link HardwareKey}) — no key material on disk at all, so a config
 * folder that gets zipped into a shared modpack carries only ciphertext
 * that is useless anywhere else. A store that this machine's hardware
 * can't unlock (copied from another PC, or this PC's CPU/board/GPU
 * changed) is deleted on sight rather than left around to be poked at.
 *
 * <p>The flip side, deliberately accepted: swapping the GPU/board (or a
 * platform change that alters what identifiers are readable) wipes the
 * saved accounts and everyone signs in again once. Tokens are recoverable
 * that way; a leaked refresh token isn't.
 */
public final class AccountStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/auth");
	private static final Gson GSON = new GsonBuilder().create();
	private static final int GCM_TAG_BITS = 128;
	private static final int GCM_IV_BYTES = 12;
	/** File-format marker, also bound into the ciphertext as GCM additional data. */
	private static final byte[] HEADER = {'N', 'E', 'X', 'O', 'A', 'C', 'C', 2};

	private static final Path DATA_FILE = FabricLoader.getInstance().getConfigDir().resolve("nexomod-accounts.dat");
	/** Only referenced to clean up installs of the old scheme that kept the key next to the data. */
	private static final Path LEGACY_KEY_FILE = FabricLoader.getInstance().getConfigDir().resolve("nexomod-accounts.key");

	private record StoredAccount(String name, String uuid, String minecraftAccessToken, String microsoftRefreshToken, long expiresAtEpochSecond, boolean offline) {}
	private record StoredData(List<StoredAccount> accounts, String activeUuid) {}

	private List<MinecraftAccount> accounts = new ArrayList<>();
	private UUID activeUuid;

	private static AccountStore instance;

	public static synchronized AccountStore get() {
		if (instance == null) {
			instance = new AccountStore();
			instance.load();
		}
		return instance;
	}

	public List<MinecraftAccount> accounts() {
		return List.copyOf(accounts);
	}

	public Optional<MinecraftAccount> active() {
		return accounts.stream().filter(a -> a.uuid().equals(activeUuid)).findFirst();
	}

	public void upsertAndActivate(MinecraftAccount account) {
		accounts.removeIf(a -> a.uuid().equals(account.uuid()));
		accounts.add(account);
		activeUuid = account.uuid();
		save();
	}

	/**
	 * Records which account the live session now uses. Clears the marker when
	 * that account isn't one of ours (the launcher's own, un-stored session),
	 * so {@link #active()} never claims an account the game isn't playing as.
	 */
	public void markActive(UUID uuid) {
		activeUuid = accounts.stream().anyMatch(a -> a.uuid().equals(uuid)) ? uuid : null;
		save();
	}

	public void remove(UUID uuid) {
		accounts.removeIf(a -> a.uuid().equals(uuid));
		if (uuid.equals(activeUuid)) {
			activeUuid = accounts.isEmpty() ? null : accounts.get(0).uuid();
		}
		save();
	}

	private void load() {
		deleteQuietly(LEGACY_KEY_FILE);
		if (!Files.exists(DATA_FILE)) {
			return;
		}
		SecretKey key = HardwareKey.await();
		if (key == null) {
			// Can't verify the file without a fingerprint; don't destroy what we can't check.
			LOGGER.error("No hardware key available — leaving {} untouched and starting without saved accounts", DATA_FILE.getFileName());
			return;
		}
		try {
			byte[] stored = Files.readAllBytes(DATA_FILE);
			byte[] plaintext = decrypt(stored, key);
			StoredData data = GSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), StoredData.class);
			if (data == null) {
				return;
			}
			accounts = new ArrayList<>();
			for (StoredAccount entry : data.accounts()) {
				accounts.add(new MinecraftAccount(
						entry.name(),
						UUID.fromString(entry.uuid()),
						entry.minecraftAccessToken(),
						entry.microsoftRefreshToken(),
						Instant.ofEpochSecond(entry.expiresAtEpochSecond()),
						entry.offline()));
			}
			activeUuid = data.activeUuid() != null ? UUID.fromString(data.activeUuid()) : null;
		} catch (Exception e) {
			// Not decryptable on this hardware means it was copied here (shared
			// config folder) or the machine changed — either way, per design it
			// self-destructs immediately instead of lingering as an oracle.
			LOGGER.warn("Stored accounts can't be decrypted on this machine ({}) — deleting {}", e.toString(), DATA_FILE.getFileName());
			accounts = new ArrayList<>();
			activeUuid = null;
			deleteQuietly(DATA_FILE);
		}
	}

	private void save() {
		SecretKey key = HardwareKey.await();
		if (key == null) {
			LOGGER.error("No hardware key available — keeping accounts in memory only for this session");
			return;
		}
		try {
			List<StoredAccount> stored = accounts.stream()
					.map(a -> new StoredAccount(a.name(), a.uuid().toString(), a.minecraftAccessToken(), a.microsoftRefreshToken(), a.expiresAt().getEpochSecond(), a.offline()))
					.toList();
			StoredData data = new StoredData(stored, activeUuid != null ? activeUuid.toString() : null);
			byte[] plaintext = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
			byte[] encrypted = encrypt(plaintext, key);
			Files.write(DATA_FILE, encrypted);
		} catch (Exception e) {
			LOGGER.error("Failed to save accounts", e);
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (Exception e) {
			LOGGER.warn("Failed to delete {}", path.getFileName(), e);
		}
	}

	private static byte[] encrypt(byte[] plaintext, SecretKey key) throws GeneralSecurityException {
		byte[] iv = new byte[GCM_IV_BYTES];
		new SecureRandom().nextBytes(iv);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
		cipher.updateAAD(HEADER);
		byte[] ciphertext = cipher.doFinal(plaintext);
		return ByteBuffer.allocate(HEADER.length + iv.length + ciphertext.length)
				.put(HEADER).put(iv).put(ciphertext).array();
	}

	private static byte[] decrypt(byte[] stored, SecretKey key) throws GeneralSecurityException {
		if (stored.length < HEADER.length + GCM_IV_BYTES
				|| !Arrays.equals(stored, 0, HEADER.length, HEADER, 0, HEADER.length)) {
			throw new GeneralSecurityException("not a current-format nexomod account store");
		}
		ByteBuffer buffer = ByteBuffer.wrap(stored, HEADER.length, stored.length - HEADER.length);
		byte[] iv = new byte[GCM_IV_BYTES];
		buffer.get(iv);
		byte[] ciphertext = new byte[buffer.remaining()];
		buffer.get(ciphertext);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
		cipher.updateAAD(HEADER);
		return cipher.doFinal(ciphertext);
	}
}
