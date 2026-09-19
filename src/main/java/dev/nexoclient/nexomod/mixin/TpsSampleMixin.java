package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;

import dev.nexoclient.nexomod.hud.NexoStatsHud;

/**
 * Feeds the stats HUD's TPS guess. Vanilla has no plugin-free way to ask a
 * server its tick rate, so this reads the one thing the protocol already
 * carries: {@link ClientboundSetTimePacket#gameTime()} is the server's
 * absolute tick counter, synced whenever the server sends this packet. Real
 * elapsed time between two such syncs, divided into the tick-count delta
 * between them, is ticks/sec — i.e. TPS — regardless of the server's actual
 * send cadence.
 *
 * <p>HEAD, before anything else reads the packet: this only needs the raw
 * value, not the level state the rest of {@code handleSetTime} updates.
 */
@Mixin(ClientPacketListener.class)
public class TpsSampleMixin {
	@Inject(
			method = "handleSetTime(Lnet/minecraft/network/protocol/game/ClientboundSetTimePacket;)V",
			at = @At("HEAD"))
	private void nexomod$sampleTps(ClientboundSetTimePacket packet, CallbackInfo ci) {
		NexoStatsHud.recordGameTime(packet.gameTime());
	}
}
