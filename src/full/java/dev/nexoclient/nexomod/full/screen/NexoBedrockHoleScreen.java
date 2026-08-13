package dev.nexoclient.nexomod.full.screen;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoIntSlider;
import dev.nexoclient.nexomod.screen.NexoOptionScreen;
import dev.nexoclient.nexomod.screen.NexoSettingsOptionList;
import dev.nexoclient.nexomod.full.bedrock.BedrockHoleFinder;
import dev.nexoclient.nexomod.screen.NexoConfig.BedrockHoleRadius;

/**
 * Bedrock Hole Finder category. Every change clears the finder's cache through
 * {@link BedrockHoleFinder#onSettingsChanged()}, since cached chunk results were
 * produced under the settings that were active when they were scanned.
 *
 * The two size sliders clamp against each other in {@link NexoConfig}, so
 * dragging the minimum past the maximum pushes the maximum along instead of
 * leaving an empty range; the other slider's label catches up the next time the
 * screen is built.
 */
public class NexoBedrockHoleScreen extends NexoOptionScreen {
	public NexoBedrockHoleScreen(Screen lastScreen) {
		super(lastScreen, Component.translatable("nexomod.settings.bedrockHoles.title"),
				new NexoSettingsOptionList(NexoBedrockHoleScreen::addRows));
	}

	private static void addRows(NexoSettingsOptionList list) {
		NexoConfig config = NexoConfig.get();
		list.addWidgetRow(CycleButton.onOffBuilder(config.bedrockHoleFinderEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.bedrockHoles.enabled.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.bedrockHoles.enabled"),
						(button, value) -> {
							config.setBedrockHoleFinderEnabled(value);
							BedrockHoleFinder.onSettingsChanged();
						}));
		list.addWidgetRow(CycleButton.<BedrockHoleRadius>builder(NexoBedrockHoleScreen::radiusLabel, config.bedrockHoleRadius())
				.withValues(BedrockHoleRadius.values())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.bedrockHoles.radius.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.bedrockHoles.radius"),
						(button, value) -> {
							config.setBedrockHoleRadius(value);
							BedrockHoleFinder.onSettingsChanged();
						}));
		NexoIntSlider minSize = new NexoIntSlider(list.rowX(), 0, list.rowWidth(), list.rowHeight(),
				"nexomod.settings.bedrockHoles.minSize", NexoConfig.MIN_HOLE_SIZE_FLOOR, NexoConfig.MAX_HOLE_SIZE_CEILING,
				config.bedrockHoleMinSize(), value -> {
					config.setBedrockHoleMinSize(value);
					BedrockHoleFinder.onSettingsChanged();
				});
		minSize.setTooltip(Tooltip.create(Component.translatable("nexomod.settings.bedrockHoles.minSize.tooltip")));
		list.addWidgetRow(minSize);
		NexoIntSlider maxSize = new NexoIntSlider(list.rowX(), 0, list.rowWidth(), list.rowHeight(),
				"nexomod.settings.bedrockHoles.maxSize", NexoConfig.MIN_HOLE_SIZE_FLOOR, NexoConfig.MAX_HOLE_SIZE_CEILING,
				config.bedrockHoleMaxSize(), value -> {
					config.setBedrockHoleMaxSize(value);
					BedrockHoleFinder.onSettingsChanged();
				});
		maxSize.setTooltip(Tooltip.create(Component.translatable("nexomod.settings.bedrockHoles.maxSize.tooltip")));
		list.addWidgetRow(maxSize);
		list.addWidgetRow(CycleButton.onOffBuilder(config.bedrockHoleLabelsEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.bedrockHoles.labels.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.bedrockHoles.labels"),
						(button, value) -> config.setBedrockHoleLabelsEnabled(value)));
		list.addWidgetRow(CycleButton.onOffBuilder(config.bedrockHoleChatEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.bedrockHoles.chat.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.bedrockHoles.chat"),
						(button, value) -> config.setBedrockHoleChatEnabled(value)));
		list.addWidgetRow(CycleButton.onOffBuilder(config.bedrockHoleToastEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.bedrockHoles.toast.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.bedrockHoles.toast"),
						(button, value) -> config.setBedrockHoleToastEnabled(value)));
		list.addWidgetRow(CycleButton.onOffBuilder(config.bedrockHoleShowCoordsEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.bedrockHoles.coords.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.bedrockHoles.coords"),
						(button, value) -> config.setBedrockHoleShowCoordsEnabled(value)));
		list.addWidgetRow(CycleButton.onOffBuilder(config.bedrockHoleSoundEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.bedrockHoles.sound.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.bedrockHoles.sound"),
						(button, value) -> config.setBedrockHoleSoundEnabled(value)));
	}

	private static Component radiusLabel(BedrockHoleRadius radius) {
		return Component.translatable("nexomod.settings.bedrockHoles.radius.chunks", radius.chunks);
	}
}
