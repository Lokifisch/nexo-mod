package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;

/**
 * Swaps the bottom-left "Minecraft &lt;version&gt;" text for Nexo's own branding line.
 *
 * <p>While {@link NexoHudVisibility#hidden()} the original string is drawn
 * unchanged: this line is the mod's name in the corner of every title-screen
 * screenshot, so it belongs to the clean-screenshot toggle as much as the
 * badges do.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenBrandingMixin {
	@Redirect(
			method = "extractRenderState",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
	private void nexomod$brandingText(GuiGraphicsExtractor graphics, Font font, String versionString, int x, int y, int color) {
		graphics.text(font, NexoHudVisibility.hidden() ? versionString : "Minecraft Java Edition — Nexo Client", x, y, color);
	}
}
