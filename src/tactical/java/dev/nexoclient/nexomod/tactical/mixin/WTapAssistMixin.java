package dev.nexoclient.nexomod.tactical.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * W-tap assist. A sprint hit makes the server (and the client) drop sprint, so
 * the next hit has no sprint knockback until the player taps forward again. If
 * the player was sprinting and is still holding forward, this sets sprint back
 * on locally and tells the server with an explicit START_SPRINTING — the
 * client's own change detection would send nothing, since it never saw the
 * state flip.
 *
 * <p>Knockback Displacement also switches this on: it only redirects the bonus
 * of a sprint hit, and a sprint hit ends sprint, so without the re-sprint every
 * second hit has nothing to redirect.
 */
@Mixin(MultiPlayerGameMode.class)
public class WTapAssistMixin {
	@Unique
	private boolean nexomod$wasSprinting;

	@Inject(method = "attack", at = @At("HEAD"))
	private void nexomod$noteSprint(Player player, Entity target, CallbackInfo ci) {
		nexomod$wasSprinting = player.isSprinting();
	}

	@Inject(method = "attack", at = @At("RETURN"))
	private void nexomod$resprint(Player player, Entity target, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (nexomod$wasSprinting && !player.isSprinting() && (NexoConfig.get().wTapAssistEnabled() || NexoConfig.get().knockbackDisplacementEnabled())
				&& client.options.keyUp.isDown() && client.getConnection() != null
				// Vanilla's own sprint preconditions, so this never grants sprint it would refuse.
				&& player.getFoodData().getFoodLevel() > 6 && !player.isUsingItem() && !player.horizontalCollision
				&& !player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)) {
			player.setSprinting(true);
			client.getConnection().send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
		}
	}
}
