package dev.nexoclient.nexomod.full.tactical;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * What has been heard recently, and for how much longer it should be drawn.
 *
 * <h2>Why this is full-only</h2>
 *
 * <p>Vanilla already draws a direction for a sound — subtitles have carried a
 * left/right arrow for years — but only for sounds the audio engine actually
 * played, and only as "somewhere to your left". This turns the packet into a
 * bearing and a distance, which is information the vanilla client has but does
 * not show, and that is the line {@code src/full} exists to hold.
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link #record} runs on the client thread (the mixin sits after
 * {@code PacketUtils.ensureRunningOnSameThread}); the HUD reads from the render
 * thread. A {@link CopyOnWriteArrayList} costs an array copy per sound heard,
 * which at the rate sounds arrive is nothing, and it removes the question of
 * what a synchronised block around a render path would do to frame times.
 */
public final class NexoSoundRadar {
	/** How long a ping stays on screen, in milliseconds. Matches vanilla's subtitle dwell time. */
	public static final long LIFETIME_MILLIS = 3000L;

	/** Hard cap, so a fireworks show cannot turn the HUD into a wall of arrows. */
	private static final int MAX_PINGS = 24;

	private static final List<NexoSoundPing> PINGS = new CopyOnWriteArrayList<>();

	private NexoSoundRadar() {
	}

	/**
	 * Called from the packet mixin. Drops everything the settings say not to
	 * show <em>before</em> storing it, so a disabled radar costs one boolean per
	 * sound packet and nothing else.
	 */
	public static void record(Holder<SoundEvent> sound, SoundSource source, double x, double y, double z) {
		NexoConfig config = NexoConfig.get();
		if (!config.tacticalEnabled() || !config.tacticalCategoryEnabled(source)) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}
		double range = config.tacticalRange();
		if (player.distanceToSqr(x, y, z) > range * range) {
			return;
		}

		prune();
		if (PINGS.size() >= MAX_PINGS) {
			PINGS.remove(0);
		}
		PINGS.add(new NexoSoundPing(x, y, z, source, subtitleOf(client, sound), System.currentTimeMillis()));
	}

	/** Live pings, oldest first, with the expired ones already dropped. */
	public static List<NexoSoundPing> pings() {
		prune();
		return PINGS;
	}

	/** Dropped on disconnect: a bearing to something in the world you just left is nonsense. */
	public static void clear() {
		PINGS.clear();
	}

	private static void prune() {
		if (PINGS.isEmpty()) {
			return;
		}
		long cutoff = System.currentTimeMillis() - LIFETIME_MILLIS;
		List<NexoSoundPing> expired = new ArrayList<>();
		for (NexoSoundPing ping : PINGS) {
			if (ping.timestamp() < cutoff) {
				expired.add(ping);
			}
		}
		PINGS.removeAll(expired);
	}

	/**
	 * The subtitle vanilla would show for this sound, or null.
	 *
	 * <p>{@code SoundManager.getSoundEvent} is keyed by the sound's
	 * {@code Identifier} and returns the resource-pack-defined
	 * {@code WeighedSoundEvents}, which is where the {@code subtitle} field
	 * lives — the registry {@code SoundEvent} itself only knows its id and its
	 * range. A sound with no {@code sounds.json} entry (some mods play sounds
	 * that way) returns null, which is not an error.
	 */
	private static Component subtitleOf(Minecraft client, Holder<SoundEvent> sound) {
		if (sound == null || !NexoConfig.get().tacticalLabelsEnabled()) {
			return null;
		}
		WeighedSoundEvents events = client.getSoundManager().getSoundEvent(sound.value().location());
		return events == null ? null : events.getSubtitle();
	}
}
