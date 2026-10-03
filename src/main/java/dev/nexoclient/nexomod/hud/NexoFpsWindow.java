package dev.nexoclient.nexomod.hud;

import java.util.ArrayDeque;

import net.minecraft.client.Minecraft;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Rolling low / high / average FPS for the stats HUD's "fps" line, over the
 * window {@link NexoConfig#fpsWindowSeconds()} picks. Fed once per drawn frame
 * from {@link NexoStatsHud}, not from the stat's supplier, which runs once per
 * enabled stat and would double-count a frame.
 *
 * <p>Low and high are the slowest and fastest single frame in the window, which
 * is what makes a stutter visible; the average is frames over elapsed time, so
 * one long frame cannot dominate it the way averaging per-frame rates would.
 */
final class NexoFpsWindow {
	/** Frame end times, in nanoseconds; frame length is the gap to the previous entry. */
	private static final ArrayDeque<Long> FRAMES = new ArrayDeque<>();
	private static String text = "--";

	private NexoFpsWindow() {
	}

	static String text() {
		return text;
	}

	static void sample() {
		long now = System.nanoTime();
		long windowNanos = NexoConfig.get().fpsWindowSeconds() * 1_000_000_000L;
		FRAMES.addLast(now);
		// The oldest entry is only the baseline for the next frame's length.
		while (FRAMES.size() > 2 && now - FRAMES.peekFirst() > windowNanos) {
			FRAMES.removeFirst();
		}
		if (FRAMES.size() < 2) {
			return;
		}
		long longest = 0;
		long shortest = Long.MAX_VALUE;
		long first = FRAMES.peekFirst();
		long previous = first;
		boolean skipBaseline = true;
		for (long t : FRAMES) {
			if (skipBaseline) {
				skipBaseline = false;
				continue;
			}
			long dt = Math.max(t - previous, 1);
			longest = Math.max(longest, dt);
			shortest = Math.min(shortest, dt);
			previous = t;
		}
		int frames = FRAMES.size() - 1;
		long elapsed = Math.max(1, previous - first);
		text = Minecraft.getInstance().getFps() + "  ↓" + Math.round(1e9 / longest)
				+ " ↑" + Math.round(1e9 / shortest)
				+ " ø" + Math.round(frames * 1e9 / elapsed);
	}
}
