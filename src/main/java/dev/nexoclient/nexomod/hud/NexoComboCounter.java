package dev.nexoclient.nexomod.hud;

import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.EntityHitResult;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoStyle;

/**
 * Consecutive attacks landed on a target, reset after a few seconds of no
 * hits — the "am I still comboing" number PvP-flavored clients show.
 *
 * <h2>Why "landed" is an approximation</h2>
 *
 * <p>A hit counts as landed when {@link ClientPreAttackCallback} fires while
 * {@link Minecraft#hitResult} is an {@link EntityHitResult} — the same
 * targeting check vanilla itself uses to decide an attack has something to
 * hit — and the swing is at least {@link #CHARGED_THRESHOLD} through its
 * cooldown. That second check is the whole point: vanilla doesn't rate-limit
 * the attack action itself, so holding the button fires an attack attempt
 * every tick regardless of cooldown, and without the gate that alone would
 * run the combo up. It still does not verify the attack actually dealt
 * damage (invulnerability frames and the like aren't accounted for), because
 * that data doesn't reliably reach the client for entities it doesn't own.
 * Good enough for a number to watch while fighting; not a combat-log-accurate
 * counter.
 *
 * <p>Crits are counted alongside the combo, not required for it — the same
 * vanilla eligibility check the client itself uses for crit particles and
 * bonus damage (charged, falling, airborne, not climbing/swimming/riding/
 * blind/sprinting), so a run of ordinary hits still combos, it just won't
 * bump the crit count.
 */
public final class NexoComboCounter implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "combo_counter");
	private static final long TIMEOUT_MS = 3000;
	private static final int NOMINAL_WIDTH = 70;
	private static final int NOMINAL_HEIGHT = 10;
	private static final float CHARGED_THRESHOLD = 0.9f;

	private static final AtomicInteger combo = new AtomicInteger();
	private static final AtomicInteger crits = new AtomicInteger();
	private static volatile long lastHitTime;

	private NexoComboCounter() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ID, new NexoComboCounter());
		ClientPreAttackCallback.EVENT.register((client, player, button) -> {
			if (client.hitResult instanceof EntityHitResult && player.getAttackStrengthScale(1.0f) > CHARGED_THRESHOLD) {
				combo.incrementAndGet();
				if (isCritical(player)) {
					crits.incrementAndGet();
				}
				lastHitTime = System.currentTimeMillis();
			}
			return false;
		});
	}

	/** Vanilla's own crit eligibility check (see the class doc) — not a combo requirement. */
	private static boolean isCritical(LocalPlayer player) {
		return player.fallDistance > 0.0
				&& !player.onGround()
				&& !player.onClimbable()
				&& !player.isInWater()
				&& !player.isPassenger()
				&& !player.isSprinting()
				&& !player.hasEffect(MobEffects.BLINDNESS);
	}

	/** Where this element draws right now — shared by rendering and the layout editor. */
	public static ScreenRectangle resolveBounds(int guiWidth, int guiHeight) {
		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.COMBO);
		float scale = override != null ? override.scale : 1f;
		int width = Math.round(NOMINAL_WIDTH * scale);
		int height = Math.round(NOMINAL_HEIGHT * scale);
		int x = override != null ? override.x : guiWidth / 2 - width / 2;
		int y = override != null ? override.y : guiHeight / 2 + 30;
		return NexoHudBounds.clamp(x, y, width, height, guiWidth, guiHeight);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (NexoHudVisibility.hidden()) {
			return;
		}
		if (!NexoConfig.get().comboCounterEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options.hideGui) {
			return;
		}
		if (System.currentTimeMillis() - lastHitTime > TIMEOUT_MS) {
			combo.set(0);
			crits.set(0);
		}
		int current = combo.get();
		if (current == 0) {
			return;
		}
		int currentCrits = crits.get();

		ScreenRectangle bounds = resolveBounds(graphics.guiWidth(), graphics.guiHeight());
		Component text = currentCrits > 0
				? Component.translatable("nexomod.qol.combo.count.crit", current, currentCrits)
				: Component.translatable("nexomod.qol.combo.count", current);
		int textWidth = client.font.width(text);
		int x = bounds.left() + (bounds.width() - textWidth) / 2;
		int y = bounds.top() + (bounds.height() - client.font.lineHeight) / 2;
		graphics.text(client.font, text, x, y, NexoStyle.TEXT_ACTIVE_ACCENT);
	}
}
