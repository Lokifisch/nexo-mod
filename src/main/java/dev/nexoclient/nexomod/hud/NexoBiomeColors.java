package dev.nexoclient.nexomod.hud;

import java.util.Map;

import dev.nexoclient.nexomod.screen.NexoStyle;

/**
 * A representative color per vanilla biome, for the stats HUD's biome line —
 * stony shores read grey, plains green, savanna the acacia orange, and so on.
 * Keyed by the biome's registry path ({@code minecraft:plains} → "plains"),
 * not its display name, so it doesn't depend on translation.
 *
 * <p>Datapack and modded biomes have no entry here and fall back to
 * {@link NexoStyle#TEXT_PRIMARY} — there is no vibe to guess for a biome this
 * table has never heard of.
 */
final class NexoBiomeColors {
	private static final Map<String, Integer> COLORS = Map.ofEntries(
			// Grassy / temperate
			Map.entry("plains", 0xFF7FB238),
			Map.entry("sunflower_plains", 0xFFB6D94E),
			Map.entry("meadow", 0xFF83C651),
			Map.entry("cherry_grove", 0xFFF3AFC7),

			// Forests
			Map.entry("forest", 0xFF4F9F3B),
			Map.entry("flower_forest", 0xFF6FBF4A),
			Map.entry("birch_forest", 0xFF8AB84B),
			Map.entry("old_growth_birch_forest", 0xFF8AB84B),
			Map.entry("dark_forest", 0xFF2E4B1F),
			Map.entry("old_growth_pine_taiga", 0xFF2F6E4F),
			Map.entry("old_growth_spruce_taiga", 0xFF356B4A),
			Map.entry("taiga", 0xFF3C8F6B),
			Map.entry("snowy_taiga", 0xFFA9D8C7),

			// Savanna (the acacia orange)
			Map.entry("savanna", 0xFFC98F3C),
			Map.entry("savanna_plateau", 0xFFC98F3C),
			Map.entry("windswept_savanna", 0xFFB98A4E),

			// Windswept / mountains
			Map.entry("windswept_hills", 0xFF8C9E7A),
			Map.entry("windswept_gravelly_hills", 0xFF9AA08F),
			Map.entry("windswept_forest", 0xFF7C9169),

			// Jungle
			Map.entry("jungle", 0xFF2FA82A),
			Map.entry("sparse_jungle", 0xFF4FAF3C),
			Map.entry("bamboo_jungle", 0xFF6FCB3A),

			// Badlands
			Map.entry("badlands", 0xFFB2653B),
			Map.entry("eroded_badlands", 0xFFAE6339),
			Map.entry("wooded_badlands", 0xFF9C7248),

			// Desert
			Map.entry("desert", 0xFFE0C56B),

			// Snow / peaks
			Map.entry("grove", 0xFFD8ECD8),
			Map.entry("snowy_slopes", 0xFFE6EEF5),
			Map.entry("frozen_peaks", 0xFFDDEBF2),
			Map.entry("jagged_peaks", 0xFFD6E4EC),
			Map.entry("stony_peaks", 0xFF9FA3A8),
			Map.entry("snowy_plains", 0xFFEFF3F7),
			Map.entry("ice_spikes", 0xFFCFF0F5),

			// Coasts / rivers
			Map.entry("river", 0xFF3E7BC4),
			Map.entry("frozen_river", 0xFFA8CBE0),
			Map.entry("beach", 0xFFE9DFA9),
			Map.entry("snowy_beach", 0xFFE9EEF0),
			Map.entry("stony_shore", 0xFF9A9A9A),
			Map.entry("mushroom_fields", 0xFFC15A6B),

			// Caves
			Map.entry("dripstone_caves", 0xFF8A6A4E),
			Map.entry("lush_caves", 0xFF3FAF5A),
			Map.entry("deep_dark", 0xFF1B2A2E),

			// Oceans
			Map.entry("ocean", 0xFF2E6FBE),
			Map.entry("deep_ocean", 0xFF1E4E8C),
			Map.entry("warm_ocean", 0xFF2FB2C4),
			Map.entry("lukewarm_ocean", 0xFF2C93B8),
			Map.entry("deep_lukewarm_ocean", 0xFF237390),
			Map.entry("cold_ocean", 0xFF3E6C93),
			Map.entry("deep_cold_ocean", 0xFF2C4F6E),
			Map.entry("frozen_ocean", 0xFFAFD4E0),
			Map.entry("deep_frozen_ocean", 0xFF7FA9BC),

			// Swamp
			Map.entry("swamp", 0xFF546B4A),
			Map.entry("mangrove_swamp", 0xFF3E5C3E),

			// Void
			Map.entry("the_void", 0xFF1A1A1A),

			// Nether
			Map.entry("nether_wastes", 0xFF8B3A3A),
			Map.entry("crimson_forest", 0xFFC23B4F),
			Map.entry("warped_forest", 0xFF2FA6A0),
			Map.entry("soul_sand_valley", 0xFF5C6B78),
			Map.entry("basalt_deltas", 0xFF4A4A50),

			// End
			Map.entry("the_end", 0xFF9B6FCE),
			Map.entry("end_highlands", 0xFF9B6FCE),
			Map.entry("end_midlands", 0xFF9B6FCE),
			Map.entry("small_end_islands", 0xFF9B6FCE),
			Map.entry("end_barrens", 0xFF8878A0));

	private NexoBiomeColors() {
	}

	static int forPath(String path) {
		return COLORS.getOrDefault(path, NexoStyle.TEXT_PRIMARY);
	}
}
