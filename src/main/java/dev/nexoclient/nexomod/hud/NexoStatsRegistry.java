package dev.nexoclient.nexomod.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.chat.Component;

/**
 * Every stat line {@link NexoStatsHud} can show, and the same
 * {@code src/main}-registers-a-seam-for-src/tactical pattern
 * {@link dev.nexoclient.nexomod.screen.NexoExtraCategories} and
 * {@link dev.nexoclient.nexomod.screen.NexoQolModules} already use: light-
 * level and nearby-hostile-count are registered from
 * {@code NexoTacticalFeatures} rather than named here, since they read
 * information vanilla withholds and {@code src/main} cannot reference
 * {@code src/tactical}.
 *
 * <p>{@code value} is called once per enabled stat per frame this HUD
 * element draws, so it must be cheap — a field read or a simple computation,
 * never a scan or an allocMultiplier-heavy loop (the nearby-hostile-count
 * stat is the one exception, and it is bounded to a small radius for exactly
 * this reason).
 */
public final class NexoStatsRegistry {
	public record Stat(String id, Component label, Supplier<String> value) {
	}

	private static final List<Stat> STATS = new ArrayList<>();

	private NexoStatsRegistry() {
	}

	public static void register(String id, Component label, Supplier<String> value) {
		STATS.add(new Stat(id, label, value));
	}

	/** In registration order: built-ins first (registered from {@code NexoStatsHud.register()}), then Tactical's. */
	public static List<Stat> stats() {
		return List.copyOf(STATS);
	}
}
