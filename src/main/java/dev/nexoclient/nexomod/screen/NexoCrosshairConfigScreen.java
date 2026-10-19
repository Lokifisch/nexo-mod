package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.hud.NexoCrosshair;

/** Draws a replacement crosshair at screen centre, so there is no HUD layout to edit. */
public class NexoCrosshairConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoCrosshairConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.crosshair"), parent);
	}

	private void reopen() {
		minecraft.gui.setScreen(new NexoCrosshairConfigScreen(parent));
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.crosshair.description"),
				config::crosshairEnabled,
				() -> {
					config.setCrosshairEnabled(!config.crosshairEnabled());
					reopen();
				}));

		NexoCrosshair.Shape[] shapes = NexoCrosshair.Shape.values();
		layout.addChild(Button.builder(
				Component.translatable("nexomod.qol.crosshair.shape",
						Component.translatable("nexomod.qol.crosshair.shape." + config.crosshairShape().name().toLowerCase())),
				b -> {
					config.setCrosshairShape(shapes[(config.crosshairShape().ordinal() + 1) % shapes.length]);
					reopen();
				}).width(ROW_WIDTH).build());

		int[] presets = NexoCrosshair.PRESETS;
		layout.addChild(Button.builder(
				Component.translatable("nexomod.qol.crosshair.color", String.format("#%06X", config.crosshairColor())),
				b -> {
					int i = 0;
					while (i < presets.length && presets[i] != config.crosshairColor()) {
						i++;
					}
					config.setCrosshairColor(presets[(i + 1) % presets.length]);
					reopen();
				}).width(ROW_WIDTH).build());

		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.crosshair.size",
				1, 20, config.crosshairSize(), config::setCrosshairSize));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.crosshair.thickness",
				1, 6, config.crosshairThickness(), config::setCrosshairThickness));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.crosshair.gap",
				0, 12, config.crosshairGap(), config::setCrosshairGap));
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.crosshair.outline"),
				Component.translatable("nexomod.qol.crosshair.outline.description"),
				config::crosshairOutline,
				() -> {
					config.setCrosshairOutline(!config.crosshairOutline());
					reopen();
				}));

		finishLayout();
	}
}
