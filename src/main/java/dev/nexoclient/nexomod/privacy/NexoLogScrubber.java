package dev.nexoclient.nexomod.privacy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.impl.ThrowableProxy;
import org.apache.logging.log4j.message.SimpleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.nexoclient.nexomod.nativecore.NexoNative;

/**
 * Puts {@code rust-core}'s log scrubber in front of every Log4j2 appender, so
 * access tokens, home-directory paths and network addresses are masked
 * <em>before</em> the bytes reach {@code logs/latest.log} — not after somebody
 * has already uploaded it.
 *
 * <h2>Which Log4j2 mechanism, and why</h2>
 *
 * <p>Minecraft's logging is Log4j2 (2.25.2 on 26.1.2) configured from an XML
 * file the mod cannot edit: in a Loom dev run it is
 * {@code .gradle/loom-cache/log4j.xml}, in a real install it is Fabric Loader's
 * own copy. Both declare a {@code SysOut} console appender and two
 * {@code RollingRandomAccessFile}s ({@code latest.log}, {@code debug.log}) and
 * attach them to the root logger by name.
 *
 * <p>The textbook answer — a {@code RewritePolicy} inside a
 * {@code RewriteAppender} — is a <em>configuration</em> element: it only exists
 * if the XML names it, and adding a plugin to someone else's config file is not
 * something a mod can do. Log4j2's {@code Filter} interface is no good either;
 * a filter decides whether an event is published, it cannot alter it.
 *
 * <p>What is available at runtime, through public API, is the live
 * {@link Configuration}: every {@link LoggerConfig} exposes
 * {@link LoggerConfig#getAppenderRefs()},
 * {@link LoggerConfig#removeAppender(String)} and
 * {@link LoggerConfig#addAppender(Appender, org.apache.logging.log4j.Level, Filter)}.
 * So each attached appender is replaced by a {@link ScrubbingAppender} that
 * scrubs and forwards to the original. The original object stays registered in
 * {@code Configuration.getAppenders()} untouched, so Log4j2 still owns its
 * lifecycle and still flushes and closes the file at shutdown, and anything
 * that looks an appender up by name still finds the real one.
 *
 * <p>Log4j2 2.25 marks most of {@code log4j-core}'s 2.x surface deprecated
 * ahead of 3.0 — {@code AbstractAppender}, {@code Log4jLogEvent.Builder} and
 * {@code ThrowableProxy} among them. There is no replacement <em>in 2.25</em>,
 * which is the version Minecraft 26.1.2 ships, so the deprecation warning at
 * compile time is expected and not actionable here.
 *
 * <h2>Every appender, not just the file ones</h2>
 *
 * <p>Wrapping only {@code latest.log} and {@code debug.log} would have been the
 * narrow reading of "before it lands on disk", but console output lands on disk
 * too: Prism, MultiMC and the vanilla launcher all capture the game's stdout
 * into their own log panes and log files. Classifying appenders by whether they
 * happen to own a {@code FileManager} is also the sort of check that silently
 * stops matching when a version bumps. Wrapping everything is simpler, strictly
 * safer, and — thanks to the per-thread memo in {@link #scrub(String)} — costs
 * one native call per <em>line</em> rather than one per appender.
 *
 * <h2>Failure semantics: fail-closed per line, fail-open at install</h2>
 *
 * <p>{@code rust-core}'s contract is that {@link NexoNative#scrub} returning
 * null means <b>do not publish this line</b>, never "publish it unchanged". In
 * a log appender that reading needs care, because a scrubber that drops lines
 * destroys the log it is protecting — and an empty {@code latest.log} is a real
 * harm this feature would have caused, where an unscrubbed one is merely the
 * status quo.
 *
 * <p>The two failure classes are therefore split:
 *
 * <ul>
 * <li><b>Systemic failure is caught at install time and fails open.</b> If the
 *     native library is missing (the normal case on Windows and macOS today), if
 *     the handle cannot be created, or if {@link #selfTest} cannot mask a
 *     synthetic token, no appender is wrapped at all. Logging then behaves
 *     exactly as it does without this mod: complete and unscrubbed. Crucially
 *     the self-test also covers the one failure that would otherwise be
 *     catastrophic — a regex table that failed to compile makes {@code scrub}
 *     return null for <em>every</em> line, and catching that here is what stops
 *     it turning the log file into nothing.</li>
 * <li><b>Per-line failure stays closed.</b> After a passing self-test the only
 *     way a single line can fail is the 256 KiB size limit in {@code scrub.rs}
 *     (a mod dumping a serialised blob through the logger). That line's content
 *     never reaches disk; a fixed placeholder carrying only its length takes its
 *     place, so a reader can see that something was withheld and ask for it
 *     directly instead of debugging against a log with a silent hole in it.
 *     Passing the raw line through instead would make the promise this class
 *     exists to make — "if it is in the file, it has been scrubbed" — a lie in
 *     exactly the case where it matters.</li>
 * </ul>
 *
 * <h2>The scrubber never logs</h2>
 *
 * <p>Nothing on the path from {@link ScrubbingAppender#append} through
 * {@link #scrub(String)} may call a logger: that event would be routed straight
 * back into the appender it came from. Log4j2's own {@code AppenderControl}
 * guards against an appender re-entering <em>itself</em>, but there are three
 * wrapped appenders here and a line emitted from inside one would still reach
 * the other two. So failures are counted in {@link #withheldLines()} rather
 * than reported, and {@link Slot#busy} is a second, independent stop: if a
 * scrub ever does re-enter on the same thread, the inner call returns the line
 * unchanged instead of recursing. That is the one place this class fails open,
 * and deliberately — a single unscrubbed line beats a StackOverflowError inside
 * the logging subsystem, which takes the process with it.
 */
