package dev.nexoclient.nexomod.tactical.combat;

import java.util.concurrent.ThreadLocalRandom;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.tactical.aim.NexoAimAssist;
import dev.nexoclient.nexomod.tactical.freecam.NexoFreecam;

/**
 * The crosshair target-range indicator and the trigger bot. Both read the
 * vanilla crosshair pick ({@code Minecraft#hitResult}), which is already
 * limited to the player's attack reach, so "on a target" means "in reach".
 *
 * <p>The trigger bot presses the attack key the way a click would, so every
 * normal attack hook (combo counter, combat log, knockback displacement) sees
 * the swing. Tactical only: it attacks with no click from the player.
 *
 * <p>The swing is held back by a randomised reaction delay
 * ({@code triggerBotDelayMin}..{@code triggerBotDelayMax}) counted from the
 * tick the target first qualifies, drawn from a triangular distribution so
 * most swings land mid-window and the ends stay rare. A fixed "swing the
 * instant the cooldown fills" cadence is the easiest thing about a trigger bot
 * to fingerprint; timing alone does not make it undetectable, but it stops the
 * machine-perfect one-tick rhythm. Ticks are 50 ms apart, so the real
 * resolution of the window is one tick — the sub-tick part only decides which
 * of the following ticks fires.
 */
public final class NexoTriggerBot {
	private static final Identifier RANGE_ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "range_indicator");

	/** Target the pending swing belongs to; {@code null} when nothing is queued. */
	private static LivingEntity pendingTarget;
	/** {@link System#nanoTime()} deadline for the pending swing. */
	private static long pendingSwingAt;
	/** Deferrals already spent on this swing waiting for the aim to settle. */
	private static int deferredTicks;

	private NexoTriggerBot() {
	}

	private static KeyMapping toggleKey;

	public static void register() {
		// Unbound by default, like the other Tactical toggles.
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.triggerBot",
				InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
		// START, not END: vanilla consumes the click later in the same tick, so the swing
		// lands against the target that was just checked rather than one tick later.
		ClientTickEvents.START_CLIENT_TICK.register(NexoTriggerBot::tick);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, RANGE_ID, NexoTriggerBot::drawRange);
	}

	private static LivingEntity looked(Minecraft client) {
		return client.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity living
				&& living.isAlive() && !living.isSpectator() ? living : null;
	}

	private static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		NexoConfig config = NexoConfig.get();
		while (toggleKey.consumeClick()) {
			config.setTriggerBotEnabled(!config.triggerBotEnabled());
			if (player != null) {
				player.sendOverlayMessage(CommonComponents.optionStatus(
						Component.translatable("nexomod.qol.triggerBot"), config.triggerBotEnabled()));
			}
		}
		if (player == null || client.level == null || !config.triggerBotEnabled() || client.gui.screen() != null
				|| !player.isAlive() || player.isSpectator() || player.isUsingItem() || NexoFreecam.active()) {
			clearPending();
			return;
		}
		LivingEntity target = looked(client);
		if (target == null || target == player.getVehicle() || !NexoAimAssist.wanted(target, config.triggerBotTargets())
				|| player.getAttackStrengthScale(0.0F) < config.triggerBotCharge() / 100.0F) {
			// Nothing queued survives the target leaving the crosshair or the charge
			// dropping: the delay belongs to the moment everything was ready, not to
			// the entity, so re-acquiring rolls a fresh window.
			clearPending();
			return;
		}
		KeyMapping attack = client.options.keyAttack;
		if (attack.isUnbound()) {
			clearPending();
			return;
		}

		long now = System.nanoTime();
		if (pendingTarget != target) {
			pendingTarget = target;
			pendingSwingAt = now + delayNanos(config);
		}
		if (now < pendingSwingAt) {
			return;
		}
		// Do not fire on the tick the aim assist just moved the view: the attack
		// packet would carry a rotation delta and land with the crosshair arriving
		// exactly on the hitbox, which is its own pattern. Capped at two deferrals
		// so a target that keeps the aim busy still gets hit, and skipped entirely
		// at aim-assist stealth stage 0 — that rung is the "maximum accuracy" end
		// and should not pay latency for it.
		if (config.aimAssistHumanised() && NexoAimAssist.aimedThisTick() && deferredTicks < 2) {
			deferredTicks++;
			pendingSwingAt = now + 50_000_000L;
			return;
		}

		InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(attack);
		KeyMapping.click(key);
		clearPending();
	}

	/**
	 * Triangular draw over the configured window: the mean of two uniform picks,
	 * so swings cluster around the middle and the extremes stay uncommon, which
	 * is closer to human reaction times than a flat spread.
	 */
	private static long delayNanos(NexoConfig config) {
		int min = config.triggerBotDelayMin();
		int max = config.triggerBotDelayMax();
		if (max <= min) {
			return min * 1_000_000L;
		}
		int span = max - min;
		int millis = min + (ThreadLocalRandom.current().nextInt(span + 1)
				+ ThreadLocalRandom.current().nextInt(span + 1)) / 2;
		return millis * 1_000_000L;
	}

	private static void clearPending() {
		pendingTarget = null;
		pendingSwingAt = 0L;
		deferredTicks = 0;
	}

	/** Two red squares above and below the crosshair (the crit marks sit left and right). */
	private static void drawRange(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		NexoConfig config = NexoConfig.get();
		LivingEntity target = looked(client);
		if (client.player == null || target == null || !config.rangeIndicatorEnabled() || NexoHudVisibility.hidden()
				|| client.gui.hud.isHidden() || !NexoAimAssist.wanted(target, config.aimAssistTargets())) {
			return;
		}
		int cx = graphics.guiWidth() / 2;
		int cy = graphics.guiHeight() / 2;
		graphics.fill(cx - 1, cy - 12, cx + 2, cy - 9, 0xFFFF5555);
		graphics.fill(cx - 1, cy + 9, cx + 2, cy + 12, 0xFFFF5555);
	}
}
