package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.badge.NexoBadges;
import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/**
 * Prepends the Nexo badge to the row of every player known to run Nexo —
 * yours always, and anyone else the badge roster confirms.
 *
 * <p>Skipped entirely while {@link NexoHudVisibility#hidden()} — the tab list is
 * in more screenshots than any other overlay, so the badge has to be one of the
 * things the clean-screenshot toggle takes away.
 *
 * <p>This runs once per player per frame, which is why
 * {@link NexoBadges#hasBadge} is a memoised binary search rather than anything
 * that touches the network.
 */
@Mixin(PlayerTabOverlay.class)
public class TabListBadgeMixin {
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void nexomod$badgeRow(PlayerInfo playerInfo, CallbackInfoReturnable<Component> cir) {
		if (NexoHudVisibility.hidden()) {
			return;
		}
		if (!NexoBadges.hasBadge(playerInfo.getProfile().id())) {
			return;
		}
		cir.setReturnValue(NexoMod.withBadge(cir.getReturnValue()));
	}
}
