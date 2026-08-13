package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.servers.NexoServerList;

/**
 * The quick-switch screen: one row per favourite, clicking a row joins it.
 *
 * <p>Reachable two ways, and the second is the point of the feature — from the
 * settings hub, and from a keybind that works while a world is loaded. Pressing
 * the key mid-game and clicking one row is the whole interaction; the pause
 * menu, the disconnect confirmation and the multiplayer list are all skipped.
 */
public class NexoQuickServerScreen extends NexoOptionScreen {
	public NexoQuickServerScreen(Screen lastScreen) {
		super(lastScreen, Component.translatable("nexomod.servers.title"), new NexoQuickServerOptionList(
				Minecraft.getInstance(), 0, 0, HEADER_MARGIN, BASE_LIST_ENTRY_WIDTH, LIST_ENTRY_HEIGHT, LIST_ENTRY_SPACING));
	}

	@Override
	public void onClose() {
		super.onClose();
		NexoServerList.get().save();
	}
}
