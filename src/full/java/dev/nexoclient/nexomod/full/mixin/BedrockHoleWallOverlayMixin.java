package dev.nexoclient.nexomod.full.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * When the camera sits inside a solid block, vanilla paints that block's texture
 * across the whole screen — and skips it for spectators, which is why hole
 * outlines were visible in spectator but not when standing in the bedrock layer
 * with your head inside it. The outlines draw during the level pass, and this
 * overlay is painted over the finished frame, so no amount of always-on-top
 * helps: the fix has to be here.
 *
 * With the hole finder on, the overlay is dropped, which is what makes the
 * highlights usable while you're actually digging in the layer. Everything else
 * this renderer does — the water and fire overlays, item activation — is
 * untouched, and with the finder off vanilla behaves exactly as before.
 *
 * {@code getViewBlockingState}'s caller null-checks the result, so null is the
 * supported way to say "nothing is blocking the view".
 */
@Mixin(ScreenEffectRenderer.class)
public class BedrockHoleWallOverlayMixin {
	@Inject(method = "getViewBlockingState", at = @At("RETURN"), cancellable = true)
	private static void nexomod$seeThroughWallWhileFinding(Player player, CallbackInfoReturnable<BlockState> cir) {
		if (cir.getReturnValue() != null && NexoConfig.get().bedrockHoleFinderEnabled()) {
			cir.setReturnValue(null);
		}
	}
}
