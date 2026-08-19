package dev.nexoclient.nexomod.tactical.chunkborder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.level.ChunkPos;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * A grid of lines along real chunk boundaries — the same information F3+G
 * shows, permanently on instead of a debug-key toggle, and with the player's
 * current chunk picked out with vertical edges so it reads at a glance while
 * moving.
 *
 * <p>Registered on {@link LevelRenderEvents#AFTER_TRANSLUCENT_TERRAIN}, the
 * successor to classic Fabric's {@code WorldRenderEvents.AFTER_TRANSLUCENT}
 * in this API version — the one point in the level-render pipeline that
 * still hands a mod a plain {@code PoseStack}/{@code VertexConsumer} pair
 * already positioned for camera-relative world-space drawing, the same way
 * vanilla's own translucent-terrain-adjacent renderers work.
 */
public final class NexoChunkBorderOverlay {
	/** Chunks in each direction from the player's own chunk that the ground grid covers. */
	private static final int GRID_RADIUS_CHUNKS = 6;
	/** Blocks above and below the player's feet the current-chunk edges are drawn for. */
	private static final int EDGE_HEIGHT = 48;

	private NexoChunkBorderOverlay() {
	}

	public static void register() {
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(NexoChunkBorderOverlay::render);
	}

	private static void render(LevelRenderContext context) {
		if (NexoHudVisibility.hidden() || !NexoConfig.get().chunkBorderOverlayEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		if (player == null || level == null) {
			return;
		}

		VertexConsumer lines = context.bufferSource().getBuffer(RenderTypes.LINES);
		PoseStack.Pose pose = context.poseStack().last();
		ChunkPos playerChunk = player.chunkPosition();

		drawGrid(lines, pose, playerChunk, (int) Math.floor(player.getY()));
		drawCurrentChunkEdges(lines, pose, playerChunk, player.getY(), level.getMinY(), level.getMaxY());
	}

	private static void drawGrid(VertexConsumer lines, PoseStack.Pose pose, ChunkPos center, int y) {
		int baseX = center.getMinBlockX();
		int baseZ = center.getMinBlockZ();
		int span = GRID_RADIUS_CHUNKS * 16;

		for (int i = -GRID_RADIUS_CHUNKS; i <= GRID_RADIUS_CHUNKS + 1; i++) {
			int x = baseX + i * 16;
			line(lines, pose, x, y, baseZ - span, x, y, baseZ + span + 16, 255, 255, 255, 60, 1f);
			int z = baseZ + i * 16;
			line(lines, pose, baseX - span, y, z, baseX + span + 16, y, z, 255, 255, 255, 60, 1f);
		}
	}

	private static void drawCurrentChunkEdges(VertexConsumer lines, PoseStack.Pose pose, ChunkPos chunk,
			double playerY, int minY, int maxY) {
		int x0 = chunk.getMinBlockX();
		int x1 = x0 + 16;
		int z0 = chunk.getMinBlockZ();
		int z1 = z0 + 16;
		int yLow = Math.max(minY, (int) Math.floor(playerY) - EDGE_HEIGHT);
		int yHigh = Math.min(maxY, (int) Math.floor(playerY) + EDGE_HEIGHT);

		line(lines, pose, x0, yLow, z0, x0, yHigh, z0, 255, 0, 200, 220, 2f);
		line(lines, pose, x1, yLow, z0, x1, yHigh, z0, 255, 0, 200, 220, 2f);
		line(lines, pose, x0, yLow, z1, x0, yHigh, z1, 255, 0, 200, 220, 2f);
		line(lines, pose, x1, yLow, z1, x1, yHigh, z1, 255, 0, 200, 220, 2f);
	}

	private static void line(VertexConsumer lines, PoseStack.Pose pose,
			double x0, double y0, double z0, double x1, double y1, double z1, int r, int g, int b, int a, float width) {
		float nx = (float) (x1 - x0);
		float ny = (float) (y1 - y0);
		float nz = (float) (z1 - z0);
		lines.addVertex(pose, (float) x0, (float) y0, (float) z0).setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(width);
		lines.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(width);
	}
}
