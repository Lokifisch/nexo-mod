package dev.nexoclient.nexomod.tactical.aim;

import java.util.concurrent.ThreadLocalRandom;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.tactical.freecam.NexoFreecam;

/**
 * Nudges the player's view toward the best target in a cone, a fraction of the
 * remaining angle per tick, so the mouse stays in control and strength reads
 * as "how hard it pulls". Tactical because it acts with input the player did
 * not give. Off by default.
 *
 * <h2>Making the pull look like a hand</h2>
 *
 * <p>A proportional pull applied every tick is trivially distinguishable from
 * a player: the angular velocity is a fixed function of the error (so it decays
 * exponentially, which no hand does), the first tick is already at full speed
 * (no reaction time), and the deltas land on arbitrary floats instead of the
 * mouse grid vanilla produces. Each of those is something the aim checks of a
 * serious anticheat test for, so this module adds, per engagement:
 *
 * <ul>
 * <li>a reaction pause before the first pull ({@code aimAssistReactionMin}..{@code Max}
 *     ticks),</li>
 * <li>an ease-in ramp over the first few ticks ({@code aimAssistRamp}),</li>
 * <li>per-tick speed noise ({@code aimAssistNoise}), a chance of one deliberate
 *     overshoot the next ticks correct ({@code aimAssistOvershoot}), a chance of
 *     skipping a tick entirely ({@code aimAssistSkip}), and an overall pull
 *     speed below 1 ({@code aimAssistSpeed}),</li>
 * <li>and every step snapped to the vanilla mouse quantum (see
 *     {@link NexoMouseModel}) unless {@code aimAssistSnap} is off, so the
 *     rotation the server receives could have come from a mouse.</li>
 * </ul>
 *
 * <p>Each knob is its own config value and its own slider; the Stealth slider on
 * the screen only writes a preset set of them. Turn them all off and this is the
 * raw proportional pull it started as — which is the fastest and the most
 * obvious.
 *
 * <p>None of that makes it undetectable — a perfect hit rate and a rotation
 * that ends exactly on a hitbox point are still statistical evidence. It only
 * removes the mechanical tells.
 */
public final class NexoAimAssist {
	private NexoAimAssist() {
	}

	private static KeyMapping toggleKey;

	/** Entity id of the target this engagement belongs to, or -1 when none. */
	private static int engagedId = -1;
	/** Ticks still to wait before the first pull: reaction time, not smoothing. */
	private static int reactionTicks;
	/** Ticks spent pulling on this engagement, for the ease-in ramp. */
	private static int engagedTicks;
	/** Whether this engagement starts with a deliberate overshoot. */
	private static boolean overshoot;
	/** Set on a tick where the view was actually moved; read by the trigger bot. */
	private static boolean aimedThisTick;

	public static void register() {
		// Unbound by default, like the other Tactical toggles.
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.aimAssist",
				InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(NexoAimAssist::tick);
	}

	private static void tick(Minecraft client) {
		while (toggleKey.consumeClick()) {
			NexoConfig c = NexoConfig.get();
			c.setAimAssistEnabled(!c.aimAssistEnabled());
			if (client.player != null) {
				client.player.sendOverlayMessage(CommonComponents.optionStatus(
						Component.translatable("nexomod.qol.aimAssist"), c.aimAssistEnabled()));
			}
		}
		LocalPlayer player = client.player;
		NexoConfig config = NexoConfig.get();
		aimedThisTick = false;
		if (player == null || client.level == null || !config.aimAssistEnabled() || client.gui.screen() != null
				|| player.isSpectator() || NexoFreecam.active()
				|| (config.aimAssistOnlyAttacking() && !client.options.keyAttack.isDown())) {
			reset();
			return;
		}
		Vec3 eye = player.getEyePosition();
		double range = config.aimAssistRange();
		float halfFov = config.aimAssistFov() / 2.0F;
		LivingEntity best = null;
		float bestYaw = 0;
		float bestPitch = 0;
		double bestScore = Double.MAX_VALUE;
		for (LivingEntity target : client.level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(range), e -> e != player && e != player.getVehicle() && e.isAlive() && !e.isSpectator() && !e.isInvisible()
						&& !(e instanceof ArmorStand) && wanted(e, config.aimAssistTargets()))) {
			Vec3 point = aimPoint(target, eye, config.aimAssistPoint());
			Vec3 d = point.subtract(eye);
			double flat = Math.sqrt(d.x * d.x + d.z * d.z);
			// Straight above/below the eye has no meaningful yaw.
			if (d.length() > range || flat < 1.0E-3) {
				continue;
			}
			float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
			float pitch = (float) -(Mth.atan2(d.y, flat) * Mth.RAD_TO_DEG);
			float dYaw = Mth.wrapDegrees(yaw - player.getYRot());
			float dPitch = pitch - player.getXRot();
			double off = Math.hypot(dYaw, dPitch);
			// Hysteresis: an engaged target wins ties, so a second entity drifting
			// through the cone does not restart the reaction delay every other tick.
			double score = target.getId() == engagedId ? off * 0.8 : off;
			if (off <= halfFov && score < bestScore && canSee(client, player, eye, point)) {
				best = target;
				bestScore = score;
				bestYaw = dYaw;
				bestPitch = dPitch;
			}
		}
		if (best == null) {
			reset();
			return;
		}
		if (best.getId() != engagedId) {
			engagedId = best.getId();
			engagedTicks = 0;
			// Reaction time: the hand has to notice the target before it moves.
			int reactionMin = Math.min(config.aimAssistReactionMin(), config.aimAssistReactionMax());
			int reactionMax = Math.max(config.aimAssistReactionMin(), config.aimAssistReactionMax());
			reactionTicks = reactionMin + ThreadLocalRandom.current().nextInt(reactionMax - reactionMin + 1);
			overshoot = ThreadLocalRandom.current().nextInt(100) < config.aimAssistOvershoot();
		}
		if (reactionTicks > 0) {
			reactionTicks--;
			return;
		}
		engagedTicks++;
		// A hand does not correct on every single tick either; skipping one now and
		// then breaks the perfectly periodic tracking signature.
		if (engagedTicks > 3 && ThreadLocalRandom.current().nextInt(100) < config.aimAssistSkip()) {
			return;
		}

