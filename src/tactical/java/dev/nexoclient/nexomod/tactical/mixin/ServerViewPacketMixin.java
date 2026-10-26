package dev.nexoclient.nexomod.tactical.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;

import dev.nexoclient.nexomod.tactical.stealth.NexoServerView;

/**
 * The only place the audit HUD can honestly measure from: everything this
 * client sends leaves through here, after every module has had its say.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ServerViewPacketMixin {
	@Inject(method = "send", at = @At("HEAD"))
	private void nexomod$observe(Packet<?> packet, CallbackInfo ci) {
		NexoServerView.observePacket(packet);
	}
}
