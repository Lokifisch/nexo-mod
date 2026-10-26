package dev.nexoclient.nexomod.tactical.knockback;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * The yaw offset Knockback Displacement sends with the next qualifying hit.
 *
 * <p>Holding the key freezes the camera and turns the mouse into a pointer
 * around a pivot. There are exactly four angles, picked by whichever way the
 * pointer leans furthest: up 0°, right 90°, down 180°, left 270°, relative to
 * where the player faces (0 is vanilla, 90 the player's right). The pointer
 * has to leave a small dead zone before it changes anything, and is held
 * within {@link #RADIUS} pixels of the pivot so switching direction is a short
 * flick. Releasing hands the mouse back to the camera and keeps the angle.
 *
 * <p>A tap — key down and up without the pointer leaving the dead zone, so a
 * little jitter still counts — resets the angle to {@link #DEFAULT}. The angle
 * is otherwise kept until a displaced hit uses it, then returns to the default.
 */
public final class NexoKnockbackAngle {
	public static final float DEFAULT = 180.0F;
	private static final double RADIUS = 40.0;
	/** The pointer must get this far from the pivot to count as steering rather than a tap. */
	private static final double DEAD_ZONE = 20.0;

	private static KeyMapping key;
	private static boolean held;
	/** Whether the pointer left the dead zone during this hold. */
	private static boolean moved;
	/** Pointer position relative to the pivot, in mouse pixels; screen y grows downward. */
	private static double px;
	private static double py;
	private static float angle = DEFAULT;

	private NexoKnockbackAngle() {
	}

	public static void register() {
		key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.knockbackAngle",
				InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(NexoKnockbackAngle::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			held = false;
			consume();
		});
	}

	/** Offset in degrees to add to the player's yaw for the displaced hit. */
	public static float current() {
		return angle;
	}

	/** A displaced hit used the offset: back to the default. */
	public static void consume() {
		angle = DEFAULT;
	}

	/**
	 * Evaluated live, not once per tick: the mouse handler asks this every
	 * frame, and a tick-old answer lets up to 50 ms of movement turn the camera
	 * after the key goes down (or steer the angle after it comes up). Press and
	 * release edges are handled here for the same reason.
	 */
	public static boolean holding() {
		Minecraft client = Minecraft.getInstance();
		boolean keyDown = key != null && key.isDown();
		boolean down = keyDown && client.player != null && client.gui.screen() == null
				&& NexoConfig.get().knockbackDisplacementEnabled();
		if (down && !held) {
			px = 0;
			py = 0;
			moved = false;
		} else if (held && !keyDown && !moved && client.player != null) {
			// Released without steering: a tap, so reset.
			consume();
			client.player.sendOverlayMessage(Component.translatable("nexomod.knockback.reset", Math.round(angle)));
		}
		held = down;
		return down;
	}

	private static void tick(Minecraft client) {
		if (holding()) {
			client.player.sendOverlayMessage(Component.translatable("nexomod.knockback.angle", Math.round(angle)));
		}
	}

	/**
	 * Called by the mouse mixin with the mouse movement the camera would have
	 * turned by, in pixels. Only meaningful while {@link #holding()}.
	 */
	public static void addMouse(double dx, double dy) {
		px += dx;
		py += dy;
		double length = Math.hypot(px, py);
		if (length > RADIUS) {
			px *= RADIUS / length;
			py *= RADIUS / length;
			length = RADIUS;
		}
		if (length < DEAD_ZONE) {
			return;
		}
		moved = true;
		if (Math.abs(px) > Math.abs(py)) {
			angle = px > 0 ? 90.0F : 270.0F;
		} else {
			angle = py < 0 ? 0.0F : 180.0F;
		}
	}
}
