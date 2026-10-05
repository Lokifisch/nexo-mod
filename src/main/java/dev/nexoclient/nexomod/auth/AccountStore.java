package dev.nexoclient.nexomod.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

import dev.nexoclient.nexomod.util.NexoPaths;

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
 * <p><b>Migration release:</b> every save also writes a second copy encrypted
 * under a key held in the OS keychain (see {@link KeychainKey}), which is
 * loaded only when the hardware-keyed file can't be read. The release after
 * this one makes the keychain copy the sole store and removes
 * {@link HardwareKey}, the format spec, and the launcher's matching reader.
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
	/** Same layout, keyed from the OS keychain instead of the hardware; version byte 3. */
	private static final byte[] KEYCHAIN_HEADER = {'N', 'E', 'X', 'O', 'A', 'C', 'C', 3};

	/**
	 * Shared with Nexo Client, so an account added in the launcher appears in
	 * the in-game switcher and the other way round.
	 *
	 * <p>Deliberately <em>not</em> the instance config dir: that made the store
	 * per-instance, so accounts did not even follow the player between their
	 * own instances. This resolves the same OS location the launcher's
	 * {@code directories} crate does — see {@code Mod/docs/SHARED-ACCOUNT-STORE.md}.
	 */
	private static final Path DATA_FILE = NexoPaths.sharedDataDir().resolve("accounts.dat");
	/**
	 * Migration copy, encrypted under the keychain key. Written alongside
	 * {@link #DATA_FILE} (which the launcher still reads) and used only when that
	 * file can't be read. The next release makes this the only store and drops
	 * {@link HardwareKey}.
	 */
	private static final Path KEYCHAIN_FILE = NexoPaths.sharedDataDir().resolve("accounts.kc.dat");
	/** Only referenced to clean up installs of the old scheme that kept the key next to the data. */
	private static final Path LEGACY_KEY_FILE = FabricLoader.getInstance().getConfigDir().resolve("nexomod-accounts.key");

	/**
	 * Field names are the wire format: Gson serialises record components by
	 * name and the launcher's Rust structs are renamed to match.
	 *
	 * <p>The cosmetic fields belong to the launcher. This mod does not use
	 * them, but must carry them through untouched — dropping them would wipe
	 * the launcher's skin and cape data on every in-game account change.
	 */
	private record StoredAccount(String name, String uuid, String minecraftAccessToken, String microsoftRefreshToken, long expiresAtEpochSecond, boolean offline,
			String skinUrl, String skinModel, String capeUrl) {}
	private record StoredData(List<StoredAccount> accounts, String activeUuid) {}

	private List<MinecraftAccount> accounts = new ArrayList<>();
	private UUID activeUuid;
	/** Launcher-owned cosmetic fields, kept verbatim so a save doesn't drop them. */
	private final Map<UUID, StoredAccount> passthrough = new HashMap<>();

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
		if (!Files.exists(DATA_FILE) && !Files.exists(KEYCHAIN_FILE)) {
			return; // brand-new user: nothing to decrypt, so don't wait on any key
		}
		// The hardware-keyed file stays authoritative this release: the launcher
		// writes it, so it is the freshest. The keychain copy is the fallback for
		// when this machine's hardware changed and the file can no longer be read.
		StoredData data = read(DATA_FILE, HardwareKey.await(), HEADER);
		boolean recovered = false;
		// Sign-out rewrites the primary (empty) rather than deleting it, so a missing primary
		// means the hardware key was unavailable at first save: the copy is then the only record.
		if (data == null) {
			data = read(KEYCHAIN_FILE, KeychainKey.get(), KEYCHAIN_HEADER);
			recovered = data != null;
			if (recovered && Files.exists(DATA_FILE)) {
				// The primary is shared with the launcher and may only be unreadable because a
				// key probe misfired; keep it recoverable before save() overwrites it.
				try {
					Files.copy(DATA_FILE, DATA_FILE.resolveSibling("accounts.dat.bak"),
							java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				} catch (java.io.IOException e) {
					LOGGER.error("Could not back up {}", DATA_FILE.getFileName(), e);
				}
			}
		}
		if (data != null) {
			accounts = new ArrayList<>();
			passthrough.clear();
			for (StoredAccount entry : data.accounts()) {
				passthrough.put(UUID.fromString(entry.uuid()), entry);
				accounts.add(new MinecraftAccount(
						entry.name(),
						UUID.fromString(entry.uuid()),
						entry.minecraftAccessToken(),
						entry.microsoftRefreshToken(),
						Instant.ofEpochSecond(entry.expiresAtEpochSecond()),
						entry.offline()));
			}
			activeUuid = data.activeUuid() != null ? UUID.fromString(data.activeUuid()) : null;
		}
		// Migration: create the keychain copy for existing users, or restore both files after a recovery.
		if (data != null && (recovered || !Files.exists(KEYCHAIN_FILE)) && KeychainKey.get() != null) {
			save();
		}
	}

	/** Decrypts one store file; null (and a log line) if it is missing, unkeyed, or unreadable. Never deletes. */
	private static StoredData read(Path file, SecretKey key, byte[] header) {
		if (!Files.exists(file)) {
			return null;
		}
		if (key == null) {
			// Can't verify the file without a key; don't destroy what we can't check.
			LOGGER.error("No key available for {} — leaving it untouched", file.getFileName());
			return null;
		}
		try {
			byte[] plaintext = decrypt(Files.readAllBytes(file), key, header);
			return GSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), StoredData.class);
		} catch (Exception e) {
			// Left in place rather than deleted. The hardware file is shared with the
			// launcher, so destroying it would take the launcher's accounts with it,
			// and an undecryptable file is already useless to anyone without the key.
			LOGGER.error("Can't decrypt {} ({}) — leaving it alone", file.getFileName(), e.toString());
			return null;
		}
	}

	private void save() {
		SecretKey hardwareKey = HardwareKey.await();
		SecretKey keychainKey = KeychainKey.get();
		if (hardwareKey == null && keychainKey == null) {
			LOGGER.error("No key available — keeping accounts in memory only for this session");
			return;
		}
		try {
			List<StoredAccount> stored = accounts.stream()
					.map(a -> {
						StoredAccount previous = passthrough.get(a.uuid());
						return new StoredAccount(a.name(), a.uuid().toString(), a.minecraftAccessToken(), a.microsoftRefreshToken(), a.expiresAt().getEpochSecond(), a.offline(),
								previous != null ? previous.skinUrl() : null,
								previous != null ? previous.skinModel() : null,
								previous != null ? previous.capeUrl() : null);
					})
					.toList();
			StoredData data = new StoredData(stored, activeUuid != null ? activeUuid.toString() : null);
			byte[] plaintext = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
			Files.createDirectories(DATA_FILE.getParent());
			if (hardwareKey != null) {
				writeAtomic(DATA_FILE, encrypt(plaintext, hardwareKey, HEADER));
			}
			if (keychainKey != null) {
				writeAtomic(KEYCHAIN_FILE, encrypt(plaintext, keychainKey, KEYCHAIN_HEADER));
			}
		} catch (Exception e) {
			LOGGER.error("Failed to save accounts", e);
		}
	}

	/** Owner-only (0600 where POSIX) temp file, then an atomic move, so a crash never leaves a torn or world-readable store. */
	private static void writeAtomic(Path file, byte[] bytes) throws java.io.IOException {
		Path temp;
		try {
			temp = Files.createTempFile(file.toAbsolutePath().getParent(), file.getFileName().toString(), ".tmp",
					java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
							java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
		} catch (UnsupportedOperationException e) {
			// non-POSIX (Windows): inherits the user-profile ACL
			temp = Files.createTempFile(file.toAbsolutePath().getParent(), file.getFileName().toString(), ".tmp");
		}
		try {
			Files.write(temp, bytes);
			try {
				Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (java.io.IOException e) {
			Files.deleteIfExists(temp);
			throw e;
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (Exception e) {
			LOGGER.warn("Failed to delete {}", path.getFileName(), e);
		}
	}

	private static byte[] encrypt(byte[] plaintext, SecretKey key, byte[] header) throws GeneralSecurityException {
		byte[] iv = new byte[GCM_IV_BYTES];
		new SecureRandom().nextBytes(iv);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
		cipher.updateAAD(header);
		byte[] ciphertext = cipher.doFinal(plaintext);
		return ByteBuffer.allocate(header.length + iv.length + ciphertext.length)
				.put(header).put(iv).put(ciphertext).array();
	}

	private static byte[] decrypt(byte[] stored, SecretKey key, byte[] header) throws GeneralSecurityException {
		if (stored.length < header.length + GCM_IV_BYTES
				|| !Arrays.equals(stored, 0, header.length, header, 0, header.length)) {
			throw new GeneralSecurityException("not a current-format nexomod account store");
		}
		ByteBuffer buffer = ByteBuffer.wrap(stored, header.length, stored.length - header.length);
		byte[] iv = new byte[GCM_IV_BYTES];
		buffer.get(iv);
		byte[] ciphertext = new byte[buffer.remaining()];
		buffer.get(ciphertext);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
		cipher.updateAAD(header);
		return cipher.doFinal(ciphertext);
	}
}
