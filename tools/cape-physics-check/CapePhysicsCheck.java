package dev.nexoclient.nexomod.capes;

/** The cape's physics against the behaviour it exists for. Run: tools/cape-physics-check/run.sh */
public final class CapePhysicsCheck {
	public static void main(String[] args) {
		for (boolean lively : new boolean[] {false, true}) {
			LIVELY = lively;
			run();
		}
		System.out.println("cape physics: calm and lively both pass");
	}

	private static boolean LIVELY;

	private static NexoCapePhysics cape(float length) {
		NexoCapePhysics cape = new NexoCapePhysics(length);
		cape.lively(LIVELY);
		return cape;
	}

	private static void run() {
		// Standing: hangs straight down, behind the back, the full length.
		NexoCapePhysics cape = settle(0F, 0F);
		check(hem(cape)[1] < -0.9F, "standing: the hem hangs about a cape-length below the shoulders, got " + hem(cape)[1]);
		check(Math.abs(hem(cape)[0]) < 0.05F, "standing: hangs straight, got x " + hem(cape)[0]);

		// Running forward (-z, the back points +z): the air holds it back, so it lifts out behind.
		NexoCapePhysics run = settle(0F, -5.6F);
		float[] h = hem(run);
		float lift = (float) Math.toDegrees(Math.atan2(h[2], -h[1]));
		check(lift > 15F && lift < 75F, "running: lifts 15-75 degrees, got " + lift);

		// Falling fast: the hem ends above the shoulders, upside down.
		NexoCapePhysics fall = settle(-25F, 0F);
		check(hem(fall)[1] > 0.6F, "falling fast: the hem is above the shoulders, got " + hem(fall)[1]);

		// Every row stays one row long, whatever happened.
		for (NexoCapePhysics c : new NexoCapePhysics[] {cape, run, fall}) {
			for (int i = 1; i < NexoCapePhysics.LINES; i++) {
				float d = (float) Math.sqrt(sq(c.x(i) - c.x(i - 1)) + sq(c.y(i) - c.y(i - 1)) + sq(c.z(i) - c.z(i - 1)));
				check(Math.abs(d - 1F / 16F) < 0.01F, "row " + i + " is " + d + " long");
			}
		}
		// Walking backwards into it: no line ends up inside the body.
		NexoCapePhysics back = settle(0F, 4F);
		for (int i = 1; i < NexoCapePhysics.LINES; i++) {
			float rel = back.z(i) - back.z(0);
			check(rel > -0.02F || back.y(i) - back.y(0) < -1.45F, "walking backwards: line " + i + " went into the body (" + rel + ")");
		}
		// Falling from rest, with the head straight, turned and tilted: through the whole flip,
		// no point of the cape (each line's centre and edges, and halfway between lines) enters the head.
		float worst = 0F;
		for (float[] look : new float[][] {{0F, 0F}, {70F, 0F}, {-70F, 0F}, {0F, -80F}, {0F, 80F}, {50F, -60F}}) {
			NexoCapePhysics drop = cape(0.9375F);
			float yaw = (float) Math.toRadians(look[0]);
			float pitch = (float) Math.toRadians(look[1]);
			float forward = NexoCapePhysics.STANDING_HEAD_FORWARD;
			drop.head(0F, -forward, yaw, pitch);
			float y = 100F, vy = 0F;
			drop.reset(0F, y, 0F, 0F, 1F);
			for (int f = 0; f < 240; f++) {
				vy = Math.max(vy - 32F / 60F, -40F);
				y += vy / 60F;
				drop.advance(1F / 60F, 0F, y, 0F, 0F, 1F);
				for (int i = 1; i < 2 * NexoCapePhysics.LINES - 1; i++) {
					int a = i / 2, b = (i + 1) / 2;
					// Below the shoulders the head is ignored on purpose: tipped back, it overlaps the pinned top.
					if ((drop.y(a) + drop.y(b)) / 2F - y <= 0F) {
						continue;
					}
					for (float edge : new float[] {-NexoCapePhysics.CAPE_HALF_WIDTH, 0F, NexoCapePhysics.CAPE_HALF_WIDTH}) {
						// The back is +z and the left -x here (back x up = left points along -x).
						float[] out = drop.headExit((drop.z(a) + drop.z(b)) / 2F + forward, -((drop.x(a) + drop.x(b)) / 2F) + edge,
								(drop.y(a) + drop.y(b)) / 2F - y);
						worst = Math.max(worst, (float) Math.sqrt(sq(out[0]) + sq(out[1]) + sq(out[2])));
					}
				}
			}
			// Looking far up tips the back of the head down over the cape, which then rightly can't pass it.
			if (look[1] > -45F) {
				check(hem(drop)[1] > 0.5F, "falling, head at " + look[0] + "/" + look[1] + ": still flips upside down, got " + hem(drop)[1]);
			}
		}
		check(worst < 0.04F, "falling: the cape went " + worst + " blocks into the head");
		System.out.println("cape physics: all checks passed (deepest into the head while falling: " + worst + ")");
	}

	/** Two seconds of a steady move at (vy, vz) blocks/s, from rest. The back faces +z. */
	private static NexoCapePhysics settle(float vy, float vz) {
		NexoCapePhysics cape = cape(1F);
		float y = 100F, z = 0F;
		cape.reset(0F, y, z, 0F, 1F);
		for (int f = 0; f < 120; f++) {
			y += vy / 60F;
			z += vz / 60F;
			cape.advance(1F / 60F, 0F, y, z, 0F, 1F);
		}
		return cape;
	}

	private static float[] hem(NexoCapePhysics c) {
		int last = NexoCapePhysics.LINES - 1;
		return new float[] {c.x(last) - c.x(0), c.y(last) - c.y(0), c.z(last) - c.z(0)};
	}

	private static float sq(float v) {
		return v * v;
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
	}
}
