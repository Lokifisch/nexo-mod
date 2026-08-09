package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import dev.nexoclient.nexomod.coords.CoordObfuscator;
import dev.nexoclient.nexomod.coords.XaeroCompat;

/**
 * Applies the position-obscuring offset to the coordinate readout in Xaero's
 * World Map.
 *
 * <p>The map screen prints the coordinates under the cursor, which is a
 * screenshot leak in exactly the same way the minimap's readout is.
 *
 * <p>Unlike the minimap there is no single position argument to shift: the
 * figures come from private fields the screen fills in while rendering. They
 * are offset on the way into the render pass and put back on the way out,
 * which keeps the change strictly to what is drawn. Anything acting on the
 * real position — clicking to place a waypoint, the hop-to-coordinates box,
 * the map image itself — runs outside this method and still sees true values.
 *
 * <p>The restore runs on return. A mixin cannot wrap the method in a
 * {@code finally}, so an exception thrown mid-render would strand the shifted
 * values — acceptable here, since the next frame's HEAD injection overwrites
 * them before anything reads them again.
 *
 * <p>{@code @Pseudo} and {@code remap = false} because the target isn't
 * Minecraft: the class is absent when the World Map isn't installed, and its
 * names are already runtime names.
 */
@Pseudo
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public class XaeroWorldMapCoordsMixin {
	@Shadow
	private int mouseBlockPosX;
	@Shadow
	private int mouseBlockPosZ;

	@Unique
	private boolean nexomod$shifted;
	@Unique
	private int nexomod$realX;
	@Unique
	private int nexomod$realZ;

	@Inject(method = "extractRenderState", at = @At("HEAD"), require = 0)
	private void nexomod$obscureBeforeDrawing(
			GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		nexomod$shifted = false;
		XaeroCompat.worldMapPatchRan();

		if (!CoordObfuscator.active()) {
			return;
		}

		nexomod$realX = mouseBlockPosX;
		nexomod$realZ = mouseBlockPosZ;
		mouseBlockPosX = (int) CoordObfuscator.obscureX(mouseBlockPosX);
		mouseBlockPosZ = (int) CoordObfuscator.obscureZ(mouseBlockPosZ);
		nexomod$shifted = true;
	}

	@Inject(method = "extractRenderState", at = @At("RETURN"), require = 0)
	private void nexomod$restoreAfterDrawing(
			GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		nexomod$restore();
	}

	@Unique
	private void nexomod$restore() {
		if (!nexomod$shifted) {
			return;
		}
		mouseBlockPosX = nexomod$realX;
		mouseBlockPosZ = nexomod$realZ;
		nexomod$shifted = false;
	}
}
