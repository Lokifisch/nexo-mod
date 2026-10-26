package dev.nexoclient.nexomod.tactical.combat;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Two small fixed-position HUD pieces: the fall-damage warning under the
 * crosshair and the player radar left of centre. Neither is in the layout
 * editor.
 */
public final class NexoCombatHud {
	private static final int RADAR_RANGE = 32;
	private static final int RADAR_SIZE = 64;

	private NexoCombatHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
				Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "fall_warning"), NexoCombatHud::drawFall);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
				Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "player_radar"), NexoCombatHud::drawRadar);
	}

	private static boolean hudHidden(Minecraft client) {
		return client.player == null || NexoHudVisibility.hidden() || client.gui.hud.isHidden();
	}

	private static void drawFall(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		if (hudHidden(client) || !NexoConfig.get().fallWarningEnabled()) {
			return;
		}
		LocalPlayer player = client.player;
		if (player.onGround() || player.isInWater() || player.isFallFlying() || player.getAbilities().flying
				|| player.hasEffect(MobEffects.SLOW_FALLING) || player.fallDistance <= 3.0) {
			return;
		}
		// Vanilla damage before armour/enchantments: one point per block past three, two points a heart.
		double hearts = Math.ceil(player.fallDistance - 3.0) / 2.0;
		graphics.centeredText(client.font, Component.translatable("nexomod.fall.warning", hearts),
				graphics.guiWidth() / 2, graphics.guiHeight() / 2 + 20, 0xFFFF5555);
	}

	private static void drawRadar(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		if (hudHidden(client) || client.level == null || !NexoConfig.get().playerRadarEnabled()) {
			return;
		}
		LocalPlayer self = client.player;
		int x0 = 8;
		int y0 = graphics.guiHeight() / 2 - RADAR_SIZE / 2;
		int cx = x0 + RADAR_SIZE / 2;
		int cy = y0 + RADAR_SIZE / 2;
		graphics.fill(x0, y0, x0 + RADAR_SIZE, y0 + RADAR_SIZE, 0x90000000);
		graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);
		// World dx/dz rotated so "up" on the radar is where the player is facing.
		double yaw = Math.toRadians(self.getYRot());
		double sin = Math.sin(yaw);
		double cos = Math.cos(yaw);
		for (Player other : client.level.players()) {
			if (other == self || other.isSpectator()) {
				continue;
			}
			double dx = other.getX() - self.getX();
			double dz = other.getZ() - self.getZ();
			double right = -dx * cos - dz * sin;
			double ahead = dx * sin - dz * cos;
			double scale = (RADAR_SIZE / 2.0 - 3) / RADAR_RANGE;
			if (Math.hypot(dx, dz) > RADAR_RANGE) {
				continue;
			}
			int px = cx + (int) Math.round(right * scale);
			int py = cy - (int) Math.round(ahead * scale);
			graphics.fill(px - 1, py - 1, px + 2, py + 2, 0xFFFF5555);
		}
	}
}
