package dev.nexoclient.nexomod.tactical.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import dev.nexoclient.nexomod.tactical.freecam.NexoFreecam;

/**
 * Keeps the real body facing wherever it was when freecam engaged.
 *
 * <p>Mouse-look rotation is applied by exactly one call in the whole client:
 * {@code MouseHandler.turnPlayer()} invoking {@code player.turn(yRotDelta,
 * xRotDelta)} — statically typed as {@code LocalPlayer}, but
 * {@code LocalPlayer} declares no {@code turn} of its own, so this mixes into
 * {@link Entity}, the class that actually owns it, and checks for the local
 * player itself rather than narrowing the {@code @Mixin} target.
 *
 * <p>Cancelled entirely rather than applied-then-reset: the deltas are handed
 * to {@link NexoFreecam#turn} instead, which replicates this method's own
 * math for the camera's independent look state. Cancelling means the
 * player's real rotation never changes in the first place — not just
 * locally, but in what gets sent to the server and every other client too.
 */
@Mixin(Entity.class)
public class FreecamBodyFreezeMixin {
	@Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true)
	private void nexomod$freezeDuringFreecam(double yRotDelta, double xRotDelta, CallbackInfo ci) {
		if (NexoFreecam.active() && (Object) this == Minecraft.getInstance().player) {
			NexoFreecam.turn(yRotDelta, xRotDelta);
			ci.cancel();
		}
	}
}
