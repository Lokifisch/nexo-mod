package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The button look, shared by {@link NexoButton} and {@code NeonButtonMixin}
 * so both draw identically: a flat dark field with a 1px line, which lights
 * up — accent outline, faint tint, soft glow — only while hovered or focused.
 *
 * <p>Every button used to glow and cycle colour at rest. A screen of twenty
 * buttons then has no emphasis left to give the one under the cursor, so the
 * light is now spent only there (see design/STYLE.md).
 */
public final class NexoButtonRenderer {
	private static final int CORNER_RADIUS = 4;

	private NexoButtonRenderer() {}

	public static void draw(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, boolean active, boolean hovered) {
		if (!active) {
			NexoShapes.outlined(graphics, x0, y0, x1, y1, NexoStyle.FIELD, NexoStyle.fade(NexoStyle.CONTROL, 0.6F), CORNER_RADIUS);
			return;
		}
		if (!hovered) {
			NexoShapes.outlined(graphics, x0, y0, x1, y1, NexoStyle.FIELD, NexoStyle.CONTROL, CORNER_RADIUS);
			return;
		}
		NexoShapes.glow(graphics, x0, y0, x1, y1, NexoStyle.ACCENT, CORNER_RADIUS, 0.14F);
		NexoShapes.outlined(graphics, x0, y0, x1, y1,
				NexoStyle.mix(NexoStyle.FIELD, NexoStyle.ACCENT, 0.12F), NexoStyle.ACCENT, CORNER_RADIUS);
	}
}
