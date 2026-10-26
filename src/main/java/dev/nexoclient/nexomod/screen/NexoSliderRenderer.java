package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The slider's box: the same flat dark field with a 1px line as a button,
 * lit on hover. The name, the rainbow track with its knob and the value are
 * drawn over it by {@link NexoRowRenderer#slider}, in place of vanilla's
 * centred text — see {@code NeonSliderMixin}.
 */
public final class NexoSliderRenderer {
	private static final int RADIUS = 4;

	private NexoSliderRenderer() {}

	public static void draw(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, double value, boolean active, boolean hovered) {
		if (!active) {
			NexoShapes.outlined(graphics, x0, y0, x1, y1, NexoStyle.FIELD, NexoStyle.fade(NexoStyle.CONTROL, 0.6F), RADIUS);
			return;
		}
		if (hovered) {
			NexoShapes.glow(graphics, x0, y0, x1, y1, NexoStyle.ACCENT, RADIUS, 0.14F);
		}
		NexoShapes.outlined(graphics, x0, y0, x1, y1, NexoStyle.FIELD, hovered ? NexoStyle.ACCENT : NexoStyle.CONTROL, RADIUS);
	}
}
