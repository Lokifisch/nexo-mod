package dev.nexoclient.nexomod.bedrock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a pocket's blocks into one merged shape.
 *
 * Drawing a box per block leaves the walls between neighbouring blocks visible,
 * so a 6-block pocket reads as six cubes instead of one opening. Two rules fix
 * that without any geometry library:
 *
 * <ul>
 * <li>A face is drawn only when the block on its other side isn't part of the
 * pocket, which removes every interior wall and leaves a single closed surface.
 * <li>An edge is drawn only where that surface turns. An edge shared by two or
 * more faces that all point the same way is the seam between coplanar
 * neighbours, so it's dropped; anything else — a silhouette edge with one face,
 * a corner where the normals differ — is kept.
 * </ul>
 *
 * Vertices are integers, so an edge can be keyed exactly by its two endpoints
 * and matched between faces with no floating-point tolerance.
 */
public final class BedrockHoleOutline {
	/**
	 * Corners in counter-clockwise order seen from outside the pocket. The kind
	 * rides along instead of a colour because the highlight is animated: the
	 * geometry is rebuilt a few times a second, the colour changes every frame.
	 */
	public record Face(Vec3 a, Vec3 b, Vec3 c, Vec3 d, BedrockHole.Kind kind) {
	}

	public record Edge(Vec3 from, Vec3 to, BedrockHole.Kind kind) {
	}

	private static final Direction[] DIRECTIONS = Direction.values();

	private record EdgeKey(long from, long to) {
	}

	private BedrockHoleOutline() {
	}

	public static void build(BedrockHole hole, List<Face> faces, List<Edge> edges) {
		LongSet members = new LongOpenHashSet(hole.blockCount());
		for (int i = 0; i < hole.blockCount(); i++) {
			members.add(hole.block(i));
		}

		// Per edge: how many boundary faces meet along it, and which way they face.
		Map<EdgeKey, int[]> seen = new HashMap<>();
		for (int i = 0; i < hole.blockCount(); i++) {
			long block = hole.block(i);
			int x = BlockPos.getX(block);
			int y = BlockPos.getY(block);
			int z = BlockPos.getZ(block);
			for (Direction direction : DIRECTIONS) {
				if (members.contains(BlockPos.asLong(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ()))) {
					continue;
				}
				long[] corners = corners(x, y, z, direction);
				faces.add(new Face(vec(corners[0]), vec(corners[1]), vec(corners[2]), vec(corners[3]), hole.kind()));
				for (int corner = 0; corner < 4; corner++) {
					record(seen, corners[corner], corners[(corner + 1) & 3], direction);
				}
			}
		}

		for (Map.Entry<EdgeKey, int[]> entry : seen.entrySet()) {
			int[] tally = entry.getValue();
			if (tally[0] >= 2 && Integer.bitCount(tally[1]) == 1) {
				continue;
			}
			edges.add(new Edge(vec(entry.getKey().from()), vec(entry.getKey().to()), hole.kind()));
		}
	}

	private static void record(Map<EdgeKey, int[]> seen, long from, long to, Direction direction) {
		// Endpoints ordered so both faces sharing an edge produce the same key.
		EdgeKey key = from <= to ? new EdgeKey(from, to) : new EdgeKey(to, from);
		int[] tally = seen.computeIfAbsent(key, ignored -> new int[2]);
		tally[0]++;
		tally[1] |= 1 << direction.ordinal();
	}

	/** The four corners of one face of the unit cube at {@code x, y, z}, packed as positions. */
	private static long[] corners(int x, int y, int z, Direction direction) {
		int x1 = x + 1;
		int y1 = y + 1;
		int z1 = z + 1;
		return switch (direction) {
			case DOWN -> new long[] {pack(x, y, z), pack(x, y, z1), pack(x1, y, z1), pack(x1, y, z)};
			case UP -> new long[] {pack(x, y1, z), pack(x1, y1, z), pack(x1, y1, z1), pack(x, y1, z1)};
			case NORTH -> new long[] {pack(x, y, z), pack(x, y1, z), pack(x1, y1, z), pack(x1, y, z)};
			case SOUTH -> new long[] {pack(x, y, z1), pack(x1, y, z1), pack(x1, y1, z1), pack(x, y1, z1)};
			case WEST -> new long[] {pack(x, y, z), pack(x, y, z1), pack(x, y1, z1), pack(x, y1, z)};
			case EAST -> new long[] {pack(x1, y, z), pack(x1, y1, z), pack(x1, y1, z1), pack(x1, y, z1)};
		};
	}

	private static long pack(int x, int y, int z) {
		return BlockPos.asLong(x, y, z);
	}

	private static Vec3 vec(long packed) {
		return new Vec3(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
	}
}
