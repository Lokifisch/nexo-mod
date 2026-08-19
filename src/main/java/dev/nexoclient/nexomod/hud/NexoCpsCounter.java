package dev.nexoclient.nexomod.hud;

import java.util.ArrayDeque;
import java.util.Deque;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoStyle;

/**
 * Left/right clicks-per-second, counted from a sliding one-second window.
 *
 * <p>Counted by edge-detecting {@code Options.keyAttack}/{@code keyUse} each
 * client tick, not by draining their click queues — {@code consumeClick()}
 * would steal the presses the game itself needs to process attacks and item
 * use, so this only ever reads {@code isDown()}.
 *
 * <p>ponytail: tick-rate-bounded (20 Hz), so clicks faster than 50ms apart
 * within the same tick collapse into one. A real cheat-client CPS counter
 * hooks the raw mouse callback for sub-tick precision; this is the "good
 * enough to watch your own clicking" version — upgrade to a GLFW mouse
 * button callback if sub-tick accuracy ever actually matters.
 */
public final class NexoCpsCounter implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "cps_counter");
	private static final long WINDOW_MS = 1000;

	/**
	 * A fixed nominal size for dragging/hit-testing in the layout editor,
	 * wide enough for "88 / 88 cps" — the live text is centered inside it
	 * rather than the box resizing to the text every frame, since a
	 * draggable region that moves under the cursor as the count changes
	 * would fight the editor's own drag math.
	 */
	private static final int NOMINAL_WIDTH = 70;
	private static final int NOMINAL_HEIGHT = 10;

	private static final Deque<Long> leftClicks = new ArrayDeque<>();
	private static final Deque<Long> rightClicks = new ArrayDeque<>();
	private static boolean leftWasDown;
	private static boolean rightWasDown;

	private NexoCpsCounter() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ID, new NexoCpsCounter());
		ClientTickEvents.END_CLIENT_TICK.register(NexoCpsCounter::tick);
	}

	private static void tick(Minecraft client) {
		if (client.options == null) {
			return;
		}
		long now = System.currentTimeMillis();

		boolean leftDown = client.options.keyAttack.isDown();
		if (leftDown && !leftWasDown) {
			leftClicks.addLast(now);
		}
		leftWasDown = leftDown;

		boolean rightDown = client.options.keyUse.isDown();
		if (rightDown && !rightWasDown) {
			rightClicks.addLast(now);
		}
		rightWasDown = rightDown;

		prune(leftClicks, now);
		prune(rightClicks, now);
	}

	private static void prune(Deque<Long> clicks, long now) {
		while (!clicks.isEmpty() && now - clicks.peekFirst() > WINDOW_MS) {
			clicks.pollFirst();
		}
	}

	/** Where this element draws right now — shared by rendering and the layout editor. */
	public static ScreenRectangle resolveBounds(int guiWidth, int guiHeight) {
		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.CPS);
		float scale = override != null ? override.scale : 1f;
		int width = Math.round(NOMINAL_WIDTH * scale);
		int height = Math.round(NOMINAL_HEIGHT * scale);
		int x = override != null ? override.x : guiWidth / 2 - width / 2;
		int y = override != null ? override.y : guiHeight / 2 + 16;
		return NexoHudBounds.clamp(x, y, width, height, guiWidth, guiHeight);
	}

	/** Temporary: confirms extractRenderState is ever called at all — see NexoArmorHud's diagnose(). */
	private static boolean loggedFirstCall = false;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (!loggedFirstCall) {
			loggedFirstCall = true;
			dev.nexoclient.nexomod.NexoMod.LOGGER.info("[nexomod] CpsCounter.extractRenderState is being called.");
		}
		if (NexoHudVisibility.hidden()) {
			return;
		}
		if (!NexoConfig.get().cpsCounterEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options == null || client.options.hideGui) {
			return;
		}

		ScreenRectangle bounds = resolveBounds(graphics.guiWidth(), graphics.guiHeight());
		Component text = Component.literal(leftClicks.size() + " / " + rightClicks.size() + " cps");
		int textWidth = client.font.width(text);
		int x = bounds.left() + (bounds.width() - textWidth) / 2;
		int y = bounds.top() + (bounds.height() - client.font.lineHeight) / 2;
		graphics.text(client.font, text, x, y, NexoStyle.TEXT_SECONDARY);
	}
}
