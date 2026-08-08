package dev.nexoclient.nexomod.discord;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/**
 * Wires the game's actual world/server state into {@link DiscordRichPresence}.
 * The launcher side (Client/) only ever knows "an instance is running" —
 * this is the part that can see which specific world or server you're
 * actually in, since that's in-game state.
 *
 * <p>Also handles the "Join" button: when on a multiplayer server, the
 * activity carries a join secret (the server address) so friends viewing it
 * in Discord get a Join button. Clicking it only does something if the
 * friend's own Nexo Mod is already running (it needs a live IPC connection
 * subscribed to {@code ACTIVITY_JOIN} to hear about it at all) — Discord
 * launching the game fresh from a cold click isn't implemented, since that
 * needs OS-level protocol registration on top of everything here.
 */
public final class NexoDiscordRpc {
	private NexoDiscordRpc() {
	}

	public static void register() {
		DiscordRichPresence.get().setJoinHandler(NexoDiscordRpc::onJoinRequested);

		// Establishes the IPC connection (and its ACTIVITY_JOIN subscription) as
		// soon as the game is up, rather than waiting for the first world join —
		// otherwise a friend's Join click before you've ever joined anything
		// would have nothing listening on the other end.
		ClientLifecycleEvents.CLIENT_STARTED.register(client ->
				DiscordRichPresence.get().setActivity("In the menus", System.currentTimeMillis()));

		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
			String joinAddress = client.hasSingleplayerServer() ? null : currentServerAddress(client);
			DiscordRichPresence.get().setActivity(describeCurrentWorld(client), System.currentTimeMillis(), joinAddress);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) ->
				DiscordRichPresence.get().setActivity("In the menus", System.currentTimeMillis()));

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> DiscordRichPresence.get().shutdown());
	}

	private static String describeCurrentWorld(Minecraft client) {
		if (client.hasSingleplayerServer()) {
			IntegratedServer server = client.getSingleplayerServer();
			if (server != null) {
				String levelName = server.getWorldData().getLevelName();
				return "World: " + levelName;
			}
		}

		ServerData serverData = client.getCurrentServer();
		if (serverData != null) {
			String label = serverData.name != null && !serverData.name.isBlank() ? serverData.name : serverData.ip;
			return "Server: " + label;
		}

		return "In a world";
	}

	private static String currentServerAddress(Minecraft client) {
		ServerData serverData = client.getCurrentServer();
		return serverData != null ? serverData.ip : null;
	}

	/** Invoked from the Discord IPC listener thread — must hop onto the client thread before touching any game state. */
	private static void onJoinRequested(String address) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (client.level != null) {
				// Already in a world — don't yank the player out without asking; just let them know.
				client.gui.getChat().addClientSystemMessage(
						Component.literal("A friend invited you to join " + address + " on Discord — leave your current game to accept."));
				return;
			}

			ServerData serverData = new ServerData(address, address, ServerData.Type.OTHER);
			ConnectScreen.startConnecting(client.screen, client, ServerAddress.parseString(address), serverData, false, null);
		});
	}
}
