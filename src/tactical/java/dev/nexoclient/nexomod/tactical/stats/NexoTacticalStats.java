package dev.nexoclient.nexomod.tactical.stats;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.LightLayer;

import dev.nexoclient.nexomod.hud.NexoStatsRegistry;

/**
 * The two {@link NexoStatsRegistry} stats that belong on this side of the
 * split: block light where you stand, and how many hostiles are near you.
 *
 * <p>Both read information the vanilla client has but does not show — light
 * level is an F3 field, and a hostile count behind you is not on screen at
 * all — which is the same test the sound radar and the hole finder pass. The
 * registry's own javadoc has always named these two as registered from
 * {@code NexoTacticalFeatures}; this is that registration.
 *
 * <p>The hostile count is the one stat the registry documents as expensive.
 * It is computed once per client tick into {@link #hostileCount} and the stat
 * supplier only reads that field — a value supplier runs once per frame per
 * enabled stat, so scanning entities there would repeat the same walk a few
 * hundred times a second for a number that can only change twenty.
 */
public final class NexoTacticalStats {
	/** Horizontal+vertical radius, in blocks, the hostile count covers. */
	private static final double HOSTILE_RADIUS = 24.0;
	private static final double HOSTILE_RADIUS_SQUARED = HOSTILE_RADIUS * HOSTILE_RADIUS;

	private static volatile int hostileCount;

	private NexoTacticalStats() {
	}

	public static void register() {
		NexoStatsRegistry.register("light", Component.translatable("nexomod.stats.light"),
				NexoTacticalStats::lightText);
		NexoStatsRegistry.register("hostiles", Component.translatable("nexomod.stats.hostiles"),
				() -> String.valueOf(hostileCount));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			LocalPlayer player = client.player;
			ClientLevel level = client.level;
			if (player == null || level == null) {
				hostileCount = 0;
				return;
			}
			hostileCount = countHostiles(player, level);
		});
	}

	/**
	 * Block light only, not the combined brightness: the question this answers is
	 * "can something spawn here", and sky light does not stop a spawn at night.
	 */
	private static String lightText() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		if (player == null || level == null) {
			return "--";
		}
		return String.valueOf(level.getLightEngine()
				.getLayerListener(LightLayer.BLOCK)
				.getLightValue(player.blockPosition()));
	}

	/**
	 * Walks the entities the client is already tracking for rendering, which is
	 * bounded by render distance — there is no wider set a client-only mod could
	 * see anyway, and no chunk is loaded to answer this.
	 */
	private static int countHostiles(LocalPlayer player, ClientLevel level) {
		int count = 0;
		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof Enemy)) {
				continue;
			}
			if (entity.isRemoved() || entity == player) {
				continue;
			}
			if (entity.distanceToSqr(player) <= HOSTILE_RADIUS_SQUARED) {
				count++;
			}
		}
		return count;
	}
}
