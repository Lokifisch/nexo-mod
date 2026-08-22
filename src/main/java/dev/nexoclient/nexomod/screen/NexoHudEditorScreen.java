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
			new Draggable(NexoHudLayout.Element.INVENTORY, Component.translatable("nexomod.qol.inventoryHud"),
					NexoInventoryHud::resolveBounds));

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
		addRenderableWidget(Button.builder(Component.translatable("nexomod.qol.resetLayout"),
						button -> NexoHudLayout.get().resetAll())
				.pos(width / 2 - 205, height - 28).size(200, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.pos(width / 2 + 5, height - 28).size(200, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		for (Draggable d : elements()) {
			ScreenRectangle bounds = d.bounds().apply(graphics.guiWidth(), graphics.guiHeight());
			int color = d.element() == dragging ? NexoStyle.TEXT_ACTIVE_ACCENT : NexoStyle.BORDER_BRIGHT;
			drawOutline(graphics, bounds, color);
			graphics.fill(bounds.right() - RESIZE_GRIP, bounds.bottom() - RESIZE_GRIP, bounds.right(), bounds.bottom(), color);
			graphics.text(font, d.label(), bounds.left(), bounds.top() - font.lineHeight - 2, NexoStyle.TEXT_PRIMARY);
		}
	}

	private void drawOutline(GuiGraphicsExtractor graphics, ScreenRectangle bounds, int color) {
		graphics.fill(bounds.left(), bounds.top(), bounds.right(), bounds.top() + 1, color);
		graphics.fill(bounds.left(), bounds.bottom() - 1, bounds.right(), bounds.bottom(), color);
		graphics.fill(bounds.left(), bounds.top(), bounds.left() + 1, bounds.bottom(), color);
		graphics.fill(bounds.right() - 1, bounds.top(), bounds.right(), bounds.bottom(), color);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
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
		minecraft.setScreen(parent);
	}
}
