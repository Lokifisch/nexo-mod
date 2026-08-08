package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.coords.CoordObfuscator;
import dev.nexoclient.nexomod.screen.NexoConfig.ObscurePreset;

/**
 * Position Obscuring category: a None/Full/Custom preset plus the individual
 * feature toggles. The toggles always display the <em>effective</em> state
 * (preset resolved), and editing one while a preset is selected silently
 * switches to Custom — seeded from what the preset was doing — rather than
 * being locked, so changing a single feature is one click, not two.
 */
public class NexoPositionObscuringScreen extends NexoOptionScreen {
	public NexoPositionObscuringScreen(Screen lastScreen) {
		super(lastScreen, Component.translatable("nexomod.settings.positionObscuring.title"),
				new NexoSettingsOptionList(NexoPositionObscuringScreen::addRows));
	}

	private static void addRows(NexoSettingsOptionList list) {
		NexoConfig config = NexoConfig.get();
		list.addWidgetRow(CycleButton.<ObscurePreset>builder(NexoPositionObscuringScreen::presetLabel, config.obscurePreset())
				.withValues(ObscurePreset.values())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.obscure.preset.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.obscure.preset"),
						(button, value) -> {
							config.setObscurePreset(value);
							CoordObfuscator.onSettingsChanged();
							list.rebuildRows();
						}));
		list.addWidgetRow(CycleButton.onOffBuilder(config.obscureCoordinatesActive())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.obscure.coords.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.obscure.coords"),
						(button, value) -> {
							makeCustom(config);
							config.setObscureCoordinatesEnabled(value);
							CoordObfuscator.onSettingsChanged();
							list.rebuildRows();
						}));
		list.addWidgetRow(CycleButton.onOffBuilder(config.obscureBlockRotationActive())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.obscure.blockRotation.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.obscure.blockRotation"),
						(button, value) -> {
							makeCustom(config);
							config.setObscureBlockRotationEnabled(value);
							CoordObfuscator.onSettingsChanged();
							list.rebuildRows();
						}));
		list.addWidgetRow(CycleButton.onOffBuilder(config.obscureBedrockFloorActive())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.obscure.bedrockFloor.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.obscure.bedrockFloor"),
						(button, value) -> {
							makeCustom(config);
							config.setObscureBedrockFloorEnabled(value);
							CoordObfuscator.onSettingsChanged();
							list.rebuildRows();
						}));
	}

	/**
	 * Before an individual toggle edit lands, freeze the current preset's
	 * effective values into the CUSTOM flags so the edit changes exactly one
	 * feature instead of resurrecting stale CUSTOM state.
	 */
	private static void makeCustom(NexoConfig config) {
		if (config.obscurePreset() != ObscurePreset.CUSTOM) {
			config.setObscureCoordinatesEnabled(config.obscureCoordinatesActive());
			config.setObscureBlockRotationEnabled(config.obscureBlockRotationActive());
			config.setObscureBedrockFloorEnabled(config.obscureBedrockFloorActive());
			config.setObscurePreset(ObscurePreset.CUSTOM);
		}
	}

	private static Component presetLabel(ObscurePreset preset) {
		String key = switch (preset) {
			case NONE -> "nexomod.settings.obscure.preset.none";
			case FULL -> "nexomod.settings.obscure.preset.full";
			case CUSTOM -> "nexomod.settings.obscure.preset.custom";
		};
		return Component.translatable(key);
	}
}
