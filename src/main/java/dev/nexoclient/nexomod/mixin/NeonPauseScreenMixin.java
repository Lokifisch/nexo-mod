package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoBackground;
import dev.nexoclient.nexomod.screen.NexoButton;

/**
 * The pause menu gets the theme's effect drawn translucently over vanilla's
 * blurred world (PauseScreen overrides extractBackground, so
 * {@link NeonMenuBackgroundMixin} never reaches it), which also applies the
 * theme's colours to its buttons. Plus a top-right Switch Server button: it
 * opens the server list on top of the running game; only joining from there
 * leaves the current world (see {@link MultiplayerLoginButtonMixin}).
 */
@Mixin(PauseScreen.class)
public abstract class NeonPauseScreenMixin extends Screen {
	@Shadow @Final private boolean showPauseMenu;

	protected NeonPauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "extractBackground", at = @At("TAIL"))
	private void nexomod$seeThroughBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (this.showPauseMenu && NexoHudVisibility.nexoSkinActive()) {
			NexoBackground.drawSeeThrough(graphics, this.width, this.height);
		}
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void nexomod$switchServerButton(CallbackInfo ci) {
		if (!this.showPauseMenu) {
			return;
		}
		this.addRenderableWidget(NexoButton.builder(Component.translatable("nexomod.pause.switchServer"),
				() -> Minecraft.getInstance().gui.setScreen(new JoinMultiplayerScreen(this)))
				.bounds(this.width - 104, 4, 100, 20)
				.build());
	}
}
