package dev.nexoclient.nexomod.tactical.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;

import dev.nexoclient.nexomod.tactical.environment.NexoEnvironmentOverride;

/**
 * Replaces the weather strength the client draws with.
 *
 * <h2>Why {@code Level} and not {@code ClientLevel}</h2>
 *
 * <p>{@code getRainLevel}/{@code getThunderLevel} are declared on
 * {@code Level} and {@code ClientLevel} does not override them, and Mixin can
 * only inject into a method the target class actually declares. Targeting
 * {@code Level} means the injection is also compiled into the integrated
 * server's {@code ServerLevel} path, which is why every handler starts by
 * checking the instance — a single-player host must keep sending its real
 * weather to itself and to anyone connected over the LAN tunnel, and only the
 * client-side copy may lie.
 *
 * <h2>What this changes and what it cannot</h2>
 *
 * <p>These two values feed the rain/snow renderer and the sky. They also decide
 * {@code Level.isRaining()}, which is {@code getRainLevel(1.0F) &gt; 0.2} —
 * on the client that governs the rain ambience and splash particles, which is
 * the point. Nothing here reaches the server: weather is server-authoritative
 * and arrives as {@code ClientboundGameEventPacket}, so overriding the local
 * copy cannot stop a storm, change a mob spawn, or charge a creeper.
 */
@Mixin(Level.class)
public class WeatherOverrideMixin {
	@Inject(method = "getRainLevel(F)F", at = @At("HEAD"), cancellable = true)
	private void nexomod$overrideRain(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (!nexomod$isClientLevel()) {
			return;
		}
		float override = NexoEnvironmentOverride.rainLevel();
		if (override >= 0.0F) {
			cir.setReturnValue(override);
		}
	}

	@Inject(method = "getThunderLevel(F)F", at = @At("HEAD"), cancellable = true)
	private void nexomod$overrideThunder(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (!nexomod$isClientLevel()) {
			return;
		}
		float override = NexoEnvironmentOverride.thunderLevel();
		if (override >= 0.0F) {
			cir.setReturnValue(override);
		}
	}

	private boolean nexomod$isClientLevel() {
		return (Object) this instanceof ClientLevel;
	}
}