public final class NexoLogScrubber {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/privacy");

	/** Set {@code -Dnexomod.logscrub=false} to keep the raw log while debugging. */
	private static final String DISABLE_PROPERTY = "nexomod.logscrub";

	/**
	 * A synthetic line in the shape of the three things that matter most: a JWT
	 * (what Minecraft Services issues), a home path (which carries the OS
	 * account name), and a routable address. Deliberately built from reserved
	 * documentation values — {@code 203.0.113.0/24} is RFC 5737 — so this string
	 * is not itself a secret and can sit in the jar.
	 */
	private static final String CANARY_TOKEN =
			"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJuZXhvLWNhbmFyeSJ9.QUJDREVGR0hJSktMTU5PUFFSUw";
	private static final String CANARY_PATH = "/home/nexocanary";
	private static final String CANARY_IP = "203.0.113.7";
	private static final String CANARY_LINE = "nexomod scrubber self-test: accessToken=" + CANARY_TOKEN
			+ " home=" + CANARY_PATH + "/.minecraft/options.txt peer=" + CANARY_IP;

	private static final String WITHHELD_PREFIX =
			"[nexomod] log line withheld: the privacy scrubber could not process it (";
	private static final String WITHHELD_SUFFIX = " chars)";

	private static final AtomicLong WITHHELD = new AtomicLong();

	/**
	 * Per-thread scratch: a one-entry memo plus the re-entrancy flag.
	 *
	 * <p>The memo exists because one log event visits every wrapped appender in
	 * turn, on the same thread, with the same text — without it a three-appender
	 * configuration would pay three JNI round trips and three regex passes per
	 * line. One {@code String.equals} against the previous input is orders of
	 * magnitude cheaper than the call it replaces, and a single slot is enough
	 * because the appenders run back to back.
	 */
	private static final class Slot {
		private String in;
		private String out;
		private boolean busy;
	}

	private static final ThreadLocal<Slot> SLOT = ThreadLocal.withInitial(Slot::new);

	/**
	 * Read on every log line from every thread, so it is {@code volatile} rather
	 * than guarded: {@link #install()} and {@link #uninstall()} are the only
	 * writers and both are synchronized.
	 */
	private static volatile long handle = NexoNative.INVALID_HANDLE;

	/** Undo steps recorded by {@link #install()}, applied in reverse. */
	private static final Deque<Runnable> UNDO = new ArrayDeque<>();

	private NexoLogScrubber() {
	}

	/**
	 * Wraps the live logging configuration. Idempotent, never throws, and a
	 * no-op — leaving logging exactly as it was — whenever anything it needs is
	 * unavailable.
	 *
	 * <p>Called from {@code NexoMod.onInitializeClient()}, which is the earliest
	 * point this mod's code runs without adding a {@code preLaunch} entrypoint.
	 * Lines written before that (Fabric Loader's own startup, Mixin) are not
	 * scrubbed; none of them carries a credential today, but see the note in
	 * {@code fabric.mod.json} if that ever changes.
	 */
	public static synchronized void install() {
		if (!UNDO.isEmpty()) {
			return;
		}
		if (!Boolean.parseBoolean(System.getProperty(DISABLE_PROPERTY, "true"))) {
			LOGGER.info("[nexomod] Log scrubbing disabled by -D{}=false.", DISABLE_PROPERTY);
			return;
		}
		if (!NexoNative.isAvailable()) {
			// The common case on Windows and macOS. Not a warning: every
			// native-backed feature is already absent there and says so once.
			LOGGER.debug("[nexomod] Log scrubbing off: native core unavailable.");
			return;
		}

		long h = NexoNative.scrubberCreate();
		if (h == NexoNative.INVALID_HANDLE) {
			LOGGER.warn("[nexomod] Log scrubbing off: {}", NexoNative.lastErrorOrUnknown());
			return;
		}
		String failure = selfTest(h);
		if (failure != null) {
			NexoNative.scrubberDestroy(h);
			LOGGER.warn("[nexomod] Log scrubbing off: the scrubber failed its self-test ({}). Logs are written"
					+ " unscrubbed, which is what they would be without this mod — check one before sharing it.",
					failure);
			return;
		}

		handle = h;
		int wrapped = 0;
		try {
			wrapped = wrapConfiguration();
		} catch (RuntimeException | LinkageError e) {
			// A Log4j2 whose shape this class does not recognise. Roll all the
			// way back rather than leave half the appenders wrapped: a partial
			// install is a log file where some lines are scrubbed and some are
			// not, which is worse than either end state.
			LOGGER.warn("[nexomod] Log scrubbing off: could not adapt the Log4j2 configuration ({}).", e.toString());
			uninstall();
			return;
		}
		if (wrapped == 0) {
			LOGGER.warn("[nexomod] Log scrubbing off: no Log4j2 appenders were found to wrap.");
			uninstall();
			return;
		}
		LOGGER.info("[nexomod] Log scrubbing on: {} appender(s) masked before write.", wrapped);
	}

