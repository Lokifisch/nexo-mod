package dev.nexoclient.nexomod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoRowRenderer;

/**
 * Plain buttons that are really options — labelled "Name: %s" with one
 * value, the way this mod builds its cycling settings by hand — drawn as the
 * same settings row as a real option button (see {@link NexoCycleButtonMixin}).
 * Any other plain button keeps its centred label. Wraps only the label call,
 * so the box and any other mod's hooks on this method still run.
 */
@Mixin(targets = "net.minecraft.client.gui.components.Button$Plain")
public abstract class NexoPlainButtonMixin {
	@WrapOperation(
			method = "extractContents",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/components/Button$Plain;extractDefaultLabel(Lnet/minecraft/client/gui/ActiveTextCollector;)V"))
	private void nexomod$row(Button.Plain self, ActiveTextCollector text, Operation<Void> original,
			@Local(argsOnly = true) GuiGraphicsExtractor graphics) {
		NexoRowRenderer.Parts parts = NexoHudVisibility.nexoSkinActive() && self.getWidth() >= NexoRowRenderer.MIN_ROW_WIDTH
				? NexoRowRenderer.parse(self.getMessage())
				: null;
		if (parts == null) {
			original.call(self, text);
			return;
		}
		NexoRowRenderer.option(graphics, self.getX(), self.getY(), self.getX() + self.getWidth(),
				self.getY() + self.getHeight(), parts.name(), parts.value(), parts.valueLabel(), self.active);
	}
}
