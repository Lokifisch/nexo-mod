package dev.nexoclient.nexomod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoRowRenderer;
import dev.nexoclient.nexomod.screen.NexoSliderRenderer;

/**
 * Draws every vanilla slider (FOV, volume, Sprint Window, etc.) as a
 * settings row — a flat box with the name on the left and a rainbow track,
 * knob and value on the right — instead of vanilla's gray track-plus-handle
 * look with centred text, game-wide, unless the re-skin is turned off in
 * Nexo Settings. {@code extractWidgetRenderState}
 * makes two {@code blitSprite} calls — track first, handle second — the
 * first is redirected to draw the whole box, the second becomes a no-op
 * (no separate handle piece in this design).
 */
@Mixin(AbstractSliderButton.class)
public abstract class NeonSliderMixin {
	@Shadow
	protected double value;

	@Shadow
	protected boolean canChangeValue;

	@Redirect(
			method = "extractWidgetRenderState",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
					ordinal = 0))
	private void nexomod$box(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height, int color) {
		if (!NexoHudVisibility.nexoSkinActive()) {
			graphics.blitSprite(pipeline, sprite, x, y, width, height, color);
			return;
		}
		AbstractSliderButton self = (AbstractSliderButton) (Object) this;
		if (NexoRowRenderer.isFlat(self)) {
			NexoRowRenderer.flatBox(graphics, x, y, x + width, y + height, self.active, self.isHoveredOrFocused());
			return;
		}
		NexoSliderRenderer.draw(graphics, x, y, x + width, y + height, value, self.active, self.isHoveredOrFocused());
	}

	@Redirect(
			method = "extractWidgetRenderState",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
					ordinal = 1))
	private void nexomod$handle(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height, int color) {
		// A slider too narrow for the row layout keeps vanilla's label, so it
		// keeps vanilla's handle too — otherwise it would show no position at all.
		AbstractSliderButton self = (AbstractSliderButton) (Object) this;
		if (!NexoHudVisibility.nexoSkinActive() || self.getWidth() < NexoRowRenderer.MIN_ROW_WIDTH) {
			graphics.blitSprite(pipeline, sprite, x, y, width, height, color);
		}
	}

	/** The centred "Name: Value" string becomes a settings row — see {@link NexoRowRenderer#slider}. */
	@WrapOperation(
			method = "extractWidgetRenderState",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/components/AbstractSliderButton;extractScrollingStringOverContents(Lnet/minecraft/client/gui/ActiveTextCollector;Lnet/minecraft/network/chat/Component;I)V"))
	private void nexomod$row(AbstractSliderButton self, ActiveTextCollector text, Component message, int margin,
			Operation<Void> original, @Local(argsOnly = true) GuiGraphicsExtractor graphics) {
		if (!NexoHudVisibility.nexoSkinActive() || self.getWidth() < NexoRowRenderer.MIN_ROW_WIDTH) {
			original.call(self, text, message, margin);
			return;
		}
		NexoRowRenderer.slider(graphics, self.getX(), self.getY(), self.getX() + self.getWidth(),
				self.getY() + self.getHeight(), message, value, self.active, canChangeValue);
	}
}
