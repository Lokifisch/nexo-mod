package dev.nexoclient.nexomod.privacy;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.filter.Filterable;

/**
 * One appender standing in front of another: it masks the event and forwards it,
 * and does nothing else.
 *
 * <p>Installed by {@link NexoLogScrubber#install()}, which swaps it into a
 * {@code LoggerConfig} in place of the appender it wraps. The wrapped appender
 * keeps its own name and its own registration in the {@code Configuration}, so
 * Log4j2 still starts, flushes and stops it, and this class deliberately does
 * neither: {@link #stop()} takes only itself down.
 *
 * <h2>The delegate's filter is applied here</h2>
 *
 * <p>Log4j2 applies an appender's own filter from outside, in
 * {@code AppenderControl}, by asking the appender it holds — which is now this
 * one. Minecraft's console appender carries four {@code RegexFilter}s (the
 * "Failed to verify authentication" family that a dev run would otherwise
 * spam), so dropping them silently would be a visible regression. They are
 * evaluated below instead, against the <em>original</em> event: the regexes
 * match on the text the game produced, not on the text after masking.
 *
 * <h2>Nothing in here logs</h2>
 *
 * <p>Not on the append path, not on failure. A log call from inside an appender
 * re-enters the logging subsystem from a thread already inside it; see
 * {@link NexoLogScrubber} for the full reasoning and the second guard behind
 * this one.
 */
final class ScrubbingAppender extends AbstractAppender {
	/** Prefix chosen so the wrapper is obvious in a Log4j2 status dump. */
	private static final String NAME_PREFIX = "NexoScrub:";

	private final Appender delegate;

	ScrubbingAppender(Appender delegate) {
		// A null filter of our own is the point: AppenderControl asks *this*
		// object whether the event is filtered, and answering "no" here is what
		// lets append() ask the delegate exactly once, below. Handing the
		// delegate's filter to super would have worked too, but it would also
		// have entangled two Filterables in one Filter's start/stop lifecycle.
		//
		// The layout is passed through only so requiresLocation() keeps
		// answering what it answered before the swap; this class never renders
		// anything itself.
		super(NAME_PREFIX + delegate.getName(), null, delegate.getLayout(), delegate.ignoreExceptions(), null);
		this.delegate = delegate;
	}

	@Override
	public void append(LogEvent event) {
		if (delegate instanceof Filterable filterable && filterable.isFiltered(event)) {
			return;
		}
		delegate.append(NexoLogScrubber.scrubEvent(event));
	}
}
