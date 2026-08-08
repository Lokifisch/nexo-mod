package dev.nexoclient.nexomod.coords;

import java.util.List;
import java.util.Locale;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryLookingAt;
import net.minecraft.client.gui.components.debug.DebugEntryPosition;
import net.minecraft.client.gui.components.debug.DebugEntrySectionPosition;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Replaces the vanilla F3 entries that print absolute world coordinates with
 * versions that apply {@link CoordObfuscator}'s per-session X/Z offset. The
 * debug screen resolves entries through {@link DebugScreenEntries}' mutable
 * registry on every frame, so re-registering under the vanilla ids swaps the
 * implementation without touching any rendering code. Every printed value
 * (XYZ, block, chunk, region file, section-relative, targeted block/fluid) is
 * derived from the same shifted position, so the fake numbers stay
 * self-consistent. When the toggle is off, the untouched vanilla entries run.
 *
 * The targeted block/fluid entries (and their tag entries) additionally lie
 * about deep blocks when the bedrock-floor disguise is on, so F3 reports
 * exactly what the disguised chunk meshes show instead of contradicting them.
 *
 * The other position-adjacent entries (heightmap, light, biome, local
 * difficulty) only use the player's position as a lookup key and never print
 * X/Z, so they don't need replacing.
 */
final class ObfuscatedDebugEntries {
	private ObfuscatedDebugEntries() {
	}

	static void install() {
		DebugScreenEntries.register(DebugScreenEntries.PLAYER_POSITION, new ObscuredPosition());
		DebugScreenEntries.register(DebugScreenEntries.PLAYER_SECTION_POSITION, new ObscuredSectionPosition());
		DebugScreenEntries.register(DebugScreenEntries.LOOKING_AT_BLOCK_STATE, new ObscuredTargetedBlock());
		DebugScreenEntries.register(DebugScreenEntries.LOOKING_AT_FLUID_STATE, new ObscuredTargetedFluid());
		DebugScreenEntries.register(DebugScreenEntries.LOOKING_AT_BLOCK_TAGS, new ObscuredTargetedBlockTags());
		DebugScreenEntries.register(DebugScreenEntries.LOOKING_AT_FLUID_TAGS, new ObscuredTargetedFluidTags());
	}

	/** Port of {@link DebugEntryPosition} with the offset applied to X/Z everywhere. */
	private static final class ObscuredPosition implements DebugScreenEntry {
		private final DebugScreenEntry vanilla = new DebugEntryPosition();

		@Override
		public void display(DebugScreenDisplayer displayer, Level serverOrClientLevel, LevelChunk clientChunk, LevelChunk serverChunk) {
			if (!CoordObfuscator.active()) {
				vanilla.display(displayer, serverOrClientLevel, clientChunk, serverChunk);
				return;
			}
			Minecraft minecraft = Minecraft.getInstance();
			Entity entity = minecraft.getCameraEntity();
			if (entity == null) {
				return;
			}
			BlockPos feetPos = CoordObfuscator.obscure(entity.blockPosition());
			ChunkPos chunkPos = ChunkPos.containing(feetPos);
			Direction direction = entity.getDirection();
			String faceString = switch (direction) {
				case NORTH -> "Towards negative Z";
				case SOUTH -> "Towards positive Z";
				case WEST -> "Towards negative X";
				case EAST -> "Towards positive X";
				default -> "Invalid";
			};
			LongSet chunks = serverOrClientLevel instanceof ServerLevel serverLevel ? serverLevel.getForceLoadedChunks() : LongSets.EMPTY_SET;
			displayer.addToGroup(
					DebugEntryPosition.GROUP,
					List.of(
							String.format(Locale.ROOT, "XYZ: %.3f / %.5f / %.3f",
									CoordObfuscator.obscureX(entity.getX()), entity.getY(), CoordObfuscator.obscureZ(entity.getZ())),
							String.format(Locale.ROOT, "Block: %d %d %d", feetPos.getX(), feetPos.getY(), feetPos.getZ()),
							String.format(Locale.ROOT, "Chunk: %d %d %d [%d %d in r.%d.%d.mca]",
									chunkPos.x(),
									SectionPos.blockToSectionCoord(feetPos.getY()),
									chunkPos.z(),
									chunkPos.getRegionLocalX(),
									chunkPos.getRegionLocalZ(),
									chunkPos.getRegionX(),
									chunkPos.getRegionZ()),
							String.format(Locale.ROOT, "Facing: %s (%s) (%.1f / %.1f)",
									direction, faceString, Mth.wrapDegrees(entity.getYRot()), Mth.wrapDegrees(entity.getXRot())),
							minecraft.level.dimension().identifier() + " FC: " + chunks.size()));
		}
	}

