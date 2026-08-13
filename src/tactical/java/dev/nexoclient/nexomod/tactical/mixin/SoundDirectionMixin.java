package dev.nexoclient.nexomod.tactical.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;

import dev.nexoclient.nexomod.tactical.sound.NexoSoundRadar;

/**
 * Feeds the sound radar from the packet the server sends for a positioned
 * sound.
 *
 * <h2>Why TAIL and not HEAD</h2>
 *
 * <p>{@code handleSoundEvent} begins with
 * {@code PacketUtils.ensureRunningOnSameThread}, which throws
 * {@code RunningOnDifferentThreadException} when the packet arrives on the
 * netty thread and re-queues the call for the client thread. HEAD therefore runs
 * <em>twice</em> for every sound, once on a thread that has no business touching
 * client state; TAIL runs once, on the client thread, only on the pass that
 * actually plays the sound. Verified by reading the 26.1 bytecode: the
 * {@code ensureRunningOnSameThread} call is the first instruction and the only
 * other statement is the {@code ClientLevel.playSeededSound} the method exists
 * for.
 *
 * <h2>Why not {@code ClientboundSoundEntityPacket} too</h2>
 *
 * <p>That packet identifies a sound by entity id rather than by position, so it
 * would need an entity lookup that can fail (the entity may not be tracked yet)
 * and would report the entity's current position rather than where the sound was
 * made. It is deliberately left out: a radar that is sometimes right about
 * mob sounds is worse than one that consistently covers positioned sounds only.
 */
@Mixin(ClientPacketListener.class)
public class SoundDirectionMixin {
	@Inject(
			method = "handleSoundEvent(Lnet/minecraft/network/protocol/game/ClientboundSoundPacket;)V",
			at = @At("TAIL"))
	private void nexomod$recordSoundDirection(ClientboundSoundPacket packet, CallbackInfo ci) {
		NexoSoundRadar.record(packet.getSound(), packet.getSource(), packet.getX(), packet.getY(), packet.getZ());
	}
}
