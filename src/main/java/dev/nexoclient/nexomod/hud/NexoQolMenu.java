package dev.nexoclient.nexomod.hud;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

import dev.nexoclient.nexomod.screen.NexoQolOverlayScreen;

/**
 * The right-shift QoL toggle menu, the way most other clients bind theirs —
 * one key opens it over live gameplay, the same key closes it again.
 *
 * <p>Defaults to an actual key ({@code KEY_RSHIFT}) rather than
 * {@code UNKNOWN} like most Nexo keybinds: those are toggles for optional
 * behavior a player opts into, but a menu key with nothing bound has no way
 * to be discovered at all. Still rebindable via vanilla Controls.
 *
 * <p>{@link KeyMapping#consumeClick()} only fires while no screen has
 * keyboard focus (see {@link NexoHudVisibility}'s own note on this), which
 * covers <em>opening</em> the menu from gameplay. Closing it again on a
 * second press is handled separately, by {@link NexoQolOverlayScreen} itself
 * calling {@link #isToggleKey} from its {@code keyPressed} — a screen that
 * has focus is exactly the case {@code consumeClick()} does not cover.
 */
public final class NexoQolMenu {
	private static KeyMapping toggleKey;

	private NexoQolMenu() {
	}

	public static void register() {
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.qolMenu",
				InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(NexoQolMenu::tick);
	}

	private static void tick(Minecraft client) {
		if (toggleKey == null) {
			return;
		}
		while (toggleKey.consumeClick()) {
			if (client.screen == null) {
				client.setScreen(new NexoQolOverlayScreen());
			}
		}
	}

	/** Whether `event` is this menu's toggle key — used by the open screen to close on a second press. */
	public static boolean isToggleKey(KeyEvent event) {
		return toggleKey != null && toggleKey.matches(event);
	}
}