	/**
	 * Restores the original appenders and releases the native handle.
	 *
	 * <p><b>Must run before {@code NexoNative.shutdown()}</b>, which drops every
	 * handle: a wrapper left in place over a dead handle would answer every
	 * remaining shutdown line with a withheld placeholder. Unwrapping first
	 * means the game's last few lines are written unscrubbed rather than lost.
	 */
	public static synchronized void uninstall() {
		while (!UNDO.isEmpty()) {
			try {
				UNDO.pop().run();
			} catch (RuntimeException | LinkageError ignored) {
				// Nothing useful to do, and this runs on the way out. The
				// remaining steps still get their turn.
			}
		}
		long h = handle;
		handle = NexoNative.INVALID_HANDLE;
		if (h != NexoNative.INVALID_HANDLE && NexoNative.isAvailable()) {
			NexoNative.scrubberDestroy(h);
		}
	}

	/** How many lines were replaced by a placeholder because scrubbing failed. */
	public static long withheldLines() {
		return WITHHELD.get();
	}

	/** Whether appenders are currently wrapped. */
	public static synchronized boolean isInstalled() {
		return !UNDO.isEmpty();
	}

	// ---------------------------------------------------------------------
	// Wiring
	// ---------------------------------------------------------------------

	private static int wrapConfiguration() {
		org.apache.logging.log4j.spi.LoggerContext spi = LogManager.getContext(false);
		if (!(spi instanceof LoggerContext ctx)) {
			// Something other than log4j-core is behind the facade. Nothing to
			// wrap, and nothing broken either.
			return 0;
		}
		Configuration config = ctx.getConfiguration();

		List<LoggerConfig> loggers = new ArrayList<>(config.getLoggers().values());
		LoggerConfig root = config.getRootLogger();
		if (loggers.stream().noneMatch(lc -> lc == root)) {
			loggers.add(root);
		}

		int wrapped = 0;
		for (LoggerConfig loggerConfig : loggers) {
			for (AppenderRef ref : new ArrayList<>(loggerConfig.getAppenderRefs())) {
				Appender original = loggerConfig.getAppenders().get(ref.getRef());
				if (original == null || original instanceof ScrubbingAppender) {
					continue;
				}
				wrap(loggerConfig, ref, original);
				wrapped++;
			}
		}
		// The context caches a resolved appender list per logger; without this
		// the loggers created before now keep pointing at the old set.
		ctx.updateLoggers();
		return wrapped;
	}

	private static void wrap(LoggerConfig loggerConfig, AppenderRef ref, Appender original) {
		ScrubbingAppender wrapper = new ScrubbingAppender(original);
		wrapper.start();

		// removeAppender stops the AppenderRef-level filter as part of its
		// cleanup (LoggerConfig.cleanupFilter). Minecraft's config puts filters
		// on appenders rather than on refs, so this is null in practice — but
		// restarting it after the re-add is what keeps that from being an
		// assumption this class silently depends on.
		Filter refFilter = ref.getFilter();
		loggerConfig.removeAppender(ref.getRef());
		loggerConfig.addAppender(wrapper, ref.getLevel(), refFilter);
		if (refFilter != null) {
			refFilter.start();
		}

		UNDO.push(() -> {
			loggerConfig.removeAppender(wrapper.getName());
			loggerConfig.addAppender(original, ref.getLevel(), refFilter);
			if (refFilter != null) {
				refFilter.start();
			}
			wrapper.stop();
		});
	}

