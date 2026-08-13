package dev.nexoclient.nexomod.chat;

import dev.nexoclient.nexomod.nativecore.NexoNative;

/**
 * One user-defined auto-filter rule: a regular expression plus what to do with
 * a message that matches it.
 *
 * <p>Mutable public fields and a no-arg-constructible shape, matching
 * {@link dev.nexoclient.nexomod.macro.NexoMacro} — both are edited in place by a
 * settings screen and round-tripped through Gson, and a record would force a
 * rebuild-and-replace on every keystroke in the regex field.
 *
 * <p>The regex itself is compiled and matched <em>natively</em>; Java never
 * evaluates it. That is deliberate: an anchored search over every incoming chat
 * line is exactly the kind of hot path the native core exists for, and it keeps
 * one regex dialect in play instead of two that quietly disagree about
 * lookbehind.
 */
public final class NexoChatPattern {
	/** The regular expression, in the Rust {@code regex} crate's dialect. */
	public String regex = "";
	/** One of {@link NexoNative#FILTER_HIDE} or {@link NexoNative#FILTER_HIGHLIGHT}. */
	public int action = NexoNative.FILTER_HIDE;
	public boolean enabled = true;

	public NexoChatPattern() {
	}

	public NexoChatPattern(String regex, int action) {
		this.regex = regex;
		this.action = action;
	}

	/**
	 * {@link NexoNative#FILTER_ALLOW} is not offered in the UI: a rule that
	 * matches and then does nothing is indistinguishable from no rule, and
	 * having it in the cycle button only invites "why is my filter not
	 * working". Cycling therefore flips between the two actions that do
	 * something.
	 */
	public void cycleAction() {
		action = action == NexoNative.FILTER_HIDE ? NexoNative.FILTER_HIGHLIGHT : NexoNative.FILTER_HIDE;
	}
}
