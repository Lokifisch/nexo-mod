package dev.nexoclient.nexomod.badge;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
	 * The periodic backstop. Most refreshes now happen because an unrecognised
	 * player turned up (see {@link #noteUnknownPlayer()}); this only covers
	 * sitting in a world alone while somebody elsewhere installs the mod.
	 */
	private static final long REFRESH_MINUTES = 15;

	/**
	 * Registration waits this long after startup. The session is not
	 * necessarily settled the instant the mod initialises — the launcher may
	 * still be handing the game its token — and nothing here is urgent.
	 */
	private static final long REGISTER_DELAY_SECONDS = 20;

	private static final BadgeService SERVICE = new BadgeService();
	private static final BadgeRoster ROSTER = new BadgeRoster();
	private static final BadgeIdentity IDENTITY = new BadgeIdentity(SERVICE);

	/**
	 * Shortest gap between refreshes triggered by seeing an unregistered
	 * player. Without a floor this would fire on every frame on a server full
	 * of people who don't use Nexo.
	 */
	private static final long ON_DEMAND_COOLDOWN_MS = 90_000;

	/** One thread, daemon: this must never hold the game open at shutdown. */
	private static ScheduledExecutorService worker;

	/** Accounts already tried this session, so a failure isn't retried per tick. */
	private static final Set<UUID> attemptedThisSession = ConcurrentHashMap.newKeySet();

	/** Guards {@link #noteUnknownPlayer()}; read from the render thread. */
	private static final AtomicLong lastOnDemandRefresh = new AtomicLong();

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
		if (ROSTER.contains(id)) {
			return true;
		}
		// Somebody here isn't in our copy of the roster. Usually that just means
		// they don't use Nexo, but it is also exactly what a friend who
		// installed it after our last fetch looks like, and waiting out the
		// periodic refresh to find out takes half an hour.
		noteUnknownPlayer();
		return false;
	}

	/**
	 * Asks for an out-of-band refresh, at most once per
	 * {@link #ON_DEMAND_COOLDOWN_MS}.
	 *
	 * <p>Called from the render thread for every unbadged player on screen, so
	 * the common path is two atomic reads and nothing else. The refresh itself
	 * is a conditional GET, so when nothing has changed it costs a 304.
	 */
	private static void noteUnknownPlayer() {
		ScheduledExecutorService current = worker;
		if (current == null) {
			return;
		}
		long now = System.currentTimeMillis();
		long last = lastOnDemandRefresh.get();
		if (now - last < ON_DEMAND_COOLDOWN_MS) {
			return;
		}
		// Whoever wins the CAS does the work; everyone else this frame drops out.
		if (!lastOnDemandRefresh.compareAndSet(last, now)) {
			return;
		}
		current.execute(NexoBadges::refreshRoster);
	}

	/**
	 * Brings the service in line with the setting, for whichever account is
	 * signed in right now.
	 *
	 * <p>Registration state is tracked per account, not per installation. As a
	 * single flag it meant the first account to register made every later one
	 * look already-done, so a second account launched from the same instance —
	 * or picked in Nexo's account switcher — never registered and stayed
	 * invisible to everybody else.
	 *
	 * <p>It is persisted rather than inferred, because turning the setting off
	 * has to remove the account from the roster even if the game was offline at
	 * the time — otherwise "off" would only mean "stop looking", and the player
	 * would still be published to everyone else.
	 */
	private static void reconcile() {
		try {
			NexoConfig config = NexoConfig.get();
			User user = BadgeIdentity.currentUser();
			if (user == null) {
				return;
			}
			UUID account = user.getProfileId();

			if (config.badgeSyncEnabled()) {
				if (!config.badgeSyncRegistered(account) && attemptedThisSession.add(account)) {
					if (IDENTITY.register(user)) {
						config.setBadgeSyncRegistered(account, true);
						NexoMod.LOGGER.info("[nexomod] Badge sync: registered as {}.", user.getName());
					} else {
						// Dropped again so the next start, account switch or
						// toggle retries rather than giving up for good.
						attemptedThisSession.remove(account);
					}
				}
				refreshRoster();
			} else if (config.badgeSyncRegistered(account)) {
				if (IDENTITY.unregister(user)) {
					config.setBadgeSyncRegistered(account, false);
					NexoMod.LOGGER.info("[nexomod] Badge sync: removed {} from the roster.", user.getName());
				}
			}
		} catch (RuntimeException e) {
			// A background thread that dies takes the periodic refresh with it.
			NexoMod.LOGGER.warn("[nexomod] Badge sync pass failed.", e);
		}
	}

	/**
	 * Re-runs the pass after the account changed.
	 *
	 * <p>Without this, switching account mid-session leaves the new one
	 * unregistered until the next launch, and its badge invisible to everyone
	 * for as long as the game stays open.
	 */
	public static void onAccountChanged() {
		if (worker != null) {
			worker.execute(NexoBadges::reconcile);
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
		attemptedThisSession.clear();
		if (worker != null) {
			worker.execute(NexoBadges::reconcile);
		}
	}
}
