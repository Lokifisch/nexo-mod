package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import dev.nexoclient.nexomod.cosmetics.NexoCosmeticAvatarState;
import dev.nexoclient.nexomod.cosmetics.NexoCosmetics;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Resolves this frame's cape cosmetic for every rendered player, the same
 * injection point {@link NameTagBadgeMixin} already uses for the same reason:
 * this is where the game hands the render state a chance to be enriched
 * before {@code submit} reads it back a moment later.
 *
 * <p>Doubles as the render-side half of {@code CosmeticsEquipped}'s
 * visibility tracking — every player whose render state passes through here
 * is a player worth including in the next bulk equipped-lookup.
 */
@Mixin(LivingEntityRenderer.class)
public class AvatarCosmeticExtractMixin {
	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL"))
	private void nexomod$cosmeticCape(LivingEntity entity, LivingEntityRenderState renderState, float partialTick, CallbackInfo ci) {
		if (!NexoConfig.get().cosmeticsEnabled()) {
			return;
		}
		if (!(entity instanceof Player player) || !(renderState instanceof NexoCosmeticAvatarState cosmeticState)) {
			return;
		}
		var uuid = player.getGameProfile().id();
		NexoCosmetics.equipped().noteVisible(uuid);
		Integer capeId = NexoCosmetics.equipped().equippedCosmetic(uuid, "cape");
		cosmeticState.nexomod$setCapeCosmeticId(capeId != null ? capeId : -1);
	}
}
