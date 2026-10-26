package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoRowRenderer;

/**
 * Plain buttons that are really options — labelled "Name: %s" with one
 * value, the way this mod builds its cycling settings by hand — drawn as the
 * same settings row as a real option button (see {@link NexoCycleButtonMixin}).
 * Any other plain button keeps its centred label.
 */
@Mixin(targets = "net.minecraft.client.gui.components.Button$Plain")
public abstract class NexoPlainButtonMixin extends Button {
	protected NexoPlainButtonMixin(int x, int y, int width, int height, Component message, OnPress onPress,
			CreateNarration createNarration) {
		super(x, y, width, height, message, onPress, createNarration);
	}

	@Inject(method = "extractContents", at = @At("HEAD"), cancellable = true)
	private void nexomod$row(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!NexoHudVisibility.nexoSkinActive()) {
			return;
		}
		NexoRowRenderer.Parts parts = NexoRowRenderer.parse(getMessage());
		if (parts == null) {
			return;
		}
		extractDefaultSprite(graphics);
		NexoRowRenderer.option(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(),
				parts.name(), parts.value(), parts.valueLabel(), active);
		ci.cancel();
	}
}
