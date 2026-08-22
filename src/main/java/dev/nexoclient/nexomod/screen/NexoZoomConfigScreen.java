package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * No "Edit Layout" button here, unlike most QoL config screens: zoom draws
 * nothing on the HUD, it changes the camera, so there is no box to drag.
 */
public class NexoZoomConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoZoomConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.zoom"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.zoom.description"),
				config::zoomEnabled,
				() -> {
					config.setZoomEnabled(!config.zoomEnabled());
					minecraft.setScreen(new NexoZoomConfigScreen(parent));
				}));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.zoom.factor",
				NexoConfig.ZOOM_FACTOR_MIN, NexoConfig.ZOOM_FACTOR_MAX,
				config.zoomFactor(), config::setZoomFactor));
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.zoom.smooth"),
				Component.translatable("nexomod.qol.zoom.smooth.description"),
				config::zoomSmoothEnabled,
				() -> {
					config.setZoomSmoothEnabled(!config.zoomSmoothEnabled());
					minecraft.setScreen(new NexoZoomConfigScreen(parent));
				}));

		finishLayout();
	}
}
