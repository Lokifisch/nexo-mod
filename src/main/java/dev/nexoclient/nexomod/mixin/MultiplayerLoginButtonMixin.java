package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.AccountSwitcherScreen;
import dev.nexoclient.nexomod.screen.NexoButton;

/**
 * Adds a Microsoft account button to the top-left of the multiplayer server list,
 * and lets the list join a server while a world is loaded (it is reachable from
 * the pause menu's Switch Server button).
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerLoginButtonMixin extends Screen {
	protected MultiplayerLoginButtonMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void nexomod$addLoginButton(CallbackInfo ci) {
		this.addRenderableWidget(NexoButton.builder(nexomod$buttonLabel(),
				() -> Minecraft.getInstance().gui.setScreen(new AccountSwitcherScreen(this)))
				// Live rather than fixed: a switch that finishes after this
				// screen was built — which is what switching quickly causes —
				// would otherwise leave the previous account's name showing.
				.liveLabel(MultiplayerLoginButtonMixin::nexomod$buttonLabel)
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

	/**
	 * Vanilla's join assumes no world is loaded; with one, the old connection stays
	 * attached and crashes a few frames later. Leave it the way the Disconnect
	 * button does (synchronous), then connect with a parent that doesn't lead back
	 * into the now-dead pause menu.
	 */
	@Inject(method = "join", at = @At("HEAD"), cancellable = true)
	private void nexomod$leaveWorldFirst(ServerData server, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return;
		}
		ci.cancel();
		client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
		ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), client,
				ServerAddress.parseString(server.ip), server, false, null);
	}
}
