package dev.nexoclient.nexomod.tactical.freecam;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Detaches the camera from the player and flies it around while the body
 * stands still. The other half of ROADMAP Phase 3's unbuilt QoL pair.
 *
 * <p>Tactical, unambiguously: a camera that can leave your head and drift
 * through a wall reports terrain and players the vanilla client will not show
 * you. That is the "information vanilla withholds" side of the split, and it
 * is why this cannot sit next to zoom in the light jar even though both are
 * camera features on a keybind.
 *
 * <h2>Why the body has to be frozen explicitly</h2>
 *
 * <p>Moving the camera does nothing to the player — WASD would still walk the
 * real body around while the view flew off, which is both useless and a way to
 * walk into lava you cannot see. {@link LocalPlayer#input} is swapped for a
 * plain {@link ClientInput} for the duration: the base class never reads the
 * keyboard (its {@code KeyboardInput} subclass is what does), so the player
 * reports no movement, sends no movement packets, and simply stands there. The
 * original is put back on exit.
 *
 * <p>ponytail: the mouse is deliberately <em>not</em> intercepted. The body
 * still turns with it and the camera reads its yaw/pitch straight off the
 * player, which costs a {@code MouseHandler} mixin and a whole second copy of
 * the look state to avoid — at the price that the body visibly turns while
 * flying. Hook {@code MouseHandler#turnPlayer} and keep local yaw/pitch here
 * if that ever matters.
 *
 * <p>Every exit path funnels through {@link #disable}, including disconnect —
 * without which a detached camera would survive into the next world with a
 * stale position and no obvious way to recover.
 */
public final class NexoFreecam {
	/** Blocks per tick at speed 1, before the sprint multiplier. */
	private static final double BASE_SPEED = 0.6;
	private static final double SPRINT_MULTIPLIER = 3.0;

	private static KeyMapping toggleKey;

	private static boolean active;
	private static Vec3 position = Vec3.ZERO;
	private static ClientInput realInput;

	private NexoFreecam() {
	}

	public static void register() {
		// F4 by default, unlike the other Tactical keybinds (ghost mode, the hole
		// finder) which ship UNKNOWN. Deliberate: freecam is useless until it has
		// a key, and F4 is one of the few function keys vanilla leaves alone —
		// F3 is debug, F5 perspective, F11 fullscreen. Still rebindable.
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.freecam",
				InputConstants.Type.KEYSYM, InputConstants.KEY_F4, KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(NexoFreecam::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> disable());
	}

	private static void tick(Minecraft client) {
		if (toggleKey == null) {
			return;
		}
		while (toggleKey.consumeClick()) {
			if (active) {
				disable();
			} else {
				enable(client);
			}
		}
		if (!active) {
			return;
		}
		// The world going away underneath an active freecam is the one case the
		// toggle cannot cover, so it is rechecked every tick rather than trusted.
		if (client.player == null || client.level == null || !NexoConfig.get().freecamEnabled()) {
			disable();
			return;
		}
		fly(client, client.player);
	}

	private static void enable(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || !NexoConfig.get().freecamEnabled()) {
			return;
		}
		position = player.getEyePosition();
		realInput = player.input;
		player.input = new ClientInput();
		active = true;
	}

	/** Safe to call when already off — every exit path goes through here. */
	public static void disable() {
		if (!active) {
			return;
		}
		active = false;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && realInput != null) {
			player.input = realInput;
		}
		realInput = null;
	}

	private static void fly(Minecraft client, LocalPlayer player) {
		Options options = client.options;
		// A screen with keyboard focus must not fly the camera — isDown() stays
		// true for a key held when a screen opened, and chat would spell words in
		// movement.
		if (client.screen != null) {
			return;
		}

		double speed = BASE_SPEED * NexoConfig.get().freecamSpeed();
		if (options.keySprint.isDown()) {
			speed *= SPRINT_MULTIPLIER;
		}

		double forward = (options.keyUp.isDown() ? 1 : 0) - (options.keyDown.isDown() ? 1 : 0);
		double strafe = (options.keyLeft.isDown() ? 1 : 0) - (options.keyRight.isDown() ? 1 : 0);
		double vertical = (options.keyJump.isDown() ? 1 : 0) - (options.keyShift.isDown() ? 1 : 0);
		if (forward == 0 && strafe == 0 && vertical == 0) {
			return;
		}

		float yawRadians = player.getYRot() * Mth.DEG_TO_RAD;
		double sin = Math.sin(yawRadians);
		double cos = Math.cos(yawRadians);
		// Horizontal-only forward: looking at your feet shouldn't drive the camera
		// into the floor, which is what using the full look vector would do.
		double dx = (forward * -sin + strafe * cos) * speed;
		double dz = (forward * cos + strafe * sin) * speed;
		position = position.add(dx, vertical * speed, dz);
	}

	/** The key this is currently bound to, for the config screen's hint — read live, since it is rebindable. */
	public static Component toggleKeyName() {
		return toggleKey == null ? Component.empty() : toggleKey.getTranslatedKeyMessage();
	}

	public static boolean active() {
		return active;
	}

	public static Vec3 position() {
		return position;
	}
}
