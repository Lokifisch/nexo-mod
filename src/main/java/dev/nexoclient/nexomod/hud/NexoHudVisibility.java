package dev.nexoclient.nexomod.hud;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The single switch every Nexo render path asks before drawing anything of its
 * own — the "clean screenshot" toggle.
 *
 * <h2>Why one switch and not a setting per feature</h2>
 *
 * <p>The point of this is a screenshot with no mod in it. That only works if
 * <em>everything</em> Nexo draws goes away in the same instant: a frame with the
 * badges gone but the neon buttons still there is exactly the screenshot the
 * feature exists to avoid. So there is one boolean, it is read at the top of
 * every render path (badges, watermark, the menu re-skin, and — in the full jar
 * — the tactical indicator, the armor HUD and the bedrock outlines), and nothing
 * gets its own opt-out.
 *
 * <h2>Why it is not a config value</h2>
 *
 * <p>{@link dev.nexoclient.nexomod.screen.NexoConfig} is persisted and, for the
 * font toggle, needs a resource reload to take effect. Neither is wanted here:
 * the state must not survive a restart (you would come back to a mod that looks
 * uninstalled), and a reload mid-session is a visible stall plus a rebuilt atlas
 * — far more than "stop drawing". This is a plain in-memory flag instead.
 *
 * <h2>Why the flip happens in the client tick</h2>
 *
 * <p>{@link KeyMapping#consumeClick()} is polled from
 * {@link ClientTickEvents#END_CLIENT_TICK}, which runs between frames. A frame
 * therefore reads one consistent value from top to bottom, and there is no
 * possibility of a half-hidden frame — which a keyboard-callback flip in the
 * middle of the render pass could produce.
 *
 * <p>The field is {@code volatile} because it is written on the client thread
 * and read from render paths that may run on the render thread; it is a single
 * boolean with no invariant attached to it, so nothing stronger is needed.
 */
public final class NexoHudVisibility {
	private static volatile boolean hidden;
	private static volatile boolean ghost;

	private static KeyMapping toggleKey;

	private NexoHudVisibility() {
	}

	/** Called from {@code NexoMod.onInitializeClient()}; unbound by default. */
	public static void register() {
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.hideNexoHud",
				InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(NexoHudVisibility::tick);
	}

	private static void tick(Minecraft client) {
		if (toggleKey == null) {
			return;
		}
		while (toggleKey.consumeClick()) {
			hidden = !hidden;
			rebuildOpenScreen(client);
			announce(client);
		}
	}

	/**
	 * Widgets Nexo adds to <em>vanilla</em> screens — the "Nexo Settings" button
	 * on the options screen is the one today — are created in that screen's
	 * {@code init()} and then simply exist. A per-frame visibility check would
	 * hide such a button while leaving it clickable, which is worse than showing
	 * it, so the switch instead re-runs the open screen's layout once, here, on
	 * the tick the flag changed. {@link net.minecraft.client.gui.screens.Screen#resize}
	 * is the public entry point vanilla itself uses for that.
	 *
	 * <p>Only on the flip, never per frame: this rebuilds every widget on the
	 * screen and would be absurd as a render-loop cost.
	 */
	private static void rebuildOpenScreen(Minecraft client) {
		if (client.screen == null) {
			return;
		}
		client.screen.resize(client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
	}

	/**
	 * Confirmation is deliberately asymmetric. Turning the mod's UI <em>off</em>
	 * says nothing — a "Nexo HUD hidden" banner in the corner is the one thing
	 * guaranteed to be in the screenshot. Turning it back on says so, because
	 * otherwise the only feedback that the keybind did anything is UI that was
	 * already there reappearing, and a mis-press with nothing bound to it looks
	 * identical to a mod that broke.
	 *
	 * <p>The action bar rather than chat: it expires on its own and never
	 * becomes part of the chat history the search screen shows.
	 */
	private static void announce(Minecraft client) {
		if (!hidden && !ghost && client.gui != null) {
			client.gui.setOverlayMessage(Component.translatable("nexomod.hud.shown"), false);
		}
	}

	/** True while Nexo's own drawing is suppressed. Check this first, in every Nexo render path. */
	public static boolean hidden() {
		return hidden || ghost;
	}

	/** Convenience inverse of {@link #hidden()}, for guards that read better positively. */
	public static boolean visible() {
		return !hidden();
	}

	/**
	 * The deeper tier: ghost mode, which the full jar's own keybind drives.
	 *
	 * <h2>Why it lives here rather than beside the feature that owns it</h2>
	 *
	 * <p>Ghost mode is the screenshot toggle plus the informational overlays only
	 * the full jar has — the sound radar, the armor HUD, the bedrock outlines.
	 * Those are two questions with one answer, and the failure mode of splitting
	 * them is a frame where the badges are gone and the radar is still painting,
	 * which is exactly the screenshot both features exist to prevent. One flag,
	 * one place, read from every render path.
	 *
	 * <p>The field and its setter are in {@code src/main} even though nothing in
	 * {@code src/main} sets them, because {@link #hidden()} has to fold ghost in
	 * and {@code src/main} cannot name a {@code src/full} class. The light jar
	 * therefore carries a flag that is never true, which costs one volatile read
	 * that was already happening.
	 */
	public static boolean ghost() {
		return ghost;
	}

	/**
	 * Called from the full jar's ghost-mode keybind. Flipping this also hides
	 * everything {@link #hidden()} hides, since {@link #hidden()} folds it in.
	 *
	 * @return the new state, so the caller can announce it without re-reading
	 */
	public static boolean setGhost(boolean enabled) {
		ghost = enabled;
		Minecraft client = Minecraft.getInstance();
		if (client != null) {
			rebuildOpenScreen(client);
		}
		return ghost;
	}

	/**
	 * Whether the neon menu/button/slider re-skin should draw: the persisted
	 * setting <em>and</em> this toggle.
	 *
	 * <p>The two questions are combined here rather than at each of the four
	 * re-skin mixins because they have to be asked together every single time —
	 * a mixin that only checked the setting would keep painting neon buttons in
	 * a screenshot that was supposed to have none, and the mistake would be
	 * invisible until someone looked at the picture.
	 *
	 * <p>The font is deliberately <em>not</em> part of this. Swapping the glyph
	 * provider back needs a resource reload
	 * ({@code NexoFontToggleMixin} filters at font-load time), which is a stall
	 * and an atlas rebuild — the opposite of a toggle you press while lining up
	 * a shot. A custom font is also not a mod watermark in the way a badge is.
	 */
	public static boolean nexoSkinActive() {
		return visible() && dev.nexoclient.nexomod.screen.NexoConfig.get().customMenusEnabled();
	}
}
