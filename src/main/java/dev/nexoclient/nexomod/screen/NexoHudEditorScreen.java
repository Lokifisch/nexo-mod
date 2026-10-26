package dev.nexoclient.nexomod.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

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

	private record NexoElement(Draggable draggable, BooleanSupplier enabled) {
	}

	/**
	 * Everything Nexo draws itself, each with the switch that turns it on.
	 * Vanilla's own movable pieces are appended by {@link #elements()}.
	 */
	private static final List<NexoElement> NEXO_ELEMENTS = List.of(
			nexo(NexoHudLayout.Element.KEYSTROKES, "nexomod.qol.keystrokes", NexoKeystrokesHud::resolveBounds,
					() -> NexoConfig.get().keystrokesHudEnabled()),
			nexo(NexoHudLayout.Element.CPS, "nexomod.qol.cps", NexoCpsCounter::resolveBounds,
					() -> NexoConfig.get().cpsCounterEnabled()),
			nexo(NexoHudLayout.Element.ARMOR, "nexomod.settings.armorHud.enabled", NexoArmorHud::resolveBounds,
					() -> NexoConfig.get().armorHudEnabled()),
			nexo(NexoHudLayout.Element.STATS, "nexomod.stats.title", NexoStatsHud::resolveBounds,
					() -> NexoConfig.get().statsHudEnabled()),
			nexo(NexoHudLayout.Element.POTION, "nexomod.qol.potion", NexoPotionHud::resolveBounds,
					() -> NexoConfig.get().potionHudEnabled()),
			nexo(NexoHudLayout.Element.COMBO, "nexomod.qol.combo", NexoComboCounter::resolveBounds,
					() -> NexoConfig.get().comboCounterEnabled()),
			nexo(NexoHudLayout.Element.ACTIONBAR_LOG, "nexomod.qol.actionbarLog", NexoFadingLogHud.ACTIONBAR::resolveBounds,
					() -> NexoConfig.get().actionbarLogEnabled()),
			nexo(NexoHudLayout.Element.PICKUP_LOG, "nexomod.qol.pickupLog", NexoFadingLogHud.PICKUPS::resolveBounds,
					() -> NexoConfig.get().pickupLogEnabled()),
			nexo(NexoHudLayout.Element.SECOND_CHAT, "nexomod.chat.second",
					dev.nexoclient.nexomod.hud.NexoSecondChatHud::resolveBounds,
					() -> dev.nexoclient.nexomod.chat.NexoChatTweaks.secondEnabled),
			nexo(NexoHudLayout.Element.INVENTORY, "nexomod.qol.inventoryHud", NexoInventoryHud::resolveBounds,
					() -> NexoConfig.get().inventoryHudEnabled()),
			nexo(NexoHudLayout.Element.MINI_PLAYER, "nexomod.qol.miniPlayer", NexoMiniPlayerHud::resolveBounds,
					() -> NexoConfig.get().miniPlayerEnabled()),
			nexo(NexoHudLayout.Element.TARGET, "nexomod.qol.targetHud", dev.nexoclient.nexomod.hud.NexoTargetHud::resolveBounds,
					() -> NexoConfig.get().targetHudEnabled()),
			nexo(NexoHudLayout.Element.TOGGLE_KEYS, "nexomod.hudEditor.toggleKeys",
					dev.nexoclient.nexomod.hud.NexoToggleKeys::resolveBounds,
					() -> NexoConfig.get().toggleSprintEnabled() || NexoConfig.get().toggleSneakEnabled()));

	private static NexoElement nexo(NexoHudLayout.Element element, String label,
			BiFunction<Integer, Integer, ScreenRectangle> bounds, BooleanSupplier enabled) {
		return new NexoElement(new Draggable(element, Component.translatable(label), bounds), enabled);
	}

	/**
	 * Only what is actually on screen: the Nexo elements that are switched on,
	 * plus every vanilla piece the HUD Cleaner has not hidden. An outline for
	 * something switched off would move a thing nobody can see.
	 */
	private static List<Draggable> elements() {
		List<Draggable> all = new ArrayList<>();
		for (NexoElement element : NEXO_ELEMENTS) {
			if (element.enabled().getAsBoolean()) {
				all.add(element.draggable());
			}
		}
		for (NexoVanillaHud.Entry entry : NexoVanillaHud.ENTRIES) {
			if (entry.hidden().getAsBoolean()) {
				continue;
			}
			// entry.label() is the HUD Cleaner's "Hide …" toggle text; the editor
			// names the thing itself.
			all.add(new Draggable(entry.slot(),
					Component.translatable("nexomod.hudEditor.vanilla." + entry.slot().name().toLowerCase(java.util.Locale.ROOT)),
					entry::resolveBounds));
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
		followCursor(mouseX, mouseY);
		// Boxes, then the panel over any box that reaches it, then the panel's
		// buttons on top of both — so Reset and Done are never covered.
		for (Draggable d : elements()) {
			ScreenRectangle bounds = d.bounds().apply(graphics.guiWidth(), graphics.guiHeight());
			// The box under the cursor or being dragged is the one that lights up;
			// the rest are dashed placeholders.
			boolean lit = d.element() == dragging
					|| (dragging == null && !overPanel(mouseX, mouseY) && bounds.containsPoint(mouseX, mouseY));
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

	/** Exactly the rectangle drawPanel paints. */
	private boolean overPanel(double mx, double my) {
		return mx >= width / 2 - PANEL_HALF_WIDTH && mx < width / 2 + PANEL_HALF_WIDTH
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
		// A release that never arrived leaves a box stuck to the cursor; the next
		// click puts it down instead of grabbing something else.
		if (dragging != null) {
			endDrag();
			return true;
		}
		// The bottom panel is drawn over the boxes, so it takes clicks first: a
		// box hidden under Done must not start a drag when Done is clicked.
		if (overPanel(event.x(), event.y())) {
			return super.mouseClicked(event, doubleClick);
		}
		// 26.3 numbers mouse buttons from 1 (left), not GLFW's 0 — a hardcoded 0
		// here is why no box could be grabbed.
		if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
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
		moved = false;
		// Floored to whole pixels like the per-frame cursor position it is compared
		// with, so grabbing doesn't nudge the box by a fraction rounded up.
		dragStartMouseX = Math.floor(event.x());
		dragStartMouseY = Math.floor(event.y());
		dragStartX = bounds.left();
		dragStartY = bounds.top();
		NexoHudLayout.Position existing = NexoHudLayout.get().get(element);
		dragStartScale = existing != null ? existing.scale : 1f;
	}

	/**
	 * Moves (or resizes) the held box to follow the cursor, once per frame from
	 * {@link #extractRenderState}. Polling the cursor rather than relying on
	 * {@code mouseDragged} keeps the box moving even when another mod (Mouse
	 * Tweaks, Essential) handles drag events in screens.
	 */
	/** Whether the held box has actually moved yet — only then is anything saved. */
	private boolean moved;

	private void followCursor(double mouseX, double mouseY) {
		if (dragging == null) {
			return;
		}
		// A click that never moves must not write a position: a saved entry pins
		// the element to fixed pixels instead of its own default placement.
		if (!moved && Math.abs(mouseX - dragStartMouseX) < 2 && Math.abs(mouseY - dragStartMouseY) < 2) {
			return;
		}
		moved = true;
		if (resizingDrag) {
			double delta = (mouseX - dragStartMouseX + mouseY - dragStartMouseY) / 2.0;
			float scale = (float) Math.clamp(dragStartScale + delta / 80.0, 0.5, 3.0);
			NexoHudLayout.get().set(dragging, new NexoHudLayout.Position(dragStartX, dragStartY, scale));
		} else {
			int x = (int) Math.round(dragStartX + (mouseX - dragStartMouseX));
			int y = (int) Math.round(dragStartY + (mouseY - dragStartMouseY));
			NexoHudLayout.get().set(dragging, new NexoHudLayout.Position(x, y, dragStartScale));
		}
	}

	private void endDrag() {
		if (dragging != null) {
			dragging = null;
			if (moved) {
				NexoHudLayout.get().save();
			}
		}
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		endDrag();
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
