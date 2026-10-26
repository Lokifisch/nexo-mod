package dev.nexoclient.nexomod.tactical.combat;

import java.util.Locale;

import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Damage dealt and taken per fight, printed once the fight has been quiet for
 * {@link #QUIET_MS}. The client never receives damage values, so like the
 * damage numbers this works from health: "taken" is the player's own health
 * drops, "dealt" is health drops of entities you have attacked this fight —
 * which also counts damage someone else did to them meanwhile.
 */
public final class NexoCombatLog {
	private static final long QUIET_MS = 8000;
	private static final float MIN_CHANGE = 0.05F;

	/** Entities attacked this fight, id to last seen health. */
	private static final Int2FloatMap targets = new Int2FloatOpenHashMap();
	private static float dealt;
	private static float taken;
	private static float lastOwnHealth = -1;
	private static long start;
	private static long lastEvent;

	private NexoCombatLog() {
	}

	public static void register() {
		ClientPreAttackCallback.EVENT.register((client, player, button) -> {
			if (NexoConfig.get().combatLogEnabled() && client.hitResult instanceof EntityHitResult hit
					&& hit.getEntity() instanceof LivingEntity target) {
				targets.putIfAbsent(target.getId(), target.getHealth());
				touch();
			}
			return false;
		});
		ClientTickEvents.END_CLIENT_TICK.register(NexoCombatLog::tick);
	}

	private static void touch() {
		long now = System.currentTimeMillis();
		if (start == 0) {
			start = now;
		}
		lastEvent = now;
	}

	private static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.level == null || !NexoConfig.get().combatLogEnabled()) {
			reset();
			return;
		}
		float own = player.getHealth();
		if (lastOwnHealth >= 0 && lastOwnHealth - own > MIN_CHANGE) {
			taken += lastOwnHealth - own;
			touch();
		}
		lastOwnHealth = own;

		for (var it = targets.int2FloatEntrySet().iterator(); it.hasNext();) {
			var entry = it.next();
			Entity entity = client.level.getEntity(entry.getIntKey());
			if (!(entity instanceof LivingEntity living)) {
				continue;
			}
			float health = living.getHealth();
			if (entry.getFloatValue() - health > MIN_CHANGE) {
				dealt += entry.getFloatValue() - health;
				touch();
			}
			entry.setValue(health);
		}

		if (start != 0 && System.currentTimeMillis() - lastEvent > QUIET_MS) {
			if (dealt > 0 || taken > 0) {
				player.sendSystemMessage(Component.translatable("nexomod.combat.summary",
						String.format(Locale.ROOT, "%.1f", dealt), String.format(Locale.ROOT, "%.1f", taken),
						Math.max(1, (lastEvent - start) / 1000)));
			}
			reset();
		}
	}

	private static void reset() {
		targets.clear();
		dealt = 0;
		taken = 0;
		lastOwnHealth = -1;
		start = 0;
	}
}
