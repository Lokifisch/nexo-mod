package dev.nexoclient.nexomod.discord;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jcm.discordgamesdk.Core;
import de.jcm.discordgamesdk.CreateParams;
import de.jcm.discordgamesdk.DiscordEventAdapter;
import de.jcm.discordgamesdk.activity.Activity;
import de.jcm.discordgamesdk.activity.ActivityAssets;
import de.jcm.discordgamesdk.activity.ActivityButton;
import de.jcm.discordgamesdk.activity.ActivityButtonsMode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * In-game Discord Rich Presence, on top of
 * <a href="https://github.com/JnCrMx/discord-game-sdk4j">discord-game-sdk4j</a>
 * (MIT) — a pure-Java reimplementation of Discord's local RPC IPC protocol,
 * used instead of a hand-rolled socket client since it's already
 * battle-tested by other real Fabric mods on this exact MC/loader version
 * (see build.gradle for the specific reference).
 *
 * <p>Connects using the same Discord Application ID as the Nexo Client
 * launcher's own Rich Presence — so whichever of the two last called
 * {@code setActivity} is what Discord actually shows. That's deliberate:
 * the launcher sets a generic "Playing Nexo Client" the moment it starts
 * the game process, and this takes over with the specific world/server
 * line the moment the game actually joins one; there's no need for the two
 * processes to coordinate beyond that, since the launcher only touches its
 * own activity again at process launch and process exit.
 *
 * <p>All Discord IPC work happens on a single background thread, which also
 * periodically pumps the connection ({@link Core#runCallbacks()}) so that
 * Discord pushing an {@code ACTIVITY_JOIN} event (a friend clicking "Join")
 * is noticed even when nothing else is triggering an update — see
 * {@link #setJoinHandler}.
 */
public final class DiscordRichPresence {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/discord");

	/**
	 * Same Discord Application ID as Client/packages/app-lib/src/state/discord.rs's
	 * DISCORD_CLIENT_ID — must be kept in sync with that constant by hand,
	 * since they're separate processes/languages. Replace both with your
	 * own app's ID from https://discord.com/developers/applications.
	 */
	private static final long CLIENT_ID = 1529546507118706859L;

	private static final String LARGE_IMAGE_KEY = "nexo_logo";
	private static final String LARGE_IMAGE_TEXT = "Nexo Client";
	private static final String DETAILS_TEXT = "Playing Nexo Client";
	private static final String DOWNLOAD_BUTTON_LABEL = "Download Nexo Client";
	/** Placeholder until a real download page exists — matches the Client-side constant. */
	private static final String DOWNLOAD_BUTTON_URL = "https://github.com/Lokifisch/nexo-client";

	/**
	 * Small image (bottom-right of the large Nexo logo) — the head render of
	 * whichever account is running the game, from cravatar.eu's head-render
	 * endpoint, keyed by UUID (stable across name changes) rather than the
	 * account's current name.
	 */
	private static final int HEAD_IMAGE_SIZE = 128;

	/** How often the background thread pumps the Discord connection when nothing else is triggering an update. */
	private static final long PUMP_INTERVAL_MS = 1000;

	private static final DiscordRichPresence INSTANCE = new DiscordRichPresence();

	private final ScheduledExecutorService ioExecutor;
	private volatile Core core;
	private volatile boolean shutDown;
	/** Whether the last connection attempt's failure was already logged — so an outage warns once, not once per pump. */
	private boolean unreachableLogged;

	/** Set by {@code NexoDiscordRpc} to handle a friend clicking "Join" on our Discord activity. */
	private volatile Consumer<String> joinHandler;

	private DiscordRichPresence() {
		AtomicInteger threadCount = new AtomicInteger();
		ThreadFactory factory = runnable -> {
			Thread thread = new Thread(runnable, "nexomod-discord-ipc-" + threadCount.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
		this.ioExecutor = Executors.newSingleThreadScheduledExecutor(factory);
		this.ioExecutor.scheduleWithFixedDelay(this::pump, PUMP_INTERVAL_MS, PUMP_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	public static DiscordRichPresence get() {
		return INSTANCE;
	}

	public void setJoinHandler(Consumer<String> handler) {
		this.joinHandler = handler;
	}

	/** Sets the activity with no join button — used for singleplayer, menus, and idling, where there's no server to invite anyone to. */
	public void setActivity(String state, long startEpochMillis) {
		setActivity(state, startEpochMillis, null);
	}

	/**
	 * Sets the activity to "Playing Nexo Client" / {@code state}, with an
	 * elapsed timer since {@code startEpochMillis}. If {@code joinServerAddress}
	 * is non-null, attaches a party + join secret so Discord shows a "Join"
	 * button to friends instead of the download button — Discord only allows
	 * one or the other on an activity, never both — only meaningful (and only
	 * ever passed) for multiplayer, since singleplayer has no address anyone
	 * else could connect to.
	 */
	public void setActivity(String state, long startEpochMillis, String joinServerAddress) {
		if (!isEnabled()) {
			return;
		}
		submit(() -> {
			Core c = ensureConnected();
			if (c == null) {
				return;
			}

			Activity activity = new Activity();
			activity.setDetails(DETAILS_TEXT);
			activity.setState(state);
			activity.timestamps().setStart(Instant.ofEpochMilli(startEpochMillis));

			ActivityAssets assets = activity.assets();
			assets.setLargeImage(LARGE_IMAGE_KEY);
			assets.setLargeText(LARGE_IMAGE_TEXT);
			User user = Minecraft.getInstance().getUser();
			if (user != null) {
				assets.setSmallImage("https://cravatar.eu/head/" + user.getProfileId() + "/" + HEAD_IMAGE_SIZE + ".png");
				assets.setSmallText(user.getName());
			}

			if (joinServerAddress != null) {
				activity.setActivityButtonsMode(ActivityButtonsMode.SECRETS);
				activity.party().setID("nexo-" + joinServerAddress);
				activity.secrets().setJoinSecret(joinServerAddress);
			} else {
				activity.setActivityButtonsMode(ActivityButtonsMode.BUTTONS);
				activity.addButton(new ActivityButton(DOWNLOAD_BUTTON_LABEL, DOWNLOAD_BUTTON_URL));
			}

			try {
				c.activityManager().updateActivity(activity, result -> {});
			} catch (Exception e) {
				LOGGER.warn("Failed to update Discord activity, will retry on next update", e);
				closeCore();
			}
		});
	}

	/** Clears the activity (e.g. on world leave/disconnect) without closing the underlying connection. */
	public void clearActivity() {
		submit(() -> {
			Core c = core;
			if (c == null) {
				return;
			}
			try {
				// Not c.activityManager().clearActivity() — that method has a bug
				// upstream where it ignores the activity it's meant to clear and
				// always uses the default (exception-throwing) callback.
				c.activityManager().updateActivity(null, result -> {});
			} catch (Exception e) {
				LOGGER.warn("Failed to clear Discord activity", e);
				closeCore();
			}
		});
	}

	/** Call once, when the game is fully shutting down. */
	public void shutdown() {
		shutDown = true;
		submit(this::closeCore);
		ioExecutor.shutdown();
	}

	private boolean isEnabled() {
		return !shutDown && dev.nexoclient.nexomod.screen.NexoConfig.get().discordRpcEnabled();
	}

	private void submit(Runnable task) {
		try {
			ioExecutor.submit(() -> {
				try {
					task.run();
				} catch (Exception e) {
					LOGGER.debug("Discord RPC task failed", e);
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			// Executor already shut down (game closing) — nothing to do.
		}
	}

	/** Must only be called from the IO executor thread. Runs on a timer so a friend's Discord "Join" click is noticed even between activity updates. */
	private void pump() {
		if (shutDown || !isEnabled()) {
			return;
		}
		Core c = ensureConnected();
		if (c == null) {
			return;
		}
		try {
			c.runCallbacks();
		} catch (Exception e) {
			LOGGER.debug("Discord IPC connection dropped, will reconnect on next update", e);
			closeCore();
		}
	}

	/** Must only be called from the IO executor thread. Creates a fresh Core if there's none, or the existing one lost its connection — this library doesn't retry on its own. */
	private Core ensureConnected() {
		Core existing = core;
		if (existing != null && existing.isDiscordRunning()) {
			return existing;
		}
		closeCore();

		try {
			CreateParams params = new CreateParams();
			params.setClientID(CLIENT_ID);
			params.setFlags(CreateParams.Flags.SUPPRESS_EXCEPTIONS);
			params.registerEventHandler(new DiscordEventAdapter() {
				@Override
				public void onActivityJoin(String secret) {
					Consumer<String> handler = joinHandler;
					if (handler != null) {
						handler.accept(secret);
					}
				}
			});

			Core fresh = new Core(params);
			// The library's default log hook prints every IPC frame to stdout at
			// VERBOSE — reroute into our logger so the game log stays clean.
			fresh.setLogHook(de.jcm.discordgamesdk.LogLevel.ERROR,
					(level, message) -> LOGGER.debug("[discord-sdk] {}", message));
			if (!fresh.isDiscordRunning()) {
				fresh.close();
				logUnreachableOnce(null);
				return null;
			}
			LOGGER.info("Discord Rich Presence: connected.");
			unreachableLogged = false;
			core = fresh;
			return fresh;
		} catch (Exception e) {
			logUnreachableOnce(e);
			return null;
		}
	}

	/** Must only be called from the IO executor thread. Warns once per outage instead of once per reconnect attempt. */
	private void logUnreachableOnce(Exception cause) {
		if (unreachableLogged) {
			return;
		}
		unreachableLogged = true;
		LOGGER.warn("Discord Rich Presence: can't reach Discord's IPC socket — is Discord running and its socket visible"
				+ " to this process? (If the game runs inside a flatpak launcher, the socket is bound into the sandbox"
				+ " when the launcher starts — after restarting Discord, the launcher must be restarted too.)"
				+ " Will keep retrying quietly.", cause);
	}

	/** Must only be called from the IO executor thread. */
	private void closeCore() {
		Core existing = core;
		if (existing != null) {
			try {
				existing.close();
			} catch (Exception ignored) {
				// Best-effort close; nothing sensible to do if it's already gone.
			}
			core = null;
		}
	}
}
