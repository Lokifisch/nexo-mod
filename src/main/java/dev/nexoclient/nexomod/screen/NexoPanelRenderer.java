package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shared "floating panel" backdrop for the mod menu and popups (account
 * switcher, sign-in, offline login): a flat near-black panel with a 1px line,
 * no glow. Panels hold content; the light belongs to the controls inside them.
 */
public final class NexoPanelRenderer {
	private static final int CORNER_RADIUS = 6;

	private NexoPanelRenderer() {}

	public static void draw(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
		// A faint dark shadow, so the panel still lifts off the world behind it.
		NexoShapes.fillRoundedFast(graphics, x0 - 3, y0 - 2, x1 + 3, y1 + 4, 0x40000000, CORNER_RADIUS + 3);
		NexoShapes.outlined(graphics, x0, y0, x1, y1, NexoStyle.PANEL, NexoStyle.LINE, CORNER_RADIUS);
	}
}
