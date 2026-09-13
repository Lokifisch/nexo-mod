package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.paperserver.PaperServerRecord;

/**
 * A live-ish console for one hosted server — see {@code Mod/ROADMAP.md}
 * Phase 7. "Live" with a caveat: Source RCON (the only control channel this
 * feature has) is strictly request/response, it has no push channel for
 * ambient log lines, so this tails {@code logs/latest.log} on disk instead
 * of trying to stream it — everything the server itself logs shows up here,
 * commands typed in the box run over RCON same as anywhere else in this
 * feature.
 */
public class NexoPaperConsoleScreen extends NexoOptionScreen {
	/** ~1s at 20 ticks/sec — frequent enough to feel live, cheap enough not to matter. */
	private static final int REFRESH_INTERVAL_TICKS = 20;

	private final NexoPaperConsoleOptionList consoleList;
	private int ticksUntilRefresh = REFRESH_INTERVAL_TICKS;

	public NexoPaperConsoleScreen(Screen lastScreen, PaperServerRecord record) {
		super(lastScreen, Component.translatable("nexomod.paperServer.console.title", record.name()),
				new NexoPaperConsoleOptionList(Minecraft.getInstance(), 0, 0, HEADER_MARGIN,
						BASE_LIST_ENTRY_WIDTH, LIST_ENTRY_HEIGHT, LIST_ENTRY_SPACING, record));
		this.consoleList = (NexoPaperConsoleOptionList) this.list;
	}

	@Override
	public void tick() {
		super.tick();
		if (--ticksUntilRefresh <= 0) {
			ticksUntilRefresh = REFRESH_INTERVAL_TICKS;
			consoleList.refreshLog();
		}
	}
}
