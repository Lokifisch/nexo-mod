package dev.nexoclient.nexomod.tactical.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

import dev.nexoclient.nexomod.tactical.freecam.NexoFreecam;

/**
 * Moves the camera to the freecam position and rotation once vanilla has
 * finished aligning it to the player, and reports the camera as detached so
 * the player's own body renders while flying away.
 *
 * <p>Injected at the tail of {@code alignWithEntity}, <em>not</em> of
 * {@code update}. {@code update} calls {@code alignWithEntity} first and then
 * builds the cull frustum and the perspective projection from wherever the
 * camera ended up — so overriding the position here means culling and
 * projection are computed from the freecam position too. Injecting at the tail
 * of {@code update} would leave both built around the player's head, and
 * everything the camera flew towards would be culled away.
 *
 * <p>Position is interpolated ({@link NexoFreecam#renderPosition}) rather
 * than snapped to the raw tick-quantised value: {@code alignWithEntity} runs
 * once per rendered frame with a {@code partialTicks} argument vanilla uses
 * to blend an entity's previous- and current-tick position, and freecam's own
 * position only changes once per client tick — skipping that same blend is
 * what made flying visibly stair-step at any framerate above 20.
 *
 * <p>Rotation now comes from {@link NexoFreecam#yaw}/{@link NexoFreecam#pitch}
 * rather than the player's own, now-frozen rotation — see
 * {@code FreecamBodyFreezeMixin}.
 *
 * <h2>{@code isDetached}</h2>
 *
 * <p>{@code LevelRenderer} skips drawing the local player's own model under
 * exactly one condition: it's the camera's entity, the camera isn't detached,
 * and it isn't sleeping. Forcing {@code isDetached()} true while active is
 * the entire "see your own body" fix, confirmed to have no other reader
 * anywhere in the client — it does not touch {@code Options}' camera type, so
 * everything else that keys off "is this first person" (the held-item/hand
 * overlay, most notably) is unaffected.
 */
@Mixin(Camera.class)
public abstract class FreecamCameraMixin {
	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void nexomod$applyFreecam(float partialTicks, CallbackInfo ci) {
		if (NexoFreecam.active()) {
			setRotation(NexoFreecam.yaw(), NexoFreecam.pitch());
			setPosition(NexoFreecam.renderPosition(partialTicks));
		}
	}

	@Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
	private void nexomod$forceDetachedForFreecam(CallbackInfoReturnable<Boolean> cir) {
		if (NexoFreecam.active()) {
			cir.setReturnValue(true);
		}
	}
}
