package dev.nexoclient.nexomod.miniplayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBus;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads whatever the desktop's MPRIS session-bus players report as
 * "now playing" — no Spotify/SoundCloud/YouTube Music-specific code at all.
 * Spotify's desktop app and any Chromium/Firefox tab (which is how SoundCloud
 * and YouTube Music are actually played) all publish the same
 * {@code org.mpris.MediaPlayer2.Player} interface on their own; reading the
 * one OS-level standard covers all three for free instead of juggling three
 * services' auth flows.
 *
 * <p>No Minecraft import in this file on purpose, same split as
 * {@code hud.NexoArmorBarLayout}: this is plain data-fetching, and
 * {@code hud.NexoMiniPlayerHud} is the only place that turns it into pixels.
 *
 * <p>Every wire value below is an assumption about another process's D-Bus
 * shape, not something the compiler checks — same posture as the JSON structs
 * in {@code Client/}'s launcher code. Values are read defensively
 * ({@link #unwrap}/{@link #stringOf}/etc. all fall back to "absent" instead of
 * throwing) so a player that answers slightly off-spec degrades to "nothing
 * shown" rather than killing the poller.
 */
public final class NexoMiniPlayer {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/miniplayer");

	private static final String MPRIS_PREFIX = "org.mpris.MediaPlayer2.";
	private static final String MPRIS_PATH = "/org/mpris/MediaPlayer2";
	private static final String ROOT_IFACE = "org.mpris.MediaPlayer2";
	private static final String PLAYER_IFACE = "org.mpris.MediaPlayer2.Player";
	private static final long POLL_SECONDS = 2;

	/** Immutable snapshot published to the render thread; {@code null} means "nothing to show". */
	public record NowPlaying(String source, String title, String artist, boolean playing,
			long positionMicros, long lengthMicros) {
	}

	private static ScheduledExecutorService worker;
	private static DBusConnection connection;
	private static DBus bus;
	private static volatile NowPlaying current;

	private NexoMiniPlayer() {
	}

	public static void register() {
		worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "nexo-miniplayer");
			thread.setDaemon(true);
			return thread;
		});
		worker.execute(NexoMiniPlayer::connect);
	}

	public static void shutdown() {
		if (worker != null) {
			worker.shutdownNow();
			worker = null;
		}
		if (connection != null) {
			connection.disconnect();
			connection = null;
		}
		current = null;
	}

	/** The current track, or {@code null} if nothing is playing/found/available. Safe to read from any thread. */
	public static NowPlaying current() {
		return current;
	}

	// ponytail: no reconnect-on-drop — a session bus that dies mid-game is rare
	// enough that "restart the game to get the mini player back" is an
	// acceptable ceiling. Upgrade to a reconnect attempt in poll() if that ever
	// turns out to be wrong in practice.
	private static void connect() {
		try {
			connection = DBusConnectionBuilder.forSessionBus().build();
			bus = connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus.class);
			worker.scheduleWithFixedDelay(NexoMiniPlayer::poll, 0, POLL_SECONDS, TimeUnit.SECONDS);
		} catch (DBusException | RuntimeException e) {
			// Expected and permanent on Windows/macOS (no session bus at all) and on
			// some minimal Linux setups — one line, not a warning that repeats.
			LOGGER.info("[nexomod] Mini player: no D-Bus session bus available, now-playing will stay empty ({})", e.toString());
		}
	}

	private static void poll() {
		try {
			NowPlaying playing = null;
			NowPlaying fallback = null;
			for (String name : bus.ListNames()) {
				if (!name.startsWith(MPRIS_PREFIX)) {
					continue;
				}
				NowPlaying np = readPlayer(name);
				if (np == null) {
					continue;
				}
				if (np.playing()) {
					playing = np;
					break;
				}
				if (fallback == null) {
					fallback = np;
				}
			}
			current = playing != null ? playing : fallback;
		} catch (RuntimeException e) {
			// A bus hiccup shows as "nothing playing" rather than stale/garbage data.
			current = null;
		}
	}

	private static NowPlaying readPlayer(String busName) {
		try {
			Properties props = connection.getRemoteObject(busName, MPRIS_PATH, Properties.class);
			String status = stringOf(props.Get(PLAYER_IFACE, "PlaybackStatus"));
			Object metadataRaw = props.Get(PLAYER_IFACE, "Metadata");
			if (!(metadataRaw instanceof Map<?, ?> metadata)) {
				return null;
			}
			String title = stringOf(metadata.get("xesam:title"));
			if (title == null || title.isEmpty()) {
				return null;
			}
			String artist = String.join(", ", stringListOf(metadata.get("xesam:artist")));
			long length = longOf(metadata.get("mpris:length"));
			long position = longOf(safeGet(props, PLAYER_IFACE, "Position"));
			String identity = stringOf(safeGet(props, ROOT_IFACE, "Identity"));
			String source = identity != null && !identity.isEmpty() ? identity : sourceNameOf(busName);
			return new NowPlaying(source, title, artist, "Playing".equals(status), position, length);
		} catch (DBusException | RuntimeException e) {
			return null;
		}
	}

	/** {@code Position} and {@code Identity} are optional per the MPRIS spec — absence is not an error. */
	private static Object safeGet(Properties props, String iface, String property) {
		try {
			return props.Get(iface, property);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String sourceNameOf(String busName) {
		String rest = busName.substring(MPRIS_PREFIX.length());
		int dot = rest.indexOf('.');
		String stem = dot >= 0 ? rest.substring(0, dot) : rest;
		return stem.isEmpty() ? rest : Character.toUpperCase(stem.charAt(0)) + stem.substring(1);
	}

	private static Object unwrap(Object value) {
		return value instanceof Variant<?> variant ? variant.getValue() : value;
	}

	private static String stringOf(Object value) {
		Object v = unwrap(value);
		return v instanceof String s ? s : null;
	}

	private static long longOf(Object value) {
		Object v = unwrap(value);
		return v instanceof Number n ? n.longValue() : 0L;
	}

	private static List<String> stringListOf(Object value) {
		Object v = unwrap(value);
		if (v instanceof List<?> list) {
			List<String> out = new ArrayList<>(list.size());
			for (Object o : list) {
				out.add(String.valueOf(unwrap(o)));
			}
			return out;
		}
		if (v instanceof String[] array) {
			return Arrays.asList(array);
		}
		return List.of();
	}
}
