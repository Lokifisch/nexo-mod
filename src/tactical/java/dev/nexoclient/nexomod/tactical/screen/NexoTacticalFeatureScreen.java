package dev.nexoclient.nexomod.tactical.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

import dev.nexoclient.nexomod.tactical.chunks.NexoChunkHistory;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoConfig.TimeOverride;
import dev.nexoclient.nexomod.screen.NexoConfig.WeatherOverride;
import dev.nexoclient.nexomod.screen.NexoIntSlider;
import dev.nexoclient.nexomod.screen.NexoOptionScreen;
import dev.nexoclient.nexomod.screen.NexoSettingsOptionList;

/**
 * Every full-only feature that is not the bedrock hole finder, in one scrolling
 * category.
 *
 * <h2>Why one screen and not five</h2>
 *
 * <p>The settings hub is a fixed grid with no scrollbar, so each extra category
 * costs a row there whether or not it earns one. This list scrolls; five
 * categories in the hub would have pushed it past the bottom of a small window,
 * which is a worse problem than a long list.
 *
 * <p>The bedrock hole finder stays separate because it already had its own
 * category before the variant split existed and it has nine settings of its own.
 */
public class NexoTacticalFeatureScreen extends NexoOptionScreen {
	/**
	 * The categories offered for the sound radar. Not all of
	 * {@code SoundSource}: music, records, voice and UI are either not
	 * positional or fire constantly, and an arrow for every note of the
	 * background music is an arrow for nothing.
	 */
	private static final SoundSource[] RADAR_CATEGORIES = {
			SoundSource.HOSTILE, SoundSource.NEUTRAL, SoundSource.PLAYERS,
			SoundSource.BLOCKS, SoundSource.AMBIENT, SoundSource.WEATHER
	};

	/** Chunk radius the "how much do you remember" query asks about. */
	private static final int QUERY_RADIUS_CHUNKS = 16;

	public NexoTacticalFeatureScreen(Screen lastScreen) {
		super(lastScreen, Component.translatable("nexomod.settings.tactical.title"),
				new NexoSettingsOptionList(NexoTacticalFeatureScreen::addRows));
	}

	private static void addRows(NexoSettingsOptionList list) {
		NexoConfig config = NexoConfig.get();
		addRadarRows(list, config);
		addEnvironmentRows(list, config);
		addChunkRows(list, config);
		addTriggerRows(list, config);
	}

	private static void addRadarRows(NexoSettingsOptionList list, NexoConfig config) {
		list.addWidgetRow(CycleButton.onOffBuilder(config.tacticalEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.tactical.enabled.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(),
						Component.translatable("nexomod.settings.tactical.enabled"),
						(button, value) -> {
							config.setTacticalEnabled(value);
							list.rebuildRows();
						}));
		if (!config.tacticalEnabled()) {
			return;
		}
		NexoIntSlider range = new NexoIntSlider(list.rowX(), 0, list.rowWidth(), list.rowHeight(),
				"nexomod.settings.tactical.range", NexoConfig.MIN_TACTICAL_RANGE, NexoConfig.MAX_TACTICAL_RANGE,
				config.tacticalRange(), config::setTacticalRange);
		range.setTooltip(Tooltip.create(Component.translatable("nexomod.settings.tactical.range.tooltip")));
		list.addWidgetRow(range);
		list.addWidgetRow(CycleButton.onOffBuilder(config.tacticalLabelsEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.tactical.labels.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(),
						Component.translatable("nexomod.settings.tactical.labels"),
						(button, value) -> config.setTacticalLabelsEnabled(value)));
		for (SoundSource source : RADAR_CATEGORIES) {
			list.addWidgetRow(CycleButton.onOffBuilder(config.tacticalCategoryEnabled(source))
					.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(),
							Component.translatable("nexomod.settings.tactical.category",
									Component.translatable("soundCategory." + source.getName())),
							(button, value) -> config.setTacticalCategoryEnabled(source, value)));
		}
	}

	private static void addEnvironmentRows(NexoSettingsOptionList list, NexoConfig config) {
		list.addWidgetRow(CycleButton.<TimeOverride>builder(NexoTacticalFeatureScreen::timeLabel, config.timeOverride())
				.withValues(TimeOverride.values())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.environment.time.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(),
						Component.translatable("nexomod.settings.environment.time"),
						(button, value) -> config.setTimeOverride(value)));
		list.addWidgetRow(CycleButton.<WeatherOverride>builder(NexoTacticalFeatureScreen::weatherLabel, config.weatherOverride())
				.withValues(WeatherOverride.values())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.environment.weather.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(),
						Component.translatable("nexomod.settings.environment.weather"),
						(button, value) -> config.setWeatherOverride(value)));
	}

	private static void addChunkRows(NexoSettingsOptionList list, NexoConfig config) {
		list.addWidgetRow(CycleButton.onOffBuilder(config.chunkHistoryEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.chunkHistory.enabled.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(),
						Component.translatable("nexomod.settings.chunkHistory.enabled"),
						(button, value) -> {
							config.setChunkHistoryEnabled(value);
							list.rebuildRows();
						}));
		if (!NexoChunkHistory.isAvailable()) {
			// Says why rather than hiding the query button: the store is
			// genuinely absent wherever the full native library is, which is
			// every platform but Linux x86-64 today.
			list.addWidgetRow(Button.builder(Component.translatable("nexomod.settings.chunkHistory.unavailable"),
							button -> { })
					.pos(list.rowX(), 0).size(list.rowWidth(), list.rowHeight()).build());
			return;
		}
		list.addWidgetRow(Button.builder(queryLabel(), button -> {
					NexoChunkHistory.queryAround(QUERY_RADIUS_CHUNKS);
					// The result lands a tick or more later; re-opening or
					// re-entering the screen picks it up. Rebuilding here just
					// swaps the label to "querying...".
					list.rebuildRows();
				})
				.tooltip(Tooltip.create(Component.translatable("nexomod.settings.chunkHistory.query.tooltip")))
				.pos(list.rowX(), 0).size(list.rowWidth(), list.rowHeight()).build());
	}

	private static void addTriggerRows(NexoSettingsOptionList list, NexoConfig config) {
		list.addWidgetRow(CycleButton.onOffBuilder(config.macroTriggersEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.macroTriggers.enabled.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(),
						Component.translatable("nexomod.settings.macroTriggers.enabled"),
						(button, value) -> config.setMacroTriggersEnabled(value)));
		Minecraft client = Minecraft.getInstance();
		list.addWidgetRow(Button.builder(Component.translatable("nexomod.settings.macroTriggers.edit"),
						button -> client.setScreen(new NexoMacroTriggerScreen(client.screen)))
				.pos(list.rowX(), 0).size(list.rowWidth(), list.rowHeight()).build());
	}

	private static Component queryLabel() {
		if (NexoChunkHistory.queryRunning()) {
			return Component.translatable("nexomod.settings.chunkHistory.querying");
		}
		int count = NexoChunkHistory.lastQueryCount();
		return count < 0
				? Component.translatable("nexomod.settings.chunkHistory.query")
				: Component.translatable("nexomod.settings.chunkHistory.queryResult", count, QUERY_RADIUS_CHUNKS);
	}

	private static Component timeLabel(TimeOverride override) {
		return Component.translatable("nexomod.settings.environment.time." + override.name().toLowerCase(java.util.Locale.ROOT));
	}

	private static Component weatherLabel(WeatherOverride override) {
		return Component.translatable("nexomod.settings.environment.weather." + override.name().toLowerCase(java.util.Locale.ROOT));
	}
}
