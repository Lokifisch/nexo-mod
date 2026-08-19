package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Armor HUD's settings, ported from the Tactical-only feature screen it used
 * to share with the sound radar and the rest — see {@code hud.NexoArmorHud}
 * for why it moved to both editions.
 */
public class NexoArmorHudConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoArmorHudConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.settings.armorHud.enabled"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.armorHud.description"),
				config::armorHudEnabled,
				() -> {
					config.setArmorHudEnabled(!config.armorHudEnabled());
					minecraft.setScreen(new NexoArmorHudConfigScreen(parent));
				}));

		if (config.armorHudEnabled()) {
			NexoIntSlider warn = new NexoIntSlider(0, 0, ROW_WIDTH, 20,
					"nexomod.settings.armorHud.warn", 0, 100, config.armorHudWarnPercent(), config::setArmorHudWarnPercent);
			warn.setTooltip(Tooltip.create(Component.translatable("nexomod.settings.armorHud.warn.tooltip")));
			layout.addChild(warn);
			layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
					Component.translatable("nexomod.settings.armorHud.offhand"),
					Component.translatable("nexomod.qol.armorHud.offhand.description"),
					config::armorHudOffhandEnabled,
					() -> {
						config.setArmorHudOffhandEnabled(!config.armorHudOffhandEnabled());
						minecraft.setScreen(new NexoArmorHudConfigScreen(parent));
					}));
			layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
					Component.translatable("nexomod.settings.armorHud.heldItem"),
					Component.translatable("nexomod.settings.armorHud.heldItem.description"),
					config::armorHudHeldItemEnabled,
					() -> {
						config.setArmorHudHeldItemEnabled(!config.armorHudHeldItemEnabled());
						minecraft.setScreen(new NexoArmorHudConfigScreen(parent));
					}));
		}
		layout.addChild(Button.builder(Component.translatable("nexomod.qol.editLayout"),
						button -> minecraft.setScreen(new NexoHudEditorScreen(this)))
				.size(ROW_WIDTH, 20).build());

		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}
}
