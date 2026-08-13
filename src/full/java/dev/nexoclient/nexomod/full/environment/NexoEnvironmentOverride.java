package dev.nexoclient.nexomod.full.environment;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * The client-side time and weather override, in one place both mixins ask.
 *
 * <h2>Why this cannot change gameplay</h2>
 *
 * <p>Everything here rewrites a value the client <em>reads</em> in order to
 * decide what to draw. Nothing is sent anywhere: the client never tells the
 * server what time it thinks it is, and there is no packet in which it could.
 * Mob spawning, crop growth, phantom timers, villager schedules, sleeping and
 * every other consequence of the time of day are computed by the server from
 * its own clock and arrive as their normal packets whatever this returns.
 *
 * <p>Concretely, the two injection points are:
 *
 * <ul>
 * <li>{@code ClientClockManager.getTotalTicks} — the client's copy of the world
 *     clock. Verified against the 26.1 class files: 26.1 replaced
 *     {@code Level.getDayTime()} with the {@code WorldClock}/{@code ClockManager}
 *     pair, and the environment-attribute timelines
 *     ({@code AttributeTrackSampler}, which is what feeds sky colour, sun and
 *     moon position, and sky light to the renderer) sample from exactly this
 *     method. So one override moves the whole visual day cycle and nothing
 *     else.</li>
 * <li>{@code Level.getRainLevel} / {@code getThunderLevel} — the interpolated
 *     weather strength the rain renderer and the sky use. Note this also flips
 *     {@code Level.isRaining()}, which is derived from
 *     {@code getRainLevel(1.0F) &gt; 0.2}; on the client that governs the rain
 *     ambience and the splash particles, which is the intended effect and still
 *     nothing the server hears about.</li>
 * </ul>
 *
 * <p>The day <em>count</em> is deliberately preserved when the time of day is
 * pinned: the moon phase is derived from it, and a fixed midnight with a moon
 * that never changes phase looks wrong in a way that is hard to place.
 */
public final class NexoEnvironmentOverride {
	/** Ticks in one Minecraft day. The clock is a running total, so this is what separates day count from time of day. */
	private static final long DAY_LENGTH = 24000L;

	private NexoEnvironmentOverride() {
	}

	/** Whether anything is being overridden at all — the cheap check both mixins make first. */
	public static boolean timeActive() {
		return NexoConfig.get().timeOverride() != NexoConfig.TimeOverride.OFF;
	}

	public static boolean weatherActive() {
		return NexoConfig.get().weatherOverride() != NexoConfig.WeatherOverride.OFF;
	}

	/**
	 * Pins the time of day inside {@code totalTicks} while keeping which day it
	 * is.
	 *
	 * @return the adjusted total, or {@code totalTicks} unchanged when no
	 *         override is set — the caller compares the two and only overrides
	 *         the return value when they differ, so an inactive override costs
	 *         nothing beyond this call
	 */
	public static long applyTime(long totalTicks) {
		NexoConfig.TimeOverride override = NexoConfig.get().timeOverride();
		if (override == NexoConfig.TimeOverride.OFF) {
			return totalTicks;
		}
		// floorDiv rather than /: the clock is a signed long and a world with a
		// negative total (possible after /time set on a fresh world) would
		// otherwise jump a day every time it crossed zero.
		return (Math.floorDiv(totalTicks, DAY_LENGTH) * DAY_LENGTH) + override.dayTime;
	}

	/**
	 * The rain strength to draw, or -1 when the player has not asked for one.
	 * -1 rather than an {@code Optional} because this is called from a mixin on
	 * a per-frame path, and rain strength is never negative.
	 */
	public static float rainLevel() {
		return switch (NexoConfig.get().weatherOverride()) {
			case OFF -> -1.0F;
			case CLEAR -> 0.0F;
			// Full strength rather than the ~0.7 a natural storm settles at:
			// this is a deliberate choice by the player, and a "rain" setting
			// that produces visibly less rain than real rain reads as broken.
			case RAIN, THUNDER -> 1.0F;
		};
	}

	/** @see #rainLevel() */
	public static float thunderLevel() {
		return switch (NexoConfig.get().weatherOverride()) {
			case OFF -> -1.0F;
			case CLEAR, RAIN -> 0.0F;
			case THUNDER -> 1.0F;
		};
	}
}
