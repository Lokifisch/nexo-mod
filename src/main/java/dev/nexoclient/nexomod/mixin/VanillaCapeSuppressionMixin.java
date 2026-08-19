package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import dev.nexoclient.nexomod.cosmetics.NexoCosmeticAvatarState;

/**
 * Stops vanilla's own cape from drawing underneath a Nexo cosmetic cape.
 *
 * <p>Both layers target the exact same mesh/slot on the model, so without
 * this a player wearing a Nexo cape while also owning a real Mojang cape
 * would render both at once. The absence of a Nexo cape (the common case,
 * and every player who has never equipped one) leaves this untouched, which
 * is also the entire mechanism behind "equip your official cape" on the
 * Nexo side — there is nothing to build for that beyond making sure it is
 * not being overdrawn: {@code /unequip} clears the Nexo slot, and vanilla's
 * own {@link CapeLayer} — never touched by this mod otherwise — goes back to
 * showing whatever cape the account actually owns.
 */
@Mixin(CapeLayer.class)
public class VanillaCapeSuppressionMixin {
	@Inject(
			method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
			at = @At("HEAD"), cancellable = true)
	private void nexomod$suppressForNexoCape(PoseStack poseStack, SubmitNodeCollector collector, int light,
			AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (state instanceof NexoCosmeticAvatarState cosmeticState && cosmeticState.nexomod$capeCosmeticId() >= 0) {
			ci.cancel();
		}
	}
}
