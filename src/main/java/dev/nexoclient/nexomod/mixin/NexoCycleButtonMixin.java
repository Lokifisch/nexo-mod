package dev.nexoclient.nexomod.mixin;

import java.util.function.Function;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoRowRenderer;

/**
 * Every "Name: Value" option button (CycleButton) drawn as a settings row:
 * name left, value right — a switch for on/off, an accent pill otherwise.
 * See {@link NexoRowRenderer#option}. Only the label is replaced; the box
 * underneath is still {@code NeonButtonMixin}'s, and buttons that show only
 * their value (no name) keep vanilla's centred label.
 */
@Mixin(CycleButton.class)
public abstract class NexoCycleButtonMixin<T> {
	@Shadow
	@Final
	private Component name;

	@Shadow
	@Final
	private CycleButton.DisplayState displayState;

	@Shadow
	@Final
	private Function<T, Component> valueStringifier;

	@Shadow
	public abstract T getValue();

	@WrapOperation(
			method = "extractContents",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/components/CycleButton;extractDefaultLabel(Lnet/minecraft/client/gui/ActiveTextCollector;)V"))
	private void nexomod$row(CycleButton<T> self, ActiveTextCollector text, Operation<Void> original,
			@Local(argsOnly = true) GuiGraphicsExtractor graphics) {
		if (!NexoHudVisibility.nexoSkinActive() || displayState != CycleButton.DisplayState.NAME_AND_VALUE) {
			original.call(self, text);
			return;
		}
		T value = getValue();
		NexoRowRenderer.option(graphics, self.getX(), self.getY(), self.getX() + self.getWidth(),
				self.getY() + self.getHeight(), name, value, valueStringifier.apply(value), self.active);
	}
}
