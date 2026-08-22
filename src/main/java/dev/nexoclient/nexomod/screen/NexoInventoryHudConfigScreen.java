package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** On/off only — the grid's position and size are dragged in the layout editor. */
public class NexoInventoryHudConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoInventoryHudConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.inventoryHud"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.inventoryHud.description"),
				config::inventoryHudEnabled,
				() -> {
					config.setInventoryHudEnabled(!config.inventoryHudEnabled());
					minecraft.setScreen(new NexoInventoryHudConfigScreen(parent));
				}));
		if (config.inventoryHudEnabled()) {
			layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
					Component.translatable("nexomod.qol.inventoryHud.background"),
					Component.translatable("nexomod.qol.inventoryHud.background.description"),
					config::inventoryHudBackgroundEnabled,
					() -> {
						config.setInventoryHudBackgroundEnabled(!config.inventoryHudBackgroundEnabled());
						minecraft.setScreen(new NexoInventoryHudConfigScreen(parent));
					}));
		}
		layout.addChild(Button.builder(Component.translatable("nexomod.qol.editLayout"),
						button -> minecraft.setScreen(new NexoHudEditorScreen(this)))
				.size(ROW_WIDTH, 20).build());

		finishLayout();
	}
}
