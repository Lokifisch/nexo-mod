package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.hud.NexoStatsConfig;
import dev.nexoclient.nexomod.hud.NexoStatsRegistry;

/** One checkbox per registered stat line — see {@code hud.NexoStatsHud} for why this is one module, not ten. */
public class NexoStatsConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 220;

	public NexoStatsConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.stats.title"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig modConfig = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.stats.description"),
				modConfig::statsHudEnabled,
				() -> modConfig.setStatsHudEnabled(!modConfig.statsHudEnabled())));

		NexoStatsConfig config = NexoStatsConfig.get();
		for (NexoStatsRegistry.Stat stat : NexoStatsRegistry.stats()) {
			layout.addChild(CycleButton.onOffBuilder(config.isEnabled(stat.id()))
					.create(0, 0, ROW_WIDTH, 20, stat.label(),
							(button, value) -> config.setEnabled(stat.id(), value)));
		}
		layout.addChild(Button.builder(Component.translatable("nexomod.qol.editLayout"),
						button -> minecraft.setScreen(new NexoHudEditorScreen(this)))
				.size(ROW_WIDTH, 20).build());

		finishLayout();
	}
}
