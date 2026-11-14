package dev.nexoclient.nexomod.capes;

/**
 * A cape as a hanging chain of horizontal lines: line 0 is the top edge, held at
 * the shoulders, and each line below is one texture row further down. Every line
 * keeps its own momentum (position-based Verlet), is pulled by gravity and slowed
 * by still air, and stays one row's length from its neighbours.
 *
 * <p>Nothing here knows about sprinting or falling: the cape only sees where its
 * top edge is dragged. Run, and the air holds the lines back so the cape lifts;
 * fall faster than the cape's own terminal speed and the air pushes it up past
 * the shoulders until it hangs completely upside down. Nothing limits the bend
 * between lines, so it can curl; one soft rule stops it folding flat onto itself.
 *
 * <p>World space, in blocks and seconds. No Minecraft imports, so
 * {@code tools/cape-physics-check} runs it with plain {@code javac}.
 */
public final class NexoCapePhysics {
	public static final int LINES = 17;
	static final float GRAVITY = 18F;
	/** Linear air drag per second, calm and lively: the cape falls no faster than GRAVITY / drag on its own. */
	static final float DRAG = 1.8F;
	static final float LIVELY_DRAG = 3.5F;
	private float drag = DRAG;
	private float flutter = 0.35F;
	/** Fastest a line may move relative to the shoulders, in blocks per second: nothing is ever flung far. */
	private static final float MAX_RELATIVE = 9F;
	private static final float STEP = 1F / 120F;
	/** Enough passes that a hanging chain of 16 rows stretches under 2%. Cheap: 17 lines. */
	private static final int ITERATIONS = 24;
	/** Closest two lines one apart may come, as a share of two rows: allows curling, not crumpling. */
	private static final float FOLD = 0.45F;

	private final float[] x = new float[LINES];
	private final float[] y = new float[LINES];
	private final float[] z = new float[LINES];
	private final float[] px = new float[LINES];
	private final float[] py = new float[LINES];
	private final float[] pz = new float[LINES];
	private final float row;
	private float time;
	// The head, as last given to head(); standing straight, looking ahead, by default.
	private float headUp;
	private float headBehind = -STANDING_HEAD_FORWARD;
	private float headCos = 1F, headSin;
	private float pitchCos = 1F, pitchSin;
	private float pending;
	private boolean placed;

	/** @param length the cape's length in blocks, top edge to hem */
	public NexoCapePhysics(float length) {
		this.row = length / (LINES - 1);
	}

	/** Hangs straight down from the anchor, at rest. */
	public void reset(float ax, float ay, float az, float backX, float backZ) {
		for (int i = 0; i < LINES; i++) {
			x[i] = px[i] = ax + backX * 0.01F * i;
			y[i] = py[i] = ay - row * i;
			z[i] = pz[i] = az + backZ * 0.01F * i;
		}
		pending = 0F;
		placed = true;
	}

	/** Lively catches more air (lifts higher, flutters more) than the calm default. */
	public void lively(boolean lively) {
		drag = lively ? LIVELY_DRAG : DRAG;
		flutter = lively ? 0.9F : 0.35F;
	}

	public boolean placed() {
		return placed;
	}

	/**
	 * Where the head is, for the collision: its pivot (the bottom centre of the head)
	 * relative to the anchor in blocks, up and out of the back, and how it is turned
	 * against the body, in radians: yaw positive to the right, pitch positive looking down.
	 */
	public void head(float up, float behind, float yaw, float pitch) {
		headUp = up;
		headBehind = behind;
		headCos = (float) Math.cos(yaw);
		headSin = (float) Math.sin(yaw);
		pitchCos = (float) Math.cos(pitch);
		pitchSin = (float) Math.sin(pitch);
	}

	/**
	 * Advances by {@code seconds}, with the top edge now at the anchor.
	 * {@code back*} is the horizontal unit vector out of the player's back.
	 */
	public void advance(float seconds, float ax, float ay, float az, float backX, float backZ) {
		if (!placed) {
			reset(ax, ay, az, backX, backZ);
			return;
		}
		pending = Math.min(pending + seconds, 0.25F);
		// The anchor moves in a straight line across this call's sub-steps.
		int steps = (int) (pending / STEP);
		float sx = x[0], sy = y[0], sz = z[0];
		for (int s = 1; s <= steps; s++) {
			float f = (float) s / steps;
			step(sx + (ax - sx) * f, sy + (ay - sy) * f, sz + (az - sz) * f, backX, backZ);
		}
		pending -= steps * STEP;
	}

