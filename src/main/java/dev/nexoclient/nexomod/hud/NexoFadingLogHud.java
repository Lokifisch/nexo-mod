package dev.nexoclient.nexomod.hud;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

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
 * A small stack of recent messages that fade out after a few seconds — used
 * for two unrelated feeds ({@link #ACTIONBAR} and {@link #PICKUPS}) that
 * happen to be the exact same widget: a rolling, fading text log. One class
 * with two instances rather than two copies of the same rendering and
 * fade-timer code.
 *
 * <p>Entries arrive via {@link #record}, called from
 * {@code mixin.ActionbarLogMixin} (a {@code Gui.setOverlayMessage} hook) and
 * {@code mixin.PickupLogMixin} (a {@code ClientPacketListener
 * .handleTakeItemEntity} hook) respectively — neither Fabric API exposes a
 * dedicated event for "the actionbar changed" or "the local player picked
 * something up off the ground", so both need a real mixin rather than a
 * supported extension point.
 */
public final class NexoFadingLogHud implements HudElement {
	private static final int LINE_HEIGHT = 10;
	private static final int NOMINAL_WIDTH = 170;
	private static final int MAX_ENTRIES = 5;
	private static final long FADE_AFTER_MS = 6000;
	private static final int EDGE_MARGIN = 4;

	public static final NexoFadingLogHud ACTIONBAR = new NexoFadingLogHud(
			Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "actionbar_log"),
			NexoHudLayout.Element.ACTIONBAR_LOG,
			() -> NexoConfig.get().actionbarLogEnabled(),
			(guiWidth, guiHeight) -> new int[]{EDGE_MARGIN, guiHeight - 110});

	public static final NexoFadingLogHud PICKUPS = new NexoFadingLogHud(
			Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "pickup_log"),
			NexoHudLayout.Element.PICKUP_LOG,
			() -> NexoConfig.get().pickupLogEnabled(),
			(guiWidth, guiHeight) -> new int[]{EDGE_MARGIN, guiHeight / 2 - 60});

	private interface DefaultPosition {
		int[] compute(int guiWidth, int guiHeight);
	}

	private record Entry(Component text, long addedAt) {
	}

	private final Identifier id;
	private final NexoHudLayout.Element layoutElement;
	private final BooleanSupplier enabled;
	private final DefaultPosition defaultPosition;
	private final Deque<Entry> entries = new ArrayDeque<>();

	private NexoFadingLogHud(Identifier id, NexoHudLayout.Element layoutElement, BooleanSupplier enabled,
			DefaultPosition defaultPosition) {
		this.id = id;
		this.layoutElement = layoutElement;
		this.enabled = enabled;
		this.defaultPosition = defaultPosition;
	}

	public void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, id, this);
	}

	public synchronized void record(Component text) {
		entries.addLast(new Entry(text, System.currentTimeMillis()));
		while (entries.size() > MAX_ENTRIES) {
			entries.pollFirst();
		}
	}

	private synchronized List<Entry> live() {
		long now = System.currentTimeMillis();
		entries.removeIf(entry -> now - entry.addedAt() > FADE_AFTER_MS);
		return List.copyOf(entries);
	}

	/** Where this element draws right now — shared by rendering and the layout editor. */
	public ScreenRectangle resolveBounds(int guiWidth, int guiHeight) {
		NexoHudLayout.Position override = NexoHudLayout.get().get(layoutElement);
		float scale = override != null ? override.scale : 1f;
		int width = Math.round(NOMINAL_WIDTH * scale);
		int height = Math.round(MAX_ENTRIES * LINE_HEIGHT * scale);
		int[] fallback = defaultPosition.compute(guiWidth, guiHeight);
		int x = override != null ? override.x : fallback[0];
		int y = override != null ? override.y : fallback[1];
		return NexoHudBounds.clamp(x, y, width, height, guiWidth, guiHeight);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (NexoHudVisibility.hidden() || !enabled.getAsBoolean()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options.hideGui) {
			return;
		}
		List<Entry> current = live();
		if (current.isEmpty()) {
			return;
		}

		NexoHudLayout.Position override = NexoHudLayout.get().get(layoutElement);
		float scale = override != null ? override.scale : 1f;
		ScreenRectangle bounds = resolveBounds(graphics.guiWidth(), graphics.guiHeight());
		int lineHeight = Math.round(LINE_HEIGHT * scale);

		int y = bounds.top();
		for (Entry entry : current) {
			graphics.text(client.font, entry.text(), bounds.left(), y, NexoStyle.TEXT_SECONDARY);
			y += lineHeight;
		}
	}
}
