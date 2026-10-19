package dev.nexoclient.nexomod.tactical.screen;

import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoIntSlider;
import dev.nexoclient.nexomod.screen.NexoModalScreen;
import dev.nexoclient.nexomod.screen.NexoModuleRow;
import dev.nexoclient.nexomod.screen.NexoStyle;

public class NexoFullbrightConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoFullbrightConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.fullbright"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.fullbright.description"),
				config::fullbrightEnabled,
				() -> {
					config.setFullbrightEnabled(!config.fullbrightEnabled());
					minecraft.gui.setScreen(new NexoFullbrightConfigScreen(parent));
				}));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.fullbright.percent",
				NexoConfig.FULLBRIGHT_PERCENT_MIN, NexoConfig.FULLBRIGHT_PERCENT_MAX,
				config.fullbrightPercent(), config::setFullbrightPercent));

		finishLayout();
	}
}
