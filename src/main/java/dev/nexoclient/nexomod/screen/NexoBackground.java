package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Draws whichever background style is currently configured (see {@link NexoConfig}). */
public final class NexoBackground {
	private NexoBackground() {}

	public static void draw(GuiGraphicsExtractor graphics, int width, int height) {
		NexoLauncherAppearance.applyColors();
		// Started by the Nexo launcher with sync on: its background, amount and speed win.
		NexoLauncherAppearance.Look launcher = NexoLauncherAppearance.current();
		if (launcher != null) {
			NexoBackdrop.Effect effect = NexoLauncherAppearance.effect(launcher);
			NexoBackdrop.draw(graphics, width, height, effect, launcher.amount(), launcher.speed());
			return;
		}
		NexoConfig config = NexoConfig.get();
		switch (config.backgroundStyle()) {
			case MATRIX_RAIN -> MatrixRain.draw(graphics, Minecraft.getInstance().font, width, height);
			case STARFIELD -> NexoBackdrop.drawStarfield(graphics, width, height);
			case THEME -> NexoBackdrop.draw(graphics, width, height, config.theme().effect);
			default -> NexoBackdrop.draw(graphics, width, height, config.backgroundStyle().effect);
		}
	}

	/**
	 * The same style over the running game (pause menu): translucent, so the world
	 * shows through. The legacy matrix rain maps to the rune rain.
	 */
	public static void drawSeeThrough(GuiGraphicsExtractor graphics, int width, int height) {
		NexoLauncherAppearance.applyColors();
		NexoLauncherAppearance.Look launcher = NexoLauncherAppearance.current();
		if (launcher != null) {
			NexoBackdrop.drawSeeThrough(graphics, width, height, NexoLauncherAppearance.effect(launcher), launcher.amount(), launcher.speed());
			return;
		}
		NexoConfig config = NexoConfig.get();
		if (config.backgroundStyle() == NexoConfig.BackgroundStyle.STARFIELD) {
			NexoBackdrop.drawStarfieldSeeThrough(graphics, width, height);
			return;
		}
		NexoBackdrop.Effect effect = switch (config.backgroundStyle()) {
			case MATRIX_RAIN -> NexoBackdrop.Effect.RAIN;
			case THEME -> config.theme().effect;
			default -> config.backgroundStyle().effect;
		};
		NexoBackdrop.drawSeeThrough(graphics, width, height, effect, 100, 100);
	}
}
