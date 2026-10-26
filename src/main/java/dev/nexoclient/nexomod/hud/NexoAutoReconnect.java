package dev.nexoclient.nexomod.hud;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Reconnects to the last multiplayer server after the disconnect screen has sat
 * for {@link #DELAY_TICKS}. Only the disconnect screen triggers it — leaving
 * through the pause menu never lands there — and leaving that screen (Esc)
 * cancels the attempt. Capped at {@link #MAX_ATTEMPTS} in a row so a ban or a
 * dead server doesn't turn into a reconnect loop; a successful join resets it.
 */
public final class NexoAutoReconnect {
	private static final int DELAY_TICKS = 100;
	private static final int MAX_ATTEMPTS = 3;

	/** A session shorter than this doesn't earn its attempts back, so join-then-kick loops still hit the cap. */
	private static final long STABLE_MS = 60_000;
	/** How soon after leaving a world a disconnect screen still counts as that world kicking us. */
	private static final long KICK_WINDOW_MS = 3_000;
	private static final long CHAIN_WINDOW_MS = 60_000;

	private static ServerData lastServer;
	private static int attempts;
	private static int waited;
	private static long joinedAt;
	private static long leftAt;
	private static long lastAttemptAt;

	private NexoAutoReconnect() {
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			lastServer = client.getCurrentServer();
			joinedAt = System.currentTimeMillis();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			leftAt = System.currentTimeMillis();
			if (leftAt - joinedAt >= STABLE_MS) {
				attempts = 0;
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(NexoAutoReconnect::tick);
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof DisconnectedScreen) {
				ScreenEvents.afterExtract(screen).register((s, graphics, mx, my, dt) -> {
					if (active()) {
						int seconds = (DELAY_TICKS - waited + 19) / 20;
						graphics.centeredText(client.font, Component.translatable("nexomod.reconnect.countdown", seconds),
								w / 2, h - 30, 0xFFFFFFFF);
					}
				});
			}
		});
	}

	/**
	 * Only for a disconnect screen that follows this world ending (a kick), or
	 * the next failure in a reconnect chain we started — not for a failed
	 * connect to some other server minutes after the player left this one.
	 */
	private static boolean active() {
		long now = System.currentTimeMillis();
		boolean fresh = now - leftAt < KICK_WINDOW_MS || (attempts > 0 && now - lastAttemptAt < CHAIN_WINDOW_MS);
		return NexoConfig.get().autoReconnectEnabled() && lastServer != null && attempts < MAX_ATTEMPTS && fresh;
	}

	private static void tick(Minecraft client) {
		if (!(client.gui.screen() instanceof DisconnectedScreen)) {
			waited = 0;
			return;
		}
		if (!active() || ++waited < DELAY_TICKS) {
			return;
		}
		waited = 0;
		attempts++;
		lastAttemptAt = System.currentTimeMillis();
		ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), client,
				ServerAddress.parseString(lastServer.ip), lastServer, false, null);
	}
}
