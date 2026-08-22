package dev.nexoclient.nexomod.tactical.screen;

import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoIntSlider;
import dev.nexoclient.nexomod.screen.NexoModalScreen;
import dev.nexoclient.nexomod.screen.NexoModuleRow;
import dev.nexoclient.nexomod.screen.NexoStyle;

/** Draws in world space like the chunk-border overlay, so there is no HUD layout to edit. */
public class NexoLightOverlayConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoLightOverlayConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.lightOverlay"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.lightOverlay.description"),
				config::lightOverlayEnabled,
				() -> {
					config.setLightOverlayEnabled(!config.lightOverlayEnabled());
					minecraft.setScreen(new NexoLightOverlayConfigScreen(parent));
				}));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.lightOverlay.radius",
				NexoConfig.LIGHT_OVERLAY_RADIUS_MIN, NexoConfig.LIGHT_OVERLAY_RADIUS_MAX,
				config.lightOverlayRadius(), config::setLightOverlayRadius));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.lightOverlay.threshold",
				NexoConfig.LIGHT_OVERLAY_THRESHOLD_MIN, NexoConfig.LIGHT_OVERLAY_THRESHOLD_MAX,
				config.lightOverlayThreshold(), config::setLightOverlayThreshold));
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.lightOverlay.showSafe"),
				Component.translatable("nexomod.qol.lightOverlay.showSafe.description"),
				config::lightOverlayShowSafe,
				() -> {
					config.setLightOverlayShowSafe(!config.lightOverlayShowSafe());
					minecraft.setScreen(new NexoLightOverlayConfigScreen(parent));
				}));

		finishLayout();
	}
}
