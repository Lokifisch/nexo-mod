package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoSliderRenderer;

/**
 * Draws every vanilla slider (FOV, volume, Sprint Window, etc.) as one
 * full-size rounded box matching the button style — black fill, glowing
 * neon outline, current value shown as a filled portion inside — instead
 * of vanilla's gray track-plus-floating-handle look, game-wide, unless the
 * re-skin is turned off in Nexo Settings. {@code extractWidgetRenderState}
 * makes two {@code blitSprite} calls — track first, handle second — the
 * first is redirected to draw the whole box, the second becomes a no-op
 * (no separate handle piece in this design).
 */
@Mixin(AbstractSliderButton.class)
public abstract class NeonSliderMixin {
	@Shadow
	protected double value;

	@Redirect(
			method = "extractWidgetRenderState",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
					ordinal = 0))
	private void nexomod$box(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height, int color) {
		if (!NexoConfig.get().customMenusEnabled()) {
			graphics.blitSprite(pipeline, sprite, x, y, width, height, color);
			return;
		}
		AbstractSliderButton self = (AbstractSliderButton) (Object) this;
		NexoSliderRenderer.draw(graphics, x, y, x + width, y + height, value, self.active, self.isHoveredOrFocused());
	}

	@Redirect(
			method = "extractWidgetRenderState",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
					ordinal = 1))
	private void nexomod$handle(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height, int color) {
		if (!NexoConfig.get().customMenusEnabled()) {
			graphics.blitSprite(pipeline, sprite, x, y, width, height, color);
		}
	}
}
