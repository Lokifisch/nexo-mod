package dev.nexoclient.nexomod.coords;

import java.security.SecureRandom;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Per-session random X/Z offset applied to every position-obscuring feature
 * (F3 coordinates, block model seeds). Each axis gets its own offset of
 * 3,000–700,000 blocks in a random direction, re-rolled on every world/server
 * join.
 *
 * Uniqueness across players: each game instance's {@link SecureRandom} seeds
 * itself from the OS entropy pool, so no two instances ever share a sequence,
 * and with ~1.4 million values per axis (~2×10¹² X/Z pairs) two concurrent
 * sessions landing on the same offsets is vanishingly unlikely — that's as
 * strong a "never the same" guarantee as exists without a coordination server.
 *
 * Fields are volatile because {@link #rotationActive()} and
 * {@link #obscure(BlockPos)} run on chunk-meshing worker threads (via the
 * block-seed mixin), while joins and settings changes write from the render
 * thread. The rotation flag caches the config lookup so the per-block hot
 * path never touches {@link NexoConfig}'s synchronized accessor.
 */
public final class CoordObfuscator {
	/** When the current world was joined, for the Xaero compatibility check. */
	private static volatile long joinedAt;

	private static final int MIN_MAGNITUDE = 3_000;
	private static final int MAX_MAGNITUDE = 700_000;
	/** Blocks at this height or below render as bedrock when the bedrock-floor disguise is on. */
	public static final int BEDROCK_FLOOR_MAX_Y = -60;
	private static final SecureRandom RANDOM = new SecureRandom();

	private static volatile int offsetX = roll();
	private static volatile int offsetZ = roll();
	private static volatile boolean rotationActive;
	private static volatile boolean bedrockFloorActive;

	private CoordObfuscator() {
	}

	public static void register() {
		refreshMeshFlags();
		// Offsets are re-rolled on every join whether or not obscuring is on, so
		// enabling it mid-session reveals a session-stable fake position instead
		// of reusing one from an earlier world.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			offsetX = roll();
			offsetZ = roll();
			joinedAt = System.currentTimeMillis();
		});
		// Checked on a tick rather than at join: the Xaero patches only report
		// in once they have actually drawn something, which is necessarily
		// after the world has loaded.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (joinedAt != 0) {
				XaeroCompat.checkAfterJoin(System.currentTimeMillis() - joinedAt);
			}
		});
		ObfuscatedDebugEntries.install();
	}

	private static int roll() {
		int magnitude = MIN_MAGNITUDE + RANDOM.nextInt(MAX_MAGNITUDE - MIN_MAGNITUDE + 1);
		return RANDOM.nextBoolean() ? magnitude : -magnitude;
	}

	/**
	 * Call after any change to the position-obscuring settings. Refreshes the
	 * meshing-thread flags and, when a mesh-affecting behavior actually flipped
	 * while in a world, rebuilds all chunk meshes so the change is visible
	 * immediately instead of only in newly-loaded chunks.
	 */
	public static void onSettingsChanged() {
		boolean wasRotating = rotationActive;
		boolean wasBedrock = bedrockFloorActive;
		refreshMeshFlags();
		Minecraft minecraft = Minecraft.getInstance();
		if ((rotationActive != wasRotating || bedrockFloorActive != wasBedrock) && minecraft.level != null) {
			minecraft.levelRenderer.allChanged();
		}
	}

	private static void refreshMeshFlags() {
		NexoConfig config = NexoConfig.get();
		rotationActive = config.obscureBlockRotationActive();
		bedrockFloorActive = config.obscureBedrockFloorActive();
	}

	/** Whether the F3 screen should show obscured coordinates. */
	public static boolean active() {
		return NexoConfig.get().obscureCoordinatesActive();
	}

	/** Whether block model seeds should derive from obscured positions. Safe on meshing threads. */
	public static boolean rotationActive() {
		return rotationActive;
	}

	/** Whether non-air blocks at {@link #BEDROCK_FLOOR_MAX_Y} or below render as bedrock. Safe on meshing threads. */
	public static boolean bedrockFloorActive() {
		return bedrockFloorActive;
	}

	public static double obscureX(double x) {
		return x + offsetX;
	}

	public static double obscureZ(double z) {
		return z + offsetZ;
	}

	public static BlockPos obscure(BlockPos pos) {
		return pos.offset(offsetX, 0, offsetZ);
	}
}
