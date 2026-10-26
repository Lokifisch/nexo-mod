package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;

import dev.nexoclient.nexomod.hud.NexoTotemPops;

/**
 * Entity event 35 is "totem used". TAIL rather than HEAD: the handler first
 * re-queues itself onto the main thread by throwing, so HEAD would fire once on
 * the network thread and once more on the client thread.
 */
@Mixin(ClientPacketListener.class)
public class TotemPopMixin {
	@Inject(method = "handleEntityEvent", at = @At("TAIL"))
	private void nexomod$totemPop(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (packet.getEventId() == 35 && client.level != null) {
			NexoTotemPops.onPop(packet.getEntity(client.level));
		}
	}
}
