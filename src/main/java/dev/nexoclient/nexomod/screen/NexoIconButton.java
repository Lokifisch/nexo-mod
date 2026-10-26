package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * A square button showing one item icon, with its label as a tooltip — the
 * icon tab row above the QoL menu. Drawn with the shared
 * {@link NexoButtonRenderer}, so it lights up on hover like every other button.
 * With no icon it draws a red ✕ instead: the menu's close button.
 */
public class NexoIconButton extends AbstractButton {
	private final ItemStack icon;
	private final Runnable onPress;

	private NexoIconButton(int size, Component label, ItemStack icon, Runnable onPress) {
		super(0, 0, size, size, label);
		this.icon = icon;
		this.onPress = onPress;
		setTooltip(Tooltip.create(label));
	}

	public static NexoIconButton icon(int size, Component label, ItemStack icon, Runnable onPress) {
		return new NexoIconButton(size, label, icon, onPress);
	}

	public static NexoIconButton close(int size, Component label, Runnable onPress) {
		return new NexoIconButton(size, label, null, onPress);
	}

	@Override
	public void onPress(InputWithModifiers input) {
		onPress.run();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x0 = getX();
		int y0 = getY();
		int x1 = x0 + getWidth();
		int y1 = y0 + getHeight();
		if (icon == null) {
			// Close: red outline, brighter and glowing on hover.
			boolean hot = isHoveredOrFocused();
			if (hot) {
				NexoShapes.glow(graphics, x0, y0, x1, y1, NexoStyle.DANGER, 4, 0.18F);
			}
			NexoShapes.outlined(graphics, x0, y0, x1, y1,
					NexoStyle.mix(NexoStyle.FIELD, NexoStyle.DANGER, hot ? 0.2F : 0.1F), NexoStyle.DANGER, 4);
			graphics.centeredText(Minecraft.getInstance().font, Component.literal("✕"),
					(x0 + x1) / 2, y0 + (getHeight() - 8) / 2, NexoStyle.DANGER);
			return;
		}
		NexoButtonRenderer.draw(graphics, x0, y0, x1, y1, active, isHoveredOrFocused());
		graphics.item(icon, x0 + (getWidth() - 16) / 2, y0 + (getHeight() - 16) / 2);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
