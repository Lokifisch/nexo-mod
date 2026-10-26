package dev.nexoclient.nexomod.tactical.stealth;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.EntityHitResult;

import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.hud.NexoStatsHud;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoStyle;
import dev.nexoclient.nexomod.tactical.aim.NexoMouseModel;

/**
 * The audit HUD: what this client is actually sending, measured on the wire
 * rather than assumed from what the modules think they did.
 *
 * <h2>What it answers</h2>
 *
 * <p>Two questions, both of which are the ones a sensitivity/aim check asks:
 *
 * <ul>
 *   <li><b>Is every rotation delta on the vanilla mouse grid?</b> {@link NexoMouseModel}
 *       says the mod snaps what it applies — this counts the packets that came
 *       out off-grid anyway, so a module that forgets to snap, or a path that
 *       bypasses the snapping, shows up as a non-zero number instead of a
 *       surprise ban.</li>
 *   <li><b>Do the attack intervals look human?</b> A constant gap is the
 *       cheapest autoclicker fingerprint there is; mean and spread over the
 *       last attacks are right there to read.</li>
 * </ul>
 *
 * <h2>Why the packet hook and not the modules</h2>
 *
 * <p>Measuring inside a module measures the module. {@link ServerboundMovePlayerPacket}
 * is the only thing the server sees, so the audit reads exactly that, at the
 * one place it is written: {@code ClientCommonPacketListenerImpl.send}. Nothing
 * else in the client can rotate the camera without passing through here.
 */
