package dev.nexoclient.nexomod.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.hud.NexoArmorHud;
import dev.nexoclient.nexomod.hud.NexoComboCounter;
import dev.nexoclient.nexomod.hud.NexoCpsCounter;
import dev.nexoclient.nexomod.hud.NexoFadingLogHud;
import dev.nexoclient.nexomod.hud.NexoHudLayout;
import dev.nexoclient.nexomod.hud.NexoInventoryHud;
import dev.nexoclient.nexomod.hud.NexoKeystrokesHud;
import dev.nexoclient.nexomod.hud.NexoMiniPlayerHud;
import dev.nexoclient.nexomod.hud.NexoPotionHud;
import dev.nexoclient.nexomod.hud.NexoStatsHud;
import dev.nexoclient.nexomod.hud.NexoVanillaHud;

/**
 * Drag each Nexo HUD element to move it, drag its bottom-right grip to
 * resize it — the same "grab your actual HUD and rearrange it" pattern most
 * other clients use for their overlay elements.
 *
 * <p>Every element already reads its position through {@code resolveBounds},
 * consulting the same {@link NexoHudLayout} this screen writes to — so
 * there is nothing to fake here. Dragging the outline drawn over the real
 * (potentially disabled) element updates the shared layout store directly,
 * and if the element is currently enabled it moves live, in the same frame.
 *
 * <p>{@code isInGameUi()} is true — a pure transparent overlay, not even
 * vanilla's blur, since precisely aligning something against the hotbar or
 * crosshair needs the crisp real game, not a blurred approximation of it.
 * On {@link NeonMenuBackgroundMixin}'s deny-list for the same reason
 * {@code NexoQolOverlayScreen} is: nothing may replace what's being lined
 * up against.
 */
public class NexoHudEditorScreen extends Screen {
	private static final int RESIZE_GRIP = 8;

	private record Draggable(NexoHudLayout.Element element, Component label,
			BiFunction<Integer, Integer, ScreenRectangle> bounds) {
	}

	/**
	 * Everything Nexo draws itself. Vanilla's own movable pieces are appended by
	 * {@link #elements()} rather than listed here, since which of them are
	 * draggable depends on which are currently visible.
	 */
	private static final List<Draggable> NEXO_ELEMENTS = List.of(
			new Draggable(NexoHudLayout.Element.KEYSTROKES, Component.translatable("nexomod.qol.keystrokes"),
					NexoKeystrokesHud::resolveBounds),
			new Draggable(NexoHudLayout.Element.CPS, Component.translatable("nexomod.qol.cps"),
					NexoCpsCounter::resolveBounds),
			new Draggable(NexoHudLayout.Element.ARMOR, Component.translatable("nexomod.settings.armorHud.enabled"),
					NexoArmorHud::resolveBounds),
			new Draggable(NexoHudLayout.Element.STATS, Component.translatable("nexomod.stats.title"),
					NexoStatsHud::resolveBounds),
			new Draggable(NexoHudLayout.Element.POTION, Component.translatable("nexomod.qol.potion"),
					NexoPotionHud::resolveBounds),
			new Draggable(NexoHudLayout.Element.COMBO, Component.translatable("nexomod.qol.combo"),
					NexoComboCounter::resolveBounds),
			new Draggable(NexoHudLayout.Element.ACTIONBAR_LOG, Component.translatable("nexomod.qol.actionbarLog"),
					NexoFadingLogHud.ACTIONBAR::resolveBounds),
			new Draggable(NexoHudLayout.Element.PICKUP_LOG, Component.translatable("nexomod.qol.pickupLog"),
					NexoFadingLogHud.PICKUPS::resolveBounds),
			new Draggable(NexoHudLayout.Element.SECOND_CHAT, Component.translatable("nexomod.chat.second"),
					dev.nexoclient.nexomod.hud.NexoSecondChatHud::resolveBounds),
			new Draggable(NexoHudLayout.Element.INVENTORY, Component.translatable("nexomod.qol.inventoryHud"),
					NexoInventoryHud::resolveBounds),
			new Draggable(NexoHudLayout.Element.MINI_PLAYER, Component.translatable("nexomod.qol.miniPlayer"),
					NexoMiniPlayerHud::resolveBounds));

