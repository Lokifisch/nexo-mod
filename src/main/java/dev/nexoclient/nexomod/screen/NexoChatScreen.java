package dev.nexoclient.nexomod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.chat.NexoChatFilter;
import dev.nexoclient.nexomod.chat.NexoChatHistory;

/**
 * The Chat category: the two switches, and the way in to the archive and the
 * pattern list.
 *
 * <p>Both switches stay enabled when the native library is missing, and a line
 * at the top says so instead of the rows vanishing. Hiding them would make the
 * settings screen look different on Windows than on Linux for no reason the
 * player can see, and the settings are still worth persisting — they take effect
 * the moment a build with a library for that platform is installed.
 */
public class NexoChatScreen extends NexoOptionScreen {
	public NexoChatScreen(Screen lastScreen) {
		super(lastScreen, Component.translatable("nexomod.settings.chat.title"),
				new NexoSettingsOptionList(NexoChatScreen::addRows));
	}

	private static void addRows(NexoSettingsOptionList list) {
		NexoConfig config = NexoConfig.get();
		Minecraft client = Minecraft.getInstance();

		if (!NexoChatHistory.isAvailable()) {
			list.addWidgetRow(new StringWidget(list.rowX(), 0, list.rowWidth(), list.rowHeight(),
					Component.translatable("nexomod.chat.unavailable").withStyle(ChatFormatting.GRAY), client.font));
		}

		list.addWidgetRow(CycleButton.onOffBuilder(config.chatHistoryEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.chat.history.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(),
						Component.translatable("nexomod.settings.chat.history"),
						(button, value) -> config.setChatHistoryEnabled(value)));

		list.addWidgetRow(CycleButton.onOffBuilder(config.chatFilterEnabled())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.settings.chat.filter.tooltip")))
				.create(list.rowX(), 0, list.rowWidth(), list.rowHeight(),
						Component.translatable("nexomod.settings.chat.filter"),
						(button, value) -> {
							config.setChatFilterEnabled(value);
							// The compiled handle is only rebuilt when the
							// pattern list is saved; without this, flipping the
							// switch would appear to do nothing until the next
							// pattern edit.
							NexoChatFilter.get().rebuild();
						}));

		// Minecraft.screen is this screen: these rows are built from init(),
		// which vanilla only calls once the screen is the current one. Passing
		// it as the sub-screen's lastScreen is what makes Done come back here.
		list.addWidgetRow(Button.builder(Component.translatable("nexomod.settings.chat.patterns"),
						button -> client.setScreen(new NexoChatFilterScreen(client.screen)))
				.pos(list.rowX(), 0).size(list.rowWidth(), list.rowHeight()).build());

		list.addWidgetRow(Button.builder(Component.translatable("nexomod.settings.chat.search"),
						button -> client.setScreen(new NexoChatSearchScreen(client.screen)))
				.pos(list.rowX(), 0).size(list.rowWidth(), list.rowHeight()).build());
	}
}
