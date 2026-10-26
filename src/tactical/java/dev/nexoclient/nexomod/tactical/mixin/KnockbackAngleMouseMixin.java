package dev.nexoclient.nexomod.tactical.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MouseHandler;

import dev.nexoclient.nexomod.tactical.knockback.NexoKnockbackAngle;

/**
 * While the knockback-angle key is held, the mouse movement the
 * camera was about to use steers the angle instead, and the camera does not
 * turn. The accumulators are zeroed so nothing is applied when the key is let go.
 */
@Mixin(MouseHandler.class)
public class KnockbackAngleMouseMixin {
	@Shadow
	private double accumulatedDX;
	@Shadow
	private double accumulatedDY;

	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void nexomod$steerAngle(double deltaTime, CallbackInfo ci) {
		if (NexoKnockbackAngle.holding()) {
			NexoKnockbackAngle.addMouse(accumulatedDX, accumulatedDY);
			accumulatedDX = 0;
			accumulatedDY = 0;
			ci.cancel();
		}
	}
}
