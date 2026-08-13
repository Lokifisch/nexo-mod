package dev.nexoclient.nexomod.full.bedrock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import dev.nexoclient.nexomod.full.bedrock.BedrockHole.Band;
import dev.nexoclient.nexomod.full.bedrock.BedrockHole.Kind;

/**
 * Finds pockets of non-bedrock sealed inside a bedrock boundary layer.
 *
 * The method is a flood fill rather than a per-column test. Starting from any
 * non-bedrock block inside the layer, it walks all six directions through
 * <em>anything that isn't bedrock</em> — deepslate, dirt, netherrack, lava, air,
 * whatever is in there — and never leaves the layer's own Y range, so the layer's
 * outer and inner faces act as walls. If the walk closes off, every boundary it
 * touched was bedrock and the blocks it collected are one sealed pocket. If it
 * grows past {@code maxSize} blocks, it isn't a pocket at all but part of the
 * general connected rock, and the whole component is remembered as rejected so no
 * later fill re-walks a piece of it and mistakes that piece for something sealed.
 *
 * A per-column test can't tell those two apart, which is why it reported a
 * bedrock hole roughly twenty times per chunk: a four-deep dent in the layer is
 * ordinary generation, and only connectivity distinguishes it from a real gap.
 *
 * Fills cross chunk borders, since a pocket that straddles one is still a pocket,
 * and a pocket reaching into a chunk the client hasn't received is rejected rather
 * than guessed at (see {@link Bedrock#UNKNOWN}) — which is why the caller only
 * scans a chunk once its neighbours have arrived. A pocket on a border is found by
 * both chunks' scans and reports the same {@link BedrockHole#anchor()} either way,
 * the lowest of its blocks, so the caller can recognise it as one pocket.
 *
 * Which Y range is a bedrock layer comes from the level's own height plus a check
 * that the range actually contains bedrock here — see
 * {@link #MIN_BAND_BEDROCK_PERCENT} — so the Overworld floor (−64…−60), the
 * Nether floor (0…4) and the Nether roof (123…127) are all covered without
 * hardcoding a dimension, and the End (no boundary bedrock) is skipped.
 */
public final class BedrockHoleScanner {
	/** Layers per boundary band: the outermost plus the four that generate probabilistically. */
	public static final int BAND_DEPTH = 5;

	/** Hard ceiling on one fill, whatever the configured size is, so a pathological world can't stall a tick. */
	private static final int FILL_LIMIT = 512;

	/** Per-chunk cap on reported pockets, so nothing can blow up the finder's memory. */
	private static final int MAX_HOLES_PER_CHUNK = 64;

	private static final int MIN_BAND_BEDROCK_PERCENT = 10;
	private static final Predicate<BlockState> IS_BEDROCK = state -> state.is(Blocks.BEDROCK);

	private BedrockHoleScanner() {
	}

	/**
	 * @param minSize smallest pocket to report; smaller ones are still walked (and
	 *                so still sealed off correctly) but not returned
	 * @param maxSize largest pocket to report; a connected region bigger than this
	 *                is treated as ordinary rock rather than a hole
	 */
	public static List<BedrockHole> scan(ClientLevel level, LevelChunk chunk, int minSize, int maxSize) {
		List<BedrockHole> holes = new ArrayList<>();
		Bedrock bedrock = new Bedrock(level);
		int cappedSize = Math.min(maxSize, FILL_LIMIT);
		scanBand(bedrock, chunk, Band.FLOOR, chunk.getMinY(), minSize, cappedSize, holes);
		scanBand(bedrock, chunk, Band.ROOF, chunk.getMaxY() - BAND_DEPTH + 1, minSize, cappedSize, holes);
		return holes;
	}

