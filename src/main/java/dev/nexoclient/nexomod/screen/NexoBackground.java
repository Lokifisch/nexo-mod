package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Draws whichever background style is currently configured (see {@link NexoConfig}). */
public final class NexoBackground {
	private NexoBackground() {}

	public static void draw(GuiGraphicsExtractor graphics, int width, int height) {
		switch (NexoConfig.get().backgroundStyle()) {
			case MATRIX_RAIN -> MatrixRain.draw(graphics, Minecraft.getInstance().font, width, height);
			default -> Starfield.get().draw(graphics, width, height);
		}
	}
}
