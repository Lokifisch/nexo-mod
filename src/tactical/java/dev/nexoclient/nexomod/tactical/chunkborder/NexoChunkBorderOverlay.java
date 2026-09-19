package dev.nexoclient.nexomod.tactical.chunkborder;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * A grid of lines along real chunk boundaries — the same information F3+G
 * shows, permanently on instead of a debug-key toggle, and with the player's
 * current chunk picked out with vertical edges so it reads at a glance while
 * moving.
 *
 * <p>Drawn through {@code net.minecraft.gizmos}, the same vanilla debug-draw
 * API {@link dev.nexoclient.nexomod.tactical.bedrock.BedrockHoleFinder} and
 * {@link dev.nexoclient.nexomod.tactical.light.NexoLightOverlay} use, rather
 * than a hand-built {@code PoseStack}/{@code VertexConsumer} pair: this used
 * to draw on {@code LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN} with raw
 * world coordinates fed straight into that event's {@code PoseStack}, which
 * turned out to carry no camera translation at all in this MC version — every
 * line rendered offset by the camera's real position, i.e. floating in the
 * sky. {@code Gizmos} takes plain world coordinates and does its own
 * camera-relative transform per primitive, which is what this overlay always
 * actually wanted.
 */
public final class NexoChunkBorderOverlay {
	/** Chunks in each direction from the player's own chunk that the ground grid covers. */
	private static final int GRID_RADIUS_CHUNKS = 6;
	/** Blocks above and below the player's feet the current-chunk edges are drawn for. */
	private static final int EDGE_HEIGHT = 48;

	private static final int COLOR_GRID = 0x3CFFFFFF;
	private static final int COLOR_EDGE = 0xDCFF00C8;

	private NexoChunkBorderOverlay() {
	}

	public static void register() {
		LevelRenderEvents.BEFORE_GIZMOS.register(context -> render());
	}

	private static void render() {
		if (NexoHudVisibility.hidden() || !NexoConfig.get().chunkBorderOverlayEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		if (player == null || level == null) {
			return;
		}

		ChunkPos playerChunk = player.chunkPosition();

		drawGrid(playerChunk, (int) Math.floor(player.getY()));
		drawCurrentChunkEdges(playerChunk, player.getY(), level.getMinY(), level.getMaxY());
	}

	private static void drawGrid(ChunkPos center, int y) {
		int baseX = center.getMinBlockX();
		int baseZ = center.getMinBlockZ();
		int span = GRID_RADIUS_CHUNKS * 16;

		for (int i = -GRID_RADIUS_CHUNKS; i <= GRID_RADIUS_CHUNKS + 1; i++) {
			int x = baseX + i * 16;
			line(x, y, baseZ - span, x, y, baseZ + span + 16, COLOR_GRID, 1f);
			int z = baseZ + i * 16;
			line(baseX - span, y, z, baseX + span + 16, y, z, COLOR_GRID, 1f);
		}
	}

	private static void drawCurrentChunkEdges(ChunkPos chunk, double playerY, int minY, int maxY) {
		int x0 = chunk.getMinBlockX();
		int x1 = x0 + 16;
		int z0 = chunk.getMinBlockZ();
		int z1 = z0 + 16;
		int yLow = Math.max(minY, (int) Math.floor(playerY) - EDGE_HEIGHT);
		int yHigh = Math.min(maxY, (int) Math.floor(playerY) + EDGE_HEIGHT);

		line(x0, yLow, z0, x0, yHigh, z0, COLOR_EDGE, 2f);
		line(x1, yLow, z0, x1, yHigh, z0, COLOR_EDGE, 2f);
		line(x0, yLow, z1, x0, yHigh, z1, COLOR_EDGE, 2f);
		line(x1, yLow, z1, x1, yHigh, z1, COLOR_EDGE, 2f);
	}

	private static void line(double x0, double y0, double z0, double x1, double y1, double z1, int color, float width) {
		Gizmos.line(new Vec3(x0, y0, z0), new Vec3(x1, y1, z1), color, width);
	}
}
