package dev.nexoclient.nexomod.mixin;

import java.net.URL;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nexoclient.nexomod.profiles.NexoServerProfiles;
import dev.nexoclient.nexomod.servers.NexoServerPacks;
import net.minecraft.client.resources.server.DownloadedPackSource;

/** Notes which pack a server pushed, so its profile screen can copy it to the local folder. */
@Mixin(DownloadedPackSource.class)
public abstract class ServerPackRecordMixin {
	@Inject(method = "pushPack", at = @At("HEAD"))
	private void nexomod$record(UUID id, URL url, String hash, CallbackInfo ci) {
		String host = NexoServerProfiles.currentHost();
		if (host != null) {
			NexoServerPacks.remember(host, url.toString(), hash);
		}
	}
}
