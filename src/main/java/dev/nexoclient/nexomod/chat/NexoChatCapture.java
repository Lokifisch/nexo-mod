package dev.nexoclient.nexomod.chat;

import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.nativecore.NexoNative;

/**
 * The seam between the chat mixins and everything else in this package.
 *
 * <p>Two mixins feed it, both verified against the real 26.1 class files:
 *
 * <ul>
 * <li>{@code ChatComponent.addMessage(Component, MessageSignature, GuiMessageSource, GuiMessageTag)}
 *     — the single private funnel that {@code addPlayerMessage},
 *     {@code addServerSystemMessage} and {@code addClientSystemMessage} all
 *     reach. Hooking it once covers every line that ends up on screen, which
 *     hooking the three public entry points would not: they can also be called
 *     by other mods, and each would need its own copy of this logic.</li>
 * <li>{@code ChatListener.showMessageToPlayer(...)} — the only place on the
 *     client where a decorated chat line and the {@code GameProfile} that sent
 *     it are in scope at the same time. {@code addMessage} has the text but not
 *     the sender, so the name is parked in {@link #beginPlayerMessage} on the
 *     way in and read back below.</li>
 * </ul>
 *
 * <h2>Why the sender hint is safe as a plain field</h2>
 *
 * <p>{@code showMessageToPlayer} calls {@code ChatComponent.addPlayerMessage}
 * synchronously, on the client thread, and both mixins run on that thread — the
 * packet handler has already passed {@code PacketUtils.ensureRunningOnSameThread}
 * by then, and delayed messages are re-dispatched through the same method rather
 * than replayed from somewhere else. So the hint is set and consumed inside one
 * call stack. It is cleared on the way out regardless of the return value, so a
 * message that gets dropped mid-method cannot leave a stale name to be
 * mis-attributed to the next system message.
 */
public final class NexoChatCapture {
	private static String senderHint;

	private NexoChatCapture() {
	}

	/** Called from the {@code ChatListener} mixin at HEAD. */
	public static void beginPlayerMessage(String senderName) {
		senderHint = senderName;
	}

	/** Called from the {@code ChatListener} mixin at RETURN, on every path. */
	public static void endPlayerMessage() {
		senderHint = null;
	}

	/**
	 * Records the message and says whether it should be suppressed.
	 *
	 * <p>Recording happens <em>before</em> the hide decision, on purpose: the
	 * search screen is exactly where someone goes to find the line their filter
	 * ate, and a history that silently omits filtered messages cannot answer
	 * that. Nothing is stored when the history is switched off.
	 *
	 * @return true if the caller should cancel the message
	 */
	public static boolean interceptAndRecord(Component content, GuiMessageSource source) {
		NexoChatHistory.record(System.currentTimeMillis(), senderFor(source), content);
		return classify(content) == NexoNative.FILTER_HIDE;
	}

	/**
	 * Applies the highlight style if a rule asked for one, otherwise hands the
	 * component back untouched.
	 *
	 * <p>Untouched matters: returning a rebuilt copy for every message would
	 * discard the identity {@code ChatComponent} relies on when it deletes a
	 * message by signature, and would allocate on a path that runs for every
	 * line of chat.
	 */
	public static Component decorate(Component content) {
		return classify(content) == NexoNative.FILTER_HIGHLIGHT
				? NexoChatFilter.highlight(content)
				: content;
	}

	/**
	 * One filter test, or {@link NexoNative#FILTER_ALLOW} without touching the
	 * native side at all when there is nothing to match against. The cheap
	 * checks come first so a player with no rules configured pays nothing per
	 * message.
	 */
	private static int classify(Component content) {
		NexoChatFilter filter = NexoChatFilter.get();
		if (!filter.hasActiveRules()) {
			return NexoNative.FILTER_ALLOW;
		}
		return filter.test(content.getString());
	}

	/**
	 * The sender to store. A player message uses the captured profile name; the
	 * two system sources get distinct markers rather than an empty string, so
	 * "who said this" is always answerable and the sender filter can single out
	 * server announcements.
	 */
	private static String senderFor(GuiMessageSource source) {
		if (source == GuiMessageSource.PLAYER && senderHint != null) {
			return senderHint;
		}
		return source == GuiMessageSource.SYSTEM_CLIENT
				? NexoChatHistory.SENDER_SYSTEM_CLIENT
				: NexoChatHistory.SENDER_SYSTEM_SERVER;
	}
}
