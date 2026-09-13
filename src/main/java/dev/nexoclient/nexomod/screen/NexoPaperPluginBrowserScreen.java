package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.paperserver.PaperServerRecord;

/**
 * Hangar (hangar.papermc.io) plugin search, for one hosted server's
 * {@code plugins/} folder. See {@code Mod/ROADMAP.md} Phase 7 — installing
 * a plugin is arbitrary code execution, so every row keeps its
 * author/category/review-state on screen and install is a confirmed
 * second step, not a first click.
 */
public class NexoPaperPluginBrowserScreen extends NexoOptionScreen {
	public NexoPaperPluginBrowserScreen(Screen lastScreen, PaperServerRecord record) {
		super(lastScreen, Component.translatable("nexomod.paperServer.plugins.title", record.name()),
				new NexoPaperPluginOptionList(Minecraft.getInstance(), 0, 0, HEADER_MARGIN,
						BASE_LIST_ENTRY_WIDTH, LIST_ENTRY_HEIGHT, LIST_ENTRY_SPACING, record));
	}
}
