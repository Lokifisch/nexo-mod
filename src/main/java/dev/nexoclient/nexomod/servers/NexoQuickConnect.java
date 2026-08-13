package dev.nexoclient.nexomod.servers;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoQuickServerScreen;

/**
 * Joining a favourite without walking back through the main menu.
 *
 * <h2>The entry point, and the one it is not</h2>
 *
 * <p>There is no {@code Minecraft.connect(...)} in 26.1 — the name every
 * tutorial for older versions uses. Connecting is
 * {@code ConnectScreen.startConnecting(Screen parent, Minecraft, ServerAddress,
 * ServerData, boolean isQuickPlay, TransferState transfer)}, verified against
 * the 26.1 class file and against its only vanilla caller,
 * {@code JoinMultiplayerScreen.join(ServerData)}, which passes {@code false} and
 * {@code null} for the last two. That method builds the {@code Connection},
 * spawns the network thread and installs itself as the screen, so nothing else
 * here has to know how a login handshake works.
 *
 * <h2>Leaving the current world first</h2>
 *
 * <p>{@code startConnecting} assumes there is no world. Calling it while one is
 * loaded leaves the old {@code ClientPacketListener} attached and the old level
 * ticking, which is a crash a few frames later rather than an error. So the
 * current session is torn down through {@code Minecraft.disconnectFromWorld},
 * the exact method the pause menu's Disconnect button calls: it sends the quit
 * message, runs the save/progress screen, and lands on the title or multiplayer
 * screen. It is synchronous, so by the time it returns the client genuinely has
 * no level and the connect can start on the same tick.
 *
 * <p>That also fixes what the parent screen should be. Vanilla has already put a
 * sensible "where you came from" screen on {@code minecraft.screen} by then, and
 * that is the screen a failed connection needs a Back button to; inventing one
 * here would drop the player somewhere they never were.
 */
public final class NexoQuickConnect {
	private static KeyMapping openKey;

	private NexoQuickConnect() {
	}

	/** Called from {@code NexoMod.onInitializeClient()}; unbound by default. */
	public static void register() {
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.quickServers",
				InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MULTIPLAYER));
		ClientTickEvents.END_CLIENT_TICK.register(NexoQuickConnect::tick);
	}

	private static void tick(Minecraft client) {
		if (openKey == null) {
			return;
		}
		while (openKey.consumeClick()) {
			// consumeClick() only fires while no screen has the keyboard, so
			// this cannot re-open on top of itself.
			client.setScreen(new NexoQuickServerScreen(client.screen));
		}
	}

	/**
	 * Leaves whatever is running and joins {@code entry}. Safe from the title
	 * screen, from a single-player world and from another server.
	 *
	 * <p>Does nothing for an entry with no address — an empty row in the editor
	 * is a half-finished edit, not a request to connect to the empty string.
	 */
	public static void switchTo(NexoServerEntry entry) {
		if (entry == null || !entry.isUsable()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level != null) {
			client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
		}

		ServerAddress address = ServerAddress.parseString(entry.address);
		// ServerData is rebuilt per connect rather than stored: it carries a
		// ping, a MOTD, an icon and a resource-pack decision, all of which
		// describe one connection attempt. Type.OTHER is what the multiplayer
		// screen uses for a hand-typed address; LAN and REALM change how
		// vanilla treats the entry afterwards.
		ServerData server = new ServerData(entry.displayName(), entry.address, ServerData.Type.OTHER);

		Screen parent = client.screen != null ? client.screen : new TitleScreen();
		NexoMod.LOGGER.info("[nexomod] Quick-switching to {} ({})", entry.displayName(), entry.address);
		ConnectScreen.startConnecting(parent, client, address, server, false, null);
	}
}
