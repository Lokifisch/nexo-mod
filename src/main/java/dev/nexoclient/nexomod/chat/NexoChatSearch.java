package dev.nexoclient.nexomod.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.nativecore.JobPayloadReader;
import dev.nexoclient.nexomod.nativecore.NexoNative;

/**
 * Runs chat-history searches on the native job pool and collects them from the
 * client tick.
 *
 * <h2>Why the state lives here and not in the screen</h2>
 *
 * <p>A job outlives the screen that started it — the player can close the search
 * screen a frame after pressing Search — and a job nobody collects sits in the
 * pool until it is dropped. Keeping the pending job id in one static place means
 * there is exactly one owner, {@link #cancel()} is something the screen's close
 * handler can call unconditionally, and re-opening the screen finds a finished
 * search already waiting.
 *
 * <h2>Why {@code jobStatus} and not {@code jobIsReady}</h2>
 *
 * <p>{@link NexoNative#jobIsReady} is {@code false} for a <em>failed</em> job as
 * well as a running one, so a spinner driven by it never stops. The contract's
 * own example polls {@link NexoNative#jobStatus} and treats everything that is
 * not {@code JOB_PENDING} as terminal, which is what this does.
 *
 * <h2>Where the filters are applied</h2>
 *
 * <p>The native surface takes a query string and a limit — no server, sender or
 * time predicate ({@code rust-core/FFI_CONTRACT.md} §2, <i>Chat database</i>).
 * Those three are therefore applied here, over the returned records. The cost is
 * that a narrow filter over a huge history can come back with fewer rows than
 * the limit; the alternative would be inventing FFI the Rust side does not have.
 */
public final class NexoChatSearch {
	/** Hard ceiling on rows asked for; the native side clamps at 10000 anyway. */
	public static final int MAX_RESULTS = 500;

	public enum Status {
		/** Nothing has been searched yet this session. */
		IDLE,
		/** A job is queued or running. */
		SEARCHING,
		/** Finished; {@link #results()} holds what matched (possibly nothing). */
		DONE,
		/** The job failed or was rejected; {@link #error()} says why. */
		FAILED
	}

	/** Result-narrowing applied Java-side. Blank strings and 0 mean "no constraint". */
	public record Filter(String server, String sender, long sinceMillis, long untilMillis) {
		public static final Filter NONE = new Filter("", "", 0L, 0L);

		boolean accepts(NexoChatMessage message) {
			if (!server.isBlank() && !message.server().toLowerCase(Locale.ROOT).contains(server.toLowerCase(Locale.ROOT))) {
				return false;
			}
			if (!sender.isBlank() && !message.sender().toLowerCase(Locale.ROOT).contains(sender.toLowerCase(Locale.ROOT))) {
				return false;
			}
			if (sinceMillis > 0L && message.timestamp() < sinceMillis) {
				return false;
			}
			return untilMillis <= 0L || message.timestamp() <= untilMillis;
		}
	}

	private static KeyMapping openKey;
	private static long jobId = NexoNative.INVALID_HANDLE;
	private static Filter pendingFilter = Filter.NONE;
	private static Status status = Status.IDLE;
	private static String error = "";
	private static List<NexoChatMessage> results = List.of();
	private static Runnable listener;

	private NexoChatSearch() {
	}

	/** Registered once from {@code NexoMod.onInitializeClient()}. */
	public static void register() {
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.chatSearch",
				InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(NexoChatSearch::tick);
	}

	/**
	 * Called when a search settles, so the open screen can rebuild its rows.
	 * One listener, not a list: only the search screen ever cares, and clearing
	 * it on close is then a single assignment rather than a removal that has to
	 * match an addition.
	 */
	public static void setListener(Runnable onSettled) {
		listener = onSettled;
	}

	public static Status status() {
		return status;
	}

	public static String error() {
		return error;
	}

	public static List<NexoChatMessage> results() {
		return results;
	}

