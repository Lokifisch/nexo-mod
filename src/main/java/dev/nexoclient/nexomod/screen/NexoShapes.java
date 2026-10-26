package dev.nexoclient.nexomod.screen;

import org.joml.Matrix3x2fStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Anti-aliased rounded rectangle fills. {@link GuiGraphicsExtractor} has no
 * native rounded-rect primitive, and filling in GUI pixels (2-4 physical pixels
 * each) gave visibly stair-stepped corners. So the shape is drawn in physical
 * pixels (pose scaled by 1/guiScale) and each corner row's edge pixel gets
 * partial alpha from the exact circle equation.
 */
public final class NexoShapes {
	private NexoShapes() {}

	/**
	 * Smooth (anti-aliased) corners cost ~15 fills per corner row instead of one — hundreds
	 * of GUI draw calls per shape — which drops menus to single-digit FPS on weak laptops.
	 * Hence a setting ("Smooth Edges", on by default); off gives one-pixel stepped corners.
	 */
	private static boolean smooth() {
		return NexoConfig.get().smoothEdgesEnabled();
	}

	public static void fillRounded(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color, int radius) {
		fillRoundedGradient(graphics, x0, y0, x1, y1, color, color, radius, smooth());
	}

	/**
	 * Same shape without the anti-aliased edge pixels: one fill per corner row instead of
	 * ~15. For faint layers (glows) where the AA is invisible but the draw calls are not —
	 * a menu of 20 glowing buttons otherwise submits tens of thousands of fills per frame.
	 */
	public static void fillRoundedFast(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color, int radius) {
		fillRoundedGradient(graphics, x0, y0, x1, y1, color, color, radius, false);
	}

	/**
	 * A rounded box with a 1px outline — the flat card/control shape everything
	 * in design/STYLE.md is built from. Drawn as the outline colour with the fill
	 * one pixel inside it, since there is no stroke primitive.
	 */
	public static void outlined(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int fill, int border, int radius) {
		fillRounded(graphics, x0, y0, x1, y1, border, radius);
		fillRounded(graphics, x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill, Math.max(0, radius - 1));
	}

	/**
	 * A soft halo around a box: stacked rounded layers, each wider and fainter,
	 * so the falloff follows the corners. Fast fills — the AA is invisible on
	 * layers this faint, and the draw calls are not.
	 */
	public static void glow(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color, int radius, float strength) {
		int rgb = color & 0xFFFFFF;
		int layers = 3;
		for (int i = layers; i >= 1; i--) {
			float falloff = 1F - (i - 1F) / layers;
			int a = Math.max(3, (int) (255 * strength * falloff * falloff));
			fillRoundedFast(graphics, x0 - i, y0 - i, x1 + i, y1 + i, (a << 24) | rgb, radius + i);
		}
	}

	/**
	 * The logo's pink → violet → blue → mint ramp, left to right across
	 * {@code rampX0..rampX1}, drawn only from {@code rampX0} to {@code x1}. Keeping
	 * the ramp's span separate from what is drawn is what lets a slider reveal a
	 * fixed gradient instead of squeezing the whole ramp into its filled part.
	 * Built from 2px strips because {@code fillGradient} only interpolates
	 * vertically. {@code alpha} sets the strength, so text over it stays readable.
	 */
	public static void fillLogoRamp(GuiGraphicsExtractor graphics, int rampX0, int rampX1, int x1, int y0, int y1, int alpha) {
		float span = Math.max(1, rampX1 - rampX0);
		int a = (alpha & 0xFF) << 24;
		for (int x = rampX0; x < x1; x += 2) {
			int sx1 = Math.min(x + 2, x1);
			// Sampled at the strip's centre, so the last strip lands on mint.
			int color = NexoStyle.logoRamp(((x + sx1) / 2F - rampX0) / span);
			graphics.fill(x, y0, sx1, y1, a | (color & 0xFFFFFF));
		}
	}

	public static void fillRoundedGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int colorTop, int colorBottom, int radius) {
		fillRoundedGradient(graphics, x0, y0, x1, y1, colorTop, colorBottom, radius, smooth());
	}

	private static void fillRoundedGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int colorTop, int colorBottom, int radius, boolean antiAlias) {
		int s = Math.max(1, (int) Minecraft.getInstance().getWindow().getGuiScale());
		int X0 = x0 * s, Y0 = y0 * s, X1 = x1 * s, Y1 = y1 * s;
		int h = Y1 - Y0;
		if (h <= 0 || X1 <= X0 || ((colorTop | colorBottom) >>> 24) == 0) {
			return;
		}
		int r = Math.max(0, Math.min(radius * s, Math.min((X1 - X0) / 2, h / 2)));

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		try {
			pose.scale(1F / s, 1F / s);
			if (h > 2 * r) {
				if (colorTop == colorBottom) {
					graphics.fill(X0, Y0 + r, X1, Y1 - r, colorTop);
				} else {
					graphics.fillGradient(X0, Y0 + r, X1, Y1 - r,
							NexoStyle.mix(colorTop, colorBottom, (float) r / h),
							NexoStyle.mix(colorTop, colorBottom, (float) (h - r) / h));
				}
			}
			for (int row = 0; row < r; row++) {
				cornerRow(graphics, X0, X1, Y0 + row, NexoStyle.mix(colorTop, colorBottom, (row + 0.5F) / h), r, row, antiAlias);
				cornerRow(graphics, X0, X1, Y1 - 1 - row, NexoStyle.mix(colorTop, colorBottom, (h - row - 0.5F) / h), r, row, antiAlias);
			}
		} finally {
			pose.popMatrix();
		}
	}

	/** One corner row (both sides): coverage per pixel is averaged over 4 sub-rows so shallow arcs blend over several pixels, not one. */
	private static void cornerRow(GuiGraphicsExtractor graphics, int X0, int X1, int y, int color, int r, int row, boolean antiAlias) {
		double big = inset(r, row), small = inset(r, row + 1);
		int full = (int) Math.ceil(big);
		graphics.fill(X0 + full, y, X1 - full, y + 1, color);
		if (!antiAlias) {
			return;
		}
		for (int x = (int) Math.floor(small); x < full; x++) {
			float cov = 0;
			for (int k = 0; k < 4; k++) {
				double edge = inset(r, row + (k + 0.5) / 4);
				cov += (float) Math.min(1, Math.max(0, x + 1 - edge));
			}
			int aa = NexoStyle.fade(color, cov / 4);
			graphics.fill(X0 + x, y, X0 + x + 1, y + 1, aa);
			graphics.fill(X1 - x - 1, y, X1 - x, y + 1, aa);
		}
	}

	/** Horizontal inset of the arc at height {@code y} (physical px from the shape's top edge). */
	private static double inset(int r, double y) {
		double dy = r - y;
		return r - Math.sqrt(Math.max(0, r * r - dy * dy));
	}
}
