package dev.nexoclient.nexomod.tactical.light;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * F7-style spawn-proofing markers: a flat square on every nearby block a
 * hostile could spawn on.
 *
 * <p>Tactical, not light-jar. The block-light value behind this decision is a
 * number the vanilla client has but only shows on the F3 screen, and the
 * spawnability rule it feeds is not shown anywhere at all — that is the same
 * "information vanilla withholds" test the sound radar and the hole finder
 * pass, and it is why this cannot be a keybind convenience the way zoom is.
 *
 * <h2>Cost</h2>
 *
 * <p>The scan is the expensive part and it is <em>not</em> in the render path.
 * A radius-24 scan is 49×49 columns, and running that every frame would burn
 * the same work 60+ times for a result that can only change on a block update.
 * Instead {@link #scan} runs at most once every {@link #SCAN_INTERVAL_MILLIS}
 * on the client thread, writes an immutable list, and the render path only
 * walks that list. {@link #MAX_MARKERS} caps what a single scan can produce,
 * so standing in a large dark cave cannot turn this into a frame-time cliff.
 *
 * <p>Drawing goes through {@code net.minecraft.gizmos}, the same vanilla
 * debug-draw API {@code BedrockHoleFinder} already uses, rather than hand-built
 * LINES geometry — which also side-steps this version's requirement that every
 * LINES vertex carry its own line width.
 */
public final class NexoLightOverlay {
	private static final long SCAN_INTERVAL_MILLIS = 1000L;
	private static final int MAX_MARKERS = 2048;
	/** Vertical span around the player the scan covers, in blocks. */
	private static final int VERTICAL_RADIUS = 8;
	/** How far above the block face the marker floats, so it doesn't z-fight the surface. */
	private static final double MARKER_LIFT = 0.02;

	private static final int COLOR_SPAWNABLE = 0xB0FF3030;
	private static final int COLOR_SAFE = 0x6030FF60;

	private static volatile List<Marker> markers = List.of();
	private static long lastScanMillis;

	private record Marker(BlockPos pos, boolean spawnable) {
	}

	private NexoLightOverlay() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> maybeScan());
		LevelRenderEvents.BEFORE_GIZMOS.register(context -> render());
	}

	private static void maybeScan() {
		NexoConfig config = NexoConfig.get();
		if (!config.lightOverlayEnabled()) {
			markers = List.of();
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastScanMillis < SCAN_INTERVAL_MILLIS) {
			return;
		}
		lastScanMillis = now;

		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		if (player == null || level == null) {
			markers = List.of();
			return;
		}
		markers = scan(player.blockPosition(), level, config);
	}

	private static List<Marker> scan(BlockPos center, ClientLevel level, NexoConfig config) {
		int radius = config.lightOverlayRadius();
		int threshold = config.lightOverlayThreshold();
		boolean showSafe = config.lightOverlayShowSafe();

		List<Marker> found = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius && found.size() < MAX_MARKERS; dx++) {
			for (int dz = -radius; dz <= radius && found.size() < MAX_MARKERS; dz++) {
				for (int dy = -VERTICAL_RADIUS; dy <= VERTICAL_RADIUS; dy++) {
					pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (!canSpawnOn(level, pos)) {
						continue;
					}
					int light = level.getLightEngine()
							.getLayerListener(LightLayer.BLOCK)
							.getLightValue(pos.above());
					boolean spawnable = light <= threshold;
					if (!spawnable && !showSafe) {
						continue;
					}
					found.add(new Marker(pos.immutable(), spawnable));
					if (found.size() >= MAX_MARKERS) {
						break;
					}
				}
			}
		}
		return List.copyOf(found);
	}

	/**
	 * The surface test only — light is checked separately by the caller. A mob
	 * needs a sturdy top face to stand on and two free blocks above it; anything
	 * that fails either is not a spawn spot whatever its light level.
	 */
	private static boolean canSpawnOn(ClientLevel level, BlockPos pos) {
		BlockState floor = level.getBlockState(pos);
		if (floor.isAir() || !floor.isFaceSturdy(level, pos, Direction.UP)) {
			return false;
		}
		BlockPos above = pos.above();
		return isPassable(level, above) && isPassable(level, above.above());
	}

	/**
	 * Empty collision shape rather than {@code blocksMotion()}, which is
	 * deprecated in favour of the position-aware form — a block's collision can
	 * depend on its position and neighbours (fences, doors), and the deprecated
	 * call cannot see either.
	 */
	private static boolean isPassable(ClientLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private static void render() {
		if (NexoHudVisibility.hidden() || !NexoConfig.get().lightOverlayEnabled()) {
			return;
		}
		List<Marker> current = markers;
		if (current.isEmpty()) {
			return;
		}
		for (Marker marker : current) {
			BlockPos pos = marker.pos();
			double y = pos.getY() + 1 + MARKER_LIFT;
			// A zero-height cuboid is a flat quad lying on the block's top face,
			// which is what a spawn-proofing marker wants — a full cube would
			// swallow the block underneath it.
			AABB face = new AABB(pos.getX(), y, pos.getZ(), pos.getX() + 1, y, pos.getZ() + 1);
			Gizmos.cuboid(face, GizmoStyle.fill(marker.spawnable() ? COLOR_SPAWNABLE : COLOR_SAFE));
		}
	}
}
