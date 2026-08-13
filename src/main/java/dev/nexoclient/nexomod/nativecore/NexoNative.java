package dev.nexoclient.nexomod.nativecore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The whole native surface, in one class.
 *
 * <p>The implementation lives in {@code Mod/rust-core} and the normative
 * description of every method — semantics, threading, ownership, failure
 * behaviour — is {@code Mod/rust-core/FFI_CONTRACT.md}. Read that before
 * changing a signature here: the Rust symbol names are derived from this class's
 * package and name, so a rename on either side breaks the link at runtime with
 * an {@link UnsatisfiedLinkError} and no compile-time hint.
 *
 * <h2>Two rules for callers</h2>
 *
 * <p><b>Check {@link #isAvailable()} first.</b> Calling a native method with no
 * library loaded throws {@link UnsatisfiedLinkError}, which is an
 * {@link Error} and will not be caught by anybody's {@code catch (Exception)}.
 * The library is genuinely absent for every Windows and macOS player until
 * release builds cross-compile, so this is the common path, not the edge case.
 *
 * <p><b>Nothing here throws on failure.</b> Native code returns a sentinel — 0
 * for a handle or job id, {@code false}, {@code null}, {@code -1} for
 * {@link #filterTest} — and leaves a message for {@link #nativeLastError()},
 * which describes the most recent failing call <em>on the calling thread</em>.
 * Exceptions were rejected deliberately: every native feature is optional, and
 * an optional feature that throws forces a {@code try}/{@code catch} at every
 * call site to express "then don't use it".
 */
public final class NexoNative {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/native");

	/**
	 * Must equal {@code ABI_VERSION} in {@code rust-core/src/lib.rs}. Checked at
	 * bootstrap so a mismatched pair (a stale library behind
	 * {@code -Dnexomod.nativecore.library}, a packaging mistake) degrades to
	 * "native features off" instead of to signature-mismatch crashes that look
	 * like JVM bugs.
	 */
	private static final int ABI_VERSION = 2;

	/**
	 * {@link #features()} bit: the chunk-history surface is present, i.e. this
	 * is a full build of the library.
	 *
	 * <p>The declarations for that surface are
	 * {@code dev.nexoclient.nexomod.tactical.nativecore.NexoNativeChunks}, which is
	 * only compiled into the full jar — so in the light jar nothing can name
	 * them and this bit is never set. It exists for the one case the source
	 * split cannot cover: {@code -Dnexomod.nativecore.library=…} pointing a full
	 * jar at a light build of the library.
	 */
	public static final int FEATURE_CHUNK_HISTORY = 1 << 0;

	/** {@link #jobStatus} — no such job: never submitted, or already collected. */
	public static final int JOB_UNKNOWN = 0;
	/** {@link #jobStatus} — queued or running. */
	public static final int JOB_PENDING = 1;
	/** {@link #jobStatus} — finished; {@link #jobTake} returns the payload. */
	public static final int JOB_READY = 2;
	/** {@link #jobStatus} — finished badly; {@link #jobTake} returns null and sets the last error. */
	public static final int JOB_FAILED = 3;
	/** {@link #jobStatus} — cancelled via {@link #jobCancel}. */
	public static final int JOB_CANCELLED = 4;

	/** {@link #filterTest} — show the message normally. */
	public static final int FILTER_ALLOW = 0;
	/** {@link #filterTest} — suppress the message. */
	public static final int FILTER_HIDE = 1;
	/** {@link #filterTest} — show the message with emphasis. */
	public static final int FILTER_HIGHLIGHT = 2;

	/** Sentinel returned by every handle- and job-producing method on failure. */
	public static final long INVALID_HANDLE = 0L;

	private static volatile boolean available;
	private static volatile int features;
	private static boolean bootstrapped;

	private NexoNative() {
	}

	/**
	 * Loads the native library if one exists for this platform. Idempotent, and
	 * safe to call before anything else in the mod has initialised.
	 *
	 * <p>Failure produces exactly one WARN line and leaves
	 * {@link #isAvailable()} false. It catches {@link Throwable} rather than
	 * {@link Exception} on purpose: the interesting failure here is
	 * {@link UnsatisfiedLinkError}, and letting an {@code Error} escape mod
	 * init would take the whole game down over an optional feature.
	 */
	public static synchronized void bootstrap() {
		if (bootstrapped) {
			return;
		}
		bootstrapped = true;

		try {
			NativeLoader.load();
			int abi = nativeAbiVersion();
			if (abi != ABI_VERSION) {
				LOGGER.warn("[nexomod] Native core disabled: library reports ABI {} but this build expects {}."
						+ " Rebuild rust-core, or clear -Dnexomod.nativecore.library.", abi, ABI_VERSION);
				return;
			}
			// Read before `available` is set, so a library too old to export it
			// fails here — inside this try — instead of at the first feature
			// check. The ABI bump to 2 already rejects those, but the ordering
			// costs nothing and this is the one place a mismatch is survivable.
			features = nativeFeatures();
			available = true;
			LOGGER.info("[nexomod] Native core loaded: {}", nativeVersion());
		} catch (Throwable t) {
			// One line, at WARN. The features that depend on this simply don't
			// appear; a stack trace at ERROR would read as "your game is
			// broken", which it isn't.
			LOGGER.warn("[nexomod] Native core unavailable ({}); features backed by it are disabled.", t.toString());
		}
	}

	/** Whether the native library loaded. False means every method below is off limits. */
	public static boolean isAvailable() {
		return available;
	}

	/**
	 * The feature bits the loaded library reports, or 0 when none loaded.
	 *
	 * <p>The library is built in two variants, one per mod jar: the light build
	 * exports only the surface declared in this class, the full build adds the
	 * feature-gated ones. Prefer {@link #hasFeature(int)}.
	 */
	public static int features() {
		return available ? features : 0;
	}

	/**
	 * Whether every bit in {@code mask} is present. Callers of a feature-gated
	 * surface must check this <em>in addition to</em> {@link #isAvailable()};
	 * calling a method the loaded library does not export throws
	 * {@link UnsatisfiedLinkError}, which is an {@link Error} and slips through
	 * {@code catch (Exception)}.
	 */
	public static boolean hasFeature(int mask) {
		return (features() & mask) == mask;
	}

	/**
	 * Releases native resources. Idempotent, and a no-op when the library never
	 * loaded.
	 *
	 * <p>Handles obtained beforehand are dead afterwards — using one is a clean
	 * "handle is not live" error, not a crash.
	 */
	public static synchronized void shutdown() {
		if (!available) {
			return;
		}
		available = false;
		try {
			nativeShutdown();
		} catch (Throwable t) {
			LOGGER.warn("[nexomod] Native core shutdown failed: {}", t.toString());
		}
	}

	/**
	 * The last error on <em>this</em> thread, or a placeholder. Convenience for
	 * log lines, since {@link #nativeLastError()} is null when nothing failed.
	 */
	public static String lastErrorOrUnknown() {
		if (!available) {
			return "native core unavailable";
		}
		String error = nativeLastError();
		return error == null ? "unknown error" : error;
	}

	// ---------------------------------------------------------------------
	// General
	// ---------------------------------------------------------------------

	/** Crate version plus ABI, for logs and crash reports. */
	public static native String nativeVersion();

	/** @see #ABI_VERSION */
	public static native int nativeAbiVersion();

	/**
	 * The library's feature bitmask. Read once at bootstrap; use
	 * {@link #hasFeature(int)} rather than calling this again.
	 */
	public static native int nativeFeatures();

	/**
	 * The most recent failure on the calling thread, or null if the last call
	 * succeeded. Cleared at the start of every other native call, so it always
	 * describes the call that just returned a sentinel — and never cleared by
	 * reading it, so it can be logged more than once.
	 */
	public static native String nativeLastError();

	/** Cancels all jobs and drops all handles. Prefer {@link #shutdown()}. */
	public static native void nativeShutdown();

	/**
	 * Diagnostics only: a job that succeeds after {@code delayMillis} with an
	 * empty payload. Exists so a poll-and-collect loop can be exercised against
	 * a job that <em>succeeds</em>, which no real producer does yet.
	 */
	public static native long nativeSelfTestJob(int delayMillis);

	// ---------------------------------------------------------------------
	// Chat database
	// ---------------------------------------------------------------------

	/** @return a handle, or {@link #INVALID_HANDLE} on failure */
	public static native long chatDbOpen(String path);

	public static native void chatDbClose(long h);

	public static native boolean chatDbInsert(long h, long tsMillis, String server, String sender, String message);

	/**
	 * @return a job id whose payload decodes as
	 *         {@link JobPayloadReader#KIND_CHAT_SEARCH}, or
	 *         {@link #INVALID_HANDLE} on failure
	 */
	public static native long chatDbSearchAsync(long h, String query, int limit);

	// ---------------------------------------------------------------------
	// Job pool
	// ---------------------------------------------------------------------

	/** One of the {@code JOB_*} constants. */
	public static native int jobStatus(long jobId);

	/**
	 * <b>False for a failed job too.</b> A loop that only ever asks this
	 * question spins forever on a failure; use {@link #jobStatus} if that
	 * matters, which it does for anything with a spinner in front of it.
	 */
	public static native boolean jobIsReady(long jobId);

	/**
	 * @return the payload, or null if the job is still running <em>or</em>
	 *         failed — {@link #nativeLastError()} is non-null only in the
	 *         failure case. Consumes the job either way.
	 */
	public static native byte[] jobTake(long jobId);

	/** Advisory. The job stops at its next cancellation check, not instantly. */
	public static native void jobCancel(long jobId);

	// ---------------------------------------------------------------------
	// Log scrubber
	// ---------------------------------------------------------------------

	/** @return a handle, or {@link #INVALID_HANDLE} on failure */
	public static native long scrubberCreate();

	/**
	 * @return the scrubbed line, or null on failure — and null means <b>do not
	 *         publish this line</b>, never "publish it unchanged". Failing open
	 *         here would mean pasting an unscrubbed log into a public bug report
	 *         while believing it was clean.
	 */
	public static native String scrub(long h, String line);

	public static native void scrubberDestroy(long h);

	// ---------------------------------------------------------------------
	// Chat filter
	// ---------------------------------------------------------------------

	/** @return a handle, or {@link #INVALID_HANDLE} on failure */
	public static native long filterCreate();

	/** @param action one of {@link #FILTER_ALLOW}, {@link #FILTER_HIDE}, {@link #FILTER_HIGHLIGHT} */
	public static native boolean filterAddPattern(long h, String regex, int action);

	/**
	 * @return one of the {@code FILTER_*} constants, or -1 on failure. Treat
	 *         anything negative as {@link #FILTER_ALLOW}: this path is
	 *         fail-open, because chat silently vanishing is a bug nobody
	 *         reports and chat wrongly appearing is one everybody does.
	 */
	public static native int filterTest(long h, String message);

	public static native void filterDestroy(long h);

	// ---------------------------------------------------------------------
	// Feature-gated surface
	// ---------------------------------------------------------------------
	//
	// Chunk history used to be declared here. It moved to
	// dev.nexoclient.nexomod.tactical.nativecore.NexoNativeChunks in src/full,
	// because this class is compiled into the light jar too and the light build
	// of the library does not export those symbols. Declaring them here would
	// have made "don't call these from light code" a rule to remember rather
	// than something the compiler enforces, and the cost of forgetting is an
	// UnsatisfiedLinkError at runtime.
	//
	// Anything added under a feature bit belongs in its own class on the same
	// side of the split as the code that may call it.
}
