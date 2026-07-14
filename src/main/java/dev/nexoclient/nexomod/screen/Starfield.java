package dev.nexoclient.nexomod.screen;

import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A field of slowly drifting, twinkling stars over a black backdrop —
 * replaces the vanilla panorama on the menus this mod re-skins.
 * Star positions are normalized (0-1) so the same field scales to any
 * window size; a fixed seed keeps the layout stable across frames instead
 * of looking like static noise. Also parallax-shifts a little with the
 * cursor, using each star's own drift speed as a depth cue (faster/nearer
 * stars shift more than slow/distant ones). Reads the cursor directly from
 * {@code MouseHandler} rather than through the extraction callback's own
 * mouseX/mouseY, since one of the two call sites ({@code extractPanorama})
 * doesn't receive those as parameters at all.
 */
public final class Starfield {
	private static final int STAR_COUNT = 160;
	private static final long SEED = 0x4E45584F; // "NEXO"
	private static final float PARALLAX_PIXELS = 10.0F;

	private static Starfield instance;

	private final float[] x = new float[STAR_COUNT];
	private final float[] y = new float[STAR_COUNT];
	private final float[] size = new float[STAR_COUNT];
	private final float[] phase = new float[STAR_COUNT];
	private final float[] speed = new float[STAR_COUNT];

	private Starfield() {
		Random random = new Random(SEED);
		for (int i = 0; i < STAR_COUNT; i++) {
			x[i] = random.nextFloat();
			y[i] = random.nextFloat();
			size[i] = 1.0F + random.nextFloat() * 1.5F;
			phase[i] = random.nextFloat() * (float) (Math.PI * 2);
			speed[i] = 0.4F + random.nextFloat() * 0.8F;
		}
	}

	public static Starfield get() {
		if (instance == null) {
			instance = new Starfield();
		}
		return instance;
	}

	public void draw(GuiGraphicsExtractor graphics, int width, int height) {
		graphics.fill(0, 0, width, height, 0xFF04040A);

		Minecraft mc = Minecraft.getInstance();
		double mouseX = mc.mouseHandler.getScaledXPos(mc.getWindow());
		double mouseY = mc.mouseHandler.getScaledYPos(mc.getWindow());
		float parallaxX = (float) (mouseX / width - 0.5);
		float parallaxY = (float) (mouseY / height - 0.5);

		long now = System.currentTimeMillis();
		for (int i = 0; i < STAR_COUNT; i++) {
			// A slow downward drift, wrapping back to the top — "move a little", not scroll.
			float drift = (now * 0.000025F * speed[i]) % 1.0F;
			float sy = (y[i] + drift) % 1.0F;

			float twinkle = 0.4F + 0.6F * (float) (0.5 + 0.5 * Math.sin(now * 0.0015 + phase[i]));
			int alpha = Math.round(twinkle * 255);
			int color = (alpha << 24) | 0xFFFFFF;

			// speed[] doubles as a depth cue here: faster-drifting stars feel "nearer" and shift more.
			int px = Math.round(x[i] * width + parallaxX * PARALLAX_PIXELS * speed[i]);
			int py = Math.round(sy * height + parallaxY * PARALLAX_PIXELS * speed[i]);
			int s = Math.round(size[i]);
			graphics.fill(px, py, px + s, py + s, color);
		}
	}
}
