package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.SkinTextureDownloader;

/** Vanilla's conversion of old 64x32 skins and its transparency fixes, for the wardrobe's local skins. */
@Mixin(SkinTextureDownloader.class)
public interface SkinTextureDownloaderInvoker {
	@Invoker("processLegacySkin")
	static NativeImage nexomod$processLegacySkin(NativeImage image, String url) {
		throw new AssertionError();
	}
}
