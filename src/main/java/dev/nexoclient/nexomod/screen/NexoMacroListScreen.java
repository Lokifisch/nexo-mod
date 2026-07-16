package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.macro.NexoMacroConfig;

/**
 * Root macro-list screen — Nexo's take on CommandKeys' {@code MainOptionScreen}, saving to
 * disk only when closed (edits to individual macros apply live to their in-memory objects).
 */
public class NexoMacroListScreen extends NexoOptionScreen {
	public NexoMacroListScreen(Screen lastScreen) {
		super(lastScreen, Component.translatable("nexomod.macros.title"), new NexoMacroOptionList(
				Minecraft.getInstance(), 0, 0, HEADER_MARGIN, BASE_LIST_ENTRY_WIDTH, LIST_ENTRY_HEIGHT, LIST_ENTRY_SPACING));
	}

	@Override
	public void onClose() {
		super.onClose();
		NexoMacroConfig.get().save();
	}
}
