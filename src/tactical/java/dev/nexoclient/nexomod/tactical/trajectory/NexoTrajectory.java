package dev.nexoclient.nexomod.tactical.trajectory;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Where the arrow would go, for the projectile currently in hand.
 *
 * <h2>Why simulate instead of integrate a formula</h2>
 *
 * <p>Arrows are not ballistic: every tick they are moved, then dragged
 * ({@code *0.99}), then pulled down ({@code -0.05}). That is a discrete
 * recurrence, and the closed form is only equal to it at the tick boundaries —
 * which is exactly where the collision check happens. So this runs the same
 * three lines vanilla runs, one tick at a time, and clips each segment against
 * block colliders as it goes. The preview is the flight, not an approximation
 * of it.
 *
 * <p>Entity hits are not predicted: which entities the server will actually
 * have in the path is not knowable client-side, and a line that stops on a
 * player who then doesn't take the hit is worse than no line at all. Blocks
 * only.
 *
 * <p>Simulated once per client tick rather than per frame — a few hundred
 * raycasts per frame for a curve that cannot change within a tick is waste.
 */
public final class NexoTrajectory {
	private static final int COLOUR = 0xFF3CFFB0;
	private static final int IMPACT_COLOUR = 0xFFFF4D4D;
	private static final float WIDTH = 2.0f;
	/** Vanilla spawns a projectile at the owner's x/z, eye height minus a tenth. */
	private static final double SPAWN_DROP = 0.1;

	private static List<Vec3> path = List.of();
	private static Vec3 impact;

	private NexoTrajectory() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(NexoTrajectory::tick);
		LevelRenderEvents.BEFORE_GIZMOS.register(context -> render());
	}

	/**
	 * @param throwable gravity and drag are applied before the move (ThrowableProjectile.tick);
	 *                  arrows move first, then drag, then gravity
	 * @param drag      per-tick velocity multiplier (0.99, or 1.0 for a wind charge)
	 * @param ownerMotion whether the shooter's own velocity is added (not for a crossbow)
	 */
	private record Shot(double speed, double gravity, float pitchOffset, boolean throwable, double drag,
			boolean ownerMotion) {
		static Shot arrow(double speed) {
			return new Shot(speed, 0.05, 0.0F, false, 0.99, true);
		}

		static Shot thrown(double speed, double gravity, float pitchOffset) {
			return new Shot(speed, gravity, pitchOffset, true, 0.99, true);
		}
	}

	private static void tick(Minecraft client) {
		path = List.of();
		impact = null;
		if (!NexoConfig.get().trajectoryEnabled() || client.player == null || client.level == null) {
			return;
		}
		LocalPlayer player = client.player;
		Shot shot = shotFor(player);
		if (shot == null) {
			return;
		}
		// shootFromRotation: the pitch offset only enters the vertical term, then the vector is normalised.
		double yawRad = Math.toRadians(player.getYRot());
		double pitchRad = Math.toRadians(player.getXRot());
		Vec3 view = new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad),
				-Math.sin(Math.toRadians(player.getXRot() + shot.pitchOffset())),
				Math.cos(yawRad) * Math.cos(pitchRad)).normalize();
		Vec3 eye = player.getEyePosition();
		Vec3 start = new Vec3(player.getX(), eye.y - SPAWN_DROP, player.getZ());
		// shootFromRotation adds the shooter's own motion (vertical only when airborne).
		Vec3 motion = player.getDeltaMovement();
		Vec3 velocity = view.scale(shot.speed());
		if (shot.ownerMotion()) {
			velocity = velocity.add(motion.x, player.onGround() ? 0.0 : motion.y, motion.z);
		}
		List<Vec3> points = new ArrayList<>();
		points.add(start);
		int ticks = NexoConfig.get().trajectoryTicks();
		for (int i = 0; i < ticks; i++) {
			if (shot.throwable()) {
				velocity = velocity.add(0, -shot.gravity(), 0).scale(shot.drag());
			}
			Vec3 next = start.add(velocity);
			BlockHitResult hit = client.level.clip(new ClipContext(start, next,
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			if (hit.getType() != HitResult.Type.MISS) {
				points.add(hit.getLocation());
				impact = hit.getLocation();
				break;
			}
			points.add(next);
			start = next;
			if (!shot.throwable()) {
				// Arrows: move, then drag, then gravity.
				velocity = velocity.scale(shot.drag()).add(0, -shot.gravity(), 0);
			}
		}
		path = List.copyOf(points);
	}

	/** Null when the held item is not something with a predictable flight. */
	private static Shot shotFor(LocalPlayer player) {
		ItemStack using = player.isUsingItem() ? player.getUseItem() : ItemStack.EMPTY;
		// A drawn bow or charged trident beats everything else, including an
		// offhand throwable.
		if (using.is(Items.BOW)) {
			float power = BowItem.getPowerForTime(player.getTicksUsingItem());
			// Under 0.1 vanilla does not release the arrow at all.
			return power < 0.1F ? null : Shot.arrow(power * 3.0);
		}
		if (using.is(Items.TRIDENT)) {
			return Shot.arrow(2.5);
		}
		ItemStack main = player.getMainHandItem();
		ItemStack off = player.getOffhandItem();
		for (ItemStack stack : new ItemStack[] { main, off }) {
			if (stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) {
				// Fired with shoot(), not shootFromRotation: no owner motion. Fireworks fly slower.
				boolean firework = stack.getOrDefault(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES,
						net.minecraft.world.item.component.ChargedProjectiles.EMPTY).contains(Items.FIREWORK_ROCKET);
				return new Shot(firework ? 1.6 : 3.15, firework ? 0.0 : 0.05, 0.0F, false, 0.99, false);
			}
		}
		for (ItemStack stack : new ItemStack[] { main, off }) {
			Shot thrown = throwable(stack);
			if (thrown != null) {
				return thrown;
			}
		}
		return null;
	}

	/** Speed, gravity and launch-angle offset read from the item/entity classes of this game version. */
	private static Shot throwable(ItemStack stack) {
		if (stack.is(Items.SNOWBALL) || stack.is(Items.EGG) || stack.is(Items.ENDER_PEARL)) {
			return Shot.thrown(1.5, 0.03, 0.0F);
		}
		if (stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION)) {
			return Shot.thrown(0.5, 0.05, -20.0F);
		}
		if (stack.is(Items.EXPERIENCE_BOTTLE)) {
			return Shot.thrown(0.7, 0.07, -20.0F);
		}
		if (stack.is(Items.WIND_CHARGE)) {
			// No gravity and no drag (inertia 1.0).
			return new Shot(1.5, 0.0, 0.0F, true, 1.0, true);
		}
		return null;
	}

	private static void render() {
		if (path.size() < 2 || NexoHudVisibility.hidden()) {
			return;
		}
		// Fades with distance along the flight: the near end is where you are aiming, the far end is a guess.
		int segments = path.size() - 1;
		for (int i = 1; i <= segments; i++) {
			int alpha = 0xFF - (int) (0xB0 * ((double) (i - 1) / Math.max(1, segments)));
			Gizmos.line(path.get(i - 1), path.get(i), (alpha << 24) | (COLOUR & 0xFFFFFF), WIDTH).setAlwaysOnTop();
		}
		if (impact != null) {
			Vec3 p = impact;
			double arm = 0.25;
			Gizmos.cuboid(new AABB(p.x - arm, p.y - arm, p.z - arm, p.x + arm, p.y + arm, p.z + arm),
					GizmoStyle.stroke(IMPACT_COLOUR)).setAlwaysOnTop();
		}
	}
}
