package dev.nexoclient.nexomod.screen;

import org.joml.Matrix3x2fStack;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import dev.nexoclient.nexomod.NexoMod;

/**
 * The launcher's theme backgrounds ({@code Client/.../backdrop.rs} and the
 * shaders in {@code sky.rs}), so a menu looks like the launcher's Home page.
 * Same maths and constants. Every particle is a pure function of its index and
 * the clock, so nothing is simulated or stored.
 *
 * <p>The nebula, liquid chrome and Nebula Grid's floor are a GPU shader
 * ({@code assets/nexomod/shaders/core/nexo_sky.fsh}) on one full-screen quad:
 * per pixel at full resolution and close to free, where doing that work on the
 * CPU as fills cost tens of thousands of GUI elements a frame. The shader has
 * no uniforms of its own; the clock and theme ride in the quad's vertex colour,
 * the aspect in its texture coordinates (see the shader's header).
 *
 * <p>Particles are drawn in real framebuffer pixels (the pose is scaled by
 * 1/guiScale), since the launcher's sizes are pixels. On a plain background they
 * are <em>opaque</em>, precomposited over the page colour: translucent fills
 * have vanished while an Essential title-screen button is hovered, see
 * {@link Starfield}. Over the shader sky they have to be translucent, as the
 * CPU no longer knows what colour is under them.
 */
public final class NexoBackdrop {
	private NexoBackdrop() {}

	/** The launcher's effects; {@link #label} is how its {@code appearance.txt} names them. */
	public enum Effect {
		ENCHANT("Enchantment"), RAIN("Matrix rain"), EMBERS("Embers"), PORTAL("Portal"), SNOW("Snow"), PETALS("Petals"),
		VOXELS("Nebula voxels"), LIGHTNING("Nebula lightning"), GRID("Nebula grid"), CHROME("Liquid chrome"), OFF("Off");

		public final String label;

		Effect(String label) {
			this.label = label;
		}

		/** The effect the launcher calls {@code label}, or null if there is none. */
		public static Effect labelled(String label) {
			for (Effect effect : values()) {
				if (effect.label.equals(label.strip())) {
					return effect;
				}
			}
			return null;
		}
	}