		float pull = config.aimAssistStrength() / 200.0F;
		float maxStep = 1.0F + config.aimAssistStrength() * 0.15F; // degrees per tick, so a wide FOV can't yank the view
		// Ease-in over the first ticks plus per-tick speed noise: the angular
		// velocity is never the same function of the error twice, which is what
		// separates this from an exponential-decay "pull" an aim check can model.
		float rampFloor = config.aimAssistRamp() / 100.0F;
		float noise = config.aimAssistNoise() / 100.0F;
		float ramp = Math.min(1.0F, rampFloor + engagedTicks * 0.2F);
		float gain = pull * ramp * (config.aimAssistSpeed() / 100.0F)
				* (1.0F - noise * 0.5F + ThreadLocalRandom.current().nextFloat() * noise);
		float yawStep = bestYaw * gain;
		float pitchStep = bestPitch * gain;
		if (overshoot) {
			// One deliberate overshoot on the approach, then the normal pull has to
			// come back for it. Overshooting is what hands do; clients do not.
			overshoot = false;
			yawStep = bestYaw * 1.15F;
			pitchStep = bestPitch * 1.15F;
		}
		yawStep = Mth.clamp(yawStep, -maxStep, maxStep);
		pitchStep = Mth.clamp(pitchStep, -maxStep, maxStep);

		// Snap to the vanilla mouse grid: the server only ever sees rotation a real
		// mouse could have produced, which is the whole point of NexoMouseModel.
		// Switching it off is the "maximum accuracy, maximum smell" option.
		double quantum = config.aimAssistSnap() ? NexoMouseModel.quantum(client.options.sensitivity().get()) : 0.0;
		float dYaw = quantum > 0.0 ? NexoMouseModel.step(yawStep, quantum) : yawStep;
		float dPitch = quantum > 0.0 ? NexoMouseModel.step(pitchStep, quantum) : pitchStep;
		if (dYaw == 0.0F && dPitch == 0.0F) {
			return; // under one mouse count: a hand stops here too
		}
		player.setYRot(player.getYRot() + dYaw);
		player.setXRot(Mth.clamp(player.getXRot() + dPitch, -90.0F, 90.0F));
		aimedThisTick = true;
	}

	/**
	 * Whether the aim assist moved the view on the tick just processed. The
	 * trigger bot reads this so it does not fire the swing on the exact tick a
	 * correction lands — attacking mid-snap, always with the crosshair ending on
	 * the hitbox centre, is its own signature.
	 */
	public static boolean aimedThisTick() {
		return aimedThisTick;
	}

	private static void reset() {
		engagedId = -1;
		reactionTicks = 0;
		engagedTicks = 0;
		overshoot = false;
	}

	/** Clear of blocks between the eye and the point actually aimed at, not just eye to eye. */
	private static boolean canSee(Minecraft client, LocalPlayer player, Vec3 eye, Vec3 point) {
		return client.level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
				.getType() == HitResult.Type.MISS;
	}

	/** Nearest clamps the eye onto the hitbox, so below a step it picks the feet and above one the head. */
	private static Vec3 aimPoint(LivingEntity t, Vec3 eye, int mode) {
		AABB box = t.getBoundingBox();
		Vec3 c = box.getCenter();
		Vec3 p = switch (mode) {
			case 1 -> t.getEyePosition();
			case 2 -> c;
			case 3 -> new Vec3(c.x, box.minY + 0.1, c.z);
			default -> new Vec3(Mth.clamp(eye.x, box.minX, box.maxX), Mth.clamp(eye.y, box.minY, box.maxY),
					Mth.clamp(eye.z, box.minZ, box.maxZ));
		};
		// Eye inside the box (point-blank): nothing to aim at but the centre.
		return p.distanceToSqr(eye) < 1.0E-4 ? c : p;
	}

	public static boolean wanted(LivingEntity e, int mode) {
		return switch (mode) {
			case 0 -> e instanceof Player;
			case 1 -> e instanceof Enemy;
			default -> true;
		};
	}
}
