package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * On/off only, and no layout editor: these numbers are positioned in the world
 * over whatever was hit, not on the HUD, so there is no box to drag.
 */
public class NexoDamageNumbersConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoDamageNumbersConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.damageNumbers"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.damageNumbers.description"),
				config::damageNumbersEnabled,
				() -> {
					config.setDamageNumbersEnabled(!config.damageNumbersEnabled());
					minecraft.setScreen(new NexoDamageNumbersConfigScreen(parent));
				}));

		finishLayout();
	}
}
