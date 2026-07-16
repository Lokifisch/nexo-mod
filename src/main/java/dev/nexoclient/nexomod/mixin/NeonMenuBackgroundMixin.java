package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import dev.nexoclient.nexomod.screen.NexoBackground;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Replaces vanilla's dirt-blur/panorama background on every menu-style
 * screen with whichever style is set in Nexo Settings (starfield or matrix
 * rain), unless the whole re-skin is turned off there. Deny-list rather
 * than allow-list, specifically so that OTHER mods' menu screens (anything
 * that just extends {@code Screen} and relies on the vanilla default
 * background, e.g. a config/macro-editor screen added by another mod) get
 * the same treatment automatically, instead of only the vanilla screens
 * this mod happens to know about. Two hook points are needed: most screens
 * draw their background in {@code extractBackground}, but
 * {@code TitleScreen} leaves that empty and draws its panorama from inside
 * {@code extractPanorama} instead (called from its own
 * {@code extractRenderState}) — so that's hooked too, gated the same way;
 * a screen that overrides {@code extractPanorama} the same way TitleScreen
 * does (a mod with its own rotating-panorama menu) is caught by this
 * without needing to name it explicitly.
 *
 * Only screens that need their OWN real background are excluded:
 * container/inventory screens (item slots need a normal backdrop, not
 * stars), the pause menu and chat (need the actual world visible behind
 * them), and the win/credits screen (its own animation). Everything else —
 * including screens contributed by other mods — gets reskinned.
 */
@Mixin(Screen.class)
public abstract class NeonMenuBackgroundMixin {
	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void nexomod$neonBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (!NexoConfig.get().customMenusEnabled() || nexomod$keepsVanillaBackground(self)) {
			return;
		}
		NexoBackground.draw(graphics, self.width, self.height);
		ci.cancel();
	}

	@Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
	private void nexomod$neonPanorama(GuiGraphicsExtractor graphics, float partialTick, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (!NexoConfig.get().customMenusEnabled() || nexomod$keepsVanillaBackground(self)) {
			return;
		}
		NexoBackground.draw(graphics, self.width, self.height);
		ci.cancel();
	}

	private static boolean nexomod$keepsVanillaBackground(Screen screen) {
		return screen instanceof AbstractContainerScreen
				|| screen instanceof PauseScreen
				|| screen instanceof ChatScreen
				|| screen instanceof GenericMessageScreen
				|| screen instanceof WinScreen;
	}
}
