package dev.nexoclient.nexomod.screen;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * A whole-number slider over an inclusive range, built on the vanilla widget so
 * the neon slider re-skin styles it like every other slider in the game.
 *
 * Vanilla's slider stores a 0..1 fraction; this maps that onto {@code min..max}
 * and reports only integers, so dragging can't produce a value the caller has to
 * round itself.
 */
public class NexoIntSlider extends AbstractSliderButton {
	private final String labelKey;
	private final int min;
	private final int max;
	private final IntConsumer onChange;

	/**
	 * @param labelKey translation key taking the current value as its one argument
	 * @param onChange called with the new value while dragging, once per step
	 */
	public NexoIntSlider(int x, int y, int width, int height, String labelKey, int min, int max, int value, IntConsumer onChange) {
		super(x, y, width, height, Component.empty(), fractionOf(value, min, max));
		this.labelKey = labelKey;
		this.min = min;
		this.max = max;
		this.onChange = onChange;
		updateMessage();
	}

	private static double fractionOf(int value, int min, int max) {
		return max == min ? 0.0 : (double) (Math.clamp(value, min, max) - min) / (max - min);
	}

	public int intValue() {
		return min + (int) Math.round(value * (max - min));
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.translatable(labelKey, intValue()));
	}

	@Override
	protected void applyValue() {
		onChange.accept(intValue());
	}
}
