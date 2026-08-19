package dev.nexoclient.nexomod.cosmetics;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.net.MojangIdentityProof;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Entry point for cosmetics: catalog sync, equip-state sync, and every
 * network action a picker screen needs. Same shape as {@code NexoBadges} — a
 * background daemon scheduler doing periodic refreshes, gated by a settings
 * toggle that turns this mod's cosmetics network chatter off entirely.
 *
 * <p>Every player-triggered action ({@link #equip}, {@link #purchase},
 * {@link #claimChallenge}, {@link #submit}, {@link #fetchWallet}) runs on the
 * background worker and calls its callback back on the render thread, so a
 * screen can fire one of these from a button press without blocking a frame
 * on network I/O.
 */
public final class NexoCosmetics {
	private static final long CATALOG_REFRESH_MINUTES = 15;
	private static final long EQUIPPED_REFRESH_SECONDS = 30;
	private static final long START_DELAY_SECONDS = 20;

	private static final CosmeticsServiceClient SERVICE = new CosmeticsServiceClient();
	private static final CosmeticsCatalog CATALOG = new CosmeticsCatalog();
	private static final CosmeticsEquipped EQUIPPED = new CosmeticsEquipped();
	private static final CosmeticsAssetCache ASSETS = new CosmeticsAssetCache(SERVICE);
	private static final CosmeticsIdentity IDENTITY = new CosmeticsIdentity(SERVICE);

	private static ScheduledExecutorService worker;

	private NexoCosmetics() {
	}

	public static void register() {
		CATALOG.loadFromDisk();

		worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "nexo-cosmetics");
			thread.setDaemon(true);
			return thread;
		});

		worker.schedule(NexoCosmetics::reconcile, START_DELAY_SECONDS, TimeUnit.SECONDS);
		worker.scheduleWithFixedDelay(NexoCosmetics::refreshCatalog,
				START_DELAY_SECONDS + 5, CATALOG_REFRESH_MINUTES * 60, TimeUnit.SECONDS);
		worker.scheduleWithFixedDelay(NexoCosmetics::refreshEquipped,
				START_DELAY_SECONDS + 10, EQUIPPED_REFRESH_SECONDS, TimeUnit.SECONDS);
	}

	public static void shutdown() {
		if (worker != null) {
			worker.shutdownNow();
			worker = null;
		}
		ASSETS.shutdown();
	}

	/** Called when the setting is toggled, so the account resolves (or stops resolving) without a restart. */
	public static void onSettingChanged(boolean enabled) {
		if (worker != null) {
			worker.execute(NexoCosmetics::reconcile);
		}
	}

	/** Re-run after an account switch, same reason {@code NexoBadges} exposes the equivalent. */
	public static void onAccountChanged() {
		if (worker != null) {
			worker.execute(NexoCosmetics::reconcile);
		}
	}

	/** Ensures the local account's own equip state resolves even if their own model is never rendered this session. */
	private static void reconcile() {
		if (!NexoConfig.get().cosmeticsEnabled()) {
			return;
		}
		User user = MojangIdentityProof.currentUser();
		if (user != null) {
			EQUIPPED.noteVisible(user.getProfileId());
		}
	}

	private static void refreshCatalog() {
		try {
			if (NexoConfig.get().cosmeticsEnabled()) {
				CATALOG.refresh(SERVICE);
			}
		} catch (RuntimeException e) {
			NexoMod.LOGGER.warn("[nexomod] Cosmetics catalog refresh failed.", e);
		}
	}

	private static void refreshEquipped() {
		try {
			if (NexoConfig.get().cosmeticsEnabled()) {
				EQUIPPED.refresh(SERVICE);
			}
		} catch (RuntimeException e) {
			NexoMod.LOGGER.warn("[nexomod] Cosmetics equipped refresh failed.", e);
		}
	}

	/** Equips `cosmeticId` in `slot` for the signed-in account. `onDone` runs on the render thread. */
	public static void equip(String slot, int cosmeticId, Consumer<Boolean> onDone) {
		runInBackground(onDone, false, user -> {
			boolean ok = IDENTITY.equip(user, slot, cosmeticId);
			if (ok) {
				EQUIPPED.applyLocalEquip(user.getProfileId(), slot, cosmeticId);
			}
			return ok;
		});
	}

	/** Clears `slot`, falling back to whatever vanilla shows on its own — this is how "wear my official cape" works. */
	public static void unequip(String slot, Consumer<Boolean> onDone) {
		runInBackground(onDone, false, user -> {
			boolean ok = IDENTITY.unequip(user, slot);
			if (ok) {
				EQUIPPED.applyLocalUnequip(user.getProfileId(), slot);
			}
			return ok;
		});
	}

	public static void purchase(int cosmeticId, Consumer<CosmeticsIdentity.PurchaseResult> onDone) {
		runInBackground(onDone, CosmeticsIdentity.PurchaseResult.FAILED, user -> IDENTITY.purchase(user, cosmeticId));
	}

	/** The caller's balance, or null on failure. */
	public static void fetchWallet(Consumer<Integer> onDone) {
		runInBackground(onDone, null, IDENTITY::fetchWallet);
	}

	/** The caller's owned cosmetic ids, or null on failure. */
	public static void fetchOwned(Consumer<Set<Integer>> onDone) {
		runInBackground(onDone, null, IDENTITY::fetchOwned);
	}

	public static void claimChallenge(String challengeId, Consumer<CosmeticsIdentity.ClaimResult> onDone) {
		runInBackground(onDone, CosmeticsIdentity.ClaimResult.FAILED, user -> IDENTITY.claimChallenge(user, challengeId));
	}

	/** A pending submission id, or null on failure. */
	public static void submit(String type, String name, String contentType, byte[] assetData, Consumer<Integer> onDone) {
		runInBackground(onDone, null, user -> IDENTITY.submit(user, type, name, contentType, assetData));
	}

	private interface UserAction<T> {
		T run(User user);
	}

	private static <T> void runInBackground(Consumer<T> onDone, T noAccountResult, UserAction<T> action) {
		ScheduledExecutorService current = worker;
		if (current == null) {
			onDone.accept(noAccountResult);
			return;
		}
		current.execute(() -> {
			User user = MojangIdentityProof.currentUser();
			T result = user == null ? noAccountResult : action.run(user);
			Minecraft.getInstance().execute(() -> onDone.accept(result));
		});
	}

	static CosmeticsServiceClient service() {
		return SERVICE;
	}

	public static CosmeticsCatalog catalog() {
		return CATALOG;
	}

	public static CosmeticsEquipped equipped() {
		return EQUIPPED;
	}

	static CosmeticsAssetCache assets() {
		return ASSETS;
	}
}
