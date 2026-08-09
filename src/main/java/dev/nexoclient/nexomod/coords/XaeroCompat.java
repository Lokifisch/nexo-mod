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
 * <p>So the patch reports in the first time it runs, and this warns if the
 * minimap is installed, obscuring is switched on, and nothing has reported
 * after a grace period. Better a false alarm than a false sense of safety.
 *
 * <p>The World Map is handled differently. Its readout is rewritten as text on
 * the way to being drawn, because the fields behind it also index into map
 * data, so shifting those would fetch the wrong tiles rather than print a
 * different number. That patch matches a known line shape, so it reports a
 * mismatch directly — no timer needed, since by then the line has already been
 * drawn.
 *
 * <p>Worth knowing regardless: the map renders your actual surroundings, so
 * hiding the number does not make a screenshot of it safe.
 */
public final class XaeroCompat {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/coords");

	/** Long enough for the minimap to have drawn a frame after joining. */
	private static final long GRACE_MILLIS = TimeUnit.SECONDS.toMillis(8);

	private static volatile boolean minimapPatched;
	private static volatile boolean worldMapPatched;
	private static boolean warned;
	private static boolean warnedWorldMap;

	private XaeroCompat() {
	}

	/** Called by the minimap mixin the first time it shifts a position. */
	public static void minimapPatchRan() {
		minimapPatched = true;
	}

	/** Called by the world map mixin each time it rewrites a coordinate line. */
	public static void worldMapPatchRan() {
		worldMapPatched = true;
	}

	/**
	 * Called when the world map drew something that looks like a coordinate
	 * line but didn't match the shape the patch rewrites.
	 *
	 * <p>Warned about immediately rather than on a timer: unlike the minimap
	 * there is no waiting involved — the line has already been drawn with real
	 * coordinates on it.
	 */
	public static void worldMapFormatChanged() {
		if (warnedWorldMap || !CoordObfuscator.active()) {
			return;
		}
		warnedWorldMap = true;

		LOGGER.error(
				"Xaero's World Map coordinate line no longer matches the expected format — it is showing real coordinates");
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.gui.setOverlayMessage(
					Component.translatable("nexomod.coords.xaeroMapWarning").withStyle(ChatFormatting.RED),
					false);
		}
	}

	/** Whether the world map patch has rewritten a line, for diagnostics. */
	public static boolean worldMapPatched() {
		return worldMapPatched;
	}

	/**
	 * Warns once, in chat, if the minimap is installed and obscuring is on but
	 * the patch never ran.
	 *
	 * <p>Only the minimap is patched at all — see the class note on why the
	 * world map isn't.
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
}
