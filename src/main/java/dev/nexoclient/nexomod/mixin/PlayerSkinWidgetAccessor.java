package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.components.PlayerSkinWidget;

/** Lets the title screen's player model start turned towards the menu instead of vanilla's fixed angle. */
@Mixin(PlayerSkinWidget.class)
public interface PlayerSkinWidgetAccessor {
	@Accessor("rotationY")
	void nexomod$setRotationY(float rotationY);
}
