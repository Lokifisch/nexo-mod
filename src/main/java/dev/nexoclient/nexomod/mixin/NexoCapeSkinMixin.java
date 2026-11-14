package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;

import dev.nexoclient.nexomod.capes.NexoCapes;

/**
 * Puts a player's Nexo custom cape on their render state, over any Mojang cape.
 * Only the render state: the player's real skin record is never touched. The
 * elytra is left empty so vanilla draws the cape texture on it, as it does for a
 * Mojang cape.
 */
@Mixin(AvatarRenderer.class)
public abstract class NexoCapeSkinMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void nexomod$customCape(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		if (!(entity instanceof Player player)) {
			return;
		}
		Identifier cape = NexoCapes.capeFor(player.getUUID());
		if (cape == null) {
			return;
		}
		PlayerSkin skin = state.skin;
		state.skin = new PlayerSkin(skin.body(), new ClientAsset.ResourceTexture(cape, cape), null, skin.model(), skin.secure());
	}
}
