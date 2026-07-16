package dev.nexoclient.nexomod.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * A title + scrollable {@link NexoOptionList} + footer screen, built on
 * vanilla's own {@code OptionsSubScreen} rather than a hand-designed panel.
 * Ported from CommandKeys' {@code OptionScreen} (see THIRD-PARTY-NOTICES.md)
 * — deliberately plain vanilla widgets throughout (no Nexo-specific chrome),
 * since the mod's global mixins (button-sprite and menu-background) already
 * reskin every vanilla {@code Screen}/{@code AbstractButton} automatically.
 */
public class NexoOptionScreen extends OptionsSubScreen {
	public static final int HEADER_MARGIN = 32;
	public static final int FOOTER_MARGIN = 32;
	public static final int BASE_ROW_WIDTH = Window.BASE_WIDTH;
	public static final int SCROLL_BAR_MARGIN = 20;
	public static final int ELEMENT_SPACING = 4;
	public static final int ELEMENT_SPACING_NARROW = 2;
	public static final int LIST_ENTRY_HEIGHT = 20;
	public static final int LIST_ENTRY_SPACING = 5;
	public static final int HANGING_WIDGET_MARGIN = LIST_ENTRY_HEIGHT + ELEMENT_SPACING;
	public static final int BASE_LIST_ENTRY_WIDTH = BASE_ROW_WIDTH - (SCROLL_BAR_MARGIN * 2) - (HANGING_WIDGET_MARGIN * 2);

	protected NexoOptionList list;

	public NexoOptionScreen(Screen lastScreen, Component title, NexoOptionList list) {
		super(lastScreen, Minecraft.getInstance().options, title);
		this.list = list;
		this.list.setScreen(this);
	}

	@Override
	protected void init() {
		clearWidgets();
		clearFocus();
		addTitle();
		addContents();
		addFooter();
		setInitialFocus();
	}

	@Override
	public void resize(int width, int height) {
		this.width = width;
		this.height = height;
		init();
	}

	@Override
	protected void addTitle() {
		Font font = Minecraft.getInstance().font;
		int w = font.width(title);
		int h = font.lineHeight;
		int x = (width / 2) - (w / 2);
		int y = Math.max(0, (HEADER_MARGIN / 2) - (h / 2));
		addRenderableWidget(new StringWidget(x, y, w, h, title, font));
	}

	@Override
	protected void addContents() {
		list.updateSizeAndPosition(width, height - HEADER_MARGIN - FOOTER_MARGIN, HEADER_MARGIN);
		addRenderableWidget(list);
	}

	@Override
	protected void addFooter() {
		int w = BASE_LIST_ENTRY_WIDTH;
		int h = LIST_ENTRY_HEIGHT;
		int x = (width / 2) - (w / 2);
		int y = Math.min(height - h, height - (FOOTER_MARGIN / 2) - (h / 2));
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).pos(x, y).size(w, h).build());
	}

	@Override
	protected void addOptions() {
		// Unused: addContents() is overridden and doesn't call this.
	}

	@Override
	public void onClose() {
		if (lastScreen instanceof NexoOptionScreen screen) {
			screen.resize(width, height);
		}
		super.onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (list.keyPressed(InputConstants.getKey(event))) {
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (list.keyReleased(InputConstants.getKey(event))) {
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (list.mouseClicked(InputConstants.Type.MOUSE.getOrCreate(event.button()))) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (list.mouseReleased(InputConstants.Type.MOUSE.getOrCreate(event.button()))) {
			return true;
		}
		return super.mouseReleased(event);
	}
}
