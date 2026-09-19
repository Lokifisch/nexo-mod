package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.world.entity.Entity;

import dev.nexoclient.nexomod.norender.NexoNoRender;

/**
 * The other half of "no hitboxes" for No Render. {@code EntityHitboxDebugRenderer}
 * (the F3+B overlay) walks {@code entitiesForRendering()} on its own rather
 * than going through {@code EntityRenderDispatcher.shouldRender} — confirmed
 * by decompiling it — so {@code NoRenderEntityMixin} alone would leave a
 * hidden entity's hitbox still drawing under F3+B. This is that class's own
 * equivalent choke point.
 */
@Mixin(EntityHitboxDebugRenderer.class)
public class NoRenderHitboxMixin {
	@Inject(method = "showHitboxes", at = @At("HEAD"), cancellable = true)
	private void nexomod$hideNoRenderHitboxes(Entity entity, float partialTick, boolean isTarget, CallbackInfo ci) {
		if (NexoNoRender.isHidden(entity.getType())) {
			ci.cancel();
		}
	}
}
