package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Nexo's own settings — whether the menu re-skin is on at all, and its background style. */
public class NexoSettingsScreen extends NexoModalScreen {
	private NexoButton customMenusButton;
	private NexoButton backgroundButton;
	private NexoButton matrixColorButton;
	private NexoButton matrixDensityButton;

	public NexoSettingsScreen(Screen parent) {
		super(Component.translatable("nexomod.settings.title"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		customMenusButton = layout.addChild(NexoButton.builder(customMenusLabel(), this::toggleCustomMenus).size(220, 20).build());
		backgroundButton = layout.addChild(NexoButton.builder(backgroundLabel(), this::cycleBackground).size(220, 20).build());
		matrixColorButton = layout.addChild(NexoButton.builder(matrixColorLabel(), this::cycleMatrixColor).size(220, 20).build());
		matrixDensityButton = layout.addChild(NexoButton.builder(matrixDensityLabel(), this::cycleMatrixDensity).size(220, 20).build());

		LinearLayout buttonRow = layout.addChild(LinearLayout.horizontal().spacing(4));
		buttonRow.defaultCellSetting().paddingTop(12);
		buttonRow.addChild(NexoButton.builder(CommonComponents.GUI_DONE, this::onClose).build());

		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	private void toggleCustomMenus() {
		NexoConfig config = NexoConfig.get();
		config.setCustomMenusEnabled(!config.customMenusEnabled());
		customMenusButton.setMessage(customMenusLabel());
	}

	private void cycleBackground() {
		NexoConfig config = NexoConfig.get();
		config.setBackgroundStyle(config.backgroundStyle().next());
		backgroundButton.setMessage(backgroundLabel());
	}

	private void cycleMatrixColor() {
		NexoConfig config = NexoConfig.get();
		config.setMatrixColor(config.matrixColor().next());
		matrixColorButton.setMessage(matrixColorLabel());
	}

	private void cycleMatrixDensity() {
		NexoConfig config = NexoConfig.get();
		config.setMatrixDensity(config.matrixDensity().next());
		matrixDensityButton.setMessage(matrixDensityLabel());
	}

	private Component customMenusLabel() {
		boolean enabled = NexoConfig.get().customMenusEnabled();
		return Component.translatable(enabled ? "nexomod.settings.customMenus.on" : "nexomod.settings.customMenus.off");
	}

	private Component backgroundLabel() {
		String key = switch (NexoConfig.get().backgroundStyle()) {
			case STARFIELD -> "nexomod.settings.background.starfield";
			case MATRIX_RAIN -> "nexomod.settings.background.matrix";
		};
		return Component.translatable(key);
	}

	private Component matrixColorLabel() {
		String key = switch (NexoConfig.get().matrixColor()) {
			case GREEN -> "nexomod.settings.matrixColor.green";
			case CYAN -> "nexomod.settings.matrixColor.cyan";
			case MAGENTA -> "nexomod.settings.matrixColor.magenta";
			case VIOLET -> "nexomod.settings.matrixColor.violet";
			case WHITE -> "nexomod.settings.matrixColor.white";
		};
		return Component.translatable(key);
	}

	private Component matrixDensityLabel() {
		String key = switch (NexoConfig.get().matrixDensity()) {
			case SPARSE -> "nexomod.settings.matrixDensity.sparse";
			case NORMAL -> "nexomod.settings.matrixDensity.normal";
			case DENSE -> "nexomod.settings.matrixDensity.dense";
		};
		return Component.translatable(key);
	}
}
