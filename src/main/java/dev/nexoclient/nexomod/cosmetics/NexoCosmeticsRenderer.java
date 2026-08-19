package dev.nexoclient.nexomod.cosmetics;

import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.EntityType;

/**
 * Registers the cosmetic render layers onto the player renderer. Uses
 * Fabric's supported extension point for adding a layer to a
 * {@code LivingEntityRenderer} rather than a mixin into the renderer itself —
 * the callback fires once per entity type as renderers are built, so this
 * only needs to recognise {@link EntityType#PLAYER} and ignore every other
 * living entity.
 */
public final class NexoCosmeticsRenderer {
	private NexoCosmeticsRenderer() {
	}

	public static void register() {
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, renderer, helper, context) -> {
			if (entityType != EntityType.PLAYER) {
				return;
			}
			@SuppressWarnings("unchecked")
			RenderLayerParent<AvatarRenderState, PlayerModel> parent =
					(RenderLayerParent<AvatarRenderState, PlayerModel>) renderer;
			helper.register(new NexoCosmeticsCapeLayer(parent, context.getModelSet()));
		});
	}
}
