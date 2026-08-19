package dev.nexoclient.nexomod.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * Draws a Nexo cosmetic cape, in the vanilla cape's own slot on the model —
 * same mesh vanilla's own {@code CapeLayer} bakes from
 * {@link ModelLayers#PLAYER_CAPE}, just textured from
 * {@link CosmeticsAssetCache} instead of the account's skin-service cape.
 *
 * <p>Which cape (if any) to draw arrives on the render state itself, stashed
 * a moment earlier by {@code mixin.AvatarCosmeticExtractMixin} — see
 * {@link NexoCosmeticAvatarState} for why that indirection exists. If the
 * texture is not loaded yet, {@link CosmeticsAssetCache#texture} returns
 * null and this frame simply draws nothing; the next frame checks again once
 * the background fetch completes.
 */
final class NexoCosmeticsCapeLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private final PlayerCapeModel model;

	NexoCosmeticsCapeLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, EntityModelSet modelSet) {
		super(parent);
		this.model = new PlayerCapeModel(modelSet.bakeLayer(ModelLayers.PLAYER_CAPE));
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light,
			AvatarRenderState state, float yRot, float xRot) {
		if (state.isInvisible || !(state instanceof NexoCosmeticAvatarState cosmeticState)) {
			return;
		}
		int cosmeticId = cosmeticState.nexomod$capeCosmeticId();
		if (cosmeticId < 0) {
			return;
		}
		Identifier texture = NexoCosmetics.assets().texture(cosmeticId);
		if (texture == null) {
			return;
		}
		RenderType renderType = RenderTypes.entitySolid(texture);
		poseStack.pushPose();
		collector.submitModel(model, state, poseStack, renderType, light,
				OverlayTexture.NO_OVERLAY, state.outlineColor, null);
		poseStack.popPose();
	}
}
