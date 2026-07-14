package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.screen.NexoStyle;

/** Draws the Nexo logo + "Nexo Client" just above the inventory panel. */
@Mixin(InventoryScreen.class)
public abstract class InventoryWatermarkMixin {
	private static final Identifier BADGE_TEXTURE = Identifier.fromNamespaceAndPath("nexomod", "textures/font/nexo_badge.png");
	private static final int ICON_SIZE = 16;

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void nexomod$watermark(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) (Object) this;
		int leftPos = accessor.nexomod$getLeftPos();
		int topPos = accessor.nexomod$getTopPos();
		int imageWidth = accessor.nexomod$getImageWidth();

		String label = "Nexo Client";
		var font = Minecraft.getInstance().font;
		int textWidth = font.width(label);
		int textHeight = font.lineHeight;
		int totalWidth = ICON_SIZE + 4 + textWidth;
		// Icon and text are centered against a shared row height rather than the text
		// being offset from the icon's own top edge — using the real line height instead
		// of a hardcoded guess is what actually keeps them visually aligned.
		int rowHeight = Math.max(ICON_SIZE, textHeight);

		int x = leftPos + (imageWidth - totalWidth) / 2;
		int rowY = topPos - rowHeight - 6;
		int iconY = rowY + (rowHeight - ICON_SIZE) / 2;
		int textY = rowY + (rowHeight - textHeight) / 2 + Math.round(textHeight * 0.35F);

		graphics.blit(BADGE_TEXTURE, x, iconY, x + ICON_SIZE, iconY + ICON_SIZE, 0.0F, 1.0F, 0.0F, 1.0F);
		graphics.text(font, label, x + ICON_SIZE + 4, textY, NexoStyle.TEXT_ACTIVE_ACCENT);
	}
}
