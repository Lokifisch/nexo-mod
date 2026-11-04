package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Look-and-feel category: the menu re-skin toggles and background style options. */
public class NexoAppearanceScreen extends NexoOptionScreen {
	public NexoAppearanceScreen(Screen lastScreen) {
		super(lastScreen, Component.translatable("nexomod.settings.appearance.title"), new NexoSettingsOptionList(NexoAppearanceScreen::addRows));
	}

	private static void addRows(NexoSettingsOptionList list) {
		NexoConfig config = NexoConfig.get();
		list.addWidgetRow(CycleButton.onOffBuilder(config.customMenusEnabled())
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.customMenus"),
						(button, value) -> config.setCustomMenusEnabled(value)));
		list.addWidgetRow(CycleButton.onOffBuilder(config.customFontEnabled())
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.customFont"),
						(button, value) -> {
							config.setCustomFontEnabled(value);
							// Font providers are resolved once per resource reload, not per frame — flipping
							// the config alone doesn't change what's already been baked into the active FontSet.
							Minecraft.getInstance().reloadResourcePacks();
						}));
		list.addWidgetRow(CycleButton.onOffBuilder(config.smoothEdgesEnabled())
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.smoothEdges"),
						(button, value) -> config.setSmoothEdgesEnabled(value)));
		list.addWidgetRow(CycleButton.onOffBuilder(config.windowTitleServerEnabled())
				.withTooltip(value -> net.minecraft.client.gui.components.Tooltip.create(Component.translatable("nexomod.settings.windowTitleServer.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.windowTitleServer"),
						(button, value) -> {
							config.setWindowTitleServerEnabled(value);
							Minecraft.getInstance().updateTitle();
						}));
		list.addWidgetRow(CycleButton.onOffBuilder(config.titleNetworkEnabled())
				.withTooltip(value -> net.minecraft.client.gui.components.Tooltip.create(Component.translatable("nexomod.settings.titleNetwork.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.titleNetwork"),
						(button, value) -> config.setTitleNetworkEnabled(value)));
		CycleButton<NexoStyle.Theme> themeButton = CycleButton.<NexoStyle.Theme>builder(theme -> Component.literal(theme.label), config.theme())
				.withValues(NexoStyle.Theme.values())
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.theme"),
						(button, value) -> config.setTheme(value));
		CycleButton<NexoConfig.BackgroundStyle> backgroundButton = CycleButton.<NexoConfig.BackgroundStyle>builder(NexoAppearanceScreen::backgroundLabel, config.backgroundStyle())
				.withValues(NexoConfig.BackgroundStyle.values())
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.background"),
						(button, value) -> config.setBackgroundStyle(value));
		// Greyed out while the launcher's look is in use: pressing them would change nothing.
		Runnable syncState = () -> {
			boolean synced = NexoLauncherAppearance.current() != null;
			themeButton.active = !synced;
			backgroundButton.active = !synced;
		};
		list.addWidgetRow(CycleButton.onOffBuilder(config.launcherSyncEnabled())
				.withTooltip(value -> net.minecraft.client.gui.components.Tooltip.create(Component.translatable("nexomod.settings.launcherSync.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.launcherSync"),
						(button, value) -> {
							config.setLauncherSyncEnabled(value);
							syncState.run();
						}));
		syncState.run();
		list.addWidgetRow(themeButton);
		list.addWidgetRow(backgroundButton);
		list.addWidgetRow(CycleButton.<NexoConfig.MatrixColor>builder(NexoAppearanceScreen::matrixColorLabel, config.matrixColor())
				.withValues(NexoConfig.MatrixColor.values())
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.matrixColor"),
						(button, value) -> config.setMatrixColor(value)));
		list.addWidgetRow(CycleButton.<NexoConfig.MatrixDensity>builder(NexoAppearanceScreen::matrixDensityLabel, config.matrixDensity())
				.withValues(NexoConfig.MatrixDensity.values())
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(), Component.translatable("nexomod.settings.matrixDensity"),
						(button, value) -> config.setMatrixDensity(value)));
	}

	private static Component backgroundLabel(NexoConfig.BackgroundStyle style) {
		String key = switch (style) {
			case STARFIELD -> "nexomod.settings.background.starfield";
			case MATRIX_RAIN -> "nexomod.settings.background.matrix";
			default -> "nexomod.settings.background." + style.name().toLowerCase(java.util.Locale.ROOT);
		};
		return Component.translatable(key);
	}

	private static Component matrixColorLabel(NexoConfig.MatrixColor color) {
		String key = switch (color) {
			case GREEN -> "nexomod.settings.matrixColor.green";
			case CYAN -> "nexomod.settings.matrixColor.cyan";
			case MAGENTA -> "nexomod.settings.matrixColor.magenta";
			case VIOLET -> "nexomod.settings.matrixColor.violet";
			case WHITE -> "nexomod.settings.matrixColor.white";
		};
		return Component.translatable(key);
	}

	private static Component matrixDensityLabel(NexoConfig.MatrixDensity density) {
		String key = switch (density) {
			case SPARSE -> "nexomod.settings.matrixDensity.sparse";
			case NORMAL -> "nexomod.settings.matrixDensity.normal";
			case DENSE -> "nexomod.settings.matrixDensity.dense";
		};
		return Component.translatable(key);
	}
}
