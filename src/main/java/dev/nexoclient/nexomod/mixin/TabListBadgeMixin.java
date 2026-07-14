package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.nexoclient.nexomod.NexoMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/** Prepends the Nexo badge to your own row in the tab (player list) overlay. */
@Mixin(PlayerTabOverlay.class)
public class TabListBadgeMixin {
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void nexomod$badgeOwnRow(PlayerInfo playerInfo, CallbackInfoReturnable<Component> cir) {
		var localPlayer = Minecraft.getInstance().player;
		if (localPlayer == null || !localPlayer.getGameProfile().id().equals(playerInfo.getProfile().id())) {
			return;
		}
		cir.setReturnValue(NexoMod.withBadge(cir.getReturnValue()));
	}
}
