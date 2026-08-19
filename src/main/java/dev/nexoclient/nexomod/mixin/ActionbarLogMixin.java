package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.hud.NexoFadingLogHud;

/**
 * Feeds the actionbar log HUD. {@code Gui.setOverlayMessage} is the one place
 * vanilla receives an actionbar string — there is no Fabric event for it.
 */
@Mixin(Gui.class)
public class ActionbarLogMixin {
	@Inject(method = "setOverlayMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"))
	private void nexomod$captureOverlayMessage(Component message, boolean animateColor, CallbackInfo ci) {
		NexoFadingLogHud.ACTIONBAR.record(message);
	}
}
