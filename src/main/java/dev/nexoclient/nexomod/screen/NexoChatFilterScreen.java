package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.chat.NexoChatFilter;

/**
 * Pattern management for the auto chat filter.
 *
 * <p>Closing is what compiles the list into a native handle: the FFI has
 * {@code filterAddPattern} and no remove, so an edit means building a fresh
 * handle and destroying the old one. Doing that per keystroke would rebuild the
 * whole regex set on every character typed into a pattern field, so it happens
 * once, here — which is also where a regex error first has somewhere to be
 * shown, since {@link NexoChatFilter#errors()} is populated by that rebuild.
 */
public class NexoChatFilterScreen extends NexoOptionScreen {
	public NexoChatFilterScreen(Screen lastScreen) {
		super(lastScreen, Component.translatable("nexomod.chat.filters.title"), new NexoChatFilterOptionList(
				Minecraft.getInstance(), 0, 0, HEADER_MARGIN, BASE_LIST_ENTRY_WIDTH, LIST_ENTRY_HEIGHT, LIST_ENTRY_SPACING));
	}

	@Override
	public void onClose() {
		NexoChatFilter.get().save();
		NexoChatFilter.get().rebuild();
		super.onClose();
	}
}
