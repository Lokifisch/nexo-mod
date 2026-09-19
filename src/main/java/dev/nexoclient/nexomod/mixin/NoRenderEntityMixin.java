package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

import dev.nexoclient.nexomod.norender.NexoNoRender;

/**
 * The whole "no model, no nametag, no glow" half of No Render. Everything
 * {@code LevelRenderer.extractVisibleEntities} draws for an entity — model,
 * nametag, glow outline — is downstream of this one call, confirmed by
 * decompiling that method: it gates entity-render-state extraction entirely,
 * so returning {@code false} here is a complete skip, not a partial one.
 *
 * <p>Doesn't cover the F3+B hitbox overlay — that walks the entity list on
 * its own without calling this. See {@code NoRenderHitboxMixin}.
 */
@Mixin(EntityRenderDispatcher.class)
public class NoRenderEntityMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void nexomod$hideNoRenderEntities(Entity entity, Frustum frustum, double x, double y, double z,
			CallbackInfoReturnable<Boolean> cir) {
		if (NexoNoRender.isHidden(entity.getType())) {
			cir.setReturnValue(false);
		}
	}
}
