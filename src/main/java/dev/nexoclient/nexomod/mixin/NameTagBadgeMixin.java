package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;

/**
 * Prepends the Nexo badge to your own nametag (shown above your head in
 * third person, and wherever else the game reuses this render state's
 * nameTag). Vanilla doesn't render your own nametag in first person, so
 * this is mainly a third-person/tab-list-adjacent effect.
 *
 * <p>Skipped while {@link NexoHudVisibility#hidden()}: a third-person
 * screenshot is exactly where this badge would otherwise show up.
 */
@Mixin(LivingEntityRenderer.class)
public class NameTagBadgeMixin {
	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL"))
	private void nexomod$badgeOwnNameTag(LivingEntity entity, LivingEntityRenderState renderState, float partialTick, CallbackInfo ci) {
		if (NexoHudVisibility.hidden()) {
			return;
		}
		if (entity != Minecraft.getInstance().player || renderState.nameTag == null) {
			return;
		}
		renderState.nameTag = NexoMod.withBadge(renderState.nameTag);
	}
}
