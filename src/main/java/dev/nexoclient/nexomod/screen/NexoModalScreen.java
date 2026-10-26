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

	/** The ‹ in the panel's top-left corner, back to {@link #parent} — placed by repositionElements. */
	private NexoButton backButton;
	/** Fits inside the panel's top padding, so it never overlaps the content. */
	private static final int BACK_SIZE = 12;

	/** Non-null only when the content did not fit and had to be wrapped. */
	private ScrollableLayout scrollArea;
	/** Whatever is actually positioned and framed: {@link #layout}, or the scroll area wrapping it. */
	private Layout root;

	/** Set only by {@link NexoModuleInfoScreen}; every other screen is matched to its module by title. */
	private final NexoModules.Module explicitModule;
	/** Non-null while this screen shows in the wide quick-settings layout. */
	private NexoModuleShell shell;

	protected NexoModalScreen(Component title, Screen parent) {
		this(title, parent, null);
	}

	NexoModalScreen(Component title, Screen parent, NexoModules.Module module) {
		super(title);
		this.parent = parent;
		this.explicitModule = module;
	}

	/** Adds a small heading over the rows that follow it. */
	protected void section(String translationKey) {
		layout.addChild(new NexoSectionHeading(Component.translatable(translationKey)));
	}

	/**
	 * The module this screen configures, when it was opened from the
	 * right-shift menu — the case that gets the wide layout. Opened from
	 * anywhere else it stays a centred dialog.
	 */
	private NexoModules.Module shellModule() {
		if (!(parent instanceof NexoQolOverlayScreen)) {
			return null;
		}
		return explicitModule != null ? explicitModule : NexoModules.find(title);
	}

	public boolean inShell() {
		return shell != null;
	}

	<T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable
			& net.minecraft.client.gui.narration.NarratableEntry> T shellWidget(T widget) {
		return addRenderableWidget(widget);
	}

	void shellRebuild() {
		rebuildWidgets();
	}

	void shellFocus(net.minecraft.client.gui.components.events.GuiEventListener widget) {
		setFocused(widget);
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
		NexoModules.Module module = shellModule();
		shell = module != null ? new NexoModuleShell(this, module, parent) : null;
		if (shell != null) {
			// Rows sit edge to edge with a divider between them, like a list.
			layout = LinearLayout.vertical().spacing(2);
			shell.layout(width, height);
		}
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
		if (shell != null) {
			shell.prepare(layout);
		}
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
		if (shell != null) {
			// The MOD MENU tab is the way back; no "<" in the corner.
			shell.addWidgets();
			repositionElements();
			return;
		}
		backButton = addRenderableWidget(NexoButton.builder(Component.literal("<"), this::onClose)
				.size(BACK_SIZE, BACK_SIZE).build());
		// The tooltip says "Back" for anyone hovering it — and for the narrator,
		// which reads tooltips — since "<" alone means little.
		backButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(net.minecraft.network.chat.CommonComponents.GUI_BACK));
		repositionElements();
	}

	/** Tallest the content may be before the panel would start leaving the screen. */
	private int maxContentHeight() {
		if (shell != null) {
			return shell.contentHeight();
		}
		return Math.max(ROW_FLOOR, height - PANEL_PAD_Y * 2 - SCREEN_MARGIN * 2);
	}

	/** A dialog is never squeezed below this, however small the window is. */
	private static final int ROW_FLOOR = 60;

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (shell != null) {
			// Like the menu it came from: the blurred game, not the menu redrawn behind.
			super.extractBackground(graphics, mouseX, mouseY, partialTick);
			return;
		}
		if (parent != null) {
			// -1,-1 so the parent's own widgets don't render a stale hover highlight.
			parent.extractBackground(graphics, -1, -1, partialTick);
			parent.extractRenderState(graphics, -1, -1, partialTick);
		}
		graphics.fill(0, 0, width, height, 0xB2000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (shell != null) {
			shell.draw(graphics, mouseX, mouseY);
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			return;
		}
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
		if (shell != null) {
			root.setPosition(shell.contentX0() + 10, shell.contentTop());
			return;
		}
		FrameLayout.centerInRectangle(root, getRectangle());
		if (backButton != null) {
			backButton.setPosition(root.getX() - 20 + 4, root.getY() - PANEL_PAD_Y + 1);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (shell != null && shell.mouseScrolled(mouseX, mouseY, scrollY)) {
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
