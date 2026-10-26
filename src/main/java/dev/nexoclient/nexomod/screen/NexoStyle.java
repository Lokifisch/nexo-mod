package dev.nexoclient.nexomod.screen;

import net.minecraft.util.Mth;

/**
 * Nexo's neon brand palette, shared across every custom-drawn screen/widget
 * in this package. Same hex values used throughout {@code Client/} (the
 * launcher), for a consistent look across the whole product — not from
 * Essential-Mod.
 */
public final class NexoStyle {
	private NexoStyle() {}

	public static final int VIOLET = 0xFF5612E2;
	public static final int MAGENTA = 0xFFE2128A;
	public static final int MINT = 0xFF12E28E;
	public static final int BLUE = 0xFF128DE2;

	/*
	 * The flat look from design/STYLE.md, shared with the launcher's theme.rs:
	 * near-black panels split by 1px lines, one accent, and colour spent only
	 * on what is on or hovered.
	 */
	/** The one accent — glows, outlines, the enabled state. Between the logo's magenta and violet. */
	public static final int ACCENT = 0xFFB14CFF;
	/** Unlit controls: button and slider fields. */
	public static final int FIELD = 0xFF101015;
	/**
	 * Cards and module rows. Opaque on purpose: {@link NexoShapes#outlined} lays
	 * the fill over the outline colour, which would show through a translucent one.
	 */
	public static final int RAISED = 0xFF111116;
	/** The mod menu and popup panels — opaque for the same reason as {@link #RAISED}. */
	public static final int PANEL = 0xFF0C0C10;
	/** Lines between panels. */
	public static final int LINE = 0xFF1D1D25;
	/** The outline of an unlit control, a step brighter than {@link #LINE}. */
	public static final int CONTROL = 0xFF23232C;
	public static final int MUTED = 0xFF8B8B99;
	/** Close and destructive actions. */
	public static final int DANGER = 0xFFFF5470;

	/** Translucent near-black, for HUD backdrops that should let the world show through. */
	public static final int PANEL_BG = 0xE60D0D14;
	public static final int PANEL_BG_RAISED = 0xE617172A;
	public static final int BORDER_DIM = 0x40FFFFFF;
	public static final int BORDER_BRIGHT = 0xFFFFFFFF;

	public static final int TEXT_PRIMARY = 0xFFF2F0FF;
	public static final int TEXT_SECONDARY = 0xFFB0AEC2;
	public static final int TEXT_DISABLED = 0xFF5A5868;
	public static final int TEXT_ACTIVE_ACCENT = 0xFF3CFFB0;

	public static int mix(int a, int b, float t) {
		int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
		int ra = (int) (aa + (ba - aa) * t);
		int rr = (int) (ar + (br - ar) * t);
		int rg = (int) (ag + (bg - ag) * t);
		int rb = (int) (ab + (bb - ab) * t);
		return (ra << 24) | (rr << 16) | (rg << 8) | rb;
	}

	/**
	 * The same color at {@code factor} of its current opacity — for fading a
	 * palette constant in or out without writing the ARGB shuffle at every
	 * call site. Scales the existing alpha rather than replacing it, so a
	 * constant that is already translucent stays proportionally so.
	 */
	public static int fade(int color, float factor) {
		int alpha = (int) (((color >>> 24) & 0xFF) * Mth.clamp(factor, 0F, 1F));
		return (alpha << 24) | (color & 0xFFFFFF);
	}

	/**
	 * A full hue sweep, for accents meant to read as a rainbow rather than as the
	 * brand ramp {@link #cycle} walks. Saturation and value are pinned high so it
	 * stays legible against dark rock at every hue.
	 */
	public static int rainbow(long nowMillis, long periodMillis) {
		float hue = (nowMillis % periodMillis) / (float) periodMillis;
		return Mth.hsvToArgb(hue, 0.85F, 1.0F, 0xFF);
	}

	/** The logo's ramp: pink, violet, blue, mint — fixed, like the logo itself. */
	private static final int[] LOGO_RAMP = {0xFFFF3CAC, 0xFF7B3CFF, 0xFF22B8FF, 0xFF3CFFB0};

	/** The logo ramp at {@code t} (0..1) along it. */
	public static int logoRamp(float t) {
		float phase = Mth.clamp(t, 0F, 1F) * (LOGO_RAMP.length - 1);
		int index = Math.min(LOGO_RAMP.length - 2, (int) phase);
		return mix(LOGO_RAMP[index], LOGO_RAMP[index + 1], phase - index);
	}

	/** A slow color cycle through the brand palette, for animated borders/accents — same idea as the launcher's animated brand color. */
	public static int cycle(long nowMillis, long periodMillis) {
		int[] stops = {VIOLET, MAGENTA, MINT, BLUE, VIOLET};
		float phase = (nowMillis % periodMillis) / (float) periodMillis * (stops.length - 1);
		int index = (int) phase;
		float t = phase - index;
		return mix(stops[index] | 0xFF000000, stops[index + 1] | 0xFF000000, t);
	}
}
