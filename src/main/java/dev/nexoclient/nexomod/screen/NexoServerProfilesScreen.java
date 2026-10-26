package dev.nexoclient.nexomod.screen;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.profiles.NexoServerProfiles;

/**
 * The profile index: the server you are on right now, and every host that has
 * a profile stored.
 *
 * <p>Adding is deliberately not a separate "new profile" form — the only host a
 * profile can sensibly be created for is the one you are connected to, so the
 * button that creates one is bound to the current address. A profile for a
 * server you have never joined would be a text field and a typo.
 */
public class NexoServerProfilesScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 300;
	private static final int ROW_HEIGHT = 20;
	private static final int DELETE_WIDTH = 24;

	public NexoServerProfilesScreen(Screen parent) {
		super(Component.translatable("nexomod.profiles.title"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(
				title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		String current = NexoServerProfiles.currentHost();
		if (current == null) {
			layout.addChild(new StringWidget(Component.translatable("nexomod.profiles.noServer"), font));
		} else {
			layout.addChild(new StringWidget(Component.translatable("nexomod.profiles.current", current), font));
			layout.addChild(Button.builder(Component.translatable("nexomod.profiles.editCurrent"), button -> {
				NexoServerProfiles.ensureProfile(current);
				minecraft.gui.setScreen(new NexoServerProfileEditScreen(this, current));
			}).size(ROW_WIDTH, ROW_HEIGHT).build());
		}

		List<String> hosts = NexoServerProfiles.hosts();
		layout.addChild(new StringWidget(hosts.isEmpty()
				? Component.translatable("nexomod.profiles.none")
				: Component.translatable("nexomod.profiles.saved"), font));

		for (String host : hosts) {
			LinearLayout row = layout.addChild(LinearLayout.horizontal().spacing(4));
			row.addChild(Button.builder(Component.literal(host), button ->
					minecraft.gui.setScreen(new NexoServerProfileEditScreen(this, host)))
					.size(ROW_WIDTH - DELETE_WIDTH - 4, ROW_HEIGHT).build());
			row.addChild(Button.builder(Component.literal("❌").withStyle(ChatFormatting.RED), button -> {
				NexoServerProfiles.removeProfile(host);
				minecraft.gui.setScreen(new NexoServerProfilesScreen(parent));
			}).size(DELETE_WIDTH, ROW_HEIGHT).build());
		}

		LinearLayout footer = layout.addChild(LinearLayout.horizontal().spacing(4));
		footer.defaultCellSetting().paddingTop(6);
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).build());

		finishLayout();
	}
}