	/**
	 * Starts a search, replacing any that is still running. {@code query} is
	 * passed to the native side; {@code filter} is applied to what comes back.
	 */
	public static void submit(String query, Filter filter, int limit) {
		cancel();
		results = List.of();
		error = "";
		pendingFilter = filter == null ? Filter.NONE : filter;

		if (!NexoChatHistory.isAvailable()) {
			status = Status.FAILED;
			error = "native core unavailable";
			notifyListener();
			return;
		}
		long db = NexoChatHistory.handle();
		if (db == NexoNative.INVALID_HANDLE) {
			status = Status.FAILED;
			error = NexoNative.lastErrorOrUnknown();
			notifyListener();
			return;
		}

		jobId = NexoNative.chatDbSearchAsync(db, query, Math.clamp(limit, 1, MAX_RESULTS));
		if (jobId == NexoNative.INVALID_HANDLE) {
			status = Status.FAILED;
			error = NexoNative.lastErrorOrUnknown();
			notifyListener();
			return;
		}
		status = Status.SEARCHING;
		notifyListener();
	}

	/**
	 * Drops any in-flight search. Safe to call when nothing is running, which is
	 * the normal shape of a screen close handler — {@code jobCancel} is silent
	 * for an unknown id by design.
	 */
	public static void cancel() {
		if (jobId == NexoNative.INVALID_HANDLE) {
			return;
		}
		NexoNative.jobCancel(jobId);
		// Takes the settled result too, so the id can't linger in the pool
		// waiting on the five-minute drop.
		NexoNative.jobTake(jobId);
		jobId = NexoNative.INVALID_HANDLE;
		if (status == Status.SEARCHING) {
			status = Status.IDLE;
		}
	}

	private static void tick(Minecraft client) {
		pollOpenKey(client);
		if (jobId == NexoNative.INVALID_HANDLE) {
			return;
		}
		int jobStatus = NexoNative.jobStatus(jobId);
		if (jobStatus == NexoNative.JOB_PENDING) {
			return;
		}
		if (jobStatus == NexoNative.JOB_READY) {
			byte[] payload = NexoNative.jobTake(jobId);
			jobId = NexoNative.INVALID_HANDLE;
			collect(payload);
		} else {
			// FAILED, CANCELLED, or UNKNOWN. jobTake consumes the job in every
			// terminal state, so calling it here is what keeps the pool clean.
			NexoNative.jobTake(jobId);
			jobId = NexoNative.INVALID_HANDLE;
			status = Status.FAILED;
			error = NexoNative.lastErrorOrUnknown();
		}
		notifyListener();
	}

	/**
	 * Opens the search screen on the keybind. Unbound by default, and silent
	 * when the native library is missing: a screen whose every search fails is
	 * worse than a key that appears to do nothing, and the Chat settings
	 * category already explains why in that case.
	 */
	private static void pollOpenKey(Minecraft client) {
		if (openKey == null) {
			return;
		}
		while (openKey.consumeClick()) {
			if (NexoChatHistory.isAvailable()) {
				client.setScreen(new dev.nexoclient.nexomod.screen.NexoChatSearchScreen(client.screen));
			}
		}
	}

	/**
	 * Decodes a {@code KIND_CHAT_SEARCH} payload. The field order —
	 * {@code i64 ts, str server, str sender, str message} — is the contract's,
	 * and reading it in any other order produces garbage rather than an error,
	 * so it is written out one call per field rather than looped.
	 */
	private static void collect(byte[] payload) {
		if (payload == null) {
			status = Status.FAILED;
			error = NexoNative.lastErrorOrUnknown();
			return;
		}
		try {
			JobPayloadReader reader = new JobPayloadReader(payload);
			if (reader.kind() == JobPayloadReader.KIND_EMPTY) {
				results = List.of();
				status = Status.DONE;
				return;
			}
			if (reader.kind() != JobPayloadReader.KIND_CHAT_SEARCH) {
				status = Status.FAILED;
				error = "unexpected payload kind " + reader.kind();
				return;
			}
			List<NexoChatMessage> collected = new ArrayList<>(reader.recordCount());
			for (int i = 0; i < reader.recordCount(); i++) {
				NexoChatMessage message = new NexoChatMessage(
						reader.readLong(), reader.readString(), reader.readString(), reader.readString());
				if (pendingFilter.accepts(message)) {
					collected.add(message);
				}
			}
			results = List.copyOf(collected);
			status = Status.DONE;
		} catch (RuntimeException e) {
			// A malformed payload means the jar and the library disagree about
			// the layout. Loud in the log, quiet on screen.
			NexoMod.LOGGER.warn("[nexomod] Chat search payload could not be decoded", e);
			status = Status.FAILED;
			error = e.getMessage() == null ? e.toString() : e.getMessage();
		}
	}

	private static void notifyListener() {
		Runnable current = listener;
		if (current != null) {
			current.run();
		}
	}
}
