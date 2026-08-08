package dev.nexoclient.nexomod.coords;

import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * On crowded servers, opening F3 with real coordinates visible is an easy way
 * to leak your base location in a screenshot or stream. When "Obscure
 * Coordinates" is off and the server has more than {@value #PLAYER_THRESHOLD}
 * players, the first F3 press shows an action-bar warning instead of the debug
 * screen; pressing F3 again within {@value #GRACE_MILLIS}ms opens it anyway.
 */
public final class F3CoordWarning {
	private static final int PLAYER_THRESHOLD = 50;
	private static final long GRACE_MILLIS = 10_000L;

	private static long lastWarningMillis = -GRACE_MILLIS;

	private F3CoordWarning() {
	}

	/**
	 * Called when F3 is about to show the (currently hidden) debug overlay.
	 * Returns true if this press should be swallowed and the warning shown.
	 */
	public static boolean shouldBlock() {
		if (NexoConfig.get().obscureCoordinatesActive()) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		ClientPacketListener connection = minecraft.getConnection();
		if (connection == null || minecraft.isLocalServer()) {
			return false;
		}
		if (connection.getOnlinePlayers().size() <= PLAYER_THRESHOLD) {
			return false;
		}
		long now = Util.getMillis();
		if (now - lastWarningMillis <= GRACE_MILLIS) {
			return false;
		}
		lastWarningMillis = now;
		minecraft.gui.setOverlayMessage(Component.translatable("nexomod.coords.f3warning").withStyle(ChatFormatting.YELLOW), false);
		return true;
	}
}
