package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Entry point for Phase 7 (see {@code Mod/ROADMAP.md}): convert the current
 * singleplayer world into a locally-hosted Paper server, or manage/revert
 * one already converted. Same {@code title + scrollable list + footer}
 * shape as {@link NexoQuickServerScreen}.
 */
public class NexoPaperServerScreen extends NexoOptionScreen {
	public NexoPaperServerScreen(Screen lastScreen) {
		super(lastScreen, Component.translatable("nexomod.paperServer.title"), new NexoPaperServerOptionList(
				Minecraft.getInstance(), 0, 0, HEADER_MARGIN, BASE_LIST_ENTRY_WIDTH, LIST_ENTRY_HEIGHT, LIST_ENTRY_SPACING));
	}
}
