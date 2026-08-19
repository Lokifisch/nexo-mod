package dev.nexoclient.nexomod.screen;

import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * A module row: name + a one-line description, colored by whatever
 * {@code state} currently reports, click anywhere on it to act — the
 * "colored module list" pattern most other clients use for a feature menu,
 * in place of a vanilla button carrying an On/Off label.
 *
 * <p>One widget serves two jobs depending on the caller: a QoL-menu row uses
 * {@code state} to tint itself by whether that module is enabled and
 * {@code onPress} to open its config screen; a config screen's own enabled
 * toggle uses the same widget with {@code onPress} flipping the setting
 * directly. Either way the row never needs to know which job it's doing —
 * it just reflects {@code state} and reports a click.
 */
public class NexoModuleRow extends AbstractButton {
	private static final int ACCENT_BAR_WIDTH = 3;

	private final Component description;
	private final BooleanSupplier state;
	private final Runnable onPress;

	public NexoModuleRow(int x, int y, int width, int height, Component name, Component description,
			BooleanSupplier state, Runnable onPress) {
		super(x, y, width, height, name);
		this.description = description;
		this.state = state;
		this.onPress = onPress;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		onPress.run();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x0 = getX();
		int y0 = getY();
		int x1 = getX() + getWidth();
		int y1 = getY() + getHeight();
		boolean on = state.getAsBoolean();

		int bg = on ? 0x3A3CFFB0 : NexoStyle.PANEL_BG_RAISED;
		int accent = on ? NexoStyle.TEXT_ACTIVE_ACCENT : NexoStyle.BORDER_DIM;

		graphics.fill(x0, y0, x1, y1, isHoveredOrFocused() ? mix(bg) : bg);
		graphics.fill(x0, y0, x0 + ACCENT_BAR_WIDTH, y1, accent);

		Minecraft mc = Minecraft.getInstance();
		int textX = x0 + ACCENT_BAR_WIDTH + 8;
		Component name = getMessage().copy().withStyle(style -> style.withColor(on ? NexoStyle.TEXT_ACTIVE_ACCENT : NexoStyle.TEXT_PRIMARY).withBold(true));
		graphics.text(mc.font, name, textX, y0 + 4, on ? NexoStyle.TEXT_ACTIVE_ACCENT : NexoStyle.TEXT_PRIMARY);
		graphics.text(mc.font, description, textX, y0 + 4 + mc.font.lineHeight + 2, NexoStyle.TEXT_SECONDARY);
	}

	/** A touch brighter on hover, so the row reads as clickable without a separate hover asset. */
	private static int mix(int color) {
		int a = (color >>> 24) & 0xFF, r = (color >>> 16) & 0xFF, g = (color >>> 8) & 0xFF, b = color & 0xFF;
		return (a << 24) | (Math.min(255, r + 20) << 16) | (Math.min(255, g + 20) << 8) | Math.min(255, b + 20);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
