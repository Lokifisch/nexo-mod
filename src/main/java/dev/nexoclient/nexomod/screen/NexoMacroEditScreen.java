package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.macro.NexoMacro;

/**
 * Edits one macro's name, keybind, mode, timing, and command list. Edits
 * apply live to the given {@link NexoMacro} (held by reference in
 * {@code NexoMacroConfig}); disk persistence happens when the root
 * {@link NexoMacroListScreen} closes, same as CommandKeys' sub-screens.
 */
public class NexoMacroEditScreen extends NexoOptionScreen {
	public NexoMacroEditScreen(Screen lastScreen, NexoMacro macro) {
		super(lastScreen, Component.translatable("nexomod.macros.editTitle"), new NexoMacroEditOptionList(
				Minecraft.getInstance(), 0, 0, HEADER_MARGIN, BASE_LIST_ENTRY_WIDTH, LIST_ENTRY_HEIGHT, LIST_ENTRY_SPACING, macro));
	}
}