	/**
	 * Nexo's own elements plus every vanilla piece that is currently on screen.
	 * A hidden vanilla element is left out on purpose: dragging an outline around
	 * for something the HUD Cleaner has switched off would move a thing nobody can
	 * see, and the empty box would read as a bug.
	 */
	private static List<Draggable> elements() {
		List<Draggable> all = new ArrayList<>(NEXO_ELEMENTS);
		for (NexoVanillaHud.Entry entry : NexoVanillaHud.ENTRIES) {
			if (entry.hidden().getAsBoolean()) {
				continue;
			}
			all.add(new Draggable(entry.slot(), entry.label(), entry::resolveBounds));
		}
		return all;
	}

	private final Screen parent;

	private NexoHudLayout.Element dragging;
	private boolean resizingDrag;
	private double dragStartMouseX;
	private double dragStartMouseY;
	private int dragStartX;
	private int dragStartY;
	private float dragStartScale;

	public NexoHudEditorScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.editLayout"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// Reset and Done sit in a small titled panel at the bottom centre — see
		// drawPanel — rather than as two bare buttons on the HUD.
		addRenderableWidget(Button.builder(Component.translatable("nexomod.qol.resetLayout"),
						button -> NexoHudLayout.get().resetAll())
				.pos(width / 2 - PANEL_HALF_WIDTH + 10, height - 32).size(PANEL_HALF_WIDTH - 14, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.pos(width / 2 + 4, height - 32).size(PANEL_HALF_WIDTH - 14, 20).build());
	}

	/** Half the bottom panel's width; its two buttons split it. */
	private static final int PANEL_HALF_WIDTH = 130;
	private static final int GRID = 20;

