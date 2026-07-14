package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Vanilla's {@code shouldShowName} hard-excludes the local player, so you
 * never see your own nametag above your head — not even in third person,
 * where every other player's shows fine. This forces it back on for you
 * specifically, outside first person (there's no player model to hang a
 * nametag off of in first person, same as vanilla).
 *
 * <p>Technique confirmed against a real MC 26.1 decompile: the gate to
 * flip is {@code LivingEntityRenderer#shouldShowName(LivingEntity, double)}.
 * Approach adapted from Fix85/SelfNametag (MIT licensed), not from
 * Essential-Mod.
 */
@Mixin(LivingEntityRenderer.class)
public class OwnNameTagVisibilityMixin<T extends LivingEntity> {
	@Inject(
			method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z",
			at = @At("HEAD"),
			cancellable = true)
	private void nexomod$forceShowOwnName(T entity, double distanceSq, CallbackInfoReturnable<Boolean> cir) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || entity != mc.player) {
			return;
		}
		if (mc.options.getCameraType().isFirstPerson()) {
			return;
		}
		cir.setReturnValue(true);
	}
}
