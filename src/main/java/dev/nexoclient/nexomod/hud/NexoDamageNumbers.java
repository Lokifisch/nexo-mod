package dev.nexoclient.nexomod.hud;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Floating combat text: the amount of health an entity just lost, drifting up
 * from where it was standing and fading out.
 *
 * <h2>Where the number comes from</h2>
 *
 * <p>There is no client event for "this entity took damage" — the damage
 * value never reaches the client at all. What does reach it is each tracked
 * entity's <em>health</em>, synced through entity data. So this remembers
 * every visible {@link LivingEntity}'s health from the previous tick and emits
 * a number when it drops. Consequences worth knowing:
 * <ul>
 *   <li>Absorption hearts and armour are already accounted for — this is real
 *   health lost, not damage dealt before mitigation.</li>
 *   <li>An entity that leaves render distance and comes back damaged shows
 *   nothing, because {@link #lastHealth} forgets it on the way out rather than
 *   inventing one huge number on the way back in.</li>
 *   <li>Healing is ignored. A rising-health indicator is a different feature
 *   and would fire constantly from natural regeneration.</li>
 * </ul>
 *
 * <p>Sampled on the client tick rather than per frame: health only changes on
 * a tick, so a per-frame comparison would do the same work twenty times over
 * and still find the same drop.
 */
public final class NexoDamageNumbers {
	/** Below this, a drop is rounding noise rather than a hit worth annotating. */
	private static final float MIN_DAMAGE = 0.05F;
	private static final long LIFETIME_MILLIS = 1200L;
	/** Blocks the number drifts upward over its whole lifetime. */
	private static final double RISE_BLOCKS = 0.75;
	private static final float TEXT_SCALE = 0.7F;
	/** Cap on simultaneous numbers, so a crowd fight can't grow this without bound. */
	private static final int MAX_ACTIVE = 40;

	private static final int COLOR_DEALT = 0xFFFF5555;
	private static final int COLOR_TAKEN = 0xFFFFAA33;

	private static final Int2FloatMap lastHealth = new Int2FloatOpenHashMap();
	private static final List<Number> active = new ArrayList<>();

	private record Number(String text, Vec3 origin, long bornMillis, int color) {
	}

	private NexoDamageNumbers() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
		LevelRenderEvents.BEFORE_GIZMOS.register(context -> render());
		// A remembered health bar from another world would fire a bogus number the
		// first time an entity with a recycled id shows up.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());
	}

	private static void clear() {
		lastHealth.clear();
		synchronized (active) {
			active.clear();
		}
	}

	private static void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		if (player == null || level == null) {
			return;
		}
		if (!NexoConfig.get().damageNumbersEnabled()) {
			// Keep the health map current even while off, or turning it on
			// mid-fight would report the total damage taken since it was disabled.
			refreshWithoutEmitting(level);
			return;
		}

		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || living.isRemoved()) {
				continue;
			}
			int id = living.getId();
			float health = living.getHealth();
			if (lastHealth.containsKey(id)) {
				float lost = lastHealth.get(id) - health;
				if (lost >= MIN_DAMAGE) {
					emit(living, lost, living == player ? COLOR_TAKEN : COLOR_DEALT);
				}
			}
			lastHealth.put(id, health);
		}
		forgetGoneEntities(level);
	}

	private static void refreshWithoutEmitting(ClientLevel level) {
		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof LivingEntity living && !living.isRemoved()) {
				lastHealth.put(living.getId(), living.getHealth());
			}
		}
		forgetGoneEntities(level);
	}

	/** Drops ids the client is no longer tracking, so the map can't grow for a whole session. */
	private static void forgetGoneEntities(ClientLevel level) {
		if (lastHealth.isEmpty()) {
			return;
		}
		lastHealth.keySet().removeIf(id -> level.getEntity(id) == null);
	}

	private static void emit(LivingEntity target, float lost, int color) {
		Vec3 origin = target.position().add(0, target.getBbHeight() + 0.4, 0);
		// One decimal, and no trailing ".0" for the common whole-heart case.
		String text = lost >= 10F || lost == Math.rint(lost)
				? String.valueOf(Math.round(lost))
				: String.format("%.1f", lost);
		synchronized (active) {
			if (active.size() >= MAX_ACTIVE) {
				active.removeFirst();
			}
			active.add(new Number(text, origin, System.currentTimeMillis(), color));
		}
	}

	private static void render() {
		if (NexoHudVisibility.hidden() || !NexoConfig.get().damageNumbersEnabled()) {
			return;
		}
		long now = System.currentTimeMillis();
		synchronized (active) {
			Iterator<Number> iterator = active.iterator();
			while (iterator.hasNext()) {
				Number number = iterator.next();
				float age = (now - number.bornMillis()) / (float) LIFETIME_MILLIS;
				if (age >= 1F) {
					iterator.remove();
					continue;
				}
				Vec3 at = number.origin().add(0, RISE_BLOCKS * age, 0);
				// Fade by alpha rather than by dropping it early, so a number that
				// is about to expire visibly stops mattering instead of blinking out.
				int alpha = (int) ((1F - age) * 255) << 24;
				int color = alpha | (number.color() & 0x00FFFFFF);
				Gizmos.billboardText(number.text(), at,
						TextGizmo.Style.forColorAndCentered(color).withScale(TEXT_SCALE));
			}
		}
	}
}
