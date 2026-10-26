package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The slider look: one full-size box matching the button style — flat dark
 * field, 1px line — with the current value shown as the logo's rainbow ramp
 * filling it from the left, the classic "progress bar" shape rather than a
 * thin track with a floating handle. The ramp is kept translucent so the
 * vanilla label drawn over it stays readable.
 */
public final class NexoSliderRenderer {
	private static final int RADIUS = 4;
	/** Strength of the rainbow fill: bright enough to read as the value, dark enough under white text. */
	private static final int FILL_ALPHA = 0x78;

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

		// Inset by two so the square strips clear the rounded inner corners.
		int innerX0 = x0 + 2;
		int innerX1 = x1 - 2;
		int fillX1 = innerX0 + (int) Math.round((innerX1 - innerX0) * value);
		if (fillX1 > innerX0) {
			NexoShapes.fillLogoRamp(graphics, innerX0, innerX1, fillX1, y0 + 2, y1 - 2, FILL_ALPHA);
		}
	}
}
