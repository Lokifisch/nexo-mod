package dev.nexoclient.nexomod.screen;

import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * A {@link NexoOptionList} of simple one-widget settings rows. The row builder
 * runs again on every (re)init — resize, or an explicit {@link #rebuildRows()}
 * after one setting changes what another row should display — so rows are
 * always constructed from the live {@link NexoConfig} values.
 */
public class NexoSettingsOptionList extends NexoOptionList {
	private final Consumer<NexoSettingsOptionList> rowBuilder;

	public NexoSettingsOptionList(Consumer<NexoSettingsOptionList> rowBuilder) {
		super(Minecraft.getInstance(), 0, 0, NexoOptionScreen.HEADER_MARGIN, NexoOptionScreen.BASE_LIST_ENTRY_WIDTH,
				NexoOptionScreen.LIST_ENTRY_HEIGHT, NexoOptionScreen.LIST_ENTRY_SPACING);
		this.rowBuilder = rowBuilder;
	}

	@Override
	protected void addEntries() {
		rowBuilder.accept(this);
	}

	/** Where a row widget should sit: {@link #rowX()}/{@link #rowWidth()}/{@link #rowHeight()} for x/width/height, y is managed per-frame. */
	public int rowX() {
		return entryX;
	}

	public int rowWidth() {
		return entryWidth;
	}

	public int rowHeight() {
		return entryHeight;
	}

	public void addWidgetRow(AbstractWidget widget) {
		addEntry(new WidgetEntry(widget));
	}

	public void rebuildRows() {
		init();
	}

	@Override
	public boolean keyPressed(InputConstants.Key key) {
		return false;
	}

	@Override
	public boolean keyReleased(InputConstants.Key key) {
		return false;
	}

	@Override
	public boolean mouseClicked(InputConstants.Key key) {
		return false;
	}

	@Override
	public boolean mouseReleased(InputConstants.Key key) {
		return false;
	}

	private static class WidgetEntry extends Entry {
		WidgetEntry(AbstractWidget widget) {
			elements.add(widget);
		}
	}
}
