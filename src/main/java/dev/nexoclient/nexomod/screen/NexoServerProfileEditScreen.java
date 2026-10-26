package dev.nexoclient.nexomod.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.profiles.NexoServerProfiles;

/**
 * One host's overrides: a row per module, and a button that cycles
 * leave-alone → force on → force off.
 *
 * <p>Three states rather than a checkbox is the whole point of
 * {@link NexoServerProfiles}: a checkbox has to be set for every module, which
 * turns a profile into a full snapshot that goes stale the moment the global
 * config changes. "Leave alone" is the default and the way back.
 */
public class NexoServerProfileEditScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 320;
	private static final int ROW_HEIGHT = 20;
	private static final int STATE_WIDTH = 110;

	private final String host;

	public NexoServerProfileEditScreen(Screen parent, String host) {
		super(Component.translatable("nexomod.profiles.edit", host), parent);
		this.host = host;
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(
				title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));
		layout.addChild(new StringWidget(Component.translatable("nexomod.profiles.edit.hint"), font));

		for (NexoServerProfiles.Module module : NexoServerProfiles.modules()) {
			LinearLayout row = layout.addChild(LinearLayout.horizontal().spacing(4));
			row.addChild(new StringWidget(module.label(), font));
			Button state = Button.builder(label(module.id()), button -> {
				Boolean current = NexoServerProfiles.state(host, module.id());
				Boolean next = current == null ? Boolean.TRUE : current ? Boolean.FALSE : null;
				NexoServerProfiles.setState(host, module.id(), next);
				button.setMessage(label(module.id()));
			}).size(STATE_WIDTH, ROW_HEIGHT).build();
			row.addChild(state);
		}

		LinearLayout footer = layout.addChild(LinearLayout.horizontal().spacing(4));
		footer.defaultCellSetting().paddingTop(6);
		footer.addChild(Button.builder(Component.translatable("nexomod.profiles.delete").withStyle(ChatFormatting.RED),
				button -> {
					NexoServerProfiles.removeProfile(host);
					minecraft.gui.setScreen(new NexoServerProfilesScreen(parent));
				}).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).build());

		finishLayout();
	}

	private Component label(String moduleId) {
		Boolean state = NexoServerProfiles.state(host, moduleId);
		if (state == null) {
			return Component.translatable("nexomod.profiles.state.inherit")
					.withStyle(style -> style.withColor(NexoStyle.TEXT_SECONDARY));
		}
		return state
				? Component.translatable("nexomod.profiles.state.on")
						.withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT))
				: Component.translatable("nexomod.profiles.state.off")
						.withStyle(style -> style.withColor(ChatFormatting.RED));
	}
}
