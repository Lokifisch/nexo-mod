package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.AccountSwitcherScreen;
import dev.nexoclient.nexomod.screen.NexoButton;

/** Adds a Microsoft account button to the top-left of the multiplayer server list. */
@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerLoginButtonMixin extends Screen {
	protected MultiplayerLoginButtonMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void nexomod$addLoginButton(CallbackInfo ci) {
		this.addRenderableWidget(NexoButton.builder(nexomod$buttonLabel(),
				() -> Minecraft.getInstance().setScreen(new AccountSwitcherScreen(this)))
				.bounds(4, 4, 90, 20)
				.build());
	}

	private static Component nexomod$buttonLabel() {
		// The live session is the only thing that's never stale — the account
		// store's "active" marker doesn't cover the launcher's own session.
		String name = Minecraft.getInstance().getUser().getName();
		return name == null || name.isBlank()
				? Component.translatable("nexomod.login.signIn")
				: Component.literal(name);
	}
}
