package dev.nexoclient.nexomod.screen;

import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Shown while an auth flow is in progress: just a status line and a cancel
 * button. Defaults to browser-sign-in wording; the other constructor reuses
 * it for anything else that waits on the network (e.g. the token refresh
 * when switching to a saved account).
 */
public class SigningInScreen extends NexoModalScreen {
	private final AtomicBoolean cancelled;
	private final Component status;

	public SigningInScreen(Screen parent, AtomicBoolean cancelled) {
		this(parent, cancelled, Component.translatable("nexomod.login.title"),
				Component.translatable("nexomod.login.waiting"));
	}

	public SigningInScreen(Screen parent, AtomicBoolean cancelled, Component title, Component status) {
		super(title, parent);
		this.cancelled = cancelled;
		this.status = status;
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));
		layout.addChild(new StringWidget(
				status.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_SECONDARY)), font));

		LinearLayout buttonRow = layout.addChild(LinearLayout.horizontal().spacing(4));
		buttonRow.defaultCellSetting().paddingTop(12);
		buttonRow.addChild(NexoButton.builder(CommonComponents.GUI_CANCEL, this::cancelAndClose).build());

		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	private void cancelAndClose() {
		cancelled.set(true);
		minecraft.setScreen(parent);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void onClose() {
		cancelAndClose();
	}
}
