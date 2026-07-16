package dev.nexoclient.nexomod.screen;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractStringWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Tightly coupled to {@link NexoOptionScreen}, the way vanilla's own
 * {@code OptionsList} is tightly coupled to {@code OptionsSubScreen}. Holds
 * {@link Entry} objects drawn top-down, one {@link #entryHeight} slot each.
 * Ported from CommandKeys' {@code OptionList} (see THIRD-PARTY-NOTICES.md);
 * its custom-sprite icon-button entries weren't ported (no assets for
 * them here) — rows use plain vanilla {@link Button}s instead, which the
 * mod's own button-reskin mixin already re-styles.
 */
public abstract class NexoOptionList extends ContainerObjectSelectionList<NexoOptionList.Entry> {
	protected NexoOptionScreen screen;

	protected final Minecraft mc;
	protected final int entryWidth;
	protected final int entryHeight;
	protected final int entrySpacing;

	protected int rowWidth;
	protected int dynWideEntryWidth;
	protected int dynEntryWidth;
	protected int entryX;
	protected int dynWideEntryX;
	protected int dynEntryX;
	protected int smallWidgetWidth;

	public NexoOptionList(Minecraft mc, int width, int height, int y, int entryWidth, int entryHeight, int entrySpacing) {
		super(mc, width, height, y, entryHeight + entrySpacing);
		this.mc = mc;
		this.entryWidth = entryWidth;
		this.entryHeight = entryHeight;
		this.entrySpacing = entrySpacing;
		updateElementBounds();
	}

	protected void updateElementBounds() {
		this.dynWideEntryWidth = Math.max(entryWidth, (int) (width / 100F * 75F));
		this.dynEntryWidth = Math.max(entryWidth, (int) (width / 100F * 50F));
		this.entryX = width / 2 - (entryWidth / 2);
		this.dynWideEntryX = width / 2 - (dynWideEntryWidth / 2);
		this.dynEntryX = width / 2 - (dynEntryWidth / 2);
		this.rowWidth = Math.max(entryWidth, dynWideEntryWidth) + (NexoOptionScreen.SCROLL_BAR_MARGIN * 2)
				+ (NexoOptionScreen.HANGING_WIDGET_MARGIN * 2);
		this.smallWidgetWidth = Math.max(16, entryHeight);
	}

	protected void init() {
		double scrollAmount = scrollAmount();
		clearEntries();
		setFocused(null);
		addEntries();
		setScrollAmount(scrollAmount);
	}

	public void setScreen(NexoOptionScreen screen) {
		this.screen = screen;
	}

	public void addEntry(int index, Entry entry) {
		children().add(index, entry);
	}

	protected abstract void addEntries();

	@Override
	public void updateSizeAndPosition(int width, int height, int y) {
		super.updateSizeAndPosition(width, height, y);
		updateElementBounds();
		init();
	}

	@Override
	public int getRowWidth() {
		return rowWidth;
	}

	@Override
	protected int scrollBarX() {
		return width / 2 + rowWidth / 2;
	}

	public abstract boolean keyPressed(InputConstants.Key key);

	public abstract boolean keyReleased(InputConstants.Key key);

	public abstract boolean mouseClicked(InputConstants.Key key);

	public abstract boolean mouseReleased(InputConstants.Key key);

	/** Base implementation of {@link Entry}, with common row kinds. */
	public abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
		public static final int SPACE = NexoOptionScreen.ELEMENT_SPACING;
		public static final int SPACE_SMALL = NexoOptionScreen.ELEMENT_SPACING_NARROW;

		public final List<AbstractWidget> elements = new ArrayList<>();

		@Override
		public List<? extends GuiEventListener> children() {
			return elements;
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return elements;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			elements.forEach(button -> {
				button.setY(getContentY());
				button.extractRenderState(graphics, mouseX, mouseY, tickDelta);
			});
		}

		public static class Text extends Entry {
			public Text(int x, int width, int height, Component message, Tooltip tooltip, int tooltipDelay) {
				AbstractStringWidget widget;
				int widgetWidth = Minecraft.getInstance().font.width(message.getString());
				if (widgetWidth <= width) {
					widget = new StringWidget(x + (width / 2) - (widgetWidth / 2), 0, widgetWidth, height, message, Minecraft.getInstance().font);
				} else {
					widget = new MultiLineTextWidget(x, 0, message, Minecraft.getInstance().font).setMaxWidth(width).setCentered(true);
				}
				if (tooltip != null) {
					widget.setTooltip(tooltip);
				}
				if (tooltipDelay >= 0) {
					widget.setTooltipDelay(Duration.ofMillis(tooltipDelay));
				}
				elements.add(widget);
			}
		}

		public static class ActionButton extends Entry {
			private final Button button;

			public ActionButton(int x, int width, int height, Component message, Tooltip tooltip, int tooltipDelay, Button.OnPress onPress) {
				button = Button.builder(message, onPress).pos(x, 0).size(width, height).build();
				if (tooltip != null) {
					button.setTooltip(tooltip);
				}
				if (tooltipDelay >= 0) {
					button.setTooltipDelay(Duration.ofMillis(tooltipDelay));
				}
				elements.add(button);
			}

			public void setBounds(int x, int width, int height) {
				button.setPosition(x, 0);
				button.setSize(width, height);
			}
		}

		/**
		 * {@code AbstractSelectionList} only supports fixed-height entries; this
		 * invisible filler defers everything to the given {@link Entry} so that
		 * entry can visually span multiple row slots.
		 */
		public static class Space extends Entry {
			private final Entry entry;

			public Space(Entry entry) {
				this.entry = entry;
			}

			@Override
			public boolean isDragging() {
				return entry.isDragging();
			}

			@Override
			public void setDragging(boolean dragging) {
				entry.setDragging(dragging);
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				return entry.mouseClicked(event, doubleClick);
			}

			@Override
			public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
				return entry.mouseDragged(event, deltaX, deltaY);
			}

			public void setFocused(GuiEventListener listener) {
				entry.setFocused(listener);
			}

			public GuiEventListener getFocused() {
				return entry.getFocused();
			}

			public ComponentPath focusPathAtIndex(FocusNavigationEvent event, int i) {
				if (entry.children().isEmpty()) {
					return null;
				}
				ComponentPath path = entry.children().get(Math.min(i, entry.children().size() - 1)).nextFocusPath(event);
				return ComponentPath.path(entry, path);
			}
		}
	}
}
