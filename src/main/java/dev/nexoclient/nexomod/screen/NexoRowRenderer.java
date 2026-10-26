package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * The settings-row look from the "Module settings" mockup (design/STYLE.md):
 * the setting's name on the left, its control on the right — a thin rainbow
 * track with a knob and the value for a slider, a value pill for a cycling
 * option, a switch for an on/off one. Drawn over the flat box
 * {@link NexoButtonRenderer} / {@link NexoSliderRenderer} already painted, in
 * place of vanilla's one centred "Name: Value" string.
 *
 * <p>Applies to every slider and option button game-wide while the re-skin is
 * on (see {@code NeonSliderMixin}, {@code NexoCycleButtonMixin}), vanilla's own
 * options screens included, so the two never look like different products.
 */
public final class NexoRowRenderer {
	private static final int PAD = 8;
	private static final int SWITCH_WIDTH = 20;
	private static final int SWITCH_HEIGHT = 10;

	private NexoRowRenderer() {}

	/**
	 * A slider row. Vanilla only hands sliders one message, so name and value
	 * are split at its last ": " — the "Name: Value" shape every slider in the
	 * game uses. A message without one is shown whole, as the name.
	 */
	public static void slider(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, Component message,
			double value, boolean active, boolean editing) {
		Font font = Minecraft.getInstance().font;
		String text = message.getString();
		int split = text.lastIndexOf(": ");
		String name = split > 0 ? text.substring(0, split) : text;
		String shown = split > 0 ? text.substring(split + 2) : "";

		int textY = y0 + (y1 - y0 - 8) / 2;
		shown = ellipsize(font, shown, x1 - x0 - PAD * 2);
		int valueWidth = font.width(shown);
		graphics.text(font, shown, x1 - PAD - valueWidth, textY, active ? NexoStyle.TEXT_PRIMARY : NexoStyle.TEXT_DISABLED);

		// The track takes the right half, or whatever the name leaves — and is
		// dropped entirely on a slider too narrow to show one meaningfully.
		int nameWidth = font.width(name);
		int trackX1 = x1 - PAD - valueWidth - 8;
		int trackX0 = Math.max(x0 + PAD + Math.min(nameWidth, (x1 - x0) / 2) + 10, x0 + (x1 - x0) * 45 / 100);
		int nameMax = (trackX1 - trackX0 >= 24 ? trackX0 - 10 : trackX1) - (x0 + PAD);
		graphics.text(font, ellipsize(font, name, nameMax), x0 + PAD, textY,
				active ? NexoStyle.TEXT_SECONDARY : NexoStyle.TEXT_DISABLED);
		if (trackX1 - trackX0 < 24) {
			return;
		}
		// Keyboard adjust mode (Enter on a focused slider): the knob lights up so
		// it is clear the arrow keys now move the value.
		int knobColor = editing ? NexoStyle.ACCENT : NexoStyle.TEXT_PRIMARY;

		int mid = (y0 + y1) / 2;
		NexoShapes.fillRoundedFast(graphics, trackX0, mid - 1, trackX1, mid + 1, NexoStyle.CONTROL, 1);
		int fillX1 = trackX0 + (int) Math.round((trackX1 - trackX0) * Math.clamp(value, 0D, 1D));
		if (active && fillX1 > trackX0) {
			NexoShapes.fillLogoRamp(graphics, trackX0, trackX1, fillX1, mid - 1, mid + 1, 0xFF);
		}
		int knob = 3;
		NexoShapes.fillRounded(graphics, fillX1 - knob, mid - knob, fillX1 + knob, mid + knob,
				active ? knobColor : NexoStyle.TEXT_DISABLED, knob);
	}

	/** Narrower than this, a row has no room for a name and a control: callers keep vanilla's label. */
	public static final int MIN_ROW_WIDTH = 60;

