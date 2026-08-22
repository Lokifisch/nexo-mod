package dev.nexoclient.nexomod.tactical.screen;

import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoModalScreen;
import dev.nexoclient.nexomod.screen.NexoModuleRow;
import dev.nexoclient.nexomod.screen.NexoStyle;

/** The chunk-boundary overlay draws in world space, not on the HUD — nothing to drag or resize here. */
public class NexoChunkBorderConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoChunkBorderConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.chunkBorder"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.chunkBorder.description"),
				config::chunkBorderOverlayEnabled,
				() -> {
					config.setChunkBorderOverlayEnabled(!config.chunkBorderOverlayEnabled());
					minecraft.setScreen(new NexoChunkBorderConfigScreen(parent));
				}));

		finishLayout();
	}
}
