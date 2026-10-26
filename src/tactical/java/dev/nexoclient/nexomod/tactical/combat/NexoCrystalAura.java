package dev.nexoclient.nexomod.tactical.combat;

import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.tactical.freecam.NexoFreecam;

/**
 * Auto Crystal. Pops the nearest end crystal within arm's reach — the same
 * punch a player lands by hand, driven off world state with no player input,
 * which is exactly why it lives in the Tactical jar. Off by default.
 *
 * <p>The player places the crystal on obsidian; this turns to face it, pops it,
 * and turns back to where the player was looking, so the view flicks to the
 * crystal and back instead of being left staring at the hole it came from.
 *
 * <p>There is no target scan: a crystal in reach is popped whether or not an
 * enemy is nearby. Pops are throttled by {@code crystalAuraBreakDelay} ticks,
 * and the look-back happens on the tick between pops.
 */
public final class NexoCrystalAura {
	/** Attack reach in blocks; the server refuses swings past this. */
	private static final double REACH = 3.0;

	private static int cooldown;
	private static boolean restorePending;
	private static float savedYaw;
	private static float savedPitch;

	private NexoCrystalAura() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(NexoCrystalAura::tick);
	}

	private static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			reset();
			return;
		}
		NexoConfig config = NexoConfig.get();
		if (!config.crystalAuraEnabled() || client.gui.screen() != null || !player.isAlive() || player.isSpectator()
				|| player.isUsingItem() || NexoFreecam.active() || client.gameMode == null || client.getConnection() == null) {
			reset();
			return;
		}
		if (restorePending) {
			// Turn back to where the player was looking before the pop.
			player.setYRot(savedYaw);
			player.setXRot(savedPitch);
			restorePending = false;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		EndCrystal crystal = nearestCrystal(client.level, player);
		if (crystal == null) {
			return;
		}
		savedYaw = player.getYRot();
		savedPitch = player.getXRot();
		NexoAuraUtil.lookAt(client, player, crystal.getBoundingBox().getCenter());
		client.gameMode.attack(player, crystal);
		restorePending = true;
		cooldown = config.crystalAuraBreakDelay();
	}

	private static EndCrystal nearestCrystal(Level level, LocalPlayer player) {
		List<EndCrystal> list = level.getEntitiesOfClass(EndCrystal.class,
				player.getBoundingBox().inflate(REACH), EndCrystal::isAlive);
		EndCrystal best = null;
		double bestDist = Double.MAX_VALUE;
		for (EndCrystal crystal : list) {
			double distance = crystal.distanceToSqr(player);
			if (distance < bestDist) {
				bestDist = distance;
				best = crystal;
			}
		}
		return best;
	}

	private static void reset() {
		cooldown = 0;
		restorePending = false;
	}
}
