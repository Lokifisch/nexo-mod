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
		int glowAlpha = hovered ? 0x33 : 0x18;
		graphics.fill(x0 - 4, y0 - 4, x1 + 4, y1 + 4, (glowAlpha << 24) | glowRgb);
		if (hovered) {
			graphics.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0x40000000 | glowRgb);
		}

		NexoShapes.fillRounded(graphics, x0, y0, x1, y1, borderColor, CORNER_RADIUS);
		NexoShapes.fillRounded(graphics, x0 + BORDER_WIDTH, y0 + BORDER_WIDTH, x1 - BORDER_WIDTH, y1 - BORDER_WIDTH,
				FILL_COLOR, Math.max(0, CORNER_RADIUS - BORDER_WIDTH));
	}
}
