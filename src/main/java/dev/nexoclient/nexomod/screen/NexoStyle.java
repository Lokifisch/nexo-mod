package dev.nexoclient.nexomod.screen;

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

	/** Near-black panel background, matches the logo's own backdrop color. */
	public static final int PANEL_BG = 0xE60D0D14;
	public static final int PANEL_BG_RAISED = 0xE617172A;
	public static final int BORDER_DIM = 0x40FFFFFF;
	public static final int BORDER_BRIGHT = 0xFFFFFFFF;

	public static final int TEXT_PRIMARY = 0xFFF2F0FF;
	public static final int TEXT_SECONDARY = 0xFFB0AEC2;
	public static final int TEXT_DISABLED = 0xFF5A5868;
	public static final int TEXT_ACTIVE_ACCENT = 0xFF3CFFB0;

	static int mix(int a, int b, float t) {
		int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
		int ra = (int) (aa + (ba - aa) * t);
		int rr = (int) (ar + (br - ar) * t);
		int rg = (int) (ag + (bg - ag) * t);
		int rb = (int) (ab + (bb - ab) * t);
		return (ra << 24) | (rr << 16) | (rg << 8) | rb;
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
