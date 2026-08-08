package dev.nexoclient.nexomod.screen;

import java.util.Random;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

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
 *
 * <p>Rendered by rasterizing all stars into one {@link DynamicTexture} and
 * blitting it as a single full-screen quad on the same
 * {@code GUI_OPAQUE_TEXTURED_BACKGROUND} pipeline vanilla uses for its own
 * menu background texture — NOT as one {@code fill()} per star. It used to
 * be per-star fills, but 160 tiny translucent color-fill elements proved
 * fragile against other mods' render-pipeline tricks: Essential's title
 * screen button tinting (which re-renders GUI state to an offscreen
 * texture mid-frame whenever one of its buttons is hovered) made every
 * star vanish for exactly the hovered frames, while textured quads —
 * vanilla's backgrounds, Essential's own UI — survived. One opaque
 * textured quad puts this background on that proven-robust path, and as a
 * bonus is one GUI element instead of 161.
 */
public final class Starfield {
	private static final int STAR_COUNT = 160;
	private static final long SEED = 0x4E45584F; // "NEXO"
	private static final float PARALLAX_PIXELS = 10.0F;
	private static final int BACKGROUND_ARGB = 0xFF04040A;
	private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("nexomod", "starfield_background");

	private static Starfield instance;

	private final float[] x = new float[STAR_COUNT];
	private final float[] y = new float[STAR_COUNT];
	private final float[] size = new float[STAR_COUNT];
	private final float[] phase = new float[STAR_COUNT];
	private final float[] speed = new float[STAR_COUNT];

	/** Last frame's rasterized rect per star (x, y, then size in the third array), so only dirty pixels get rewritten. */
	private final int[] prevX = new int[STAR_COUNT];
	private final int[] prevY = new int[STAR_COUNT];
	private final int[] prevSize = new int[STAR_COUNT];

	private DynamicTexture texture;
	private int textureWidth;
	private int textureHeight;

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
		if (width <= 0 || height <= 0) {
			return;
		}
		ensureTexture(width, height);
		NativeImage image = texture.getPixels();

		Minecraft mc = Minecraft.getInstance();
		double mouseX = mc.mouseHandler.getScaledXPos(mc.getWindow());
		double mouseY = mc.mouseHandler.getScaledYPos(mc.getWindow());
		float parallaxX = (float) (mouseX / width - 0.5);
		float parallaxY = (float) (mouseY / height - 0.5);

		long now = System.currentTimeMillis();
		// Erase last frame's stars first, then plot this frame's — far cheaper
		// than re-clearing the whole image every frame.
		for (int i = 0; i < STAR_COUNT; i++) {
			fillClipped(image, prevX[i], prevY[i], prevSize[i], BACKGROUND_ARGB, width, height);
		}
		for (int i = 0; i < STAR_COUNT; i++) {
			// A slow downward drift, wrapping back to the top — "move a little", not scroll.
			float drift = (now * 0.000025F * speed[i]) % 1.0F;
			float sy = (y[i] + drift) % 1.0F;

			float twinkle = 0.4F + 0.6F * (float) (0.5 + 0.5 * Math.sin(now * 0.0015 + phase[i]));
			// The old translucent-fill look, precomposited: white at `twinkle`
			// opacity over the backdrop, stored as an opaque pixel.
			int gray = Math.round(twinkle * 255);
			int color = 0xFF000000 | (blend(gray, 0x04) << 16) | (blend(gray, 0x04) << 8) | blend(gray, 0x0A);

			// speed[] doubles as a depth cue here: faster-drifting stars feel "nearer" and shift more.
			int px = Math.round(x[i] * width + parallaxX * PARALLAX_PIXELS * speed[i]);
			int py = Math.round(sy * height + parallaxY * PARALLAX_PIXELS * speed[i]);
			int s = Math.round(size[i]);
			fillClipped(image, px, py, s, color, width, height);
			prevX[i] = px;
			prevY[i] = py;
			prevSize[i] = s;
		}

		texture.upload();
		graphics.blit(RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND, TEXTURE_ID, 0, 0, 0.0F, 0.0F, width, height, width, height, width, height);
	}

	/** Composites a white value {@code fg} (0-255) at fg/255 opacity over one background channel. */
	private static int blend(int fg, int bgChannel) {
		return fg + (255 - fg) * bgChannel / 255;
	}

	private static void fillClipped(NativeImage image, int px, int py, int s, int argb, int width, int height) {
		int x0 = Math.max(0, px);
		int y0 = Math.max(0, py);
		int x1 = Math.min(width, px + s);
		int y1 = Math.min(height, py + s);
		if (x0 >= x1 || y0 >= y1) {
			return;
		}
		image.fillRect(x0, y0, x1 - x0, y1 - y0, argb);
	}

	private void ensureTexture(int width, int height) {
		if (texture != null && textureWidth == width && textureHeight == height) {
			return;
		}
		DynamicTexture old = texture;
		texture = new DynamicTexture("nexomod starfield background", width, height, false);
		texture.getPixels().fillRect(0, 0, width, height, BACKGROUND_ARGB);
		textureWidth = width;
		textureHeight = height;
		for (int i = 0; i < STAR_COUNT; i++) {
			prevSize[i] = 0;
		}
		// Replaces any previous registration under the same id; the old
		// texture is closed only afterwards, once nothing references it.
		Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
		if (old != null) {
			old.close();
		}
	}
}
