package dev.nexoclient.nexomod.tactical.screen;

import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoIntSlider;
import dev.nexoclient.nexomod.screen.NexoModalScreen;
import dev.nexoclient.nexomod.screen.NexoModuleRow;
import dev.nexoclient.nexomod.screen.NexoStyle;
import dev.nexoclient.nexomod.tactical.freecam.NexoFreecam;

/**
 * Enabling here only arms the feature — flying still needs the
 * {@code key.nexomod.freecam} key (F4 out of the box). The hint row names
 * whatever it is currently bound to, rather than hardcoding F4, so a rebind
 * doesn't leave the screen lying about it.
 */
public class NexoFreecamConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoFreecamConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.freecam"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.freecam.description"),
				config::freecamEnabled,
				() -> {
					boolean next = !config.freecamEnabled();
					config.setFreecamEnabled(next);
					// Turning it off with the camera detached would strand the view.
					if (!next) {
						NexoFreecam.disable();
					}
					minecraft.setScreen(new NexoFreecamConfigScreen(parent));
				}));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.freecam.speed",
				NexoConfig.FREECAM_SPEED_MIN, NexoConfig.FREECAM_SPEED_MAX,
				config.freecamSpeed(), config::setFreecamSpeed));
		layout.addChild(new StringWidget(
				Component.translatable("nexomod.qol.freecam.hint", NexoFreecam.toggleKeyName())
						.withStyle(style -> style.withColor(NexoStyle.TEXT_SECONDARY)),
				font));

		finishLayout();
	}
}
