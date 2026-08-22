package dev.nexoclient.nexomod.screen;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Which of the armour bar's overlays are on.
 *
 * <p>The master is a full {@link NexoModuleRow} like every other QoL feature,
 * but the seven things it switches on are plain on/off buttons: they are all
 * the same kind of answer, and giving each one a 36px card would turn a short
 * list into a scroll. The rows only appear while the master is on, since with
 * it off none of them does anything and the bar is vanilla's again.
 */
public class NexoArmorBarConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoArmorBarConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.settings.armorBar.enabled"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.armorBar.description"),
				config::armorBarEnabled,
				() -> {
					config.setArmorBarEnabled(!config.armorBarEnabled());
					minecraft.setScreen(new NexoArmorBarConfigScreen(parent));
				}));

		if (config.armorBarEnabled()) {
			toggle("materials", config::armorBarMaterials, config::setArmorBarMaterials);
			toggle("enchants", config::armorBarEnchants, config::setArmorBarEnchants);
			toggle("thorns", config::armorBarThorns, config::setArmorBarThorns);
			toggle("durability", config::armorBarDurability, config::setArmorBarDurability);
			toggle("mending", config::armorBarMending, config::setArmorBarMending);
			toggle("damageFeedback", config::armorBarDamageFeedback, config::setArmorBarDamageFeedback);
			toggle("detail", config::armorBarDetail, config::setArmorBarDetail);
		}

		finishLayout();
	}

	private void toggle(String key, BooleanSupplier getter, Consumer<Boolean> setter) {
		String label = "nexomod.settings.armorBar." + key;
		layout.addChild(CycleButton.onOffBuilder(getter.getAsBoolean())
				.withTooltip(value -> Tooltip.create(Component.translatable(label + ".tooltip")))
				.create(0, 0, ROW_WIDTH, 20, Component.translatable(label),
						(button, value) -> setter.accept(value)));
	}
}
