package dev.nexoclient.nexomod.badge;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Who gets a Nexo badge.
 *
 * <p>Badges used to be hardcoded to the local player, because there was no way
 * to know whether anyone else had the mod — Nexo Mod is client-only and a
 * vanilla server discards custom payloads it does not recognise, so nothing
 * inside the game can carry that fact between two clients. This asks a small
 * out-of-band service instead, by downloading the set of everyone who uses
 * Nexo and matching against it locally.
 *
 * <p>Your own badge does not depend on any of that. {@link #hasBadge(UUID)}
 * answers yes for the local player before it looks at anything else, so with
 * the network off, sync disabled, or the service down, the mod behaves exactly
 * as it did before this existed.
 */
public final class NexoBadges {
	/**
	 * How often the roster is re-fetched. Membership changes when somebody
	 * installs the mod, which is not an event worth polling hard for; the
	 * request is a conditional GET and usually answered 304 with no body.
	 */
	private static final long REFRESH_MINUTES = 30;

	/**
	 * Registration waits this long after startup. The session is not
	 * necessarily settled the instant the mod initialises — the launcher may
	 * still be handing the game its token — and nothing here is urgent.
	 */
	private static final long REGISTER_DELAY_SECONDS = 20;

	private static final BadgeService SERVICE = new BadgeService();
	private static final BadgeRoster ROSTER = new BadgeRoster();
	private static final BadgeIdentity IDENTITY = new BadgeIdentity(SERVICE);

	/** One thread, daemon: this must never hold the game open at shutdown. */
	private static ScheduledExecutorService worker;

	/** Stops a second registration attempt in the same session. */
	private static final AtomicBoolean registeredThisSession = new AtomicBoolean();

	private NexoBadges() {
	}

	public static void register() {
		// The cached roster is read regardless of the toggle: it costs one file
		// read, and it means badges are right from the first frame instead of
		// popping in when the first fetch lands.
		ROSTER.loadFromDisk();

		worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "nexo-badges");
			thread.setDaemon(true);
			return thread;
		});

		worker.schedule(NexoBadges::reconcile, REGISTER_DELAY_SECONDS, TimeUnit.SECONDS);
		worker.scheduleWithFixedDelay(NexoBadges::refreshRoster,
				REGISTER_DELAY_SECONDS + 5, REFRESH_MINUTES * 60, TimeUnit.SECONDS);
	}

	public static void shutdown() {
		if (worker != null) {
			worker.shutdownNow();
			worker = null;
		}
	}

	/**
	 * Does the badge belong on this name?
	 *
	 * <p>Called from the render thread once per nametag and once per tab-list
	 * row, so it must not block or allocate meaningfully: the roster lookup is
	 * a binary search over a byte array behind a memoised verdict.
	 */
	public static boolean hasBadge(UUID id) {
		if (id == null) {
			return false;
		}
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.player != null
				&& id.equals(client.player.getGameProfile().id())) {
			return true;
		}
		if (!NexoConfig.get().badgeSyncEnabled()) {
			return false;
		}
		return ROSTER.contains(id);
	}

	/**
	 * Brings the service in line with the setting, in whichever direction it is
	 * out of step.
	 *
	 * <p>The registered flag is persisted rather than inferred, because turning
	 * the setting off has to remove the account from the roster even if the
	 * game was offline at the time — otherwise "off" would only mean "stop
	 * looking", and the player would still be published to everyone else.
	 */
	private static void reconcile() {
		try {
			NexoConfig config = NexoConfig.get();
			User user = BadgeIdentity.currentUser();
			if (user == null) {
				return;
			}

			if (config.badgeSyncEnabled()) {
				if (!config.badgeSyncRegistered() && registeredThisSession.compareAndSet(false, true)) {
					if (IDENTITY.register(user)) {
						config.setBadgeSyncRegistered(true);
						NexoMod.LOGGER.info("[nexomod] Badge sync: registered as {}.", user.getName());
					} else {
						// Left false so the next start tries again, and cleared
						// so a manual toggle can retry sooner.
						registeredThisSession.set(false);
					}
				}
				refreshRoster();
			} else if (config.badgeSyncRegistered()) {
				if (IDENTITY.unregister(user)) {
					config.setBadgeSyncRegistered(false);
					NexoMod.LOGGER.info("[nexomod] Badge sync: removed {} from the roster.", user.getName());
				}
			}
		} catch (RuntimeException e) {
			// A background thread that dies takes the periodic refresh with it.
			NexoMod.LOGGER.warn("[nexomod] Badge sync pass failed.", e);
		}
	}

	private static void refreshRoster() {
		try {
			if (!NexoConfig.get().badgeSyncEnabled()) {
				return;
			}
			ROSTER.refresh(SERVICE);
		} catch (RuntimeException e) {
			NexoMod.LOGGER.warn("[nexomod] Badge roster refresh failed.", e);
		}
	}

	/**
	 * Called when the setting is toggled, so the change takes effect now rather
	 * than at the next restart.
	 *
	 * <p>Switching off drops the cached roster immediately: leaving other
	 * players' hashes on disk after the player asked for the feature to stop
	 * would be keeping data they opted out of.
	 */
	public static void onSettingChanged(boolean enabled) {
		if (!enabled) {
			ROSTER.clear();
		}
		registeredThisSession.set(false);
		if (worker != null) {
			worker.execute(NexoBadges::reconcile);
		}
	}

	/** For the settings screen: how many other players this client knows about. */
	public static int rosterSize() {
		return ROSTER.size();
	}
}
