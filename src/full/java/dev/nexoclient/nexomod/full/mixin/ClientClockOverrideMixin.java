package dev.nexoclient.nexomod.full.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;

import dev.nexoclient.nexomod.full.environment.NexoEnvironmentOverride;

/**
 * Pins the client's idea of the time of day.
 *
 * <h2>Why here</h2>
 *
 * <p>26.1 has no {@code Level.getDayTime()}; the world clock is
 * {@code WorldClock} plus a {@code ClockManager}, and the client's
 * implementation is {@link ClientClockManager}, fed by the server through
 * {@code handleUpdates}. Everything that renders the sky reaches it through
 * {@code ClockManager.getTotalTicks} — confirmed by reading
 * {@code AttributeTrackSampler}, which samples its keyframe track at exactly
 * that value and is what supplies sky colour, sun and moon angle and sky
 * brightness to {@code SkyRenderer}.
 *
 * <p>Mixing into {@code ClientClockManager} rather than into the
 * {@code ClockManager} interface or into {@code Level} keeps this off the
 * integrated server entirely: {@code ServerClockManager} is a different class,
 * so a single-player world's real clock, and everything the server derives from
 * it, is untouched. This value never leaves the client.
 *
 * <h2>RETURN, not HEAD</h2>
 *
 * <p>The override keeps the day count and replaces only the time within the day,
 * so it needs the real value. HEAD would have to recompute what it is
 * overriding.
 */
@Mixin(ClientClockManager.class)
public class ClientClockOverrideMixin {
	@Inject(
			method = "getTotalTicks(Lnet/minecraft/core/Holder;)J",
			at = @At("RETURN"),
			cancellable = true)
	private void nexomod$overrideTimeOfDay(Holder<WorldClock> clock, CallbackInfoReturnable<Long> cir) {
		if (!NexoEnvironmentOverride.timeActive()) {
			return;
		}
		long real = cir.getReturnValue();
		long adjusted = NexoEnvironmentOverride.applyTime(real);
		if (adjusted != real) {
			cir.setReturnValue(adjusted);
		}
	}
}
