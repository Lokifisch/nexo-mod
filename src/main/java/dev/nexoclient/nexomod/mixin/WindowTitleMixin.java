package dev.nexoclient.nexomod.mixin;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.title.NexoWindowTitle;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces the window title. Vanilla refreshes it on every screen change, which is what
 * brings the name in on joining: {@code setLevel} runs before {@code player} is set.
 */
@Mixin(Minecraft.class)
public abstract class WindowTitleMixin {
	@Shadow public abstract ClientPacketListener getConnection();
	@Shadow public abstract ServerData getCurrentServer();
	@Shadow public abstract IntegratedServer getSingleplayerServer();

	@Inject(method = "createTitle", at = @At("HEAD"), cancellable = true)
	private void nexomod$createTitle(CallbackInfoReturnable<String> cir) {
		String version = SharedConstants.getCurrentVersion().name();
		cir.setReturnValue(NexoHudVisibility.hidden()
				? NexoWindowTitle.plainTitle(version)
				: NexoWindowTitle.title(version, NexoConfig.get().windowTitleServerEnabled() ? nexomod$place() : null));
	}

	private String nexomod$place() {
		ClientPacketListener connection = getConnection();
		if (connection == null || !connection.getConnection().isConnected()) {
			return null;
		}
		IntegratedServer world = getSingleplayerServer();
		if (world != null) {
			return world.getWorldData().getLevelName();
		}
		ServerData server = getCurrentServer();
		return server == null
				? null
				: NexoWindowTitle.serverPlace(server.name, server.ip, I18n.get("selectServer.defaultName"));
	}
}
