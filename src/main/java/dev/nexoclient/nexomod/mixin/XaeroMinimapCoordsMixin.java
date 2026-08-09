package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.core.BlockPos;

import dev.nexoclient.nexomod.coords.CoordObfuscator;
import dev.nexoclient.nexomod.coords.XaeroCompat;

/**
 * Applies the position-obscuring offset to Xaero's Minimap.
 *
 * <p>Obscuring vanilla's F3 screen isn't enough on its own: the minimap reads
 * the player's position itself and prints coordinates in a corner of the
 * screen the whole time, so a stream or screenshot leaks them regardless of
 * what F3 shows. It is also the most widely installed minimap, which makes it
 * the leak most people actually have.
 *
 * <p>The interception point is deliberately narrow. Xaero renders every
 * readout under the minimap — plain coordinates, Nether-scaled overworld
 * coordinates, and chunk coordinates — from a single {@code BlockPos} handed
 * to {@code InfoDisplayRenderer.render}. Shifting that one argument covers all
 * three at once and keeps them consistent with each other and with F3, rather
 * than patching three display implementations that could drift apart.
 *
 * <p>Display only: the value is used to build text, so nothing that depends on
 * the real position — waypoint distances, the map image itself, teleport
 * commands — is affected.
 *
 * <p>{@code @Pseudo} and {@code remap = false} because the target isn't
 * Minecraft: the class is absent when Xaero's isn't installed, and its names
 * are already runtime names.
 */
@Pseudo
@Mixin(targets = "xaero.hud.minimap.info.render.InfoDisplayRenderer", remap = false)
public class XaeroMinimapCoordsMixin {
	// require = 0: a Xaero update that changes this signature should stop the
	// patch applying, not stop the game starting. That failure would be
	// silent, so the patch reports in and XaeroCompat warns if it never does.
	@ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, require = 0)
	private BlockPos nexomod$obscureMinimapPosition(BlockPos position) {
		// Reported before the toggle is consulted: what matters is that the
		// patch is wired in, not whether it's currently shifting anything.
		XaeroCompat.minimapPatchRan();

		if (position == null || !CoordObfuscator.active()) {
			return position;
		}
		return CoordObfuscator.obscure(position);
	}
}
