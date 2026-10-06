package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The actual neon button look, shared by {@link NexoButton} and
 * {@code NeonButtonMixin} so both draw identically: a black fill with a
 * glowing, color-cycling neon outline, drawn as a slightly-larger rounded
 * rect in the border color with a slightly-smaller black rounded rect on
 * top of it (no separate stroke primitive exists on the renderer).
 */
public final class NexoButtonRenderer {
	private static final int CORNER_RADIUS = 4;
	private static final int BORDER_WIDTH = 2;
	private static final int FILL_COLOR = 0xFF0A0A0C;

	private NexoButtonRenderer() {}

	public static void draw(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, boolean active, boolean hovered) {
		if (!active) {
			NexoShapes.fillRounded(graphics, x0, y0, x1, y1, 0xFF2A2A32, CORNER_RADIUS);
			NexoShapes.fillRounded(graphics, x0 + BORDER_WIDTH, y0 + BORDER_WIDTH, x1 - BORDER_WIDTH, y1 - BORDER_WIDTH,
					FILL_COLOR, Math.max(0, CORNER_RADIUS - BORDER_WIDTH));
			return;
		}

		// The outline color continuously cycles through the brand palette rather than
		// sitting on one fixed color — hover shortens the cycle period so it visibly
		// speeds up, plus a brighter glow, instead of just a static highlight.
		long now = System.currentTimeMillis();
		long period = hovered ? 3000L : 6000L;
		int borderColor = NexoStyle.cycle(now, period);

		int glowRgb = borderColor & 0xFFFFFF;
		// Soft glow: stacked rounded layers, each wider and fainter than the last, so the
		// falloff follows the button's corners instead of a hard-edged rectangle.
		float strength = hovered ? 0.16F : 0.07F;
		int layers = hovered ? 4 : 3;
		for (int i = layers; i >= 1; i--) {
			float falloff = 1F - (i - 1F) / layers;
			int a = Math.max(3, (int) (255 * strength * falloff * falloff));
			NexoShapes.fillRoundedFast(graphics, x0 - i, y0 - i, x1 + i, y1 + i, (a << 24) | glowRgb, CORNER_RADIUS + i);
		}

		NexoShapes.fillRounded(graphics, x0, y0, x1, y1, borderColor, CORNER_RADIUS);
		NexoShapes.fillRounded(graphics, x0 + BORDER_WIDTH, y0 + BORDER_WIDTH, x1 - BORDER_WIDTH, y1 - BORDER_WIDTH,
				FILL_COLOR, Math.max(0, CORNER_RADIUS - BORDER_WIDTH));
	}
}
