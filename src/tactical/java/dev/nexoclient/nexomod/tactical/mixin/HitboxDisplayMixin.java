package dev.nexoclient.nexomod.tactical.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Hitbox Display answers "yes" to the renderer's own check instead of writing
 * ALWAYS_ON into the debug profile, which {@code setStatus} would save to disk:
 * a crash with the feature on would otherwise leave F3+B hitboxes on for good.
 */
@Mixin(DebugScreenEntryList.class)
public class HitboxDisplayMixin {
	@Inject(method = "isCurrentlyEnabled", at = @At("HEAD"), cancellable = true)
	private void nexomod$hitboxes(Identifier id, CallbackInfoReturnable<Boolean> cir) {
		if (id.equals(DebugScreenEntries.ENTITY_HITBOXES) && NexoConfig.get().hitboxDisplayEnabled()) {
			cir.setReturnValue(true);
		}
	}
}
