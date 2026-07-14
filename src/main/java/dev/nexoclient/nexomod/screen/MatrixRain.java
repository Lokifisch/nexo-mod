package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Falling character rain over black — the settings screen's background, color/density configurable. */
public final class MatrixRain {
	private static final String GLYPHS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ$#%&@*+=<>/\\|";
	private static final int TRAIL_LENGTH = 18;
	private static final int GLYPH_CHANGE_MS = 150;

	private MatrixRain() {}

	public static void draw(GuiGraphicsExtractor graphics, Font font, int width, int height) {
		NexoConfig config = NexoConfig.get();
		draw(graphics, font, width, height, config.matrixColor().rgb, config.matrixDensity().cellWidth);
	}

	public static void draw(GuiGraphicsExtractor graphics, Font font, int width, int height, int rgb, int cellWidth) {
		graphics.fill(0, 0, width, height, 0xFF000000);

		int cellHeight = font.lineHeight + 2;
		int columns = width / cellWidth + 1;
		long now = System.currentTimeMillis();

		// A column's head must travel far enough for its *whole trail* to clear the bottom
		// edge before looping back to the top, or the tail visibly vanishes mid-screen
		// instead of continuing off-screen.
		int visibleRows = height / cellHeight;
		int totalRows = visibleRows + TRAIL_LENGTH * 2 + 2;

		for (int col = 0; col < columns; col++) {
			int x = col * cellWidth;

			// Each column gets its own speed and start offset so they don't fall in lockstep.
			long colSeed = col * 7919L;
			float speed = 40.0F + (colSeed % 40); // pixels/second
			long cycleMs = (long) (totalRows * cellHeight / speed * 1000.0);
			long elapsed = (now + colSeed * 31) % Math.max(cycleMs, 1);
			int headRow = (int) (elapsed * speed / 1000.0 / cellHeight) - TRAIL_LENGTH;

			for (int t = 0; t < TRAIL_LENGTH; t++) {
				int row = headRow - t;
				if (row < 0) {
					continue;
				}
				int py = row * cellHeight;
				if (py > height) {
					continue;
				}

				float brightness = 1.0F - (float) t / TRAIL_LENGTH;
				int color;
				if (t == 0) {
					color = 0xFFC0FFC0; // near-white leading glyph
				} else {
					int alpha = Math.round(80 + brightness * 175);
					int shaded = shade(rgb, brightness);
					color = (alpha << 24) | shaded;
				}

				long glyphSeed = col * 1000L + row + now / GLYPH_CHANGE_MS;
				char glyph = GLYPHS.charAt((int) (Math.floorMod(glyphSeed, GLYPHS.length())));
				graphics.text(font, String.valueOf(glyph), x, py, color);
			}
		}
	}

	private static int shade(int rgb, float brightness) {
		int r = Math.round(((rgb >> 16) & 0xFF) * (0.3F + brightness * 0.7F));
		int g = Math.round(((rgb >> 8) & 0xFF) * (0.3F + brightness * 0.7F));
		int b = Math.round((rgb & 0xFF) * (0.3F + brightness * 0.7F));
		return (r << 16) | (g << 8) | b;
	}
}