	private void step(float ax, float ay, float az, float backX, float backZ) {
		time += STEP;
		float keep = 1F - drag * STEP;
		// How far the shoulders move this step, which the lines' own speed is measured against.
		float mx = ax - x[0], my = ay - y[0], mz = az - z[0];
		float limit = MAX_RELATIVE * STEP;
		for (int i = 1; i < LINES; i++) {
			float vx = (x[i] - px[i]) * keep;
			float vy = (y[i] - py[i]) * keep;
			float vz = (z[i] - pz[i]) * keep;
			float rx = vx - mx, ry = vy - my, rz = vz - mz;
			float rel = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
			if (rel > limit) {
				float k = limit / rel;
				vx = mx + rx * k;
				vy = my + ry * k;
				vz = mz + rz * k;
			}
			px[i] = x[i];
			py[i] = y[i];
			pz[i] = z[i];
			// Flutter: moving air shakes each line along the cloth's normal, more the faster it goes.
			float speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz) / STEP;
			float shake = Math.min(speed, 12F) * flutter * (float) Math.sin(time * 14F + i * 0.8F) * STEP * STEP;
			float nx = backX, nz = backZ;
			x[i] += vx + nx * shake;
			y[i] += vy - GRAVITY * STEP * STEP;
			z[i] += vz + nz * shake;
		}
		x[0] = px[0] = ax;
		y[0] = py[0] = ay;
		z[0] = pz[0] = az;
		for (int k = 0; k < ITERATIONS; k++) {
			for (int i = 1; i < LINES; i++) {
				hold(i - 1, i, row, row, i == 1);
			}
			for (int i = 2; i < LINES; i++) {
				hold(i - 2, i, 2F * row * FOLD, Float.MAX_VALUE, false);
			}
			for (int i = 1; i < LINES; i++) {
				collide(i, ax, ay, az, backX, backZ);
			}
			// The strips between lines too: two lines either side of a corner of the head
			// would otherwise let the strip joining them cut through it.
			for (int i = 1; i < LINES; i++) {
				collideStrip(i - 1, i, ax, ay, az, backX, backZ);
			}
		}
	}

	/** Keeps lines {@code a} and {@code b} between {@code min} and {@code max} apart. A pinned {@code a} doesn't move. */
	private void hold(int a, int b, float min, float max, boolean pinned) {
		float dx = x[b] - x[a], dy = y[b] - y[a], dz = z[b] - z[a];
		float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (d < 1e-6F) {
			// Exactly on top of each other: no direction to push in, so drop straight down.
			y[b] = y[a] - min;
			return;
		}
		float target = d < min ? min : d > max ? max : d;
		if (target == d) {
			return;
		}
		float push = (d - target) / d;
		if (pinned || a == 0) {
			x[b] -= dx * push;
			y[b] -= dy * push;
			z[b] -= dz * push;
		} else {
			x[a] += dx * push * 0.5F;
			y[a] += dy * push * 0.5F;
			z[a] += dz * push * 0.5F;
			x[b] -= dx * push * 0.5F;
			y[b] -= dy * push * 0.5F;
			z[b] -= dz * push * 0.5F;
		}
	}

	/** Blocks from the shoulders to the feet, below the anchor. */
	static final float FEET = -1.45F;
	private static final float HALF_WIDTH = 0.32F;
	/** Half the cape's width in blocks (5 pixels at the model's 0.9375 scale): its two edges. */
	static final float CAPE_HALF_WIDTH = 5F / 16F * 0.9375F;
	private static final float[] EDGES = {-CAPE_HALF_WIDTH, 0F, CAPE_HALF_WIDTH};
	/**
	 * The head as a box around its pivot, in blocks: 8 pixels plus the hat layer, at the
	 * model's 0.9375 scale, and a little margin for the cape itself.
	 */
	static final float HEAD_HALF = 4.5F / 16F * 0.9375F + 0.02F;
	static final float HEAD_TOP = 8.5F / 16F * 0.9375F + 0.02F;
	/** Standing, the head's pivot is 2 pixels in front of the anchor, which sits on the back. */
	static final float STANDING_HEAD_FORWARD = 2F / 16F * 0.9375F;
	/**
	 * The anchor sits at the head's lower back edge, so near the neck the head's back face
	 * slopes in toward the pivot over this height; as a flat face starting there, the line
	 * pinned next to the anchor could never get out from behind it.
	 */
	private static final float NECK = 0.2F;

	/** Pushes line {@code i} out of the torso and out of the head. */
	private void collide(int i, float ax, float ay, float az, float backX, float backZ) {
		float rx = x[i] - ax, ry = y[i] - ay, rz = z[i] - az;
		float behind = rx * backX + rz * backZ;
		// Along the shoulders, to the player's left.
		float side = rx * -backZ + rz * backX;
		// Below the shoulders the line may not sit in front of the back.
		if (ry < 0F && ry > FEET && behind < 0F && Math.abs(side) < HALF_WIDTH) {
			x[i] -= backX * behind;
			z[i] -= backZ * behind;
			behind = 0F;
		}
		// Only above the shoulders: looking up tips the back of the head down over where the
		// cape is pinned, and a head pushing there fights the pin and throws the cape.
		if (ry <= 0F) {
			return;
		}
		float[] out = deepestHeadExit(behind, side, ry);
		x[i] += backX * out[0] - backZ * out[1];
		y[i] += out[2];
		z[i] += backZ * out[0] + backX * out[1];
	}

	/** The midpoint of lines {@code a} and {@code b} out of the head, moving both (only {@code b} if {@code a} is the pinned top). */
	private void collideStrip(int a, int b, float ax, float ay, float az, float backX, float backZ) {
		float rx = (x[a] + x[b]) / 2F - ax, ry = (y[a] + y[b]) / 2F - ay, rz = (z[a] + z[b]) / 2F - az;
		if (ry <= 0F) {
			return;
		}
		float[] out = deepestHeadExit(rx * backX + rz * backZ, rx * -backZ + rz * backX, ry);
		float mx = backX * out[0] - backZ * out[1], my = out[2], mz = backZ * out[0] + backX * out[1];
		if (a == 0) {
			x[b] += 2F * mx;
			y[b] += 2F * my;
			z[b] += 2F * mz;
			return;
		}
		x[a] += mx;
		y[a] += my;
		z[a] += mz;
		x[b] += mx;
		y[b] += my;
		z[b] += mz;
	}

	/**
	 * The head's exit move for a point of the cape in the body's frame (behind, left, up from
	 * the anchor), tried at the cape's centre and both its edges: a turned head brings a
	 * corner toward one edge. The deepest of the three wins, since the line moves as one.
	 */
	private float[] deepestHeadExit(float behind, float side, float up) {
		float[] best = new float[3];
		float bestSize = 0F;
		for (float edge : EDGES) {
			float[] out = headExit(behind - headBehind, side + edge, up - headUp);
			float size = out[0] * out[0] + out[1] * out[1] + out[2] * out[2];
			if (size > bestSize) {
				bestSize = size;
				best = out;
			}
		}
		return best;
	}

	/**
	 * The shortest way out of the head for a point given relative to the head's pivot in
	 * the body's frame (behind, left, up), as a move in that same frame; zeros when the
	 * point is not inside. It leaves by the back, the top or a side, never the face or the
	 * neck, which is how a cape coming over the shoulders meets a head.
	 */
	float[] headExit(float b, float s, float u) {
		// Into the head's own frame: undo its yaw, then its pitch.
		float hb = b * headCos + s * headSin;
		float hs = -b * headSin + s * headCos;
		float lb = hb * pitchCos + u * pitchSin;
		float lu = -hb * pitchSin + u * pitchCos;
		float back = HEAD_HALF * Math.min(1F, Math.max(0F, lu) / NECK);
		if (lu <= 0F || lu >= HEAD_TOP || lb <= -HEAD_HALF || lb >= back || Math.abs(hs) >= HEAD_HALF) {
			return new float[3];
		}
		float ob = back - lb, os = 0F, ou = 0F;
		float best = ob;
		if (HEAD_TOP - lu < best) {
			best = HEAD_TOP - lu;
			ob = 0F;
			ou = best;
		}
		float sideOut = HEAD_HALF - Math.abs(hs);
		if (sideOut < best) {
			ob = 0F;
			ou = 0F;
			os = Math.copySign(sideOut, hs);
		}
		// And back: pitch, then yaw.
		float pb = ob * pitchCos - ou * pitchSin;
		float pu = ob * pitchSin + ou * pitchCos;
		return new float[] {pb * headCos - os * headSin, pb * headSin + os * headCos, pu};
	}

	public float x(int i) {
		return x[i];
	}

	public float y(int i) {
		return y[i];
	}

	public float z(int i) {
		return z[i];
	}
}
