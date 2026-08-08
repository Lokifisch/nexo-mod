package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.components.debug.DebugScreenEntryList;

import dev.nexoclient.nexomod.coords.F3CoordWarning;

/**
 * {@code toggleDebugOverlay} is the single method both F3 keybind paths in
 * {@code KeyboardHandler} funnel through (same-key release at line ~568 and
 * rebound-key press at ~571), so cancelling here covers every way the overlay
 * is opened by key without touching F3-combo handling, which never calls it.
 * Only the hidden→shown direction is gated — closing F3 never warns.
 */
@Mixin(DebugScreenEntryList.class)
public abstract class DebugOverlayCoordWarningMixin {
	@Shadow
	public abstract boolean isOverlayVisible();

	@Inject(method = "toggleDebugOverlay", at = @At("HEAD"), cancellable = true)
	private void nexomod$warnWhenCoordsExposed(CallbackInfo ci) {
		if (!isOverlayVisible() && F3CoordWarning.shouldBlock()) {
			ci.cancel();
		}
	}
}
