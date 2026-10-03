package dev.nexoclient.nexomod.tactical.locate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import com.mojang.datafixers.DataFixer;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * A vanilla world generator with no world attached: the datapack registries,
 * loaded from the Minecraft jar's own bundled data the way a server loads them
 * at startup, plus a biome source, noise router and structure state built from a
 * seed. Everything {@link SeedLocator} needs to ask "would a village generate
 * here?" without a server to ask.
 *
 * <p>The data is the <em>vanilla</em> data pack. A server running its own
 * datapacks, or a plugin that moves structures, will not match — the seed gives
 * the same answer as an unmodified world, which is the best a client can do.
 */
final class SeedWorldgen {
	/** Everything seed-independent: loaded once, ~a second of work, kept for the session. */
	record Data(RegistryAccess.Frozen access, StructureTemplateManager templates) {
	}

	record World(long seed, Data data, BiomeSource biomeSource, NoiseBasedChunkGenerator generator,
			RandomState randomState, ChunkGeneratorStructureState structureState, LevelHeightAccessor heights) {
	}

	private static Data data;
	/** Latest world per dimension; a different seed replaces it, since structure state is big. */
	private static final Map<ResourceKey<Level>, World> WORLDS = new ConcurrentHashMap<>();

	private SeedWorldgen() {
	}

	static synchronized Data data() throws IOException {
		if (data == null) {
			data = loadData();
		}
		return data;
	}

	/** Null until {@link #data()} has run once — lets tab completion use it without forcing the load. */
	static synchronized Data dataIfLoaded() {
		return data;
	}

	private static Data loadData() throws IOException {
		VanillaPackResources vanilla = ServerPacksSource.createVanillaPackSource();
		MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanilla));

		// Static registries' tags are only read into lookups, never applied: applying
		// would rebind the running game's own block and item tags.
		RegistryAccess.Frozen statics = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, statics);
		List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(statics, staticTags);

		RegistryAccess.Frozen worldgen = RegistryDataLoader
				.load(resources, lookups, RegistryDataLoader.WORLDGEN_REGISTRIES, Runnable::run).join();
		// These registries are this class's own, so binding their tags is harmless — and
		// a structure's biome list is a tag, which throws if read before this.
		TagLoader.loadTagsForExistingRegistries(resources, worldgen).forEach(Registry.PendingTags::apply);

		// StructureTemplateManager insists on a world folder for its "generated" directory;
		// an empty temp one is never written to by anything this class does.
		Path scratch = Files.createTempDirectory("nexo-locate");
		StructureTemplateManager templates;
		try (LevelStorageSource.LevelStorageAccess storage = LevelStorageSource.createDefault(scratch).createAccess("locate")) {
			DataFixer fixer = DataFixers.getDataFixer();
			templates = new StructureTemplateManager(resources, storage, fixer, BuiltInRegistries.BLOCK);
		}
		return new Data(worldgen, templates);
	}

	/** Null for a dimension this does not know how to build (a datapack's own). */
	static World world(long seed, ResourceKey<Level> dimension) throws IOException {
		World cached = WORLDS.get(dimension);
		if (cached != null && cached.seed() == seed) {
			return cached;
		}
		Data data = data();
		RegistryAccess access = data.access();
		ResourceKey<NoiseGeneratorSettings> settingsKey;
		BiomeSource source;
		if (dimension == Level.OVERWORLD) {
			settingsKey = NoiseGeneratorSettings.OVERWORLD;
			source = MultiNoiseBiomeSource.createFromPreset(access.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
					.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
		} else if (dimension == Level.NETHER) {
			settingsKey = NoiseGeneratorSettings.NETHER;
			source = MultiNoiseBiomeSource.createFromPreset(access.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
					.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
		} else if (dimension == Level.END) {
			settingsKey = NoiseGeneratorSettings.END;
			source = TheEndBiomeSource.create(access.lookupOrThrow(Registries.BIOME));
		} else {
			return null;
		}
		// ponytail: default / non-large-biome / non-amplified world presets only; a
		// custom-preset world needs the preset itself, which the client is never told.
		Holder<NoiseGeneratorSettings> settings = access.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settingsKey);
		RandomState random = RandomState.create(access, settingsKey, seed);
		NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(source, settings);
		ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForNormal(random, seed, source,
				access.lookupOrThrow(Registries.STRUCTURE_SET));
		int minY = settings.value().noiseSettings().minY();
		LevelHeightAccessor heights = LevelHeightAccessor.create(minY, settings.value().noiseSettings().height());
		World world = new World(seed, data, source, generator, random, state, heights);
		WORLDS.put(dimension, world);
		return world;
	}
}
