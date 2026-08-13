package dev.nexoclient.nexomod.chat;

import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.nativecore.NexoNative;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Owns the native chat database handle and writes every displayed message into
 * it.
 *
 * <h2>Everything here is optional</h2>
 *
 * <p>The store lives in {@code rust-core} and is reached over JNI, and the
 * library is genuinely absent on most platforms today (only Linux x86-64 is
 * built on the dev machine). So {@link #isAvailable()} is false far more often
 * than not, and the whole feature — capture, search screen, the settings row —
 * must be invisible rather than broken in that case. Nothing in this class
 * throws, and every native call is guarded by
 * {@link NexoNative#isAvailable()}: a missing symbol raises
 * {@link UnsatisfiedLinkError}, which is an {@link Error} and would sail
 * straight through a {@code catch (Exception)}.
 *
 * <h2>Lazy open</h2>
 *
 * <p>The handle is opened on the first insert rather than at client init, so a
 * player who never turns the feature on never gets a database file. It is closed
 * from {@code CLIENT_STOPPING}; a handle that outlives the process is not a leak
 * anyone can observe, but {@code chatDbClose} is also what flushes, so it is
 * worth doing properly.
 */
public final class NexoChatHistory {
	/** {@code sender} marker for a message the server sent with no player attached. */
	public static final String SENDER_SYSTEM_SERVER = "[server]";
	/** {@code sender} marker for a message the client produced locally (command feedback, mod messages). */
	public static final String SENDER_SYSTEM_CLIENT = "[client]";

	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-chat.db");

	private static long handle = NexoNative.INVALID_HANDLE;
	private static boolean openFailed;

	private NexoChatHistory() {
	}

	/**
	 * Whether the history can be used at all. False on any platform without the
	 * native library — callers should hide their UI entirely rather than show a
	 * search box that can never return anything.
	 */
	public static boolean isAvailable() {
		return NexoNative.isAvailable();
	}

	/** Whether messages are actually being recorded right now. */
	public static boolean isRecording() {
		return isAvailable() && NexoConfig.get().chatHistoryEnabled();
	}

	/**
	 * Records one displayed message. Called from the chat mixin, i.e. once per
	 * chat packet, so it stays a single native call with no allocation beyond
	 * the strings it is handed.
	 *
	 * <p>A failed insert is logged at debug and otherwise ignored: chat that
	 * fails to be archived is not a reason to interrupt the player, and the same
	 * failure would otherwise repeat on every message.
	 */
	public static void record(long timestampMillis, String sender, Component message) {
		if (!isRecording()) {
			return;
		}
		long db = handle();
		if (db == NexoNative.INVALID_HANDLE) {
			return;
		}
		String text = message.getString();
		if (text.isEmpty()) {
			return;
		}
		if (!NexoNative.chatDbInsert(db, timestampMillis, currentServer(), sender, text)) {
			NexoMod.LOGGER.debug("[nexomod] chat history insert failed: {}", NexoNative.lastErrorOrUnknown());
		}
	}

	/**
	 * The live handle, opening it on first use.
	 *
	 * <p>{@code openFailed} makes a failure sticky. Without it a broken path or
	 * a full disk would mean one {@code chatDbOpen} attempt — and one WARN — per
	 * chat message.
	 */
	static synchronized long handle() {
		if (handle != NexoNative.INVALID_HANDLE || openFailed || !isAvailable()) {
			return handle;
		}
		handle = NexoNative.chatDbOpen(PATH.toString());
		if (handle == NexoNative.INVALID_HANDLE) {
			openFailed = true;
			NexoMod.LOGGER.warn("[nexomod] Chat history disabled: could not open {} ({})", PATH,
					NexoNative.lastErrorOrUnknown());
		}
		return handle;
	}

	/** Closed from {@code ClientLifecycleEvents.CLIENT_STOPPING}. Idempotent. */
	public static synchronized void close() {
		if (handle == NexoNative.INVALID_HANDLE) {
			return;
		}
		NexoNative.chatDbClose(handle);
		handle = NexoNative.INVALID_HANDLE;
	}

	/**
	 * Which server a message was seen on. {@code ServerData.ip} rather than its
	 * display name: the name is whatever the player typed into their server list
	 * and differs between machines, while the address is the thing two entries
	 * for the same server agree on.
	 */
	static String currentServer() {
		ServerData server = Minecraft.getInstance().getCurrentServer();
		return server == null || server.ip == null || server.ip.isEmpty()
				? NexoChatMessage.SINGLEPLAYER
				: server.ip;
	}
}
