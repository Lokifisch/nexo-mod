package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoButton;
import dev.nexoclient.nexomod.screen.NexoSettingsScreen;

/** Adds a "Nexo Settings" button to the options screen, top-right, opening the background-style toggle. */
@Mixin(OptionsScreen.class)
public abstract class NexoSettingsButtonMixin extends Screen {
	protected NexoSettingsButtonMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void nexomod$addSettingsButton(CallbackInfo ci) {
		this.addRenderableWidget(NexoButton.builder(Component.translatable("nexomod.settings.title"),
				() -> Minecraft.getInstance().setScreen(new NexoSettingsScreen(this)))
				.bounds(this.width - 104, 4, 100, 20)
				.build());
	}
}
