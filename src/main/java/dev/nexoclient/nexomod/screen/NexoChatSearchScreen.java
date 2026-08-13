package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.chat.NexoChatSearch;

/**
 * Full-text search over the local chat archive.
 *
 * <h2>Why the results arrive through a listener</h2>
 *
 * <p>The search itself runs on the native job pool and is collected from the
 * client tick by {@link NexoChatSearch}, not by this screen — a job outlives the
 * screen that started it, and a screen that owned the job id would leak one
 * every time it was closed while a search was in flight. So this screen
 * registers a callback, rebuilds its rows when a search settles, and drops the
 * callback on close.
 *
 * <p>{@link NexoChatSearch#cancel()} on close rather than leaving the job to the
 * pool's five-minute drop: the contract makes cancelling an unknown id silent
 * precisely so a close handler can call it unconditionally.
 */
public class NexoChatSearchScreen extends NexoOptionScreen {
	public NexoChatSearchScreen(Screen lastScreen) {
		super(lastScreen, Component.translatable("nexomod.chat.search.title"), new NexoChatSearchOptionList(
				Minecraft.getInstance(), 0, 0, HEADER_MARGIN, BASE_LIST_ENTRY_WIDTH, LIST_ENTRY_HEIGHT, LIST_ENTRY_SPACING));
	}

	@Override
	protected void init() {
		super.init();
		// Re-registered on every init (which includes every resize), and only
		// ever one listener exists, so this cannot stack up.
		NexoChatSearch.setListener(this::onSearchSettled);
	}

	private void onSearchSettled() {
		if (list instanceof NexoChatSearchOptionList searchList) {
			searchList.refreshResults();
		}
	}

	@Override
	public void onClose() {
		NexoChatSearch.setListener(null);
		NexoChatSearch.cancel();
		super.onClose();
	}
}
