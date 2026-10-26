package dev.nexoclient.nexomod.tactical.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoIntSlider;
import dev.nexoclient.nexomod.screen.NexoModalScreen;
import dev.nexoclient.nexomod.screen.NexoModuleRow;
import dev.nexoclient.nexomod.screen.NexoStyle;

public class NexoTriggerBotConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoTriggerBotConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.triggerBot"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_PRIMARY).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.triggerBot.description"),
				config::triggerBotEnabled,
				() -> {
					config.setTriggerBotEnabled(!config.triggerBotEnabled());
					minecraft.gui.setScreen(new NexoTriggerBotConfigScreen(parent));
				}));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.triggerBot.charge",
				50, 100, config.triggerBotCharge(), config::setTriggerBotCharge));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.triggerBot.delayMin",
				NexoConfig.TRIGGER_BOT_DELAY_FLOOR, NexoConfig.TRIGGER_BOT_DELAY_CEILING,
				config.triggerBotDelayMin(), config::setTriggerBotDelayMin));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.triggerBot.delayMax",
				NexoConfig.TRIGGER_BOT_DELAY_FLOOR, NexoConfig.TRIGGER_BOT_DELAY_CEILING,
				config.triggerBotDelayMax(), config::setTriggerBotDelayMax));
		layout.addChild(Button.builder(targetsLabel(config), b -> {
			config.cycleTriggerBotTargets();
			b.setMessage(targetsLabel(config));
		}).width(ROW_WIDTH).build());

		finishLayout();
	}

	private static Component targetsLabel(NexoConfig c) {
		return Component.translatable("nexomod.qol.triggerBot.targets",
				Component.translatable("nexomod.qol.aimAssist.targets." + c.triggerBotTargets()));
	}
}
