package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.hud.NexoHudLayout;

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
			layout.addChild(CycleButton.<NexoConfig.ArmorOrientation>builder(NexoArmorHudConfigScreen::orientationLabel,
							config.armorHudOrientation())
					.withValues(NexoConfig.ArmorOrientation.values())
					.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.armorHud.orientation.tooltip")))
					.create(0, 0, ROW_WIDTH, 20, Component.translatable("nexomod.settings.armorHud.orientation"),
							(button, value) -> {
								config.setArmorHudOrientation(value);
								// The nominal box swaps its axes with the orientation, so a
								// position saved for the other one would put it somewhere
								// arbitrary. Dropping the override re-homes it to the new
								// default (right edge, or above the hotbar).
								NexoHudLayout.get().reset(NexoHudLayout.Element.ARMOR);
								minecraft.setScreen(new NexoArmorHudConfigScreen(parent));
							}));
			layout.addChild(CycleButton.<NexoConfig.ArmorDurabilityMode>builder(NexoArmorHudConfigScreen::durabilityLabel,
							config.armorHudDurabilityMode())
					.withValues(NexoConfig.ArmorDurabilityMode.values())
					.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.armorHud.durability.tooltip")))
					.create(0, 0, ROW_WIDTH, 20, Component.translatable("nexomod.settings.armorHud.durability"),
							(button, value) -> config.setArmorHudDurabilityMode(value)));

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

		finishLayout();
	}

	private static Component orientationLabel(NexoConfig.ArmorOrientation orientation) {
		return Component.translatable(switch (orientation) {
			case VERTICAL -> "nexomod.settings.armorHud.orientation.vertical";
			case HORIZONTAL -> "nexomod.settings.armorHud.orientation.horizontal";
		});
	}

	private static Component durabilityLabel(NexoConfig.ArmorDurabilityMode mode) {
		return Component.translatable(switch (mode) {
			case PERCENT -> "nexomod.settings.armorHud.durability.percent";
			case VALUES -> "nexomod.settings.armorHud.durability.values";
			case NONE -> "nexomod.settings.armorHud.durability.none";
		});
	}
}
