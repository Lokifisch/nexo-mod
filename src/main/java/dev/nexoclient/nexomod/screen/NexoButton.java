package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** A neon-gradient button, replacing vanilla's stone-sprite look. */
public class NexoButton extends AbstractButton {
	private final Runnable onPress;
	/**
	 * Optional source of a label that can change while the button is on
	 * screen. A label passed to the constructor is captured once at
	 * construction, which goes stale for anything reflecting mutable state —
	 * the signed-in account being the case that prompted this.
	 */
	private java.util.function.Supplier<Component> liveLabel;

	public NexoButton(int x, int y, int width, int height, Component message, Runnable onPress) {
		super(x, y, width, height, message);
		this.onPress = onPress;
	}

	public static Builder builder(Component message, Runnable onPress) {
		return new Builder(message, onPress);
	}

	@Override
	public void onPress(InputWithModifiers input) {
		onPress.run();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// Re-read the label every frame rather than only when the screen is
		// built, so a change that lands after init() — an account switch
		// completing, say — can't leave a stale name on the button.
		if (liveLabel != null) {
			Component current = liveLabel.get();
			if (!current.equals(getMessage())) {
				setMessage(current);
			}
		}

		NexoButtonRenderer.draw(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), active, isHoveredOrFocused());

		int textColor = active ? NexoStyle.TEXT_PRIMARY : NexoStyle.TEXT_DISABLED;
		graphics.centeredText(Minecraft.getInstance().font, getMessage(), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, textColor);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}

	public static final class Builder {
		private final Component message;
		private final Runnable onPress;
		private java.util.function.Supplier<Component> liveLabel;
		private int x;
		private int y;
		private int width = 150;
		private int height = 20;

		/**
		 * Recomputes the label every frame from {@code supplier}. Use for any
		 * button whose text mirrors state that can change while it's visible.
		 */
		public Builder liveLabel(java.util.function.Supplier<Component> supplier) {
			this.liveLabel = supplier;
			return this;
		}

		private Builder(Component message, Runnable onPress) {
			this.message = message;
			this.onPress = onPress;
		}

		public Builder pos(int x, int y) {
			this.x = x;
			this.y = y;
			return this;
		}

		public Builder size(int width, int height) {
			this.width = width;
			this.height = height;
			return this;
		}

		public Builder bounds(int x, int y, int width, int height) {
			return pos(x, y).size(width, height);
		}

		public NexoButton build() {
			NexoButton button = new NexoButton(x, y, width, height, message, onPress);
			button.liveLabel = liveLabel;
			return button;
		}
	}
}
