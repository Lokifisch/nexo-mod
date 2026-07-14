package dev.nexoclient.nexomod.auth;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Local storage for saved Minecraft accounts, so switching between them
 * doesn't require signing in again each time. Encrypted at rest with a
 * per-install AES-256-GCM key (stored alongside, restricted to the current
 * user by the OS's normal file permissions) — this defends the token file
 * against casual inspection/copy-paste, not against an attacker who
 * already has full access to this machine's user account, same threat
 * model as most game launchers' local session storage.
 */
public final class AccountStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/auth");
	private static final Gson GSON = new GsonBuilder().create();
	private static final int GCM_TAG_BITS = 128;
	private static final int GCM_IV_BYTES = 12;

	private static final Path DATA_FILE = FabricLoader.getInstance().getConfigDir().resolve("nexomod-accounts.dat");
	private static final Path KEY_FILE = FabricLoader.getInstance().getConfigDir().resolve("nexomod-accounts.key");

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

	public void setActive(UUID uuid) {
		activeUuid = uuid;
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
		if (!Files.exists(DATA_FILE)) {
			return;
		}
		try {
			byte[] encrypted = Files.readAllBytes(DATA_FILE);
			byte[] plaintext = decrypt(encrypted, loadOrCreateKey());
			StoredData data = GSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), StoredData.class);
			if (data == null) {
				return;
			}
			accounts = new ArrayList<>();
			for (StoredAccount stored : data.accounts()) {
				accounts.add(new MinecraftAccount(
						stored.name(),
						UUID.fromString(stored.uuid()),
						stored.minecraftAccessToken(),
						stored.microsoftRefreshToken(),
						Instant.ofEpochSecond(stored.expiresAtEpochSecond()),
						stored.offline()));
			}
			activeUuid = data.activeUuid() != null ? UUID.fromString(data.activeUuid()) : null;
		} catch (Exception e) {
			LOGGER.error("Failed to load saved accounts — starting fresh", e);
			accounts = new ArrayList<>();
			activeUuid = null;
		}
	}

	private void save() {
		try {
			List<StoredAccount> stored = accounts.stream()
					.map(a -> new StoredAccount(a.name(), a.uuid().toString(), a.minecraftAccessToken(), a.microsoftRefreshToken(), a.expiresAt().getEpochSecond(), a.offline()))
					.toList();
			StoredData data = new StoredData(stored, activeUuid != null ? activeUuid.toString() : null);
			byte[] plaintext = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
			byte[] encrypted = encrypt(plaintext, loadOrCreateKey());
			Files.write(DATA_FILE, encrypted);
		} catch (Exception e) {
			LOGGER.error("Failed to save accounts", e);
		}
	}

	private static SecretKey loadOrCreateKey() throws IOException {
		if (Files.exists(KEY_FILE)) {
			byte[] keyBytes = Files.readAllBytes(KEY_FILE);
			return new SecretKeySpec(keyBytes, "AES");
		}
		byte[] keyBytes = new byte[32];
		new SecureRandom().nextBytes(keyBytes);
		Files.write(KEY_FILE, keyBytes);
		return new SecretKeySpec(keyBytes, "AES");
	}

	private static byte[] encrypt(byte[] plaintext, SecretKey key) throws GeneralSecurityException {
		byte[] iv = new byte[GCM_IV_BYTES];
		new SecureRandom().nextBytes(iv);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
		byte[] ciphertext = cipher.doFinal(plaintext);
		return ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
	}

	private static byte[] decrypt(byte[] stored, SecretKey key) throws GeneralSecurityException {
		ByteBuffer buffer = ByteBuffer.wrap(stored);
		byte[] iv = new byte[GCM_IV_BYTES];
		buffer.get(iv);
		byte[] ciphertext = new byte[buffer.remaining()];
		buffer.get(ciphertext);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
		return cipher.doFinal(ciphertext);
	}
}
