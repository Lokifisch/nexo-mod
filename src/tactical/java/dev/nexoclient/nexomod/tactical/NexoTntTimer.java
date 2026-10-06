package dev.nexoclient.nexomod.tactical;

import java.util.Locale;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.phys.Vec3;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * A colour-coded countdown nametag over every lit TNT. The fuse is already
 * synced to the client as entity data, so this only reads it — nothing is
 * inferred or tracked between frames.
 */
public final class NexoTntTimer {
	private static final double MAX_DISTANCE_SQ = 64 * 64;
	private static final int MAX_LABELS = 100;
	private static final float TEXT_SCALE = 0.9F;
	/** Fuse ticks above which the label is green / yellow / orange; at or below the last it is red. */
	private static final int GREEN_ABOVE = 60;
	private static final int YELLOW_ABOVE = 40;
	private static final int ORANGE_ABOVE = 20;

	private NexoTntTimer() {
	}

	public static void register() {
		LevelRenderEvents.BEFORE_GIZMOS.register(context -> render());
	}

	private static int color(int fuse) {
		if (fuse > GREEN_ABOVE) {
			return 0xFF55FF55;
		}
		if (fuse > YELLOW_ABOVE) {
			return 0xFFFFFF55;
		}
		return fuse > ORANGE_ABOVE ? 0xFFFFAA33 : 0xFFFF5555;
	}

	private static void render() {
		if (NexoHudVisibility.hidden() || !NexoConfig.get().tntTimerEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null || client.player == null) {
			return;
		}
		int labels = 0;
		for (Entity entity : level.entitiesForRendering()) {
			// Bounded so a TNT cannon (or a server flooding the client with them) can't cost a frame per label.
			if (entity instanceof PrimedTnt tnt && !tnt.isRemoved()
					&& client.player.distanceToSqr(tnt) <= MAX_DISTANCE_SQ && labels++ < MAX_LABELS) {
				int fuse = Math.max(0, tnt.getFuse());
				Gizmos.billboardText(String.format(Locale.ROOT, "%.1fs", fuse / 20.0),
						tnt.position().add(0, tnt.getBbHeight() + 0.5, 0),
						TextGizmo.Style.forColorAndCentered(color(fuse)).withScale(TEXT_SCALE));
			}
		}
	}
}
