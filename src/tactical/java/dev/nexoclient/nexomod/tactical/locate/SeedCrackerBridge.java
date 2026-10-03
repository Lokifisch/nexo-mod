package dev.nexoclient.nexomod.tactical.locate;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import kaptainwutax.seedcrackerX.api.SeedCrackerAPI;

import dev.nexoclient.nexomod.NexoMod;

/**
 * SeedcrackerX's hook: once it has cracked a world's seed it calls
 * {@link #pushWorldSeed} on every mod that lists this class under a
 * {@code seedcrackerx} entrypoint (see the Tactical {@code fabric.mod.json}).
 * The seed goes into the {@link SeedIndex} for the server the player is in —
 * overwriting a community-list or older entry, since a cracked seed is verified
 * against the world and those are not.
 *
 * <p>SeedcrackerX never loads this class when it is not installed, so nothing
 * here needs a guard for its absence.
 */
public final class SeedCrackerBridge implements SeedCrackerAPI {
	@Override
	public void pushWorldSeed(long seed) {
		Minecraft client = Minecraft.getInstance();
		// SeedcrackerX calls this from wherever its cracker thread finished.
		client.execute(() -> SeedIndex.currentPlace().ifPresentOrElse(place -> {
			SeedIndex.get().put(place.key(), place.label(), place.address(), place.world(), seed,
					SeedIndex.Source.SEEDCRACKER, null);
			if (client.player != null) {
				client.gui.getChat().addClientSystemMessage(
						Component.translatable("nexomod.locate.fromSeedcracker", place.label(), seed));
			}
		}, () -> NexoMod.LOGGER.info("[nexomod] SeedcrackerX seed {} ignored: not on a server or world.", seed)));
	}
}
