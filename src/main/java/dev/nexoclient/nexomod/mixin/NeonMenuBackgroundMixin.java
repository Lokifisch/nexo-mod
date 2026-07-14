package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.WorldOptionsScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.telemetry.TelemetryInfoScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

import dev.nexoclient.nexomod.screen.NexoBackground;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Replaces vanilla's dirt-blur/panorama background on the menus this mod
 * re-skins — main menu, multiplayer, singleplayer world list, and the whole
 * options tree (every settings sub-screen: language, controls, video,
 * sound, chat, accessibility, resource packs, skin customization, credits,
 * telemetry, world defaults, key binds) — with whichever style is set in
 * Nexo Settings (starfield or matrix rain), unless the whole re-skin is
 * turned off there. Nearly every options sub-screen shares the common
 * {@code OptionsSubScreen} base, so that one check covers them (and any
 * future ones) instead of needing to enumerate each class; the handful
 * that extend {@code Screen} directly are listed explicitly. Two hook
 * points are needed: most screens draw their background in
 * {@code extractBackground}, but {@code TitleScreen} leaves that empty and
 * draws its panorama from inside {@code extractPanorama} instead (called
 * from its own {@code extractRenderState}) — so that's hooked too, gated
 * the same way. Container/inventory screens, chat, and the pause menu
 * (needs the actual world visible behind it) are deliberately untouched.
 */
@Mixin(Screen.class)
public abstract class NeonMenuBackgroundMixin {
	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void nexomod$neonBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (!NexoConfig.get().customMenusEnabled() || !nexomod$isTargetMenu(self)) {
			return;
		}
		NexoBackground.draw(graphics, self.width, self.height);
		ci.cancel();
	}

	@Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
	private void nexomod$neonPanorama(GuiGraphicsExtractor graphics, float partialTick, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (!NexoConfig.get().customMenusEnabled() || !nexomod$isTargetMenu(self)) {
			return;
		}
		NexoBackground.draw(graphics, self.width, self.height);
		ci.cancel();
	}

	private static boolean nexomod$isTargetMenu(Screen screen) {
		return screen instanceof TitleScreen
				|| screen instanceof JoinMultiplayerScreen
				|| screen instanceof OptionsScreen
				|| screen instanceof SelectWorldScreen
				|| screen instanceof OptionsSubScreen
				|| screen instanceof CreditsAndAttributionScreen
				|| screen instanceof TelemetryInfoScreen
				|| screen instanceof WorldOptionsScreen
				|| screen instanceof PackSelectionScreen;
	}
}
