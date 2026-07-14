package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A popup dialog drawn on top of whatever screen opened it, instead of
 * replacing it outright. The parent screen is redrawn first — still
 * visibly there, just dimmed behind an overlay — and this dialog's panel
 * is drawn over it with a soft highlight glow, so it reads as a modal
 * rather than a full screen change.
 */
public abstract class NexoModalScreen extends Screen {
	protected final Screen parent;
	protected final LinearLayout layout = LinearLayout.vertical().spacing(8);

	protected NexoModalScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (parent != null) {
			// -1,-1 so the parent's own widgets don't render a stale hover highlight.
			parent.extractBackground(graphics, -1, -1, partialTick);
			parent.extractRenderState(graphics, -1, -1, partialTick);
		}
		graphics.fill(0, 0, width, height, 0xB2000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x0 = layout.getX() - 20;
		int y0 = layout.getY() - 14;
		int x1 = layout.getX() + layout.getWidth() + 20;
		int y1 = layout.getY() + layout.getHeight() + 14;
		NexoPanelRenderer.draw(graphics, x0, y0, x1, y1);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		FrameLayout.centerInRectangle(layout, getRectangle());
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
