package dev.nexoclient.nexomod.tactical.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import dev.nexoclient.nexomod.tactical.aim.NexoAimAssist;
import dev.nexoclient.nexomod.tactical.aim.NexoMouseModel;

/**
 * Shared targeting, blast-exposure, hotbar-selection, rotation and block-face
 * helpers for the crystal and anchor auras. Package-private: these exist so the
 * two modules do not each carry a second copy of the same plumbing.
 */
final class NexoAuraUtil {
	private NexoAuraUtil() {
	}

	/** Nearest valid target within {@code range}, following the configured target set. */
	static LivingEntity bestTarget(Level level, LocalPlayer player, double range, int targets) {
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(range),
				e -> e != player && e != player.getVehicle() && e.isAlive() && !e.isSpectator() && !e.isInvisible()
						&& !(e instanceof ArmorStand) && NexoAimAssist.wanted(e, targets))) {
			double d = e.distanceToSqr(player);
			if (d < bestDist) {
				bestDist = d;
				best = e;
			}
		}
		return best;
	}

	/**
	 * Fraction of {@code target}'s hitbox that a blast centred on {@code center}
	 * actually reaches (0..1), the same ray-grid the server uses: sample points
	 * over the box, and a ray is "seen" only if no colliding block sits between
	 * it and the blast centre.
	 */
	static float seenPercent(Level level, Vec3 center, Entity target) {
		AABB box = target.getBoundingBox();
		double dx = 1.0D / ((box.maxX - box.minX) * 2.0D + 1.0D);
		double dy = 1.0D / ((box.maxY - box.minY) * 2.0D + 1.0D);
		double dz = 1.0D / ((box.maxZ - box.minZ) * 2.0D + 1.0D);
		if (dx <= 0.0D || dy <= 0.0D || dz <= 0.0D) {
			return 0.0F;
		}
		double gx = (1.0D - Math.floor(1.0D / dx) * dx) / 2.0D;
		double gz = (1.0D - Math.floor(1.0D / dz) * dz) / 2.0D;
		int hits = 0;
		int total = 0;
		for (double x = 0.0D; x <= 1.0D; x += dx) {
			for (double y = 0.0D; y <= 1.0D; y += dy) {
				for (double z = 0.0D; z <= 1.0D; z += dz) {
					Vec3 point = new Vec3(lerp(x, box.minX, box.maxX) + gx, lerp(y, box.minY, box.maxY),
							lerp(z, box.minZ, box.maxZ) + gz);
					// Pass the target itself: the ray starts on its own hitbox, so
					// the entity must be ignored or the first clip would hit it.
					if (level.clip(new ClipContext(point, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
							target)).getType() == HitResult.Type.MISS) {
						hits++;
					}
					total++;
				}
			}
		}
		return (float) hits / (float) total;
	}

	/** Damage an explosion at {@code center} would deal the local player, after armour. */
	static float selfDamage(Level level, LocalPlayer player, Vec3 center, float power) {
		float seen = seenPercent(level, center, player);
		return NexoExplosionMath.afterArmor(NexoExplosionMath.rawDamage(power, center.distanceToSqr(player.position()), seen),
				player.getArmorValue());
	}

	/** Raw damage an explosion at {@code center} would deal the target, for ranking placements. */
	static float targetDamage(Level level, LivingEntity target, Vec3 center, float power) {
		return NexoExplosionMath.rawDamage(power, center.distanceToSqr(target.position()), seenPercent(level, center, target));
	}

	/**
	 * Selects a hotbar slot. Deliberately no {@code ServerboundSetCarriedItemPacket}:
	 * {@code MultiPlayerGameMode.attack} and {@code useItemOn} each call
	 * {@code ensureHasSentCarriedItem} first, which sends that packet exactly once
	 * and keeps its {@code carriedIndex} bookkeeping in step. A manual send here
	 * bypasses that and is how the client's held slot desyncs from the server.
	 */
	static void selectSlot(LocalPlayer player, int slot) {
		if (slot < 0 || slot > 8 || player.getInventory().getSelectedSlot() == slot) {
			return;
		}
		player.getInventory().setSelectedSlot(slot);
	}

	/** First hotbar slot (0-8) holding {@code item}, or -1. */
	static int findSlot(LocalPlayer player, Item item) {
		for (int i = 0; i < 9; i++) {
			if (player.getInventory().getItem(i).is(item)) {
				return i;
			}
		}
		return -1;
	}

	/** A hotbar slot whose item is not glowstone (an empty one preferred), for triggering an anchor. */
	static int nonFuelSlot(LocalPlayer player) {
		for (int i = 0; i < 9; i++) {
			if (player.getInventory().getItem(i).isEmpty()) {
				return i;
			}
		}
		for (int i = 0; i < 9; i++) {
			if (!player.getInventory().getItem(i).is(Items.GLOWSTONE)) {
				return i;
			}
		}
		return -1;
	}

	/** Turns the player to face {@code point}, snapped to the vanilla mouse grid. */
	static void lookAt(Minecraft client, LocalPlayer player, Vec3 point) {
		Vec3 d = point.subtract(player.getEyePosition());
		double flat = Math.sqrt(d.x * d.x + d.z * d.z);
		if (flat < 1.0E-3D) {
			return;
		}
		float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
		float pitch = (float) -(Mth.atan2(d.y, flat) * Mth.RAD_TO_DEG);
		double quantum = NexoMouseModel.quantum(client.options.sensitivity().get());
		// Snap to the vanilla mouse grid, then let the client's own movement sync
		// carry the rotation to the server. A manual Rot packet is the loudest
		// thing this mod could send — anticheats kick for it.
		player.setYRot(NexoMouseModel.snap(Mth.wrapDegrees(yaw), quantum));
		player.setXRot(NexoMouseModel.snap(Mth.clamp(pitch, -90.0F, 90.0F), quantum));
	}

	/** Centre of the top face of {@code base} — where a block or crystal placed above it is clicked. */
	static Vec3 topFaceCenter(BlockPos base) {
		return new Vec3(base.getX() + 0.5D, base.getY() + 1.0D, base.getZ() + 0.5D);
	}

	static BlockHitResult topFaceHit(BlockPos base) {
		return new BlockHitResult(topFaceCenter(base), Direction.UP, base, false);
	}

	private static double lerp(double t, double a, double b) {
		return a + t * (b - a);
	}
}