	private static final Identifier SKY_SHADER = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "core/nexo_sky");
	/**
	 * The shader's colours: a 9x1 texture of ink, text, accent, magenta, the four
	 * gradient stops, and the particle amount in the red of the last texel. Uploaded
	 * only when one of them changes; a texture re-uploaded every frame is what lost
	 * its pixels under Essential's hover pass, a static one never has.
	 */
	private static final Identifier PALETTE_TEXTURE = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "sky_palette");
	private static DynamicTexture palette;
	private static int paletteVersion = -1;
	private static float paletteAmount = -1F;
	private static RenderPipeline skyPipeline;
	private static boolean skyPipelineTried;
	/** The same shader blended over the game, for {@link #drawSeeThrough}. */
	private static RenderPipeline seeThroughPipeline;
	private static boolean seeThroughPipelineTried;
	/** Set once the shader failed to compile (a driver, or a resource pack replacing it). */
	private static boolean skyBroken;

	/** 5x5 pixel runes in the spirit of the enchanting table's, one row per byte. */
	private static final int[][] RUNES = {
			{0b10001, 0b10001, 0b11111, 0b00100, 0b00100},
			{0b11111, 0b00001, 0b00010, 0b00100, 0b01000},
			{0b00100, 0b01010, 0b10001, 0b00000, 0b11111},
			{0b10000, 0b10000, 0b11110, 0b10010, 0b11110},
			{0b01110, 0b10001, 0b00100, 0b00100, 0b00100},
			{0b10101, 0b10101, 0b00000, 0b10101, 0b10101},
			{0b11111, 0b10000, 0b11100, 0b10000, 0b10000},
			{0b00001, 0b00011, 0b00101, 0b01001, 0b10001},
			{0b10010, 0b10010, 0b11111, 0b00010, 0b00010},
			{0b01010, 0b00000, 0b10001, 0b01010, 0b00100},
	};

	/** The effects' clock, in seconds; advanced by real time times the speed, like the launcher's {@code Clock}. */
	private static double seconds;
	private static long lastFrame;

	// Per draw.
	private static GuiGraphicsExtractor g;
	private static boolean hasSky;
	private static int ink;
	/** The launcher's particle amount, 0.25-2. */
	private static float amount = 1F;
	/** Drawing over the game (pause menu): a translucent tint, no opaque sky. */
	private static boolean seeThrough;
	/** The see-through backdrop fades in when a menu opens over the game; buttons don't wait for it. */
	private static final double FADE_IN_SECONDS = 1.0;
	private static long seeThroughStart;
	private static long seeThroughLast;
	private static boolean seeThroughStarted;
	/** 0-1, how far the current see-through backdrop has faded in. */
	private static float fade = 1F;

	/**
	 * Starts the fade when no see-through frame was drawn just before, so going from
	 * the pause menu to the server list carries on instead of starting over.
	 */
	private static void beginSeeThrough() {
		long now = System.nanoTime();
		if (!seeThroughStarted || now - seeThroughLast > 250_000_000L) {
			seeThroughStarted = true;
			seeThroughStart = now;
		}
		seeThroughLast = now;
		float t = (float) Mth.clamp((now - seeThroughStart) / 1e9 / FADE_IN_SECONDS, 0.0, 1.0);
		fade = 1F - (1F - t) * (1F - t) * (1F - t);
		seeThrough = true;
	}

	/** As {@link #draw}, but the world stays visible behind it: a tint, then the effect with its empty sky transparent. */
	public static void drawSeeThrough(GuiGraphicsExtractor graphics, int guiWidth, int guiHeight, Effect effect, int amountPercent, int speedPercent) {
		beginSeeThrough();
		try {
			tint(graphics, guiWidth, guiHeight);
			draw(graphics, guiWidth, guiHeight, effect, amountPercent, speedPercent);
		} finally {
			seeThrough = false;
		}
	}

	/** The starfield over the running game; nothing but the tint if the shader can't be used. */
	public static void drawStarfieldSeeThrough(GuiGraphicsExtractor graphics, int guiWidth, int guiHeight) {
		beginSeeThrough();
		try {
			tint(graphics, guiWidth, guiHeight);
			drawStarfield(graphics, guiWidth, guiHeight);
		} finally {
			seeThrough = false;
		}
	}

	/** Darkens the game a little under a see-through backdrop, in the theme's ink. */
	private static void tint(GuiGraphicsExtractor graphics, int guiWidth, int guiHeight) {
		graphics.fill(0, 0, guiWidth, guiHeight, (Math.round(0x66 * fade) << 24) | (NexoStyle.INK & 0xFFFFFF));
	}

	/** At the launcher's default amount and speed. */
	public static void draw(GuiGraphicsExtractor graphics, int guiWidth, int guiHeight, Effect effect) {
		draw(graphics, guiWidth, guiHeight, effect, 100, 100);
	}

	/** {@code amountPercent} and {@code speedPercent} as the launcher's particle sliders, 25-200. */
	public static void draw(GuiGraphicsExtractor graphics, int guiWidth, int guiHeight, Effect effect, int amountPercent, int speedPercent) {
		int s = Math.max(1, (int) Minecraft.getInstance().getWindow().getGuiScale());
		int w = guiWidth * s, h = guiHeight * s;
		if (w <= 0 || h <= 0) {
			return;
		}
		int centiseconds = advanceClock(speedPercent);
		// A fixed head start, so nothing begins with an empty sky.
		float t = centiseconds / 100F + 60F;
		g = graphics;
		ink = NexoStyle.INK;
		amount = Mth.clamp(amountPercent, 25, 200) / 100F;
		int mode = switch (effect) {
			case LIGHTNING -> 0;
			case CHROME -> 1;
			case GRID -> 2;
			case VOXELS -> 3;
			default -> -1;
		};
		hasSky = mode >= 0 && drawSky(graphics, guiWidth, guiHeight, mode, centiseconds & 0xFFFFFF, -(guiWidth - guiHeight) / 2F);
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		try {
			pose.scale(1F / s, 1F / s);
			if (!hasSky && !seeThrough) {
				graphics.fill(0, 0, w, h, ink);
			}
			switch (effect) {
				case ENCHANT -> enchant(w, h, t);
				case RAIN -> rain(w, h, t);
				case EMBERS -> embers(w, h, t);
				case PORTAL -> portal(w, h, t);
				case SNOW -> snow(w, h, t);
				case PETALS -> petals(w, h, t);
				case LIGHTNING -> lightning(w, h, t);
				case VOXELS, GRID, CHROME, OFF -> {}
			}
		} finally {
			pose.popMatrix();
			g = null;
		}
	}

	// ------------------------------------------------------------------
	// Shared helpers (backdrop.rs)
	// ------------------------------------------------------------------

	/** A repeatable random number in 0..1 for a particle and a purpose. */
	static float random(int index, int salt) {
		int x = index * 0x9E3779B1 ^ salt * 0x85EBCA77;
		x ^= x >>> 15;
		x *= 0xC2B2AE3D;
		x ^= x >>> 13;
		return (x & 0xFFFF) / 65536F;
	}

	private static float fract(float v) {
		return v - (float) Math.floor(v);
	}

	/** One particle's place in its own loop, 0..1, each starting somewhere else. */
	static float phase(int index, float secondsPerLoop, float t) {
		float spread = 0.75F + random(index, 7) * 0.5F;
		return fract(t * spread / secondsPerLoop + random(index, 1));
	}

	/** Fades in over the start of a loop and out over the end, so nothing pops. */
	static float fade(float phase) {
		return Mth.clamp(Math.min(phase * 6F, (1F - phase) * 6F), 0F, 1F);
	}

	/**
	 * Moves the clock on and returns it in centiseconds, wrapped daily: a float
	 * clock never gets coarse enough to make fast rain step, and it fits the
	 * shader's 24 bits. Gaps (no menu open) count as at most 0.1 s, so particles
	 * carry on where they stopped, as in the launcher.
	 */
	private static int advanceClock(int speedPercent) {
		long now = System.nanoTime();
		if (lastFrame != 0) {
			double elapsed = Math.min((now - lastFrame) / 1e9, 0.1);
			seconds = (seconds + elapsed * Mth.clamp(speedPercent, 25, 200) / 100.0) % 86_400.0;
		}
		lastFrame = now;
		return (int) (seconds * 100.0);
	}

	/** How many particles this much room gets, so a small window isn't a blizzard. */
	private static int count(float area, float perParticle, int most) {
		return (int) (Mth.clamp((int) (area / perParticle), 12, most) * amount);
	}

	/** {@code color} at {@code opacity} over {@code base}, as one opaque colour. */
	private static int over(int base, int color, float opacity) {
		return NexoStyle.mix(base | 0xFF000000, color | 0xFF000000, Mth.clamp(opacity, 0F, 1F));
	}

	private static void rect(int x, int y, int w, int h, int color, float opacity) {
		if (seeThrough) {
			opacity *= fade;
		}
		if (opacity <= 0.004F || w <= 0 || h <= 0) {
			return;
		}
		g.fill(x, y, x + w, y + h, hasSky || seeThrough
				? (Math.round(Mth.clamp(opacity, 0F, 1F) * 255F) << 24) | (color & 0xFFFFFF)
				: over(ink, color, opacity));
	}

	private static void dot(float x, float y, int size, int color, float opacity) {
		rect(Math.round(x), Math.round(y), size, size, color, opacity);
	}

	private static void rune(int which, float x, float y, int pixel, int color, float opacity) {
		int[] rows = RUNES[Math.floorMod(which, RUNES.length)];
		int rx = Math.round(x), ry = Math.round(y);
		// A run of lit pixels in a row is one fill: rain draws thousands of runes a frame.
		for (int row = 0; row < 5; row++) {
			for (int column = 0; column < 5; ) {
				if ((rows[row] & (0b10000 >> column)) == 0) {
					column++;
					continue;
				}
				int end = column;
				while (end < 5 && (rows[row] & (0b10000 >> end)) != 0) {
					end++;
				}
				rect(rx + column * pixel, ry + row * pixel, (end - column) * pixel, pixel, color, opacity);
				column = end;
			}
		}
	}

	/** A stroke as squares stepped along it; no line primitive in the GUI renderer. */
	private static void line(float x0, float y0, float x1, float y1, float width, int color, float opacity) {
		int size = Math.max(1, Math.round(width));
		// One square per `size` along the longer axis: they meet without overlapping,
		// which matters over the sky, where they are translucent and overlaps add up.
		int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) / size));
		for (int i = 0; i <= steps; i++) {
			float f = i / (float) steps;
			dot(x0 + (x1 - x0) * f - size / 2F, y0 + (y1 - y0) * f - size / 2F, size, color, opacity);
		}
	}

	private static void polyline(float[][] points, float width, int color, float opacity) {
		for (int i = 0; i + 1 < points.length; i++) {
			line(points[i][0], points[i][1], points[i + 1][0], points[i + 1][1], width, color, opacity);
		}
	}

	private static int ramp(int i) {
		return NexoStyle.ramp(i);
	}

	// ------------------------------------------------------------------
	// Particle effects (backdrop.rs)
	// ------------------------------------------------------------------

	/** Runes drifting in from all around to the middle, like at an enchanting table. */
	private static void enchant(int w, int h, float t) {
		float mx = w / 2F, my = h / 2F;
		for (int i = 0, n = count(w * (float) h, 16_000F, 48); i < n; i++) {
			float at = phase(i, 7F, t);
			float fromX = random(i, 2) * w, fromY = random(i, 3) * h;
			float pull = at * at;
			float swing = Mth.sin(at * Mth.TWO_PI) * 18F * (random(i, 4) - 0.5F);
			float x = fromX + (mx - fromX) * pull + swing;
			float y = fromY + (my - fromY) * pull - at * 14F;
			int color = i % 3 == 0 ? ramp(0) : NexoStyle.ACCENT;
			int which = i + (int) (t / 2F + random(i, 5));
			rune(which, x, y, 2, color, fade(at) * 0.45F);
		}
	}

	/** Falling columns of glyphs, a bright head and a fading tail. */
	private static void rain(int w, int h, float t) {
		final float cellSize = 14F;
		final int tail = 12;
		int columns = (int) (w / cellSize) + 1;
		float rows = h / cellSize;
		for (int column = 0; column < columns; column++) {
			if (random(column, 11) > 0.42F * amount) {
				continue;
			}
			float speed = 3F + random(column, 12) * 5F;
			float travel = rows + tail;
			float head = (float) Math.floor((t * speed + random(column, 13) * travel) % travel);
			for (int back = 0; back < tail; back++) {
				float row = head - back;
				if (row < 0 || row >= rows) {
					continue;
				}
				int which = column * 31 + (int) row * 17 + (int) (t * (0.4F + random(column, 14)));
				int color = back == 0 ? NexoStyle.TEXT_PRIMARY : NexoStyle.ACCENT;
				float opacity = back == 0 ? 0.42F : 0.30F * (1F - back / (float) tail);
				rune(which, column * cellSize + 2F, row * cellSize + 2F, 2, color, opacity);
			}
		}
	}

	/** Sparks rising and swaying, cooling as they go. */
	private static void embers(int w, int h, float t) {
		for (int i = 0, n = count(w * (float) h, 7_000F, 110); i < n; i++) {
			float at = phase(i, 9F, t);
			float sway = Mth.sin(t * (0.5F + random(i, 2)) + random(i, 3) * Mth.TWO_PI) * 14F;
			float x = random(i, 4) * w + sway;
			float y = h * (1.05F - at * 1.1F);
			dot(x, y, i % 4 == 0 ? 4 : 3, ramp(i % 4), fade(at) * (1F - at * 0.5F) * 0.7F);
		}
	}

	/** Specks spiralling into the middle, like a nether or end portal's. */
	private static void portal(int w, int h, float t) {
		float mx = w / 2F, my = h / 2F, reach = Math.max(w, h) * 0.62F;
		for (int i = 0, n = count(w * (float) h, 6_000F, 130); i < n; i++) {
			float at = phase(i, 8F, t);
			float angle = random(i, 2) * Mth.TWO_PI + at * 2.4F;
			float radius = reach * (0.15F + random(i, 3) * 0.85F) * (1F - at);
			float x = mx + Mth.cos(angle) * radius;
			float y = my + Mth.sin(angle) * radius * 0.75F;
			int color = i % 2 == 0 ? NexoStyle.MAGENTA : NexoStyle.ACCENT;
			dot(x, y, i % 4 == 0 ? 4 : 3, color, fade(at) * 0.65F);
		}
	}

	/** Falling flakes; the small ones fall slower, which reads as further away. */
	private static void snow(int w, int h, float t) {
		for (int i = 0, n = count(w * (float) h, 6_000F, 130); i < n; i++) {
			boolean near = i % 4 == 0;
			float at = phase(i, near ? 8F : 15F, t);
			float sway = Mth.sin(t * 0.7F + random(i, 2) * Mth.TWO_PI) * 16F;
			float x = random(i, 3) * w + sway;
			float y = at * h * 1.1F - h * 0.05F;
			dot(x, y, near ? 4 : 3, NexoStyle.TEXT_PRIMARY, fade(at) * (near ? 0.55F : 0.32F));
		}
	}

	/** Petals blowing down and across, tumbling as they fall. */
	private static void petals(int w, int h, float t) {
		for (int i = 0, n = count(w * (float) h, 12_000F, 70); i < n; i++) {
			float at = phase(i, 12F, t);
			float drift = at * w * 0.3F;
			float x = (random(i, 2) * w * 1.3F - w * 0.3F) + drift + Mth.sin(t + random(i, 3) * Mth.TWO_PI) * 10F;
			float y = at * h * 1.1F - h * 0.05F;
			// Narrowing and widening is what makes a flat rectangle look like it turns.
			float turn = Math.abs(Mth.cos(t * (1F + random(i, 4)) + random(i, 5) * Mth.TWO_PI));
			int color = i % 3 == 0 ? ramp(2) : NexoStyle.ACCENT;
			rect(Math.round(x), Math.round(y), Math.round(2F + 6F * turn), 4, color, fade(at) * 0.6F);
		}
	}

	/** A bolt's points from the top downward, the same for the same {@code seed}. */
	static float[][] boltPoints(int seed, int w, int h) {
		final int steps = 14;
		float x = (0.1F + random(seed, 20) * 0.8F) * w;
		float reach = h * (0.5F + random(seed, 21) * 0.4F);
		float[][] points = new float[steps + 1][];
		for (int step = 0; step <= steps; step++) {
			x += (random(seed, 100 + step) - 0.5F) * w * 0.07F;
			points[step] = new float[] {x, reach * step / steps - 4F};
		}
		return points;
	}

	/** How bright a strike is, {@code age} seconds in: a flash, a dip, a second flash, gone. */
	static float strikeBrightness(float age) {
		final float life = 0.5F;
		if (age < 0F || age >= life) {
			return 0F;
		}
		float flicker = age < 0.07F ? 1F : age < 0.14F ? 0.3F : age < 0.24F ? 0.85F : 0.5F;
		return flicker * (1F - age / life);
	}

	/** Now and then a bolt forks through a nebula. */
	private static void lightning(int w, int h, float t) {
		for (int lane = 0, lanes = Math.max(1, (int) Math.ceil(3F * amount)); lane < lanes; lane++) {
			float period = 3.5F + random(lane, 30) * 3F;
			float clock = t + random(lane, 31) * period;
			float bright = strikeBrightness(clock % period);
			if (bright == 0F) {
				continue;
			}
			int seed = lane * 97 + (int) (clock / period) * 13;
			float[][] main = boltPoints(seed, w, h);
			int forkAt = 4 + (int) (random(seed, 22) * 6F);
			float lean = random(seed, 23) > 0.5F ? 1F : -1F;
			int forkLength = Math.min(6, main.length - forkAt);
			float[][] fork = new float[forkLength][];
			for (int i = 0; i < forkLength; i++) {
				fork[i] = new float[] {main[forkAt + i][0] + lean * i * w * 0.018F, main[forkAt + i][1]};
			}
			for (int pass = 0; pass < 2; pass++) {
				float[][] points = pass == 0 ? main : fork;
				float weight = pass == 0 ? 1F : 0.55F;
				polyline(points, 7F * weight, NexoStyle.ACCENT, 0.16F * bright * weight);
				polyline(points, 3F * weight, NexoStyle.ACCENT, 0.4F * bright * weight);
				polyline(points, 1.2F * weight, NexoStyle.TEXT_PRIMARY, 0.9F * bright * weight);
			}
		}
	}

	// ------------------------------------------------------------------
	// Sky: nebula, liquid chrome and the grid floor, on the GPU
	// ------------------------------------------------------------------

	/**
	 * Draws the shader quad in GUI coordinates; false if the shader can't be used.
	 * The quad's colour carries {@code info} in RGB and the mode in alpha; u runs
	 * from {@code u0} to {@code u0 + guiWidth / guiHeight}, v from 0 to 1. See the
	 * shader's header for what each mode reads out of them.
	 */
	private static boolean drawSky(GuiGraphicsExtractor graphics, int guiWidth, int guiHeight, int mode, int info, float u0) {
		RenderPipeline pipeline = seeThrough ? seeThroughPipeline() : skyPipeline();
		if (pipeline == null || skyBroken) {
			return false;
		}
		// build() only describes the pipeline; compiling happens at draw time, and a
		// pipeline that can't compile throws inside the GUI renderer, crashing every
		// menu. Compile it here instead (cached after the first time) and give up cleanly.
		if (RenderSystem.getCompiledPipelineNullable(pipeline) == null) {
			skyBroken = true;
			NexoMod.LOGGER.warn("[nexomod] The sky shader did not compile; shader backgrounds fall back to plain.");
			return false;
		}
		updatePalette();
		// See-through: the flag, and the fade in 16 steps in the spare alpha bits, so the
		// fade never re-uploads the palette (per-frame uploads are what Essential breaks).
		int alpha = seeThrough ? 128 | (Math.round(fade * 15F) << 3) | (1 + mode) : 1 + mode;
		int color = (alpha << 24) | (info & 0xFFFFFF);
		graphics.blit(pipeline, PALETTE_TEXTURE, 0, 0, u0 * guiHeight, 0F, guiWidth, guiHeight, guiHeight, guiHeight, color);
		return true;
	}

	private static void updatePalette() {
		if (palette != null && paletteVersion == NexoStyle.version && paletteAmount == amount) {
			return;
		}
		if (palette == null) {
			palette = new DynamicTexture(() -> "nexomod sky palette", 9, 1, false);
			Minecraft.getInstance().getTextureManager().register(PALETTE_TEXTURE, palette);
		}
		NativeImage image = palette.getPixels();
		int[] colors = {NexoStyle.INK, NexoStyle.TEXT_PRIMARY, NexoStyle.ACCENT, NexoStyle.MAGENTA,
				NexoStyle.ramp(0), NexoStyle.ramp(1), NexoStyle.ramp(2), NexoStyle.ramp(3),
				// The amount as a whole percent (25-200 fits a byte), so the shader's counts match the launcher's.
				0xFF000000 | (Math.round(amount * 100F) << 16)};
		for (int i = 0; i < colors.length; i++) {
			image.setPixel(i, 0, colors[i]);
		}
		palette.upload();
		paletteVersion = NexoStyle.version;
		paletteAmount = amount;
	}

	/**
	 * The starfield background on the GPU: the CPU-drawn {@link Starfield}'s stars
	 * vanished while an Essential button was hovered, the shader quad doesn't.
	 * Falls back to {@link Starfield} if the shader can't be used.
	 */
	public static void drawStarfield(GuiGraphicsExtractor graphics, int guiWidth, int guiHeight) {
		if (guiWidth <= 0 || guiHeight <= 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		float mouseX = (float) (mc.mouseHandler.getScaledXPos(mc.getWindow()) / guiWidth - 0.5);
		float mouseY = (float) (mc.mouseHandler.getScaledYPos(mc.getWindow()) / guiHeight - 0.5);
		amount = 1F;
		// 600 s is a whole number of every drift and twinkle loop in the shader, so
		// wrapping there is seamless, and fits the 16 bits it has for time.
		int centiseconds = advanceClock(100) % 60_000;
		int mouseByte = Math.round((Mth.clamp(mouseY, -0.5F, 0.5F) + 0.5F) * 255F);
		// Mode 4: R = cursor height, GB = time; u starts at the cursor's x offset.
		if (!drawSky(graphics, guiWidth, guiHeight, 4, (mouseByte << 16) | centiseconds, Mth.clamp(mouseX, -0.5F, 0.5F)) && !seeThrough) {
			Starfield.get().draw(graphics, guiWidth, guiHeight);
		}
	}

	/**
	 * Vanilla's opaque menu-background pipeline with the sky shader in place of its
	 * fragment shader, and a depth test that always passes: Essential can start the
	 * GUI pass from a restored depth buffer, which a background fails (the reason
	 * for the same tweak in Starfield's history). Copied from the vanilla pipeline
	 * rather than restated, so its vertex format and bindings follow updates.
	 */
	private static RenderPipeline skyPipeline() {
		if (!skyPipelineTried) {
			skyPipelineTried = true;
			skyPipeline = buildSkyPipeline("pipeline/nexo_sky", RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND);
		}
		return skyPipeline;
	}

	/** As {@link #skyPipeline}, but with GUI_TEXTURED's blending, so the shader's alpha lets the world through. */
	private static RenderPipeline seeThroughPipeline() {
		if (!seeThroughPipelineTried) {
			seeThroughPipelineTried = true;
			RenderPipeline sky = buildSkyPipeline("pipeline/nexo_sky_see_through", RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND,
					RenderPipelines.GUI_TEXTURED);
			if (sky != null && RenderSystem.getCompiledPipelineNullable(sky) == null) {
				sky = null;
			}
			seeThroughPipeline = sky;
		}
		return seeThroughPipeline;
	}

	private static RenderPipeline buildSkyPipeline(String location, RenderPipeline base) {
		return buildSkyPipeline(location, base, base);
	}

	/** {@code base}'s vertex side, the sky fragment shader, and {@code colorTargets}' colour targets (blending). */
	private static RenderPipeline buildSkyPipeline(String location, RenderPipeline base, RenderPipeline colorTargets) {
		try {
			RenderPipeline.Builder builder = RenderPipeline.builder()
					.withLocation(Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, location))
					.withVertexShader(base.getShaders().get(ShaderType.VERTEX))
					.withFragmentShader(SKY_SHADER)
					.withPrimitiveTopology(base.getPrimitiveTopology())
					.withPolygonMode(base.getPolygonMode())
					.withCull(base.isCull())
					.withPushConstantSize(base.pushConstantSize())
					.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS,
							base.getDepthStencilState() != null && base.getDepthStencilState().writeDepth()));
			java.util.List<com.mojang.renderpearl.api.vertex.VertexFormat> bindings = base.getVertexFormatBindings();
			for (int i = 0; i < bindings.size(); i++) {
				if (bindings.get(i) != null) {
					builder.withVertexBinding(i, bindings.get(i));
				}
			}
			for (BindGroupLayout layout : base.getBindGroupLayouts()) {
				builder.withBindGroupLayout(layout);
			}
			for (int i = 0; i < colorTargets.getColorTargetStates().size(); i++) {
				builder.withColorTargetState(i, colorTargets.getColorTargetStates().get(i));
			}
			return builder.build();
		} catch (RuntimeException e) {
			NexoMod.LOGGER.warn("[nexomod] Could not build the sky shader pipeline {}; it falls back to plain.", location, e);
			return null;
		}
	}
}
