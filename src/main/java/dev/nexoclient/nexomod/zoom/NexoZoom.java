package dev.nexoclient.nexomod.zoom;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Hold a key, narrow the field of view — the zoom every other client ships,
 * and the last of ROADMAP Phase 3's QoL candidates.
 *
 * <p>This lives in {@code src/main} and therefore in both jars. It is
 * player-key-triggered and shows nothing the player could not walk closer to
 * see, which puts it on the same side of the split as keybind macros: the
 * light jar's test is what triggers a feature, not how much walking it saves.
 *
 * <p>The factor is eased rather than snapped, on wall-clock time so the ease
 * takes the same {@link #EASE_RATE}-scaled fraction of a second at any
 * framerate. That matters more than it sounds: an instant FOV jump reads as a
 * teleport, and a per-frame step would zoom at a speed that depended on the
 * player's hardware.
 *
 * <p>{@link #currentFactor()} is read from {@code CameraZoomMixin} on the
 * render thread while {@link #tick} writes from the client thread, hence the
 * volatile — a torn float here would be a visible stutter.
 */
public final class NexoZoom {
	/** Fraction of the remaining distance to the target covered per second. */
	private static final float EASE_RATE = 12F;
	/** Below this the zoom is treated as fully off, so the mixin can skip entirely. */
	private static final float IDLE_EPSILON = 0.001F;

	private static KeyMapping zoomKey;
	private static volatile float factor = 1F;
	private static long lastTickMillis;

	private NexoZoom() {
	}

	public static void register() {
		// Defaults to an actual key rather than UNKNOWN, for the same reason the
		// QoL menu key does: a zoom nobody can find has no way to be discovered.
		// Note this collides with Zoomify's default if that mod is also installed —
		// a normal, visible keybind conflict the player resolves in Controls.
		zoomKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.zoom",
				InputConstants.Type.KEYSYM, InputConstants.KEY_C, KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	private static void tick() {
		NexoConfig config = NexoConfig.get();
		boolean held = config.zoomEnabled() && zoomKey != null && zoomKey.isDown();
		float target = held ? config.zoomFactor() : 1F;

		if (!config.zoomSmoothEnabled()) {
			factor = target;
			return;
		}

		long now = System.currentTimeMillis();
		float delta = lastTickMillis == 0L ? 0F : Math.min(200L, now - lastTickMillis) / 1000F;
		lastTickMillis = now;
		float step = delta * EASE_RATE;
		float current = factor;
		if (current < target) {
			factor = Math.min(target, current + (target - current) * Math.min(1F, step));
		} else {
			factor = Math.max(target, current - (current - target) * Math.min(1F, step));
		}
		if (Math.abs(factor - 1F) < IDLE_EPSILON) {
			factor = 1F;
		}
	}

	/** 1 when not zooming. The mixin divides the camera's FOV by this. */
	public static float currentFactor() {
		return factor;
	}

	public static boolean active() {
		return factor > 1F + IDLE_EPSILON;
	}
}
