package dev.nexoclient.nexomod.tactical.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

import dev.nexoclient.nexomod.tactical.freecam.NexoFreecam;

/**
 * Moves the camera to the freecam position once vanilla has finished placing
 * it on the player.
 *
 * <p>Injected at the tail of {@code alignWithEntity}, <em>not</em> of
 * {@code update}. {@code update} calls {@code alignWithEntity} first and then
 * builds the cull frustum and the perspective projection from wherever the
 * camera ended up — so overriding the position here means culling and
 * projection are computed from the freecam position too. Injecting at the tail
 * of {@code update} would leave both built around the player's head, and
 * everything the camera flew towards would be culled away.
 *
 * <p>Only the position is overridden, not the rotation: the body still turns
 * with the mouse and vanilla has already aligned the camera to it, which is
 * exactly the orientation wanted. See {@code NexoFreecam}'s note on that
 * trade-off.
 */
@Mixin(Camera.class)
public abstract class FreecamCameraMixin {
	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void nexomod$applyFreecam(float partialTicks, CallbackInfo ci) {
		if (NexoFreecam.active()) {
			setPosition(NexoFreecam.position());
		}
	}
}
