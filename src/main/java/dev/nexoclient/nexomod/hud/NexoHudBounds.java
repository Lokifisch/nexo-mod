package dev.nexoclient.nexomod.hud;

import net.minecraft.client.gui.navigation.ScreenRectangle;

/**
 * Shared "keep it on-screen" clamp for every draggable HUD element's
 * {@code resolveBounds}.
 *
 * <p>Takes plain {@code guiWidth}/{@code guiHeight} rather than a
 * {@code GuiGraphicsExtractor} — {@code resolveBounds} is also called from
 * {@code NexoHudEditorScreen}'s mouse handlers, which run outside a render
 * pass and have no extractor to hand, only {@code Screen.width}/{@code
 * height} (the same GUI-scaled numbers an extractor's {@code guiWidth()}/
 * {@code guiHeight()} would report).
 */
final class NexoHudBounds {
	private NexoHudBounds() {
	}

	static ScreenRectangle clamp(int x, int y, int width, int height, int guiWidth, int guiHeight) {
		int clampedX = Math.clamp(x, 0, Math.max(0, guiWidth - width));
		int clampedY = Math.clamp(y, 0, Math.max(0, guiHeight - height));
		return new ScreenRectangle(clampedX, clampedY, width, height);
	}
}
