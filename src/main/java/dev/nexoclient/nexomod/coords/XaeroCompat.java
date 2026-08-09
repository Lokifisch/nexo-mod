package dev.nexoclient.nexomod.coords;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Checks that the Xaero coordinate patches actually took effect, and says so
 * when they didn't.
 *
 * <p>The patches target another mod's internals, so they're attached with
 * {@code require = 0}: a Xaero update that renames or reshapes those methods
 * should stop the patch applying rather than stop the game starting. That
 * trade has a sharp edge — the failure is silent, and silence on a feature
 * whose whole job is hiding your coordinates means believing you're covered
 * when you aren't.
 *
 * <p>So the patches report in the first time they run, and this warns if one
 * of Xaero's mods is installed, obscuring is switched on, and nothing has
 * reported after a grace period. Better a false alarm than a false sense of
 * safety.
 */
public final class XaeroCompat {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/coords");

	/** Long enough for the minimap to have drawn a frame after joining. */
	private static final long GRACE_MILLIS = TimeUnit.SECONDS.toMillis(8);

	private static volatile boolean minimapPatched;
	private static volatile boolean worldMapPatched;
	private static boolean warned;

	private XaeroCompat() {
	}

	/** Called by the minimap mixin the first time it shifts a position. */
	public static void minimapPatchRan() {
		minimapPatched = true;
	}

	/** Called by the world map mixin the first time it shifts a position. */
	public static void worldMapPatchRan() {
		worldMapPatched = true;
	}

	/**
	 * Warns once, in chat, if the minimap is installed and obscuring is on but
	 * the patch never ran.
	 *
	 * <p>Only the minimap is checked. It draws continuously, so not having run
	 * within the grace period is conclusive; the world map only renders while
	 * its screen is open, and warning about a screen the player may never have
	 * opened would be noise.
	 */
	public static void checkAfterJoin(long millisSinceJoin) {
		if (warned || millisSinceJoin < GRACE_MILLIS) {
			return;
		}
		if (!FabricLoader.getInstance().isModLoaded("xaerominimap")) {
			return;
		}
		if (!CoordObfuscator.active() || minimapPatched) {
			return;
		}

		warned = true;
		LOGGER.error(
				"Xaero's Minimap is installed but the coordinate patch didn't apply — its readout is showing real coordinates");

		// Action bar rather than chat, matching how the F3 warning is shown.
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.gui.setOverlayMessage(
					Component.translatable("nexomod.coords.xaeroWarning").withStyle(ChatFormatting.RED),
					false);
		}
	}

	/** Whether the world map patch has run, for diagnostics. */
	public static boolean worldMapPatched() {
		return worldMapPatched;
	}
}
