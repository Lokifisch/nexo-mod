package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Camera;

import dev.nexoclient.nexomod.zoom.NexoZoom;

/**
 * The one place the level projection's field of view comes from in this
 * version: {@code GameRenderer} reads it back through {@link Camera#getFov()}
 * and hands it to {@code Projection.setupPerspective}. Dividing the returned
 * value is therefore the whole zoom — no projection matrix is rebuilt by hand
 * and nothing else has to know.
 *
 * <p>Deliberately <em>not</em> hooked on the HUD field of view
 * ({@code CameraRenderState.hudFov}, a separate value): zooming the world
 * should not stretch the held item and the hand, which is what a shared hook
 * would do.
 *
 * <p>Returns early when no zoom is active so a player who never binds the key
 * pays one float comparison per frame and nothing else.
 */
@Mixin(Camera.class)
public class CameraZoomMixin {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void nexomod$applyZoom(CallbackInfoReturnable<Float> cir) {
		if (!NexoZoom.active()) {
			return;
		}
		cir.setReturnValue(cir.getReturnValue() / NexoZoom.currentFactor());
	}
}