	private static void scanBand(Bedrock bedrock, LevelChunk chunk, Band band, int bandBottom, int minSize, int maxSize, List<BedrockHole> holes) {
		int bandTop = bandBottom + BAND_DEPTH - 1;
		if (!isBedrockBand(chunk, bandBottom, bandTop)) {
			return;
		}

		int originX = chunk.getPos().getMinBlockX();
		int originZ = chunk.getPos().getMinBlockZ();
		// Both sets outlive a single fill: "assigned" stops a block being walked
		// twice, and "rejected" is how an over-size component stays rejected even
		// when a later fill only sees a fragment of it.
		LongSet assigned = new LongOpenHashSet();
		LongSet rejected = new LongOpenHashSet();
		Fill fill = new Fill(bedrock, bandBottom, bandTop, maxSize, assigned, rejected);

		for (int y = bandBottom; y <= bandTop; y++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				for (int localX = 0; localX < 16; localX++) {
					if (holes.size() >= MAX_HOLES_PER_CHUNK) {
						return;
					}
					long seed = BlockPos.asLong(originX + localX, y, originZ + localZ);
					if (assigned.contains(seed) || bedrock.at(seed) != Bedrock.OPEN) {
						continue;
					}

					LongArrayList pocket = fill.run(seed);
					if (pocket == null || pocket.size() < minSize) {
						continue;
					}
					holes.add(new BedrockHole(pocket.toLongArray(), band, kindOf(pocket, band, bandBottom, bandTop), anchorOf(pocket)));
				}
			}
		}
	}

	/** Lowest packed position in the pocket: an identity every neighbouring chunk's fill agrees on. */
	private static long anchorOf(LongArrayList pocket) {
		long anchor = Long.MAX_VALUE;
		for (int i = 0; i < pocket.size(); i++) {
			anchor = Math.min(anchor, pocket.getLong(i));
		}
		return anchor;
	}

	/** VOID when the pocket reaches the layer's outer face, so it opens out of the world. */
	private static Kind kindOf(LongArrayList pocket, Band band, int bandBottom, int bandTop) {
		int outerFace = band == Band.FLOOR ? bandBottom : bandTop;
		for (int i = 0; i < pocket.size(); i++) {
			if (BlockPos.getY(pocket.getLong(i)) == outerFace) {
				return Kind.VOID;
			}
		}
		return Kind.ENCLOSED;
	}

	/**
	 * Whether this Y range is a bedrock layer in this chunk at all. A real layer is
	 * roughly half bedrock; the margin below leaves room for a heavily mined one
	 * while still rejecting a range holding one stray player-placed bedrock block
	 * (0.08% of a band), which would otherwise turn a whole chunk of open air into
	 * "pockets".
	 */
	private static boolean isBedrockBand(LevelChunk chunk, int bandBottom, int bandTop) {
		if (!bandMayHaveBedrock(chunk, bandBottom, bandTop)) {
			return false;
		}
		int bedrockBlocks = 0;
		for (int y = bandBottom; y <= bandTop; y++) {
			LevelChunkSection section = sectionAt(chunk, y);
			if (section == null) {
				continue;
			}
			int localY = y & 15;
			for (int localZ = 0; localZ < 16; localZ++) {
				for (int localX = 0; localX < 16; localX++) {
					if (section.getBlockState(localX, localY, localZ).is(Blocks.BEDROCK)) {
						bedrockBlocks++;
					}
				}
			}
		}
		return bedrockBlocks * 100 >= 16 * 16 * BAND_DEPTH * MIN_BAND_BEDROCK_PERCENT;
	}

	/**
	 * Palette-level rejection before touching any block state: if no section
	 * overlapping the band can contain bedrock, this isn't a boundary layer here.
	 * Makes the Overworld's top band and the End effectively free to skip.
	 */
	private static boolean bandMayHaveBedrock(LevelChunk chunk, int bandBottom, int bandTop) {
		int sections = chunk.getSections().length;
		for (int index = chunk.getSectionIndex(bandBottom); index <= chunk.getSectionIndex(bandTop); index++) {
			if (index < 0 || index >= sections) {
				continue;
			}
			LevelChunkSection section = chunk.getSection(index);
			if (!section.hasOnlyAir() && section.maybeHas(IS_BEDROCK)) {
				return true;
			}
		}
		return false;
	}

	/** The section holding {@code y}, or null when there is none or it is pure air (and so has no bedrock). */
	private static LevelChunkSection sectionAt(LevelChunk chunk, int y) {
		int index = chunk.getSectionIndex(y);
		if (index < 0 || index >= chunk.getSections().length) {
			return null;
		}
		LevelChunkSection section = chunk.getSection(index);
		return section.hasOnlyAir() ? null : section;
	}

	/**
	 * One flood fill, reused across the seeds of a band so its queue and lists are
	 * allocated once per band rather than once per pocket.
	 */
	private static final class Fill {
		private final Bedrock bedrock;
		private final int bandBottom;
		private final int bandTop;
		private final int maxSize;
		private final LongSet assigned;
		private final LongSet rejected;
		private final LongArrayFIFOQueue frontier = new LongArrayFIFOQueue();
		private final LongArrayList pocket = new LongArrayList();

		Fill(Bedrock bedrock, int bandBottom, int bandTop, int maxSize, LongSet assigned, LongSet rejected) {
			this.bedrock = bedrock;
			this.bandBottom = bandBottom;
			this.bandTop = bandTop;
			this.maxSize = maxSize;
			this.assigned = assigned;
			this.rejected = rejected;
		}

		/**
		 * Walks the component containing {@code seed}.
		 *
		 * @return its blocks when it closed off within {@code maxSize}, or null when
		 *         it is too big, runs into an unloaded chunk, or joins a component
		 *         already known to be too big — in which case everything walked is
		 *         remembered as rejected.
		 */
		LongArrayList run(long seed) {
			frontier.clear();
			pocket.clear();
			frontier.enqueue(seed);
			assigned.add(seed);
			boolean sealed = true;

			while (!frontier.isEmpty()) {
				long block = frontier.dequeueLong();
				pocket.add(block);
				if (pocket.size() > maxSize) {
					sealed = false;
					break;
				}

				int x = BlockPos.getX(block);
				int y = BlockPos.getY(block);
				int z = BlockPos.getZ(block);
				sealed &= step(x - 1, y, z);
				sealed &= step(x + 1, y, z);
				sealed &= step(x, y, z - 1);
				sealed &= step(x, y, z + 1);
				if (y > bandBottom) {
					sealed &= step(x, y - 1, z);
				}
				if (y < bandTop) {
					sealed &= step(x, y + 1, z);
				}
				if (!sealed) {
					break;
				}
			}

			if (sealed) {
				return pocket;
			}
			// Everything reached so far belongs to a component that is not a
			// pocket. Remembering the queued blocks too is what keeps a later fill
			// from starting inside this component and finding a fragment of it
			// walled in by nothing but the blocks this fill already took.
			while (!frontier.isEmpty()) {
				pocket.add(frontier.dequeueLong());
			}
			for (int i = 0; i < pocket.size(); i++) {
				long block = pocket.getLong(i);
				assigned.add(block);
				rejected.add(block);
			}
			return null;
		}

		/** Expands into one neighbour. Returns false when this component can't be a sealed pocket. */
		private boolean step(int x, int y, int z) {
			long neighbour = BlockPos.asLong(x, y, z);
			if (rejected.contains(neighbour)) {
				return false;
			}
			if (assigned.contains(neighbour)) {
				return true;
			}
			int state = bedrock.at(neighbour);
			if (state == Bedrock.SOLID) {
				return true;
			}
			if (state == Bedrock.UNKNOWN) {
				return false;
			}
			assigned.add(neighbour);
			frontier.enqueue(neighbour);
			return true;
		}
	}

	/**
	 * Bedrock lookups against the loaded chunks, caching the last chunk because a
	 * fill walks the same one repeatedly. Unloaded chunks report
	 * {@link #UNKNOWN} rather than a guess: whether a pocket is sealed can't be
	 * decided from blocks the client hasn't received.
	 */
	private static final class Bedrock {
		static final int OPEN = 0;
		static final int SOLID = 1;
		static final int UNKNOWN = -1;

		private final ClientLevel level;
		private long cachedChunkKey = ChunkPos.INVALID_CHUNK_POS;
		private LevelChunk cachedChunk;

		Bedrock(ClientLevel level) {
			this.level = level;
		}

		int at(long packed) {
			return at(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
		}

		int at(int x, int y, int z) {
			long chunkKey = ChunkPos.pack(x >> 4, z >> 4);
			if (chunkKey != cachedChunkKey) {
				cachedChunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
				cachedChunkKey = chunkKey;
			}
			if (cachedChunk == null) {
				return UNKNOWN;
			}
			LevelChunkSection section = sectionAt(cachedChunk, y);
			if (section == null) {
				return OPEN;
			}
			return section.getBlockState(x & 15, y & 15, z & 15).is(Blocks.BEDROCK) ? SOLID : OPEN;
		}
	}
}
