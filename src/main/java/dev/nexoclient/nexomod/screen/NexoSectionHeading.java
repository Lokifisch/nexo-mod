package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A small accent-coloured heading over a group of settings rows ("LAYOUT",
 * "DURABILITY"), as in the quick-settings mockup. Added with
 * {@link NexoModalScreen#section}; not focusable or clickable.
 */
public class NexoSectionHeading extends AbstractWidget {
	public NexoSectionHeading(Component label) {
		super(0, 0, 200, 16, label);
		active = false;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.scale(0.75F, 0.75F);
		Component text = Component.literal(getMessage().getString().toUpperCase(java.util.Locale.ROOT))
				.withStyle(style -> style.withBold(true));
		graphics.text(Minecraft.getInstance().font, text, Math.round((getX() + 4) / 0.75F),
				Math.round((getY() + getHeight() - 8) / 0.75F), NexoStyle.ACCENT);
		pose.popMatrix();
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, getMessage());
	}
}
