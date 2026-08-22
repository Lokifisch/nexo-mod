package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Four independent hide toggles rather than one master switch — two of them
 * only make sense once the Nexo element that replaces them is on, and the
 * other two are unrelated clutter.
 *
 * <p>Whatever is left visible is also draggable: the same wrapper that can skip
 * a vanilla element can equally translate it, so all four are entries in the
 * layout editor too. See {@code hud.NexoVanillaHud}.
 */
public class NexoHudCleanerConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoHudCleanerConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.hudCleaner"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		addToggle(config, "nexomod.qol.hudCleaner.actionbar",
				config::hideVanillaActionbar, config::setHideVanillaActionbar);
		addToggle(config, "nexomod.qol.hudCleaner.potionIcons",
				config::hideVanillaPotionIcons, config::setHideVanillaPotionIcons);
		addToggle(config, "nexomod.qol.hudCleaner.scoreboard",
				config::hideScoreboard, config::setHideScoreboard);
		addToggle(config, "nexomod.qol.hudCleaner.bossBars",
				config::hideBossBars, config::setHideBossBars);
		// Hiding is only half of what this screen controls now — whatever stays on
		// is draggable, and nothing else would tell you that.
		layout.addChild(new StringWidget(
				Component.translatable("nexomod.qol.hudCleaner.moveHint")
						.withStyle(style -> style.withColor(NexoStyle.TEXT_SECONDARY)),
				font));
		layout.addChild(Button.builder(Component.translatable("nexomod.qol.editLayout"),
						button -> minecraft.setScreen(new NexoHudEditorScreen(this)))
				.size(ROW_WIDTH, 20).build());

		finishLayout();
	}

	private void addToggle(NexoConfig config, String key,
			java.util.function.BooleanSupplier state, java.util.function.Consumer<Boolean> setter) {
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable(key),
				Component.translatable(key + ".description"),
				state,
				() -> {
					setter.accept(!state.getAsBoolean());
					// Rebuild rather than re-init: the same reason every other Nexo
					// config screen does it, see NexoComboConfigScreen.
					minecraft.setScreen(new NexoHudCleanerConfigScreen(parent));
				}));
	}
}
