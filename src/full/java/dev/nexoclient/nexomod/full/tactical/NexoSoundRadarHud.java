package dev.nexoclient.nexomod.full.tactical;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Draws each recent sound as a marker on a ring around the crosshair.
 *
 * <h2>Which HUD API</h2>
 *
 * <p>26.1 has no {@code Gui.render} to mix into and no
 * {@code HudRenderCallback}: the HUD is a registry of named elements, each of
 * which contributes to a render state that is drawn later
 * ({@code HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker)}).
 * Fabric exposes it as {@code HudElementRegistry}, verified against
 * {@code fabric-rendering-v1} 23.3.1 in this build's dependency set. Attaching
 * after {@link VanillaHudElements#SUBTITLES} puts this in the same layer as the
 * feature it extends and keeps it under chat and the player list.
 *
 * <p>A raw {@code Gui} mixin would also work and is what every pre-26.1 guide
 * describes; it is worse here for the usual reason a mixin is worse than an API
 * — it competes with every other mod that had the same idea, in an order none of
 * them control.
 *
 * <h2>Bearing</h2>
 *
 * <p>The player's forward vector in Minecraft's yaw convention is
 * {@code (-sin(yaw), cos(yaw))} over (x, z) — yaw 0 faces +Z — and their right
 * is {@code (-cos(yaw), -sin(yaw))}, which is checkable at yaw 0 (facing south,
 * right hand points to -X) and at yaw 90 (facing west, right hand points to -Z).
 * Projecting the offset onto those two axes gives screen right and screen up
 * directly, with no angle normalisation and no quadrant special cases.
 */
public final class NexoSoundRadarHud implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "sound_radar");

	/** Distance from the crosshair to the markers, in GUI pixels. */
	private static final int RING_RADIUS = 54;
	private static final int MARKER_HALF = 3;

	private NexoSoundRadarHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.SUBTITLES, ID, new NexoSoundRadarHud());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		// Ghost mode and the screenshot toggle both land here, because
		// NexoHudVisibility.hidden() folds them together.
		if (NexoHudVisibility.hidden()) {
			return;
		}
		NexoConfig config = NexoConfig.get();
		if (!config.tacticalEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || client.options.hideGui) {
			return;
		}

		float yaw = (float) Math.toRadians(player.getYRot());
		double forwardX = -Math.sin(yaw);
		double forwardZ = Math.cos(yaw);
		double rightX = -Math.cos(yaw);
		double rightZ = -Math.sin(yaw);

		int centerX = graphics.guiWidth() / 2;
		int centerY = graphics.guiHeight() / 2;
		long now = System.currentTimeMillis();
		double range = config.tacticalRange();
		Font font = client.font;

		for (NexoSoundPing ping : NexoSoundRadar.pings()) {
			double dx = ping.x() - player.getX();
			double dz = ping.z() - player.getZ();
			double distance = Math.sqrt(dx * dx + dz * dz);
			if (distance < 1.0E-4) {
				// Directly on top of the player: there is no bearing to draw,
				// and normalising would divide by ~zero.
				continue;
			}

			double right = (dx * rightX + dz * rightZ) / distance;
			double forward = (dx * forwardX + dz * forwardZ) / distance;

			int alpha = alphaFor(ping, now, distance, range);
			if (alpha <= 0) {
				continue;
			}
			int color = (alpha << 24) | colorFor(ping.source());

			int x = centerX + (int) Math.round(right * RING_RADIUS);
			int y = centerY - (int) Math.round(forward * RING_RADIUS);
			diamond(graphics, x, y, color);

			Component label = ping.subtitle();
			if (label != null) {
				// Pushed further out along the same bearing so the text sits
				// outside the ring rather than on top of the marker.
				int labelX = centerX + (int) Math.round(right * (RING_RADIUS + 12));
				int labelY = centerY - (int) Math.round(forward * (RING_RADIUS + 12)) - (font.lineHeight / 2);
				graphics.centeredText(font, label, labelX, labelY, color);
			}
		}
	}

	/**
	 * Fade by age <em>and</em> by distance, multiplied together. Age alone makes
	 * a far-away footstep as loud on screen as one behind you; distance alone
	 * leaves a marker for something that stopped making noise seconds ago.
	 */
	private static int alphaFor(NexoSoundPing ping, long now, double distance, double range) {
		double age = (double) (now - ping.timestamp()) / NexoSoundRadar.LIFETIME_MILLIS;
		if (age >= 1.0) {
			return 0;
		}
		double near = Math.clamp(1.0 - (distance / range), 0.0, 1.0);
		// Squared, so the last third of the lifetime is where most of the fade
		// happens rather than it dimming visibly from the first frame.
		double factor = (1.0 - age) * (0.35 + (0.65 * near * near));
		return (int) Math.clamp(Math.round(factor * 255.0), 0, 255);
	}

	/** A 2·{@link #MARKER_HALF}+1 pixel diamond, drawn as stacked horizontal bars. */
	private static void diamond(GuiGraphicsExtractor graphics, int x, int y, int color) {
		for (int row = -MARKER_HALF; row <= MARKER_HALF; row++) {
			int half = MARKER_HALF - Math.abs(row);
			graphics.fill(x - half, y + row, x + half + 1, y + row + 1, color);
		}
	}

	/**
	 * RGB per category. Deliberately the same four the default category mask
	 * enables, plus a neutral fallback: a colour nobody can name is worse than
	 * white for the categories a player had to go and switch on themselves.
	 */
	private static int colorFor(SoundSource source) {
		return switch (source) {
			case HOSTILE -> 0xFF4444;
			case PLAYERS -> 0x44E0FF;
			case NEUTRAL -> 0x66FF88;
			case BLOCKS -> 0xFFD24A;
			default -> 0xFFFFFF;
		};
	}
}
