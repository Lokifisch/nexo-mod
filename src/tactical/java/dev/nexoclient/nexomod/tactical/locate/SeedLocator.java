package dev.nexoclient.nexomod.tactical.locate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.datafixers.util.Pair;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * {@code /locate} without a server: the same search vanilla runs, driven by the
 * saved seed instead of a live world.
 *
 * <p>Vanilla finds the nearest structure by walking the placement grid outward
 * from the player, asking each candidate chunk whether a start generates there.
 * This does the same, minus the chunk load: whether a start "generates" is
 * answered by {@link Structure#findValidGenerationPoint} against a generator
 * built from the seed ({@link SeedWorldgen}) — the biome and terrain checks are
 * the real ones, only the world around them is missing.
 *
 * <p>Blocking and slow on first use (the worldgen data loads once, a village's
 * template pool is real work): call from a background thread.
 */
final class SeedLocator {
	/** Vanilla's own search radius for {@code /locate structure}, in placement-grid steps. */
	private static final int STRUCTURE_RADIUS = 100;
	private static final int BIOME_RADIUS = 6400;

	private SeedLocator() {
	}

	/** The structure ids this client can search for, or empty until the worldgen data has loaded. */
	static List<Identifier> structureIds() {
		SeedWorldgen.Data data = SeedWorldgen.dataIfLoaded();
		return data == null ? List.of()
				: data.access().lookupOrThrow(Registries.STRUCTURE).keySet().stream().toList();
	}

	static List<Identifier> biomeIds() {
		SeedWorldgen.Data data = SeedWorldgen.dataIfLoaded();
		return data == null ? List.of()
				: data.access().lookupOrThrow(Registries.BIOME).keySet().stream().toList();
	}

	/** Empty if the structure does not exist in this dimension or none generates within the radius. */
	static Optional<BlockPos> structure(long seed, ResourceKey<Level> dimension, Identifier id, BlockPos origin) throws IOException {
		SeedWorldgen.World world = SeedWorldgen.world(seed, dimension);
		if (world == null) {
			return Optional.empty();
		}
		Optional<Holder.Reference<Structure>> found = world.data().access().lookupOrThrow(Registries.STRUCTURE)
				.get(ResourceKey.create(Registries.STRUCTURE, id));
		if (found.isEmpty()) {
			return Optional.empty();
		}
		Holder<Structure> target = found.get();

		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (StructurePlacement placement : world.structureState().getPlacementsForStructure(target)) {
			Optional<StructureSet> set = world.structureState().possibleStructureSets().stream()
					.map(Holder::value).filter(s -> s.placement() == placement).findFirst();
			if (set.isEmpty()) {
				continue;
			}
			BlockPos hit = placement instanceof ConcentricRingsStructurePlacement rings
					? nearestRing(world, rings, origin)
					: placement instanceof RandomSpreadStructurePlacement spread
							? nearestSpread(world, spread, set.get(), target, origin)
							: null;
			if (hit != null) {
				double distance = hit.distSqr(origin);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = hit;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	/** Strongholds: a precomputed list of ring positions, so nearest-of-list, no per-chunk validation. */
	private static BlockPos nearestRing(SeedWorldgen.World world, ConcentricRingsStructurePlacement rings, BlockPos origin) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (ChunkPos chunk : world.structureState().getRingPositionsFor(rings)) {
			BlockPos pos = rings.getLocatePos(chunk);
			double distance = pos.distSqr(origin);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = pos;
			}
		}
		return best;
	}

	private static BlockPos nearestSpread(SeedWorldgen.World world, RandomSpreadStructurePlacement placement,
			StructureSet set, Holder<Structure> target, BlockPos origin) {
		int spacing = placement.spacing();
		int originChunkX = origin.getX() >> 4;
		int originChunkZ = origin.getZ() >> 4;
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int ring = 0; ring <= STRUCTURE_RADIUS; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.abs(dx) != ring && Math.abs(dz) != ring) {
						continue;
					}
					ChunkPos chunk = placement.getPotentialStructureChunk(world.seed(),
							originChunkX + spacing * dx, originChunkZ + spacing * dz);
					if (!placement.isStructureChunk(world.structureState(), chunk.x(), chunk.z())
							|| !generates(world, set, target, chunk)) {
						continue;
					}
					BlockPos pos = placement.getLocatePos(chunk);
					double distance = pos.distSqr(origin);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = pos;
					}
				}
			}
			// A candidate in a nearer ring beats anything a later ring could hold.
			if (best != null) {
				return best;
			}
		}
		return null;
	}

	/**
	 * Which structure of the set actually generates at this chunk, and is it the
	 * one asked about. A set with several entries (nether fortress vs. bastion,
	 * the village variants) picks one by weight from a chunk-seeded random and
	 * falls through to the next if the pick fails its biome/terrain check — this
	 * is that loop, step for step, so a chunk that would spawn the other
	 * structure is not reported as ours.
	 */
	private static boolean generates(SeedWorldgen.World world, StructureSet set, Holder<Structure> target, ChunkPos chunk) {
		List<StructureSet.StructureSelectionEntry> entries = new ArrayList<>(set.structures());
		if (entries.size() == 1) {
			return entries.get(0).structure().equals(target) && validStart(world, target, chunk);
		}
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
		random.setLargeFeatureSeed(world.seed(), chunk.x(), chunk.z());
		int totalWeight = 0;
		for (StructureSet.StructureSelectionEntry entry : entries) {
			totalWeight += entry.weight();
		}
		while (!entries.isEmpty()) {
			int roll = random.nextInt(totalWeight);
			int index = 0;
			for (StructureSet.StructureSelectionEntry entry : entries) {
				roll -= entry.weight();
				if (roll < 0) {
					break;
				}
				index++;
			}
			StructureSet.StructureSelectionEntry picked = entries.get(index);
			if (validStart(world, picked.structure(), chunk)) {
				return picked.structure().equals(target);
			}
			entries.remove(index);
			totalWeight -= picked.weight();
		}
		return false;
	}

	private static boolean validStart(SeedWorldgen.World world, Holder<Structure> structure, ChunkPos chunk) {
		Structure.GenerationContext context = new Structure.GenerationContext(world.data().access(), world.generator(),
				world.biomeSource(), world.randomState(), world.data().templates(), world.seed(), chunk,
				world.heights(), structure.value().biomes()::contains);
		try {
			return structure.value().findValidGenerationPoint(context).isPresent();
		} catch (RuntimeException e) {
			// A start that throws while being checked is a start that did not generate.
			return false;
		}
	}

	static Optional<BlockPos> biome(long seed, ResourceKey<Level> dimension, Identifier id, BlockPos origin) throws IOException {
		SeedWorldgen.World world = SeedWorldgen.world(seed, dimension);
		Minecraft client = Minecraft.getInstance();
		if (world == null || client.level == null) {
			return Optional.empty();
		}
		Pair<BlockPos, Holder<Biome>> found = world.biomeSource().findClosestBiome3d(origin, BIOME_RADIUS, 32, 64,
				holder -> holder.is(id), world.randomState().sampler(), client.level);
		return found == null ? Optional.empty() : Optional.of(found.getFirst());
	}
}
