package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * leftPos/topPos/imageWidth live on AbstractContainerScreen, not on the
 * concrete subclasses (e.g. InventoryScreen) — targeting the mixin directly
 * at the declaring class avoids relying on Mixin's shadow-field hierarchy
 * walk, which failed to resolve them when shadowed from a subclass mixin.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int nexomod$getLeftPos();

	@Accessor("topPos")
	int nexomod$getTopPos();

	@Accessor("imageWidth")
	int nexomod$getImageWidth();
}
