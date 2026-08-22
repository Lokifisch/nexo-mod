package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A popup dialog drawn on top of whatever screen opened it, instead of
 * replacing it outright. The parent screen is redrawn first — still
 * visibly there, just dimmed behind an overlay — and this dialog's panel
 * is drawn over it with a soft highlight glow, so it reads as a modal
 * rather than a full screen change.
 *
 * <h2>Overflow</h2>
 *
 * <p>Subclasses fill {@link #layout} and finish with {@link #finishLayout()},
 * which decides whether the dialog fits. If it does, its widgets are registered
 * directly and nothing changes. If it does not — the Stats screen grows a row
 * per registered stat, the keystrokes screen one per bound key — the content is
 * wrapped in a {@link ScrollableLayout} capped to the window, so a dialog can
 * never run off the top and bottom of the screen no matter how much a subclass
 * puts in it.
 *
 * <p>The wrap is conditional rather than unconditional because
 * ScrollableLayout reserves a scrollbar gutter on both sides: applying it to
 * every dialog would widen the twelve that never needed it.
 */
public abstract class NexoModalScreen extends Screen {
	/** Margin left above and below the panel when its content has to be scrolled. */
	private static final int SCREEN_MARGIN = 24;
	/** Vertical padding the panel draws around its content — see {@link #extractRenderState}. */
	private static final int PANEL_PAD_Y = 14;

	protected final Screen parent;
	protected LinearLayout layout = LinearLayout.vertical().spacing(8);

	/** Non-null only when the content did not fit and had to be wrapped. */
	private ScrollableLayout scrollArea;
	/** Whatever is actually positioned and framed: {@link #layout}, or the scroll area wrapping it. */
	private Layout root;

	protected NexoModalScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	/**
	 * Starts every {@code init()} from an empty layout.
	 *
	 * <p>{@code init()} runs again on every window resize, and a {@link LinearLayout}
	 * has no way to remove what it holds — so a layout kept across runs accumulates
	 * a second copy of every row, and {@code visitWidgets} then registers the stale
	 * copies alongside the new ones. Every subclass calls {@code super.init()} before
	 * adding anything, so replacing the layout here is enough to keep all of them
	 * clean.
	 */
	@Override
	protected void init() {
		layout = LinearLayout.vertical().spacing(8);
		scrollArea = null;
		root = null;
	}

	/**
	 * Registers the finished dialog, scrolling it if it does not fit. Call this
	 * last in {@code init()}, in place of registering widgets by hand.
	 */
	protected void finishLayout() {
		// Measure first: the fit test needs a real height, and ScrollableLayout
		// derives its scroll range from the content's height — an unmeasured
		// content layout reports 0 and yields a negative range, which clamps the
		// scroll position to a negative value and pushes every row out of the
		// scissor. See NexoQolOverlayScreen.init() for the long version.
		layout.arrangeElements();

		if (layout.getHeight() <= maxContentHeight()) {
			root = layout;
			layout.visitWidgets(this::addRenderableWidget);
		} else {
			scrollArea = new ScrollableLayout(minecraft, layout, maxContentHeight());
			scrollArea.setMinWidth(layout.getWidth());
			root = scrollArea;
			scrollArea.visitWidgets(this::addRenderableWidget);
		}
		repositionElements();
	}

	/** Tallest the content may be before the panel would start leaving the screen. */
	private int maxContentHeight() {
		return Math.max(ROW_FLOOR, height - PANEL_PAD_Y * 2 - SCREEN_MARGIN * 2);
	}

	/** A dialog is never squeezed below this, however small the window is. */
	private static final int ROW_FLOOR = 60;

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (parent != null) {
			// -1,-1 so the parent's own widgets don't render a stale hover highlight.
			parent.extractBackground(graphics, -1, -1, partialTick);
			parent.extractRenderState(graphics, -1, -1, partialTick);
		}
		graphics.fill(0, 0, width, height, 0xB2000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Layout framed = root != null ? root : layout;
		int x0 = framed.getX() - 20;
		int y0 = framed.getY() - PANEL_PAD_Y;
		int x1 = framed.getX() + framed.getWidth() + 20;
		int y1 = framed.getY() + framed.getHeight() + PANEL_PAD_Y;
		NexoPanelRenderer.draw(graphics, x0, y0, x1, y1);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	protected void repositionElements() {
		if (root == null) {
			return;
		}
		if (scrollArea != null) {
			scrollArea.setMaxHeight(maxContentHeight());
		}
		root.arrangeElements();
		FrameLayout.centerInRectangle(root, getRectangle());
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
