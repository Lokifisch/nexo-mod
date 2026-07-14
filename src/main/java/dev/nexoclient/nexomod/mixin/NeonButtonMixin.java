package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;

import dev.nexoclient.nexomod.screen.NexoButtonRenderer;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Draws every vanilla button (stone sprite) as a neon gradient panel
 * instead, game-wide, unless the re-skin is turned off in Nexo Settings.
 * {@code extractDefaultSprite} is the one shared, final method every stock
 * button's background sprite routes through (see AbstractButton), so
 * hooking it once here covers every button in the game without touching
 * each screen's own button-construction code. This doesn't affect
 * {@code NexoButton} (used by this mod's own popups) — those paint
 * themselves directly rather than through this vanilla sprite path, so
 * they stay Nexo-styled regardless of this toggle.
 */
@Mixin(AbstractButton.class)
public abstract class NeonButtonMixin {
	@Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true)
	private void nexomod$neonSprite(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (!NexoConfig.get().customMenusEnabled()) {
			return;
		}
		AbstractButton self = (AbstractButton) (Object) this;
		NexoButtonRenderer.draw(graphics, self.getX(), self.getY(), self.getX() + self.getWidth(), self.getY() + self.getHeight(),
				self.active, self.isHoveredOrFocused());

		ci.cancel();
	}
}
