package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Custom neon slider look: one full-size rounded box matching the button
 * style (black fill, glowing color-cycling outline), with the current
 * value shown as a filled gradient portion inside it — the classic
 * "progress bar" shape, not a thin separate track line with a floating
 * handle rectangle.
 */
public final class NexoSliderRenderer {
	private static final int RADIUS = 4;
	private static final int BORDER_WIDTH = 2;
	private static final int FILL_COLOR = 0xFF0A0A0C;

	private NexoSliderRenderer() {}

	public static void draw(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, double value, boolean active, boolean hovered) {
		if (!active) {
			NexoShapes.fillRounded(graphics, x0, y0, x1, y1, 0xFF2A2A32, RADIUS);
			NexoShapes.fillRounded(graphics, x0 + BORDER_WIDTH, y0 + BORDER_WIDTH, x1 - BORDER_WIDTH, y1 - BORDER_WIDTH,
					FILL_COLOR, Math.max(0, RADIUS - BORDER_WIDTH));
			return;
		}

		long now = System.currentTimeMillis();
		long period = hovered ? 3000L : 6000L;
		int borderColor = NexoStyle.cycle(now, period);

		int glowRgb = borderColor & 0xFFFFFF;
		int glowAlpha = hovered ? 0x33 : 0x18;
		graphics.fill(x0 - 4, y0 - 4, x1 + 4, y1 + 4, (glowAlpha << 24) | glowRgb);

		NexoShapes.fillRounded(graphics, x0, y0, x1, y1, borderColor, RADIUS);

		int innerX0 = x0 + BORDER_WIDTH;
		int innerY0 = y0 + BORDER_WIDTH;
		int innerX1 = x1 - BORDER_WIDTH;
		int innerY1 = y1 - BORDER_WIDTH;
		int innerRadius = Math.max(0, RADIUS - BORDER_WIDTH);
		NexoShapes.fillRounded(graphics, innerX0, innerY0, innerX1, innerY1, FILL_COLOR, innerRadius);

		int fillX1 = innerX0 + (int) Math.round((innerX1 - innerX0) * value);
		if (fillX1 > innerX0) {
			int colorA = NexoStyle.cycle(now, period);
			int colorB = NexoStyle.cycle(now + period / 2, period);
			NexoShapes.fillRoundedGradient(graphics, innerX0, innerY0, fillX1, innerY1, colorA, colorB, innerRadius);
		}
	}
}