	/**
	 * @return null when the scrubber masked everything in {@link #CANARY_LINE},
	 *         otherwise a short description of what survived
	 */
	private static String selfTest(long h) {
		String out;
		try {
			out = NexoNative.scrub(h, CANARY_LINE);
		} catch (Throwable t) {
			return t.toString();
		}
		if (out == null) {
			return "scrub() returned null for the canary line";
		}
		if (out.contains(CANARY_TOKEN)) {
			return "the token survived";
		}
		if (out.contains(CANARY_PATH)) {
			return "the home path survived";
		}
		if (out.contains(CANARY_IP)) {
			return "the IP address survived";
		}
		return null;
	}

	// ---------------------------------------------------------------------
	// The hot path
	// ---------------------------------------------------------------------

	/**
	 * The text a wrapped appender should hand its delegate in place of
	 * {@code event}, or {@code event} itself when nothing needed masking.
	 *
	 * <p>Only the message and the stack trace are rewritten. Timestamp, level,
	 * thread and logger name are structural, carry nothing sensitive, and are
	 * what makes a scrubbed log still readable as a log.
	 */
	static LogEvent scrubEvent(LogEvent event) {
		String formatted = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
		if (formatted == null) {
			formatted = "";
		}

		Throwable thrown = event.getThrown();
		String input = thrown == null ? formatted : formatted + System.lineSeparator() + stackTrace(event, thrown);

		String cleaned = scrub(input);
		if (cleaned == null) {
			WITHHELD.incrementAndGet();
			return rebuild(event, WITHHELD_PREFIX + input.length() + WITHHELD_SUFFIX);
		}
		if (thrown == null && cleaned.equals(formatted)) {
			// The overwhelmingly common case: nothing matched, so the original
			// event goes through untouched and nothing is allocated.
			return event;
		}
		return rebuild(event, cleaned);
	}

	/**
	 * One scrub, memoised per thread.
	 *
	 * @return the masked line, or null when it must not be published
	 */
	private static String scrub(String line) {
		long h = handle;
		if (h == NexoNative.INVALID_HANDLE) {
			// Between uninstall() and the wrappers actually coming off. Passing
			// the line through keeps the shutdown tail of the log intact; see
			// the ordering note on uninstall().
			return line;
		}
		Slot slot = SLOT.get();
		if (slot.busy) {
			// Re-entered from inside a scrub. Nothing on this path logs, so this
			// should be unreachable; returning the input is the deliberate
			// fail-open described in the class javadoc.
			return line;
		}
		if (line.equals(slot.in)) {
			return slot.out;
		}

		String out;
		slot.busy = true;
		try {
			out = NexoNative.scrub(h, line);
		} catch (Throwable t) {
			// UnsatisfiedLinkError if the library went away underneath us, which
			// catch (Exception) would not catch. Treated as a scrub failure, so
			// the line is withheld rather than published unscanned.
			out = null;
		} finally {
			slot.busy = false;
		}

		if (out != null) {
			slot.in = line;
			slot.out = out;
		}
		return out;
	}

	/**
	 * The event with its message replaced and its throwable dropped.
	 *
	 * <p>The throwable has to go: a {@code Throwable} cannot be rewritten, and
	 * {@code PatternLayout} appends the trace itself when the pattern has no
	 * explicit {@code %throwable} — which Minecraft's does not. So the rendered
	 * trace is folded into the scrubbed message and the throwable is cleared,
	 * which produces the same bytes minus whatever the scrubber removed. An
	 * exception message reading {@code java.io.FileNotFoundException:
	 * /home/someone/…} is exactly the kind of line this exists for, so leaving
	 * traces alone was not an option.
	 */
	private static LogEvent rebuild(LogEvent event, String message) {
		return new Log4jLogEvent.Builder(event)
				.setMessage(new SimpleMessage(message))
				.setThrown(null)
				.setThrownProxy(null)
				.build();
	}

	/**
	 * The trace as Log4j2 would have rendered it.
	 *
	 * <p>{@link ThrowableProxy} is preferred because it is what
	 * {@code PatternLayout}'s default {@code %xEx} produces — including the
	 * {@code ~[minecraft.jar:?]} suffixes that make a Minecraft stack trace
	 * worth reading — and because the event has usually built it already, so
	 * asking costs nothing that rendering would not have cost anyway.
	 */
	private static String stackTrace(LogEvent event, Throwable thrown) {
		try {
			ThrowableProxy proxy = event.getThrownProxy();
			if (proxy != null) {
				return proxy.getExtendedStackTraceAsString();
			}
		} catch (RuntimeException | LinkageError ignored) {
			// Fall through to the plain rendering below.
		}
		StringWriter out = new StringWriter();
		try (PrintWriter writer = new PrintWriter(out)) {
			thrown.printStackTrace(writer);
		}
		return out.toString();
	}
}
