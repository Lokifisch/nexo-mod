package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.auth.AccountStore;
import dev.nexoclient.nexomod.auth.MinecraftAccount;
import dev.nexoclient.nexomod.auth.SessionSwap;

/** Lets you play under a plain username with no Microsoft account — no online multiplayer that requires one. */
public class OfflineLoginScreen extends NexoModalScreen {
	private static String lastUsername = "";

	private EditBox usernameBox;

	public OfflineLoginScreen(Screen parent) {
		super(Component.translatable("nexomod.login.offline.title"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		usernameBox = layout.addChild(new EditBox(font, 200, 20, Component.translatable("nexomod.login.offline.username")));
		usernameBox.setMaxLength(16);
		usernameBox.setValue(lastUsername);
		usernameBox.setHint(Component.translatable("nexomod.login.offline.username"));

		LinearLayout buttonRow = layout.addChild(LinearLayout.horizontal().spacing(4));
		buttonRow.defaultCellSetting().paddingTop(12);
		buttonRow.addChild(NexoButton.builder(Component.translatable("nexomod.login.offline.play"), this::play).build());
		buttonRow.addChild(NexoButton.builder(CommonComponents.GUI_CANCEL, () -> minecraft.setScreen(parent)).build());

		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
		setInitialFocus(usernameBox);
	}

	private void play() {
		String username = usernameBox.getValue().trim();
		if (username.isEmpty()) {
			return;
		}
		lastUsername = username;
		MinecraftAccount account = MinecraftAccount.offline(username);
		AccountStore.get().upsertAndActivate(account);
		SessionSwap.activate(account);
		minecraft.setScreen(parent);
	}
}
