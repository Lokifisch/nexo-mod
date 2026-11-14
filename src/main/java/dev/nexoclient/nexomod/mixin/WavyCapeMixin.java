package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.world.item.ItemStack;

import dev.nexoclient.nexomod.capes.NexoWavyCape;
import dev.nexoclient.nexomod.screen.NexoConfig;

/** Draws capes with {@link NexoWavyCape}'s physics instead of vanilla's stiff board, under the same conditions. */
@Mixin(CapeLayer.class)
public abstract class WavyCapeMixin {
	@Shadow
	@Final
	private HumanoidModel<AvatarRenderState> model;

	@Shadow
	private boolean hasLayer(ItemStack itemStack, EquipmentClientInfo.LayerType layerType) {
		throw new AssertionError();
	}

	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
			at = @At("HEAD"), cancellable = true)
	private void nexomod$wavy(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (!NexoConfig.get().wavyCapesEnabled()) {
			return;
		}
		// Vanilla's own checks: no cape under an elytra, which wears the cape texture instead.
		if (state.isInvisible || !state.showCape || state.skin.cape() == null || hasLayer(state.chestEquipment, EquipmentClientInfo.LayerType.WINGS)) {
			ci.cancel();
			return;
		}
		boolean overArmor = hasLayer(state.chestEquipment, EquipmentClientInfo.LayerType.HUMANOID);
		if (NexoWavyCape.submit(poseStack, collector, light, state, model, state.skin.cape().texturePath(), overArmor)) {
			ci.cancel();
		}
	}
}