	/**
	 * Port of {@link DebugEntrySectionPosition}. The vanilla line only prints the
	 * position mod 16, but it must be derived from the shifted position anyway so
	 * it agrees with the fake Block/Chunk lines above it.
	 */
	private static final class ObscuredSectionPosition implements DebugScreenEntry {
		private final DebugScreenEntry vanilla = new DebugEntrySectionPosition();

		@Override
		public void display(DebugScreenDisplayer displayer, Level serverOrClientLevel, LevelChunk clientChunk, LevelChunk serverChunk) {
			if (!CoordObfuscator.active()) {
				vanilla.display(displayer, serverOrClientLevel, clientChunk, serverChunk);
				return;
			}
			Entity entity = Minecraft.getInstance().getCameraEntity();
			if (entity == null) {
				return;
			}
			BlockPos feetPos = CoordObfuscator.obscure(entity.blockPosition());
			displayer.addToGroup(
					DebugEntryPosition.GROUP,
					String.format(Locale.ROOT, "Section-relative: %02d %02d %02d", feetPos.getX() & 15, feetPos.getY() & 15, feetPos.getZ() & 15));
		}

		@Override
		public boolean isAllowed(boolean reducedDebugInfo) {
			return true;
		}
	}

	private static final class ObscuredTargetedBlock extends DebugEntryLookingAt.BlockStateInfo {
		@Override
		public void extractInfo(List<String> result, Level level, BlockPos pos) {
			int headerIndex = result.size();
			super.extractInfo(result, level, pos);
			obscureTargetHeader(result, headerIndex, pos);
		}

		@Override
		public BlockState getInstance(Level level, BlockPos pos) {
			return disguiseBlock(super.getInstance(level, pos), pos);
		}
	}

	private static final class ObscuredTargetedFluid extends DebugEntryLookingAt.FluidStateInfo {
		@Override
		public void extractInfo(List<String> result, Level level, BlockPos pos) {
			int headerIndex = result.size();
			super.extractInfo(result, level, pos);
			obscureTargetHeader(result, headerIndex, pos);
		}

		@Override
		public FluidState getInstance(Level level, BlockPos pos) {
			return disguiseFluid(super.getInstance(level, pos), pos);
		}
	}

	/** Vanilla tag entries print no coordinates, so these only exist for the bedrock lie. */
	private static final class ObscuredTargetedBlockTags extends DebugEntryLookingAt.BlockTagInfo {
		@Override
		public BlockState getInstance(Level level, BlockPos pos) {
			return disguiseBlock(super.getInstance(level, pos), pos);
		}
	}

	private static final class ObscuredTargetedFluidTags extends DebugEntryLookingAt.FluidTagInfo {
		@Override
		public FluidState getInstance(Level level, BlockPos pos) {
			return disguiseFluid(super.getInstance(level, pos), pos);
		}
	}

	/** Mirrors {@code BedrockFloorDisguiseMixin} so F3 matches the disguised chunk meshes. */
	private static BlockState disguiseBlock(BlockState real, BlockPos pos) {
		if (CoordObfuscator.bedrockFloorActive()
				&& pos.getY() <= CoordObfuscator.BEDROCK_FLOOR_MAX_Y
				&& !real.isAir()) {
			return Blocks.BEDROCK.defaultBlockState();
		}
		return real;
	}

	private static FluidState disguiseFluid(FluidState real, BlockPos pos) {
		if (CoordObfuscator.bedrockFloorActive() && pos.getY() <= CoordObfuscator.BEDROCK_FLOOR_MAX_Y) {
			return Fluids.EMPTY.defaultFluidState();
		}
		return real;
	}

	/**
	 * Rewrites the "Targeted Block: x, y, z" header the superclass just appended.
	 * The real position still does the block-state lookup — only the printed
	 * coordinates are shifted.
	 */
	private static void obscureTargetHeader(List<String> result, int headerIndex, BlockPos realPos) {
		if (!CoordObfuscator.active() || headerIndex >= result.size()) {
			return;
		}
		String header = result.get(headerIndex);
		int colon = header.indexOf(": ");
		if (colon < 0) {
			return;
		}
		BlockPos fake = CoordObfuscator.obscure(realPos);
		result.set(headerIndex, header.substring(0, colon) + ": " + fake.getX() + ", " + fake.getY() + ", " + fake.getZ());
	}
}
