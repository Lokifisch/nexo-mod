package dev.nexoclient.nexomod.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.datafixers.util.Pair;

import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition;
import net.minecraft.client.gui.font.providers.GlyphProviderType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * Drops our own TTF font provider entry from the resolved {@code minecraft:default}
 * font when the custom font is turned off in Nexo Settings, falling back to whatever
 * the next-priority pack (vanilla) provides for the same font. Filtering by
 * {@code GlyphProviderType.TTF} identifies our provider without needing compile-time
 * access to {@code FontManager.BuilderId} — that record is private to FontManager, so
 * a normal Java source file (this mixin) can't reference it as a type at all.
 */
@Mixin(FontManager.class)
public abstract class NexoFontToggleMixin {
	@Inject(method = "loadResourceStack", at = @At("RETURN"), cancellable = true, require = 1)
	private static void nexomod$filterCustomFont(
			List<Resource> resourceStack,
			Identifier fontName,
			CallbackInfoReturnable<List<Pair<?, GlyphProviderDefinition.Conditional>>> cir) {
		if (dev.nexoclient.nexomod.screen.NexoConfig.get().customFontEnabled()
				|| !fontName.equals(Identifier.withDefaultNamespace("default"))) {
			return;
		}
		List<Pair<?, GlyphProviderDefinition.Conditional>> filtered = cir.getReturnValue().stream()
				.filter(pair -> pair.getSecond().definition().type() != GlyphProviderType.TTF)
				.toList();
		cir.setReturnValue(filtered);
	}
}
