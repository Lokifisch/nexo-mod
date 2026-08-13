package dev.nexoclient.nexomod.full.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.full.macro.NexoMacroTriggerConfig;
import dev.nexoclient.nexomod.screen.NexoOptionScreen;

/**
 * The trigger-rule editor.
 *
 * <p>Edits land in the live rule objects as they are made and are written to
 * disk on close, matching {@code NexoMacroListScreen}. The evaluator reads the
 * same objects, so a rule takes effect the moment it is edited rather than on
 * save — which is what makes trying one out a matter of closing the screen and
 * watching.
 */
public class NexoMacroTriggerScreen extends NexoOptionScreen {
	public NexoMacroTriggerScreen(Screen lastScreen) {
		super(lastScreen, Component.translatable("nexomod.macroTriggers.title"), new NexoMacroTriggerOptionList(
				Minecraft.getInstance(), 0, 0, HEADER_MARGIN, BASE_LIST_ENTRY_WIDTH, LIST_ENTRY_HEIGHT, LIST_ENTRY_SPACING));
	}

	@Override
	public void onClose() {
		super.onClose();
		NexoMacroTriggerConfig.get().save();
	}
}
