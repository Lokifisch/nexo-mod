package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.badge.NexoBadges;
import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Prepends the Nexo badge to the nametag of every player known to run Nexo —
 * yours (shown above your head in third person, and wherever else the game
 * reuses this render state's nameTag) and any other player the badge roster
 * confirms.
 *
 * <p>Skipped while {@link NexoHudVisibility#hidden()}: a third-person
 * screenshot is exactly where this badge would otherwise show up.
 *
 * <p>The {@link Player} check matters beyond saving work: mobs can carry a
 * nameTag too, and a name tag applied to a zombie must not be tested against a
 * roster of player UUIDs.
 */
@Mixin(LivingEntityRenderer.class)
public class NameTagBadgeMixin {
	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL"))
	private void nexomod$badgeNameTag(LivingEntity entity, LivingEntityRenderState renderState, float partialTick, CallbackInfo ci) {
		if (NexoHudVisibility.hidden()) {
			return;
		}
		if (renderState.nameTag == null || !(entity instanceof Player player)) {
			return;
		}
		if (!NexoBadges.hasBadge(player.getGameProfile().id())) {
			return;
		}
		renderState.nameTag = NexoMod.withBadge(renderState.nameTag);
	}
}
