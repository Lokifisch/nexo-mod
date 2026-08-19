package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Combo counter has nothing to configure beyond on/off — position and size are dragged in the layout editor. */
public class NexoComboConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoComboConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.combo"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.combo.description"),
				config::comboCounterEnabled,
				() -> {
					config.setComboCounterEnabled(!config.comboCounterEnabled());
					minecraft.setScreen(new NexoComboConfigScreen(parent));
				}));
		layout.addChild(Button.builder(Component.translatable("nexomod.qol.editLayout"),
						button -> minecraft.setScreen(new NexoHudEditorScreen(this)))
				.size(ROW_WIDTH, 20).build());

		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}
}
