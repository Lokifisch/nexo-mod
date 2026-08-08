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
import java.util.Locale;
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

	/**
	 * Shared with Nexo Client, so an account added in the launcher appears in
	 * the in-game switcher and the other way round.
	 *
	 * <p>Deliberately <em>not</em> the instance config dir: that made the store
	 * per-instance, so accounts did not even follow the player between their
	 * own instances. This resolves the same OS location the launcher's
	 * {@code directories} crate does — see {@code Mod/docs/SHARED-ACCOUNT-STORE.md}.
	 */
	private static final Path DATA_FILE = sharedDataDir().resolve("accounts.dat");
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
		} catch (Exception e) {
			// Left in place rather than deleted. This file is shared with the
			// launcher now, so destroying it would take the launcher's accounts
			// with it — and an undecryptable file is already useless to anyone
			// without this machine's hardware, which was the original reason for
			// deleting it.
			LOGGER.error("Stored accounts can't be decrypted on this machine ({}) — leaving {} alone and starting without saved accounts", e.toString(), DATA_FILE);
			accounts = new ArrayList<>();
			activeUuid = null;
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
			byte[] encrypted = encrypt(plaintext, key);
			Files.createDirectories(DATA_FILE.getParent());
			Files.write(DATA_FILE, encrypted);
		} catch (Exception e) {
			LOGGER.error("Failed to save accounts", e);
		}
	}

	/**
	 * The launcher's platform data directory, resolved the same way its
	 * {@code directories} crate does. These paths are a contract between the
	 * two halves, not a preference.
	 */
	private static Path sharedDataDir() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home", ".");

		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			Path base = appData != null && !appData.isBlank()
					? Path.of(appData)
					: Path.of(home, "AppData", "Roaming");
			return base.resolve("nexoclient").resolve("nexo").resolve("data");
		}
		if (os.contains("mac")) {
			return Path.of(home, "Library", "Application Support", "dev.nexoclient.nexo");
		}

		// Linux and the BSDs honour XDG_DATA_HOME when it is set.
		String xdg = System.getenv("XDG_DATA_HOME");
		Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".local", "share");
		return base.resolve("nexo");
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
