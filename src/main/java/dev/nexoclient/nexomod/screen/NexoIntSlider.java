package dev.nexoclient.nexomod.screen;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * A whole-number slider over an inclusive range, built on the vanilla widget so
 * the neon slider re-skin styles it like every other slider in the game.
 *
 * Vanilla's slider stores a 0..1 fraction; this maps that onto {@code min..max}
 * and reports only integers, so dragging can't produce a value the caller has to
 * round itself.
 *
 * <p>Two label forms: by default the key is filled with the raw number, and for
 * discrete stages (a quality/stealth ladder, say) a formatter turns the number
 * into a named stage so the bar reads "Careful" instead of "3".
 */
public class NexoIntSlider extends AbstractSliderButton {
	private final String labelKey;
	private final int min;
	private final int max;
	private final IntConsumer onChange;
	private final IntFunction<Component> formatter;

	/**
	 * @param labelKey translation key taking the current value as its one argument
	 * @param onChange called with the new value while dragging, once per step
	 */
	public NexoIntSlider(int x, int y, int width, int height, String labelKey, int min, int max, int value, IntConsumer onChange) {
		this(x, y, width, height, labelKey, min, max, value, onChange, null);
	}

	/**
	 * @param formatter builds the whole label from the current value, for stages
	 *                  that have names; {@code null} means "fill the key with the
	 *                  number", which is the common case
	 */
	public NexoIntSlider(int x, int y, int width, int height, String labelKey, int min, int max, int value,
			IntConsumer onChange, IntFunction<Component> formatter) {
		super(x, y, width, height, Component.empty(), fractionOf(value, min, max));
		this.labelKey = labelKey;
		this.min = min;
		this.max = max;
		this.onChange = onChange;
		this.formatter = formatter;
		updateMessage();
	}

	private static double fractionOf(int value, int min, int max) {
		return max == min ? 0.0 : (double) (Math.clamp(value, min, max) - min) / (max - min);
	}

	public int intValue() {
		return min + (int) Math.round(value * (max - min));
	}

	/**
	 * Moves the handle without firing {@code onChange}. Used when another widget
	 * changed the same setting — picking a preset has to show up on the sliders it
	 * wrote, and rebuilding the screen mid-drag would drop the drag.
	 */
	public void setIntValue(int newValue) {
		this.value = fractionOf(newValue, min, max);
		updateMessage();
	}

	@Override
	protected void updateMessage() {
		setMessage(formatter == null ? Component.translatable(labelKey, intValue()) : formatter.apply(intValue()));
	}

	@Override
	protected void applyValue() {
		onChange.accept(intValue());
	}
}
