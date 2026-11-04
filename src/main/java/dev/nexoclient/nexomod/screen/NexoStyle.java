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

	/*
	 * The flat look from design/STYLE.md, shared with the launcher's theme.rs:
	 * near-black panels split by 1px lines, one accent, and colour spent only
	 * on what is on or hovered. Not final: {@link #apply} swaps the whole set
	 * when the theme changes, so read them at draw time, never copy them into
	 * a static final.
	 */
	/** The page: menu backgrounds behind the stars. */
	public static int INK;
	/** The one accent — glows, outlines, the enabled state. */
	public static int ACCENT;
	/** Unlit controls: button and slider fields. */
	public static int FIELD;
	/**
	 * Cards and module rows. Opaque on purpose: {@link NexoShapes#outlined} lays
	 * the fill over the outline colour, which would show through a translucent one.
	 */
	public static int RAISED;
	/** The mod menu and popup panels — opaque for the same reason as {@link #RAISED}. */
	public static int PANEL;
	/** Lines between panels. */
	public static int LINE;
	/** The outline of an unlit control, a step brighter than {@link #LINE}. */
	public static int CONTROL;
	public static int MUTED;
	/** Close and destructive actions. */
	public static int DANGER;
	/** Success and "playing" states. */
	public static int MINT;
	/** The warm pole of the theme's gradient. */
	public static int MAGENTA;

	/** Translucent panel colours, for HUD backdrops that should let the world show through. */
	public static int PANEL_BG;
	public static int PANEL_BG_RAISED;
	public static final int BORDER_DIM = 0x40FFFFFF;
	public static final int BORDER_BRIGHT = 0xFFFFFFFF;

	public static int TEXT_PRIMARY;
	public static int TEXT_SECONDARY;
	public static int TEXT_DISABLED;
	public static int TEXT_ACTIVE_ACCENT;

	/** The theme's gradient, which {@link #cycle} walks. */
	private static int[] ramp;

	/**
	 * The launcher's ten built-in themes ({@code Client/.../theme.rs} PALETTES),
	 * same names and colours, so a player sees one look across both. Each comes
	 * with its background effect ({@link NexoBackdrop}), drawn when the menu
	 * background is set to follow the theme.
	 */
	public enum Theme {
		NEXO("Nexo", NexoBackdrop.Effect.ENCHANT, 0x0a0a0d, 0x0d0d11, 0x111116, 0x101015, 0x1d1d25, 0x23232c, 0xf2f2f5, 0x8b8b99, 0xff5470, 0xb14cff, 0x3cffb0, 0xff3cac, 0xff3cac, 0x9a6bff, 0x22b8ff, 0x3cffb0),
		MATRIX("Matrix", NexoBackdrop.Effect.RAIN, 0x050806, 0x070b08, 0x0a100b, 0x091009, 0x142018, 0x1a2a1f, 0xe6ffe9, 0x7da287, 0xff5470, 0x39ff7a, 0x3cffb0, 0x9dff3c, 0xb6ff3c, 0x39ff7a, 0x14d98a, 0x7dffd0),
		NETHER("Nether", NexoBackdrop.Effect.EMBERS, 0x0d0706, 0x120908, 0x170c0a, 0x150b09, 0x2a1612, 0x34201a, 0xf9eee8, 0xa88a7e, 0xff4d5e, 0xff6a2b, 0x7dff6a, 0xff3c3c, 0xff4d3c, 0xff7a2b, 0xffae2b, 0xffe06a),
		END("End", NexoBackdrop.Effect.PORTAL, 0x07070f, 0x0a0a16, 0x0f0f1e, 0x0e0e1b, 0x1c1c33, 0x25253f, 0xefeefb, 0x8f8fb0, 0xff5470, 0x2ee6c5, 0x5cffb0, 0xb64cff, 0xc77dff, 0x8f8bff, 0x4fc3ff, 0x2ee6c5),
		FROST("Frost", NexoBackdrop.Effect.SNOW, 0x070b10, 0x0a0f16, 0x0e141d, 0x0d131b, 0x1a2533, 0x223041, 0xeef6ff, 0x88a0b5, 0xff5d73, 0x5cc8ff, 0x5cffd6, 0xa5b8ff, 0xc6d8ff, 0x7db9ff, 0x4fd3ff, 0x8ff5f0),
		CHERRY("Cherry", NexoBackdrop.Effect.PETALS, 0x0f090c, 0x140c10, 0x1a1015, 0x180e13, 0x2d1c24, 0x38232d, 0xfbf0f4, 0xac8d9a, 0xff5a5a, 0xff7ab8, 0x7dffb8, 0xff4fa3, 0xff5fa8, 0xff8cc4, 0xffb3d6, 0xffd9b0),
		NEBULA_VOXELS("Nebula Voxels", NexoBackdrop.Effect.VOXELS, 0x0a0618, 0x0e0920, 0x130d29, 0x110b25, 0x241a42, 0x2e2250, 0xf5efff, 0x9d90c0, 0xff5c7a, 0xff5ce1, 0x5cffc8, 0xff6ad5, 0xff6ad5, 0xc774e8, 0xad8cff, 0x87b5ff),
		NEBULA_LIGHTNING("Nebula Lightning", NexoBackdrop.Effect.LIGHTNING, 0x060a1c, 0x090e24, 0x0d132d, 0x0b1129, 0x1a2448, 0x232f57, 0xeef3ff, 0x8e9bc4, 0xff5c7a, 0x7cb0ff, 0x5cffd0, 0xc78bff, 0xe27bff, 0xa78bff, 0x7fb2ff, 0xd6e8ff),
		NEBULA_GRID("Nebula Grid", NexoBackdrop.Effect.GRID, 0x0b0420, 0x100729, 0x150b32, 0x13092e, 0x261a4f, 0x312360, 0xf2f0ff, 0x9892c8, 0xff5c7a, 0x2be7ff, 0x4dffc0, 0xff4fd8, 0xff4fd8, 0xc56bff, 0x6f9dff, 0x2be7ff),
		LIQUID_CHROME("Liquid Chrome", NexoBackdrop.Effect.CHROME, 0x0b0c0e, 0x101114, 0x15171a, 0x131518, 0x272a2f, 0x32363c, 0xf4f6f8, 0x9aa1aa, 0xff5c70, 0xd8dee6, 0x6fe3b8, 0xb8c0ca, 0xf5f7fa, 0xaeb6bf, 0xe4e8ed, 0x8d96a0);

		public final String label;
		/** The background this theme comes with, like the launcher's pairing. */
		public final NexoBackdrop.Effect effect;
		final int ink, surface, raised, field, line, control, text, muted, danger, accent, mint, magenta;
		final int[] ramp;

		Theme(String label, NexoBackdrop.Effect effect, int ink, int surface, int raised, int field, int line, int control, int text, int muted,
				int danger, int accent, int mint, int magenta, int r0, int r1, int r2, int r3) {
			this.label = label;
			this.effect = effect;
			this.magenta = magenta | 0xFF000000;
			this.ink = ink | 0xFF000000;
			this.surface = surface | 0xFF000000;
			this.raised = raised | 0xFF000000;
			this.field = field | 0xFF000000;
			this.line = line | 0xFF000000;
			this.control = control | 0xFF000000;
			this.text = text | 0xFF000000;
			this.muted = muted | 0xFF000000;
			this.danger = danger | 0xFF000000;
			this.accent = accent | 0xFF000000;
			this.mint = mint | 0xFF000000;
			this.ramp = new int[] {r0 | 0xFF000000, r1 | 0xFF000000, r2 | 0xFF000000, r3 | 0xFF000000};
		}
	}

	static {
		apply(Theme.NEXO);
	}

	/** Bumped on every theme change, so things built from the colours know to rebuild. */
	public static int version;

	/** Switches every Nexo screen to {@code theme}; the next frame draws with it. */
	public static void apply(Theme theme) {
		apply(theme.ink, theme.surface, theme.raised, theme.field, theme.line, theme.control, theme.text, theme.muted,
				theme.danger, theme.accent, theme.mint, theme.magenta, theme.ramp);
	}

	/**
	 * A launcher custom theme: its seven chosen colours (background, text, accent,
	 * gradient 1-4), with the rest worked out exactly as the launcher's
	 * {@code Custom::palette} does, so it looks the same in both.
	 */
	public static void applyCustom(int[] colors) {
		int ink = colors[0] | 0xFF000000, text = colors[1] | 0xFF000000;
		Theme nexo = Theme.NEXO;
		int[] customRamp = {colors[3] | 0xFF000000, colors[4] | 0xFF000000, colors[5] | 0xFF000000, colors[6] | 0xFF000000};
		apply(ink, mix(ink, text, 0.013F), mix(ink, text, 0.03F), mix(ink, text, 0.026F), mix(ink, text, 0.082F),
				mix(ink, text, 0.108F), text, mix(ink, text, 0.556F), nexo.danger, colors[2] | 0xFF000000, nexo.mint,
				customRamp[0], customRamp);
	}

	private static void apply(int ink, int surface, int raised, int field, int line, int control, int text, int muted,
			int danger, int accent, int mint, int magenta, int[] gradient) {
		INK = ink;
		ACCENT = accent;
		FIELD = field;
		RAISED = raised;
		PANEL = surface;
		LINE = line;
		CONTROL = control;
		MUTED = muted;
		DANGER = danger;
		MINT = mint;
		MAGENTA = magenta;
		PANEL_BG = (surface & 0xFFFFFF) | 0xE6000000;
		PANEL_BG_RAISED = (raised & 0xFFFFFF) | 0xE6000000;
		TEXT_PRIMARY = text;
		TEXT_SECONDARY = mix(muted, text, 0.4F);
		TEXT_DISABLED = mix(ink, muted, 0.6F);
		TEXT_ACTIVE_ACCENT = mint;
		ramp = gradient;
		version++;
	}

	public static int mix(int a, int b, float t) {
		int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
		int ra = Math.round(aa + (ba - aa) * t);
		int rr = Math.round(ar + (br - ar) * t);
		int rg = Math.round(ag + (bg - ag) * t);
		int rb = Math.round(ab + (bb - ab) * t);
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

	/** Stop {@code i} (0-3) of the theme's gradient. */
	public static int ramp(int i) {
		return ramp[i];
	}

	/** A slow color cycle through the theme's gradient, for animated borders/accents — same idea as the launcher's animated brand color. */
	public static int cycle(long nowMillis, long periodMillis) {
		int[] stops = {ramp[0], ramp[1], ramp[2], ramp[3], ramp[0]};
		float phase = (nowMillis % periodMillis) / (float) periodMillis * (stops.length - 1);
		int index = (int) phase;
		float t = phase - index;
		return mix(stops[index] | 0xFF000000, stops[index + 1] | 0xFF000000, t);
	}
}
