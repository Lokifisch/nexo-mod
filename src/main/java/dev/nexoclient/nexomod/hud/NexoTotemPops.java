package dev.nexoclient.nexomod.hud;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Totem pops per player, from vanilla's entity event 35 (the packet that plays
 * the totem animation), so it reports nothing the client wasn't already shown.
 * Counting always runs; only the chat line is gated by the setting, because the
 * Tactical {@code TOTEM_POP} macro trigger reads {@link #selfPoppedRecently()}.
 */
public final class NexoTotemPops {
	private static final long RECENT_MS = 1500;

	private static final Map<UUID, Integer> pops = new ConcurrentHashMap<>();
	private static volatile long lastSelfPop;

	private NexoTotemPops() {
	}

	public static void register() {
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> pops.clear());
	}

	/** Called from {@code TotemPopMixin} on the client thread. */
	public static void onPop(Entity entity) {
		if (!(entity instanceof Player popped)) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		int count = pops.merge(popped.getUUID(), 1, Integer::sum);
		if (popped == client.player) {
			lastSelfPop = System.currentTimeMillis();
		}
		if (NexoConfig.get().totemPopCounterEnabled() && client.player != null) {
			client.player.sendSystemMessage(Component.translatable("nexomod.totem.pop", popped.getName(), count));
		}
	}

	public static boolean selfPoppedRecently() {
		return System.currentTimeMillis() - lastSelfPop < RECENT_MS;
	}
}
