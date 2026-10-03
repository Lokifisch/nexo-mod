package dev.nexoclient.nexomod.tactical.locate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoOptionScreen;

/** The saved-seed index: every server and world, servers first, each with its seed editable in place. */
public class NexoSeedIndexScreen extends NexoOptionScreen {
	public NexoSeedIndexScreen(Screen lastScreen) {
		super(lastScreen, Component.translatable("nexomod.seedIndex.title"), new NexoSeedOptionList(
				Minecraft.getInstance(), 0, 0, HEADER_MARGIN, BASE_LIST_ENTRY_WIDTH, LIST_ENTRY_HEIGHT, LIST_ENTRY_SPACING));
	}
}