	/** A faint grid over the (blurred) world, so moving a box reads as placing it on something. */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		for (int x = GRID; x < width; x += GRID) {
			graphics.fill(x, 0, x + 1, height, 0x0FFFFFFF);
		}
		for (int y = GRID; y < height; y += GRID) {
			graphics.fill(0, y, width, y + 1, 0x0FFFFFFF);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// Boxes, then the panel over any box that reaches it, then the panel's
		// buttons on top of both — so Reset and Done are never covered.
		for (Draggable d : elements()) {
			ScreenRectangle bounds = d.bounds().apply(graphics.guiWidth(), graphics.guiHeight());
			// The box under the cursor or being dragged is the one that lights up;
			// the rest are dashed placeholders.
			boolean lit = d.element() == dragging || (dragging == null && bounds.containsPoint(mouseX, mouseY));
			if (lit) {
				NexoShapes.glow(graphics, bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), NexoStyle.ACCENT, 3, 0.2F);
			}
			graphics.fill(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), 0x700A0A0E);
			int color = lit ? NexoStyle.ACCENT : 0x80FFFFFF;
			if (lit) {
				drawOutline(graphics, bounds, color);
			} else {
				drawDashed(graphics, bounds, color);
			}
			graphics.fill(bounds.right() - RESIZE_GRIP / 2, bounds.bottom() - RESIZE_GRIP / 2, bounds.right(), bounds.bottom(), color);
			Component label = d.label().copy().withStyle(style -> style.withBold(true));
			graphics.text(font, label, bounds.left(), bounds.top() - font.lineHeight - 1,
					lit ? NexoStyle.ACCENT : NexoStyle.TEXT_SECONDARY);
		}
		drawPanel(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private boolean overPanel(double mx, double my) {
		return mx >= width / 2.0 - PANEL_HALF_WIDTH && mx < width / 2.0 + PANEL_HALF_WIDTH
				&& my >= height - 52 && my < height - 6;
	}

	/** The bottom panel behind Reset and Done, with the editor's name and a one-line hint. */
	private void drawPanel(GuiGraphicsExtractor graphics) {
		int x0 = width / 2 - PANEL_HALF_WIDTH;
		int x1 = width / 2 + PANEL_HALF_WIDTH;
		int y0 = height - 52;
		NexoPanelRenderer.draw(graphics, x0, y0, x1, height - 6);
		graphics.text(font, Component.translatable("nexomod.hudEditor.title").copy().withStyle(style -> style.withBold(true)),
				x0 + 10, y0 + 6, NexoStyle.TEXT_PRIMARY);
		Component hint = Component.translatable("nexomod.hudEditor.hint");
		graphics.text(font, hint, x1 - 10 - font.width(hint), y0 + 6, NexoStyle.MUTED);
	}

	private void drawOutline(GuiGraphicsExtractor graphics, ScreenRectangle bounds, int color) {
		graphics.fill(bounds.left(), bounds.top(), bounds.right(), bounds.top() + 1, color);
		graphics.fill(bounds.left(), bounds.bottom() - 1, bounds.right(), bounds.bottom(), color);
		graphics.fill(bounds.left(), bounds.top(), bounds.left() + 1, bounds.bottom(), color);
		graphics.fill(bounds.right() - 1, bounds.top(), bounds.right(), bounds.bottom(), color);
	}

	/** The same rectangle as {@link #drawOutline}, in 3-on-2-off dashes. */
	private void drawDashed(GuiGraphicsExtractor graphics, ScreenRectangle bounds, int color) {
		for (int x = bounds.left(); x < bounds.right(); x += 5) {
			int x1 = Math.min(x + 3, bounds.right());
			graphics.fill(x, bounds.top(), x1, bounds.top() + 1, color);
			graphics.fill(x, bounds.bottom() - 1, x1, bounds.bottom(), color);
		}
		for (int y = bounds.top(); y < bounds.bottom(); y += 5) {
			int y1 = Math.min(y + 3, bounds.bottom());
			graphics.fill(bounds.left(), y, bounds.left() + 1, y1, color);
			graphics.fill(bounds.right() - 1, y, bounds.right(), y1, color);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// The bottom panel is drawn over the boxes, so it takes clicks first: a
		// box hidden under Done must not start a drag when Done is clicked.
		if (overPanel(event.x(), event.y())) {
			return super.mouseClicked(event, doubleClick);
		}
		if (event.button() == 0) {
			for (Draggable d : elements()) {
				ScreenRectangle bounds = d.bounds().apply(width, height);
				int mx = (int) event.x();
				int my = (int) event.y();
				boolean onGrip = mx >= bounds.right() - RESIZE_GRIP && mx <= bounds.right()
						&& my >= bounds.bottom() - RESIZE_GRIP && my <= bounds.bottom();
				if (onGrip || bounds.containsPoint(mx, my)) {
					startDrag(d.element(), bounds, event, onGrip);
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void startDrag(NexoHudLayout.Element element, ScreenRectangle bounds, MouseButtonEvent event, boolean resize) {
		dragging = element;
		resizingDrag = resize;
		dragStartMouseX = event.x();
		dragStartMouseY = event.y();
		dragStartX = bounds.left();
		dragStartY = bounds.top();
		NexoHudLayout.Position existing = NexoHudLayout.get().get(element);
		dragStartScale = existing != null ? existing.scale : 1f;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging == null) {
			return super.mouseDragged(event, dragX, dragY);
		}
		if (resizingDrag) {
			double delta = (event.x() - dragStartMouseX + event.y() - dragStartMouseY) / 2.0;
			float scale = (float) Math.clamp(dragStartScale + delta / 80.0, 0.5, 3.0);
			NexoHudLayout.get().set(dragging, new NexoHudLayout.Position(dragStartX, dragStartY, scale));
		} else {
			int x = (int) Math.round(dragStartX + (event.x() - dragStartMouseX));
			int y = (int) Math.round(dragStartY + (event.y() - dragStartMouseY));
			NexoHudLayout.get().set(dragging, new NexoHudLayout.Position(x, y, dragStartScale));
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
			NexoHudLayout.get().save();
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		// Safety net: a drag interrupted by e.g. alt-tab never fires mouseReleased.
		NexoHudLayout.get().save();
		minecraft.gui.setScreen(parent);
	}
}
