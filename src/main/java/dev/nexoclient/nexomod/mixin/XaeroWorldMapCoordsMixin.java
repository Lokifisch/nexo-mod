package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import dev.nexoclient.nexomod.coords.CoordObfuscator;
import dev.nexoclient.nexomod.coords.XaeroCompat;

/**
 * Obscures the coordinate readout at the top of Xaero's World Map screen.
 *
 * <p>The text is rewritten on its way to be drawn, rather than the numbers
 * being shifted at source. That distinction matters here: the fields the
 * readout is built from — {@code mouseBlockPosX/Z} — are also what the screen
 * uses to look up map tiles ({@code LeveledRegion.getTexture}), find hovered
 * highlights and place waypoints. Shifting them would make the map fetch the
 * wrong tiles under the cursor rather than merely print a different number,
 * which an earlier attempt at this got wrong.
 *
 * <p>{@code drawCenteredStringWithBackground} also draws the biome line and
 * others, so the coordinate line is identified by rebuilding what it must look
 * like from the real values and matching that exactly. A line that doesn't
 * match is passed through untouched — and reported, because a format change
 * would otherwise silently stop hiding coordinates while appearing to work.
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

	@ModifyArg(
			method = "extractRenderState",
			at = @At(
					value = "INVOKE",
					target = "Lxaero/map/graphics/MapRenderHelper;drawCenteredStringWithBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIFFFF)V"),
			index = 2,
			require = 0)
	private String nexomod$obscureMapCoordinates(String text) {
		if (text == null || !CoordObfuscator.active()) {
			return text;
		}

		// The line reads "X: <x>[ Y: <y>][ (<top>)] Z: <z>". Only the ends are
		// touched, so the height — which isn't obscured anywhere else either —
		// survives whatever shape it's in.
		String prefix = "X: " + mouseBlockPosX;
		String suffix = " Z: " + mouseBlockPosZ;

		if (!text.startsWith(prefix) || !text.endsWith(suffix)) {
			// Every other line drawn by this method — biome, region debug —
			// lands here, so only a line that looks like coordinates and still
			// fails to match counts as the format having moved.
			if (text.startsWith("X: ")) {
				XaeroCompat.worldMapFormatChanged();
			}
			return text;
		}

		XaeroCompat.worldMapPatchRan();
		String middle = text.substring(prefix.length(), text.length() - suffix.length());

		return "X: "
				+ (int) CoordObfuscator.obscureX(mouseBlockPosX)
				+ middle
				+ " Z: "
				+ (int) CoordObfuscator.obscureZ(mouseBlockPosZ);
	}
}
