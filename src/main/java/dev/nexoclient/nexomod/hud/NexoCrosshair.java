package dev.nexoclient.nexomod.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * A configurable crosshair that replaces vanilla's. Disabled, it delegates, so
 * the vanilla crosshair comes back exactly (same approach as {@link NexoArmorBar}).
 * At most eight {@code fill} calls per frame.
 */
public final class NexoCrosshair implements HudElement {
	public enum Shape { PLUS_GAP, PLUS, DOT, CIRCLE }

	/** Colour presets the config screen cycles through (0xRRGGBB). */
	public static final int[] PRESETS = {0x00FF66, 0xFFFFFF, 0xFF3333, 0xFFDD33, 0x33CCFF, 0xB366FF, 0xFF66CC};

	private final HudElement original;

	private NexoCrosshair(HudElement original) {
		this.original = original;
	}

	public static void register() {
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, NexoCrosshair::new);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		NexoConfig config = NexoConfig.get();
		if (!config.crosshairEnabled()) {
			original.extractRenderState(graphics, delta);
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (NexoHudVisibility.hidden() || client.gui.hud.isHidden() || !client.options.getCameraType().isFirstPerson()) {
			return;
		}
		int cx = graphics.guiWidth() / 2;
		int cy = graphics.guiHeight() / 2;
		int t = config.crosshairThickness();
		int s = config.crosshairSize();
		int lo = -(t / 2);
		int hi = lo + t;
		int gap = config.crosshairGap();
		// x1, y1, x2, y2 (exclusive) relative to the centre; at most 4 rects.
		int[][] r;
		switch (config.crosshairShape()) {
			case PLUS -> r = new int[][] {
					{lo - s, lo, hi + s, hi},
					{lo, lo - s, hi, hi + s}};
			case DOT -> {
				int side = Math.max(t, s / 2);
				int a = -(side / 2);
				r = new int[][] {{a, a, a + side, a + side}};
			}
			case CIRCLE -> {
				// Square ring with the corners cut by one thickness, reads as a circle at this size.
				int rad = s + gap;
				r = new int[][] {
						{-rad + t, -rad, rad - t + 1, -rad + t},
						{-rad + t, rad - t + 1, rad - t + 1, rad + 1},
						{-rad, -rad + t, -rad + t, rad - t + 1},
						{rad - t + 1, -rad + t, rad + 1, rad - t + 1}};
			}
			default -> r = new int[][] {
					{hi + gap, lo, hi + gap + s, hi},
					{lo - gap - s, lo, lo - gap, hi},
					{lo, hi + gap, hi, hi + gap + s},
					{lo, lo - gap - s, hi, lo - gap}};
		}
		// All outlines first so one arm's outline never paints over another's fill.
		if (config.crosshairOutline()) {
			for (int[] q : r) {
				graphics.fill(cx + q[0] - 1, cy + q[1] - 1, cx + q[2] + 1, cy + q[3] + 1, 0xFF000000);
			}
		}
		int color = 0xFF000000 | config.crosshairColor();
		for (int[] q : r) {
			graphics.fill(cx + q[0], cy + q[1], cx + q[2], cy + q[3], color);
		}
	}
}
