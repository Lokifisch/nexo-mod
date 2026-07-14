package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shared "floating panel" backdrop for popups (account switcher, sign-in,
 * offline login). Replaces a flat cycling-rainbow 1px outline with the same
 * soft-glow-plus-gradient-highlight language {@link NexoButtonRenderer}
 * uses, so panel borders don't read as a leftover hard rectangle next to
 * the buttons' softer look.
 */
public final class NexoPanelRenderer {
	private static final int CORNER_RADIUS = 8;

	private NexoPanelRenderer() {}

	public static void draw(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
		// A layered glow — now the main thing setting this panel apart from a dimmed
		// screen behind it, so it needs to read clearly as "highlighted", not just tinted.
		int accent = NexoStyle.cycle(System.currentTimeMillis(), 8000);
		graphics.fill(x0 - 6, y0 - 6, x1 + 6, y1 + 6, 0x1AFF3CAC);
		graphics.fill(x0 - 3, y0 - 3, x1 + 3, y1 + 3, 0x33FF3CAC);
		NexoShapes.fillRounded(graphics, x0, y0, x1, y1, NexoStyle.PANEL_BG, CORNER_RADIUS);
		graphics.fillGradient(x0 + CORNER_RADIUS, y0, x1 - CORNER_RADIUS, y0 + 1, NexoStyle.MAGENTA, accent);
		graphics.fill(x0 + CORNER_RADIUS, y1 - 1, x1 - CORNER_RADIUS, y1, 0x40000000);
	}
}
