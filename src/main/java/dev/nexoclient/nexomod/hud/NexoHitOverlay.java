package dev.nexoclient.nexomod.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;

/** Red flash while the hurt animation plays, plus a red edge vignette below {@link #LOW_HEALTH}. */
public final class NexoHitOverlay implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "hit_overlay");
	private static final float LOW_HEALTH = 0.3F;
	private static final int BANDS = 10;
	private static final int RED = 0xFF0000;

	private NexoHitOverlay() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ID, new NexoHitOverlay());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (!NexoConfig.get().hitOverlayEnabled() || NexoHudVisibility.hidden()) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || !player.isAlive()) {
			return;
		}
		int w = graphics.guiWidth();
		int h = graphics.guiHeight();
		if (player.hurtTime > 0 && player.hurtDuration > 0) {
			int alpha = Math.round(90 * player.hurtTime / (float) player.hurtDuration);
			graphics.fill(0, 0, w, h, alpha << 24 | RED);
		}
		float ratio = player.getHealth() / Math.max(1.0F, player.getMaxHealth());
		if (ratio < LOW_HEALTH) {
			float strength = (LOW_HEALTH - ratio) / LOW_HEALTH;
			int step = Math.max(2, Math.min(w, h) / 40);
			for (int i = 0; i < BANDS; i++) {
				int alpha = Math.round(140 * strength * (BANDS - i) / BANDS);
				int c = alpha << 24 | RED;
				int in = i * step;
				graphics.fill(in, in, w - in, in + step, c);
				graphics.fill(in, h - in - step, w - in, h - in, c);
				graphics.fill(in, in + step, in + step, h - in - step, c);
				graphics.fill(w - in - step, in + step, w - in, h - in - step, c);
			}
		}
	}
}