public final class NexoServerView implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("nexomod", "server_view");

	private static final int MARGIN = 4;
	private static final int LINE_HEIGHT = 10;
	/** Rotation samples kept for the off-grid ratio — roughly six seconds of turning. */
	private static final int ROTATION_WINDOW = 200;
	/** Attacks kept for the interval statistics. */
	private static final int ATTACK_WINDOW = 12;
	private static final int CPS_TICKS = 20;
	private static final int WARN = 0xFFFF4D4D;

	private static final boolean[] ROTATION_GRID = new boolean[ROTATION_WINDOW];
	private static int rotationIndex;
	private static int rotationSamples;
	private static int rotationOffGrid;
	private static float lastYaw;
	private static float lastPitch;
	private static float lastDeltaYaw = Float.NaN;
	private static boolean hasLastRotation;

	private static final long[] ATTACK_TICKS = new long[ATTACK_WINDOW];
	private static int attackIndex;
	private static int attackSamples;
	private static long lastAttackTick = Long.MIN_VALUE;
	private static double lastReach = Double.NaN;
	private static double lastGap = Double.NaN;

	private NexoServerView() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, ID, new NexoServerView());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
		ClientPreAttackCallback.EVENT.register((client, player, hand) -> {
			recordAttack(client);
			return false;
		});
		// The HUD only needs to know when the world changed under it; the numbers
		// themselves are written by the packet hook.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.level == null) {
				reset();
			}
		});
	}

	// ------------------------------------------------------------------
	// Capture
	// ------------------------------------------------------------------

	/** Called from the mixin for every outgoing packet; ignores everything but movement. */
	public static void observePacket(Object packet) {
		if (!(packet instanceof ServerboundMovePlayerPacket move) || !move.hasRotation()) {
			return;
		}
		NexoConfig config = NexoConfig.get();
		if (!config.serverViewEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		float yaw = move.getYRot(0f);
		float pitch = move.getXRot(0f);
		if (hasLastRotation) {
			double quantum = NexoMouseModel.quantum(client.options.sensitivity().get());
			float rawYaw = yaw - lastYaw;
			lastDeltaYaw = rawYaw;
			// Either spelling of the delta may be the mouse's; a wrap across the seam is not off-grid.
			boolean yawOk = onGrid(rawYaw, quantum) || onGrid(wrapDelta(rawYaw), quantum);
			// Pitch pinned at +-90 clamps a delta to a non-whole count; that is the game, not a fault.
			boolean pitchOk = Math.abs(pitch) >= 90.0F || Math.abs(lastPitch) >= 90.0F
					|| onGrid(pitch - lastPitch, quantum);
			pushRotation(yawOk && pitchOk);
		}
		lastYaw = yaw;
		lastPitch = pitch;
		hasLastRotation = true;
	}

	private static void pushRotation(boolean aligned) {
		if (rotationSamples == ROTATION_WINDOW) {
			// Overwrite the oldest slot; keep the running totals in step with it.
			if (!ROTATION_GRID[rotationIndex]) {
				rotationOffGrid--;
			}
			ROTATION_GRID[rotationIndex] = aligned;
			if (!aligned) {
				rotationOffGrid++;
			}
			rotationIndex = (rotationIndex + 1) % ROTATION_WINDOW;
			return;
		}
		ROTATION_GRID[rotationIndex] = aligned;
		if (!aligned) {
			rotationOffGrid++;
		}
		rotationIndex = (rotationIndex + 1) % ROTATION_WINDOW;
		rotationSamples++;
	}

	/** Yaw wraps at ±180, so a delta across the seam is a full turn off. */
	private static float wrapDelta(float delta) {
		if (delta > 180f) {
			return delta - 360f;
		}
		if (delta < -180f) {
			return delta + 360f;
		}
		return delta;
	}

	/**
	 * A delta is on-grid when it is a whole number of mouse counts. The epsilon is
	 * far below one count (0.15° at default sensitivity) but far above float noise
	 * on a value that went through a wrap and a packet round trip.
	 */
	private static boolean onGrid(double delta, double quantum) {
		if (quantum <= 0) {
			return true;
		}
		double counts = delta / quantum;
		return Math.abs(counts - Math.round(counts)) * quantum < 0.02;
	}

	private static void recordAttack(Minecraft client) {
		// Only swings that start an attack on an entity; mining and air swings are not attacks.
		if (!NexoConfig.get().serverViewEnabled() || client.level == null
				|| !(client.hitResult instanceof EntityHitResult)) {
			return;
		}
		long tick = client.level.getGameTime();
		if (lastAttackTick != Long.MIN_VALUE) {
			lastGap = tick - lastAttackTick;
		}
		lastAttackTick = tick;
		ATTACK_TICKS[attackIndex] = tick;
		attackIndex = (attackIndex + 1) % ATTACK_WINDOW;
		if (attackSamples < ATTACK_WINDOW) {
			attackSamples++;
		}
		LocalPlayer player = client.player;
		if (player != null && client.hitResult instanceof EntityHitResult hit) {
			lastReach = player.getEyePosition().distanceTo(hit.getLocation());
		} else {
			lastReach = Double.NaN;
		}
	}

	private static void reset() {
		rotationIndex = 0;
		rotationSamples = 0;
		rotationOffGrid = 0;
		hasLastRotation = false;
		lastDeltaYaw = Float.NaN;
		attackIndex = 0;
		attackSamples = 0;
		lastAttackTick = Long.MIN_VALUE;
		lastReach = Double.NaN;
		lastGap = Double.NaN;
	}

	// ------------------------------------------------------------------
	// Render
	// ------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (NexoHudVisibility.hidden()) {
			return;
		}
		NexoConfig config = NexoConfig.get();
		if (!config.serverViewEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.gui.hud.isHidden()) {
			return;
		}
		Font font = client.font;
		int x = MARGIN;
		// Under the stats HUD's current box rather than at a hardcoded y: the two
		// are both top-left, and stats grows a line at a time.
		ScreenRectangle stats = NexoStatsHud.resolveBounds(graphics.guiWidth(), graphics.guiHeight());
		int y = Math.max(MARGIN, stats.bottom() + MARGIN);

		y = line(graphics, font, x, y, "wire",
				rotationSamples == 0 ? "--" : rotationOffGrid + "/" + rotationSamples + " off-grid",
				rotationOffGrid == 0 ? NexoStyle.TEXT_ACTIVE_ACCENT : WARN);
		y = line(graphics, font, x, y, "last \u0394yaw",
				Float.isNaN(lastDeltaYaw) ? "--" : String.format(java.util.Locale.ROOT, "%.2f\u00b0", lastDeltaYaw),
				NexoStyle.TEXT_PRIMARY);
		y = line(graphics, font, x, y, "gap",
				attackSamples < 2 ? "--" : format(lastGap) + "t  spread " + format(spread()) + "t",
				NexoStyle.TEXT_PRIMARY);
		y = line(graphics, font, x, y, "cps",
				Integer.toString(cps(client)),
				NexoStyle.TEXT_PRIMARY);
		line(graphics, font, x, y, "reach",
				Double.isNaN(lastReach) ? "--" : String.format(java.util.Locale.ROOT, "%.2f", lastReach),
				NexoStyle.TEXT_PRIMARY);
	}

	private static int line(GuiGraphicsExtractor graphics, Font font, int x, int y, String label,
			String value, int valueColor) {
		Component text = Component.literal(label + " ")
				.append(Component.literal(value).withStyle(style -> style.withColor(valueColor & 0xFFFFFF)));
		graphics.text(font, text, x, y, NexoStyle.TEXT_SECONDARY);
		return y + LINE_HEIGHT;
	}

	private static String format(double value) {
		return Double.isNaN(value) ? "--" : String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	/** Standard deviation of the attack gaps in the window — the "is this a robot" number. */
	private static double spread() {
		if (attackSamples < 2) {
			return Double.NaN;
		}
		double sum = 0;
		int gaps = 0;
		long previous = Long.MIN_VALUE;
		for (int i = 0; i < attackSamples; i++) {
			int slot = (attackIndex - attackSamples + i + ATTACK_WINDOW) % ATTACK_WINDOW;
			long tick = ATTACK_TICKS[slot];
			if (previous != Long.MIN_VALUE) {
				sum += tick - previous;
				gaps++;
			}
			previous = tick;
		}
		if (gaps == 0) {
			return Double.NaN;
		}
		double mean = sum / gaps;
		double squares = 0;
		previous = Long.MIN_VALUE;
		for (int i = 0; i < attackSamples; i++) {
			int slot = (attackIndex - attackSamples + i + ATTACK_WINDOW) % ATTACK_WINDOW;
			long tick = ATTACK_TICKS[slot];
			if (previous != Long.MIN_VALUE) {
				double diff = (tick - previous) - mean;
				squares += diff * diff;
			}
			previous = tick;
		}
		return Math.sqrt(squares / gaps);
	}

	private static int cps(Minecraft client) {
		if (client.level == null) {
			return 0;
		}
		long now = client.level.getGameTime();
		int count = 0;
		for (int i = 0; i < attackSamples; i++) {
			int slot = (attackIndex - 1 - i + ATTACK_WINDOW * 2) % ATTACK_WINDOW;
			if (now - ATTACK_TICKS[slot] <= CPS_TICKS) {
				count++;
			}
		}
		return count;
	}
}
