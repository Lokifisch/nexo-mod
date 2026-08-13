package dev.nexoclient.nexomod.full.ghost;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;

/**
 * Ghost mode: one key that takes every trace of Nexo off the screen.
 *
 * <h2>What it adds over the screenshot toggle</h2>
 *
 * <p>The screenshot toggle in {@code src/main} hides what the light jar can
 * draw — the badges, the inventory watermark, the neon menu re-skin. The full
 * jar also draws things that say something about the world: the sound radar, the
 * armour HUD, the bedrock hole outlines. Ghost mode is the same switch with
 * those included, and it exists as a separate key so "clean screenshot" and
 * "look like a vanilla client right now" are not the same gesture.
 *
 * <h2>Why it reuses {@code NexoHudVisibility}</h2>
 *
 * <p>Because a second, parallel flag would have to be checked in every render
 * path next to the first one, and the first path someone forgot would be a frame
 * with half the mod still on screen — which is the exact failure both features
 * exist to prevent. {@link NexoHudVisibility#hidden()} folds ghost in, so every
 * existing guard already honours it and the full-only paths only have to ask the
 * same question the light ones do.
 */
public final class NexoGhostMode {
	private static KeyMapping toggleKey;

	private NexoGhostMode() {
	}

	/** Registered from {@code NexoFullFeatures}; unbound by default. */
	public static void register() {
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.ghostMode",
				InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(NexoGhostMode::tick);
	}

	/**
	 * Polled from the client tick rather than from a key callback, for the same
	 * reason the screenshot toggle is: the tick runs between frames, so a frame
	 * reads one consistent value from top to bottom and no half-hidden frame can
	 * exist.
	 */
	private static void tick(Minecraft client) {
		if (toggleKey == null) {
			return;
		}
		while (toggleKey.consumeClick()) {
			boolean enabled = NexoHudVisibility.setGhost(!NexoHudVisibility.ghost());
			announce(client, enabled);
		}
	}

	/**
	 * Only the way back says anything, matching the screenshot toggle: a
	 * "ghost mode on" banner is the one thing guaranteed to end up in the
	 * screenshot the mode was turned on for.
	 */
	private static void announce(Minecraft client, boolean enabled) {
		if (!enabled && client.gui != null) {
			client.gui.setOverlayMessage(Component.translatable("nexomod.ghost.off"), false);
		}
	}
}
