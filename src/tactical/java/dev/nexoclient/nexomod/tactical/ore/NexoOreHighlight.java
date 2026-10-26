package dev.nexoclient.nexomod.tactical.ore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Outlines diamonds, emeralds, ancient debris and spawners through terrain, in
 * the loaded chunks around the player. It reads only blocks the server actually
 * sent, so anti-xray servers hand it their decoy data like everything else.
 *
 * <p>Each chunk is scanned once (a few per tick, nearest unscanned first) and
 * the result kept until the chunk unloads. A section is skipped by a palette
 * check before any block is read, which is what keeps this cheap. Blocks mined
 * since the scan are dropped at draw time by re-checking the state.
 */
public final class NexoOreHighlight {
	private static final int RADIUS_CHUNKS = 3;
	private static final int SCANS_PER_TICK = 2;
	private static final int MAX_PER_CHUNK = 64;

	private static final Map<Long, List<BlockPos>> found = new HashMap<>();
	private static ClientLevel scannedLevel;

	private NexoOreHighlight() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(NexoOreHighlight::tick);
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> found.remove(chunk.getPos().pack()));
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> found.remove(chunk.getPos().pack()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> found.clear());
		LevelRenderEvents.BEFORE_GIZMOS.register(context -> render());
	}

	private static boolean wanted(BlockState state) {
		return isDiamond(state) || isEmerald(state)
				|| state.is(Blocks.ANCIENT_DEBRIS) || state.is(Blocks.SPAWNER) || state.is(Blocks.TRIAL_SPAWNER);
	}

	private static boolean isDiamond(BlockState state) {
		return state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE);
	}

	private static boolean isEmerald(BlockState state) {
		return state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE);
	}

	private static int colour(BlockState state) {
		if (isDiamond(state)) {
			return 0xFF55FFFF;
		}
		if (isEmerald(state)) {
			return 0xFF55FF55;
		}
		return state.is(Blocks.ANCIENT_DEBRIS) ? 0xFFCC7722 : 0xFFFF3333;
	}

	private static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null || client.player == null || !NexoConfig.get().oreHighlightEnabled()) {
			return;
		}
		if (level != scannedLevel) {
			// New level object (dimension change): nothing scanned for the old one applies.
			found.clear();
			scannedLevel = level;
		}
		ChunkPos centre = client.player.chunkPosition();
		int budget = SCANS_PER_TICK;
		for (int r = 0; r <= RADIUS_CHUNKS && budget > 0; r++) {
			for (int dx = -r; dx <= r && budget > 0; dx++) {
				for (int dz = -r; dz <= r && budget > 0; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					long key = ChunkPos.pack(centre.x() + dx, centre.z() + dz);
					if (found.containsKey(key)) {
						continue;
					}
					LevelChunk chunk = level.getChunkSource().getChunk(centre.x() + dx, centre.z() + dz, ChunkStatus.FULL, false);
					if (chunk != null) {
						found.put(key, scan(level, chunk));
						budget--;
					}
				}
			}
		}
	}

	private static List<BlockPos> scan(ClientLevel level, LevelChunk chunk) {
		List<BlockPos> out = new ArrayList<>();
		LevelChunkSection[] sections = chunk.getSections();
		int minSection = level.getMinY() >> 4;
		scan:
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section.hasOnlyAir() || !section.maybeHas(NexoOreHighlight::wanted)) {
				continue;
			}
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (wanted(section.getBlockState(x, y, z))) {
							if (out.size() >= MAX_PER_CHUNK) {
								break scan;
							}
							out.add(new BlockPos(chunk.getPos().getMinBlockX() + x, (minSection + i) * 16 + y,
									chunk.getPos().getMinBlockZ() + z));
						}
					}
				}
			}
		}
		return out;
	}

	private static void render() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || !NexoConfig.get().oreHighlightEnabled() || NexoHudVisibility.hidden()) {
			return;
		}
		for (List<BlockPos> list : found.values()) {
			for (BlockPos pos : list) {
				BlockState state = client.level.getBlockState(pos);
				if (wanted(state)) {
					Gizmos.cuboid(pos, GizmoStyle.stroke(colour(state))).setAlwaysOnTop();
				}
			}
		}
	}
}
