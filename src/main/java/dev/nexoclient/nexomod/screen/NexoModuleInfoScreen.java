package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The quick-settings page for a module with nothing to configure: the shell's
 * header switch and preview, and a line saying there is nothing else. Only
 * reached from the quick-settings sidebar — the menu card itself just toggles.
 */
public class NexoModuleInfoScreen extends NexoModalScreen {
	public NexoModuleInfoScreen(NexoModules.Module module, Screen parent) {
		super(module.name(), parent, module);
	}

	@Override
	protected void init() {
		super.init();
		layout.addChild(new StringWidget(Component.translatable("nexomod.shell.nothing")
				.withStyle(style -> style.withColor(NexoStyle.MUTED)), font));
		finishLayout();
	}
}
