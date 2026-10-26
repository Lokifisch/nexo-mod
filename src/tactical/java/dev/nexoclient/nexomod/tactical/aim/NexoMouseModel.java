package dev.nexoclient.nexomod.tactical.aim;

/**
 * The arithmetic behind "a mouse did this".
 *
 * <h2>Why a quantum exists at all</h2>
 *
 * <p>Vanilla never turns the camera by a free number. {@code MouseHandler}
 * multiplies the accumulated mouse counts by a per-count step derived from the
 * sensitivity slider ({@code f = sensitivity * 0.6 + 0.2}, step =
 * {@code f^3 * 8 * 0.15} degrees), so every rotation the server ever sees from
 * a real player is an integer multiple of that step, applied through
 * {@code turnPlayer}. A client that calls {@code setYRot} with an arbitrary
 * float produces deltas that are <em>not</em> on that grid — the "sensitivity"
 * family of anticheat checks (Grim's {@code AimDuplicateLook} /
 * sensitivity check, and the equivalents elsewhere) exists exactly to find
 * that, and it is the cheapest way to catch a client-side aimbot: no statistics
 * needed, one modulo per tick.
 *
 * <p>So every rotation this mod applies is snapped to the same grid vanilla
 * would have produced. That does not make the aim legitimate, but it removes
 * the single most obvious fingerprint, and it costs nothing: the grid is
 * ~0.15° at default sensitivity, far finer than anything a player can see.
 *
 * <p>No Minecraft imports on purpose — this is plain arithmetic and can be
 * unit-tested (or `javac`'d) without a game classpath.
 */
public final class NexoMouseModel {
	private NexoMouseModel() {
	}

	/**
	 * Degrees of rotation per mouse count for a sensitivity in {@code 0..1}.
	 * Mirrors {@code MouseHandler.turnPlayer} exactly, constants and all.
	 */
	public static double quantum(double sensitivity) {
		double f = sensitivity * 0.6 + 0.2;
		return f * f * f * 8.0 * 0.15;
	}

	/**
	 * Rounds a desired rotation delta to whole mouse counts.
	 *
	 * <p>Deltas below half a count become zero rather than being forced up to
	 * one count: a real mouse cannot move by less than one count, so a client
	 * that keeps nudging in half-count steps is as suspicious as one that never
	 * quantises at all — and it dithers around the target, which looks worse.
	 * The cost is that the crosshair settles up to half a count off target,
	 * which is what a hand does too.
	 */
	public static float step(float delta, double quantum) {
		if (delta == 0.0F || quantum <= 0.0 || !Float.isFinite(delta)) {
			return 0.0F;
		}
		double counts = Math.round(delta / quantum);
		return (float) (counts * quantum);
	}

	/**
	 * Snaps an absolute angle onto the same grid. Used where a packet carries an
	 * angle that was not produced by adding a step to the current rotation —
	 * the knockback flick, which jumps to {@code yaw + 180} in one tick.
	 */
	public static float snap(float angle, double quantum) {
		if (quantum <= 0.0 || !Float.isFinite(angle)) {
			return angle;
		}
		return (float) (Math.round(angle / quantum) * quantum);
	}
}
