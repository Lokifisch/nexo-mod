package dev.nexoclient.nexomod.screen;

import java.util.function.Consumer;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** One text field and a confirm button: add a skin by username or by URL. */
public class NexoWardrobeInputScreen extends NexoModalScreen {
	private final Component hint;
	private final Component confirm;
	private final int maxLength;
	private final Consumer<String> onConfirm;
	private EditBox box;
	private String value = "";

	public NexoWardrobeInputScreen(Screen parent, Component title, Component hint, Component confirm, int maxLength, Consumer<String> onConfirm) {
		super(title, parent);
		this.hint = hint;
		this.confirm = confirm;
		this.maxLength = maxLength;
		this.onConfirm = onConfirm;
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_PRIMARY).withBold(true)), font));
		layout.addChild(new MultiLineTextWidget(hint.copy().withStyle(style -> style.withColor(NexoStyle.MUTED)), font).setMaxWidth(240).setCentered(true));
		box = layout.addChild(new EditBox(font, 240, 20, title));
		box.setMaxLength(maxLength);
		box.setValue(value);
		box.setResponder(text -> value = text);
		LinearLayout buttons = layout.addChild(LinearLayout.horizontal().spacing(4));
		buttons.defaultCellSetting().paddingTop(8);
		buttons.addChild(NexoButton.builder(CommonComponents.GUI_CANCEL, this::onClose).size(118, 20).build());
		buttons.addChild(NexoButton.builder(confirm, this::submit).size(118, 20).build());
		finishLayout();
		setInitialFocus(box);
	}

	private void submit() {
		String text = value.strip();
		if (!text.isEmpty()) {
			minecraft.gui.setScreen(parent);
			onConfirm.accept(text);
		}
	}
}
