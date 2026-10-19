package dev.nexoclient.nexomod.tactical.mixin;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Fullbright by overwriting the lightmap uniforms after vanilla fills them.
 * Vanilla's gamma option is capped at 1.0 in the options UI, but the shader
 * just takes {@code brightness} as a float, so the value written here is not
 * bound by that cap. Sodium samples the same lightmap texture, so this needs
 * no Sodium-specific path.
 */
@Mixin(LightmapRenderStateExtractor.class)
public class FullbrightMixin {
	@Inject(method = "extract(Lnet/minecraft/client/renderer/state/LightmapRenderState;F)V", at = @At("RETURN"))
	private void nexomod$fullbright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
		NexoConfig config = NexoConfig.get();
		// An early return leaves needsUpdate false and the state untouched; leave it alone too.
		if (!config.fullbrightEnabled() || !state.needsUpdate) {
			return;
		}
		float amount = config.fullbrightPercent() / 100.0F;
		state.ambientColor = new Vector3f(amount, amount, amount);
		state.brightness = amount;
		state.darknessEffectScale = 0.0F;
		state.bossOverlayWorldDarkening = 0.0F;
	}
}
