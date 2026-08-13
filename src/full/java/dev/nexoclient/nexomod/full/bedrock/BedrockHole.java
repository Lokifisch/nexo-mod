package dev.nexoclient.nexomod.full.bedrock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * One pocket of non-bedrock blocks sealed inside a bedrock boundary layer: the
 * complete set of connected blocks, so every enclosed block can be highlighted
 * rather than just a representative column.
 *
 * Blocks are stored packed via {@link BlockPos#asLong}, since the finder can
 * hold a lot of these at once and a {@code BlockPos} per block would be mostly
 * object header.
 */
public final class BedrockHole {
	/** Which boundary layer the pocket sits in — the bedrock under the world, or the Nether's ceiling. */
	public enum Band {
		FLOOR,
		ROOF
	}

	/**
	 * VOID — the pocket includes a block in the outermost layer (the bottom of a
	 * floor, the top of a roof), so it opens out of the world: a real void hole
	 * or roof hole.
	 *
	 * ENCLOSED — the pocket is sealed inside the layer, the shape you can stand
	 * in but not pass through.
	 */
	public enum Kind {
		VOID,
		ENCLOSED
	}

	private final long[] blocks;
	private final Band band;
	private final Kind kind;
	private final long anchor;

	BedrockHole(long[] blocks, Band band, Kind kind, long anchor) {
		this.blocks = blocks;
		this.band = band;
		this.kind = kind;
		this.anchor = anchor;
	}

	public Band band() {
		return band;
	}

	public Kind kind() {
		return kind;
	}

	public int blockCount() {
		return blocks.length;
	}

	/** The packed position of one enclosed block, for building the merged outline. */
	public long block(int index) {
		return blocks[index];
	}

	/** Where a label for the whole pocket belongs: its mean position, just above its highest block. */
	public Vec3 labelPos() {
		long sumX = 0;
		long sumZ = 0;
		int maxY = Integer.MIN_VALUE;
		for (long block : blocks) {
			sumX += BlockPos.getX(block);
			sumZ += BlockPos.getZ(block);
			maxY = Math.max(maxY, BlockPos.getY(block));
		}
		return new Vec3(sumX / (double) blocks.length + 0.5, maxY + 1.4, sumZ / (double) blocks.length + 0.5);
	}

	/**
	 * The pocket's canonical block: the same one whichever chunk's scan walks it,
	 * so it doubles as the identity used to report it once and to remember that it
	 * has already been announced.
	 */
	public BlockPos anchor() {
		return BlockPos.of(anchor);
	}
}
