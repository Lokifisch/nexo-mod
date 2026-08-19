package dev.nexoclient.nexomod.hud;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundSource;

/**
 * One toggle: make the client as cheap to run as possible for AFK, then put
 * every touched setting back exactly as it was.
 *
 * <h2>Why the snapshot never touches disk</h2>
 *
 * <p>{@link #apply()} deliberately never calls {@code Options.save()} — the
 * potato values live only in the in-memory {@code Options} object,
 * {@code options.txt} keeps the player's real settings untouched the whole
 * time. That is what makes a crash or force-quit while active self-correcting
 * for free: the next launch reads the file that was never rewritten, rather
 * than a persisted "was in potato mode" flag this would otherwise need to
 * remember and restore on startup. {@link #restore()} does call it, once
 * things are back to their real values, so nothing else that happened to
 * touch settings in between is lost.
 *
 * <p>Same reasoning {@link NexoHudVisibility} already uses for its own
 * screenshot toggle: state that would be actively confusing to come back to
 * (a mod that looks broken, a client stuck at 10 FPS) is kept in memory only,
 * not persisted.
 */
public final class NexoPotatoMode {
	private record Snapshot(
			int renderDistance, int simulationDistance, double entityDistanceScaling,
			int framerateLimit, CloudStatus cloudStatus, boolean ambientOcclusion,
			int mipmapLevels, int biomeBlendRadius, boolean entityShadows,
			ParticleStatus particles, double masterVolume) {
	}

	private static volatile boolean active;
	private static Snapshot snapshot;

	private NexoPotatoMode() {
	}

	public static boolean active() {
		return active;
	}

	public static void toggle() {
		if (active) {
			restore();
		} else {
			apply();
		}
	}

	private static void apply() {
		Options options = Minecraft.getInstance().options;
		snapshot = new Snapshot(
				options.renderDistance().get(), options.simulationDistance().get(),
				options.entityDistanceScaling().get(), options.framerateLimit().get(),
				options.cloudStatus().get(), options.ambientOcclusion().get(),
				options.mipmapLevels().get(), options.biomeBlendRadius().get(),
				options.entityShadows().get(), options.particles().get(),
				options.getSoundSourceOptionInstance(SoundSource.MASTER).get());

		options.renderDistance().set(2);
		options.simulationDistance().set(5);
		options.entityDistanceScaling().set(0.5);
		options.framerateLimit().set(10);
		options.cloudStatus().set(CloudStatus.OFF);
		options.ambientOcclusion().set(false);
		options.mipmapLevels().set(0);
		options.biomeBlendRadius().set(0);
		options.entityShadows().set(false);
		options.particles().set(ParticleStatus.MINIMAL);
		options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
		active = true;
	}

	private static void restore() {
		Snapshot previous = snapshot;
		if (previous == null) {
			active = false;
			return;
		}
		Options options = Minecraft.getInstance().options;
		options.renderDistance().set(previous.renderDistance());
		options.simulationDistance().set(previous.simulationDistance());
		options.entityDistanceScaling().set(previous.entityDistanceScaling());
		options.framerateLimit().set(previous.framerateLimit());
		options.cloudStatus().set(previous.cloudStatus());
		options.ambientOcclusion().set(previous.ambientOcclusion());
		options.mipmapLevels().set(previous.mipmapLevels());
		options.biomeBlendRadius().set(previous.biomeBlendRadius());
		options.entityShadows().set(previous.entityShadows());
		options.particles().set(previous.particles());
		options.getSoundSourceOptionInstance(SoundSource.MASTER).set(previous.masterVolume());
		options.save();

		snapshot = null;
		active = false;
	}
}
