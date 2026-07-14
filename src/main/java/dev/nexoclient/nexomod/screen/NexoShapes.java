package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Pixel-rounded rectangle fills. {@link GuiGraphicsExtractor} has no native
 * rounded-rect primitive — this approximates one by, for each of the top
 * {@code radius} rows (mirrored for the bottom), computing how far to inset
 * from the edges using the actual circle equation, rather than a crude
 * diagonal chamfer.
 */
public final class NexoShapes {
	private NexoShapes() {}

	public static void fillRounded(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color, int radius) {
		radius = clampRadius(x0, y0, x1, y1, radius);
		if (radius <= 0) {
			graphics.fill(x0, y0, x1, y1, color);
			return;
		}

		graphics.fill(x0, y0 + radius, x1, y1 - radius, color);
		graphics.fill(x0 + radius, y0, x1 - radius, y0 + radius, color);
		graphics.fill(x0 + radius, y1 - radius, x1 - radius, y1, color);

		for (int row = 0; row < radius; row++) {
			int inset = cornerInset(radius, row);
			graphics.fill(x0 + inset, y0 + row, x1 - inset, y0 + row + 1, color);
			graphics.fill(x0 + inset, y1 - row - 1, x1 - inset, y1 - row, color);
		}
	}

	public static void fillRoundedGradient(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int colorTop, int colorBottom, int radius) {
		radius = clampRadius(x0, y0, x1, y1, radius);
		if (radius <= 0) {
			graphics.fillGradient(x0, y0, x1, y1, colorTop, colorBottom);
			return;
		}

		int height = y1 - y0;
		graphics.fillGradient(x0, y0 + radius, x1, y1 - radius,
				NexoStyle.mix(colorTop, colorBottom, (float) radius / height),
				NexoStyle.mix(colorTop, colorBottom, (float) (height - radius) / height));

		for (int row = 0; row < radius; row++) {
			int inset = cornerInset(radius, row);
			graphics.fill(x0 + inset, y0 + row, x1 - inset, y0 + row + 1, NexoStyle.mix(colorTop, colorBottom, (float) row / height));
			int bottomRow = height - row - 1;
			graphics.fill(x0 + inset, y1 - row - 1, x1 - inset, y1 - row, NexoStyle.mix(colorTop, colorBottom, (float) bottomRow / height));
		}
	}

	private static int clampRadius(int x0, int y0, int x1, int y1, int radius) {
		return Math.max(0, Math.min(radius, Math.min((x1 - x0) / 2, (y1 - y0) / 2)));
	}

	private static int cornerInset(int radius, int row) {
		double dy = radius - row - 0.5;
		return (int) Math.round(radius - Math.sqrt(Math.max(0, radius * radius - dy * dy)));
	}
}