	/**
	 * A cycling option's row: name on the left; on the right a switch when the
	 * value is a boolean, otherwise the value in an accent pill.
	 */
	public static void option(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, Component name,
			Object value, Component valueLabel, boolean active) {
		Font font = Minecraft.getInstance().font;
		int textY = y0 + (y1 - y0 - 8) / 2;
		int right;
		if (value instanceof Boolean on) {
			int sx1 = x1 - PAD;
			int sx0 = sx1 - SWITCH_WIDTH;
			int sy0 = (y0 + y1) / 2 - SWITCH_HEIGHT / 2;
			int sy1 = sy0 + SWITCH_HEIGHT;
			boolean lit = on && active;
			if (lit) {
				NexoShapes.glow(graphics, sx0, sy0, sx1, sy1, NexoStyle.ACCENT, SWITCH_HEIGHT / 2, 0.16F);
			}
			NexoShapes.outlined(graphics, sx0, sy0, sx1, sy1,
					lit ? NexoStyle.mix(0xFF0A0A0D, NexoStyle.ACCENT, 0.3F) : 0xFF16161C,
					lit ? NexoStyle.ACCENT : 0xFF2A2A34, SWITCH_HEIGHT / 2);
			int size = SWITCH_HEIGHT - 4;
			int kx = on ? sx1 - 2 - size : sx0 + 2;
			NexoShapes.fillRounded(graphics, kx, sy0 + 2, kx + size, sy1 - 2,
					lit ? NexoStyle.TEXT_PRIMARY : 0xFF5A5A68, size / 2);
			right = sx0 - 8;
		} else {
			int labelWidth = font.width(valueLabel);
			int px1 = x1 - PAD + 3;
			int px0 = Math.max(x0 + (x1 - x0) / 2, px1 - labelWidth - 10);
			int py0 = (y0 + y1) / 2 - 6;
			NexoShapes.outlined(graphics, px0, py0, px1, py0 + 12,
					active ? NexoStyle.mix(NexoStyle.FIELD, NexoStyle.ACCENT, 0.2F) : NexoStyle.FIELD,
					active ? NexoStyle.mix(NexoStyle.CONTROL, NexoStyle.ACCENT, 0.8F) : NexoStyle.CONTROL, 3);
			graphics.text(font, ellipsize(font, valueLabel.getString(), px1 - px0 - 10), px0 + 5, textY,
					active ? NexoStyle.TEXT_PRIMARY : NexoStyle.TEXT_DISABLED);
			right = px0 - 8;
		}
		graphics.text(font, ellipsize(font, name.getString(), right - (x0 + PAD)), x0 + PAD, textY,
				active ? NexoStyle.TEXT_SECONDARY : NexoStyle.TEXT_DISABLED);
	}

	/**
	 * A plain button whose label is a "Name: %s" translation with one argument
	 * — the shape this mod's hand-built option buttons use — split into its
	 * name and value, or null for any other label. The value is a
	 * {@link Boolean} when the argument is vanilla's ON/OFF text, so it can be
	 * drawn as a switch like a real boolean option.
	 */
	public static Parts parse(Component message) {
		if (!(message.getContents() instanceof TranslatableContents t) || t.getArgs().length != 1
				|| !(t.getArgs()[0] instanceof Component arg)) {
			return null;
		}
		String full = message.getString();
		String value = arg.getString();
		if (value.isEmpty() || !full.endsWith(value)) {
			return null;
		}
		String name = full.substring(0, full.length() - value.length()).stripTrailing();
		if (!name.endsWith(":")) {
			return null;
		}
		name = name.substring(0, name.length() - 1).stripTrailing();
		Object raw = arg;
		if (arg.getContents() instanceof TranslatableContents key) {
			if (key.getKey().equals("options.on")) {
				raw = Boolean.TRUE;
			} else if (key.getKey().equals("options.off")) {
				raw = Boolean.FALSE;
			}
		}
		return new Parts(Component.literal(name), raw, arg);
	}

	/** Whether {@code label} is vanilla's ON or OFF text. */
	public static boolean isOnOff(Component label) {
		return label.getContents() instanceof TranslatableContents key
				&& (key.getKey().equals("options.on") || key.getKey().equals("options.off"));
	}

	public record Parts(Component name, Object value, Component valueLabel) {}

	private static String ellipsize(Font font, String text, int maxPx) {
		if (maxPx <= 0) {
			return "";
		}
		if (font.width(text) <= maxPx) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, maxPx - font.width("…"))).stripTrailing() + "…";
	}
}
