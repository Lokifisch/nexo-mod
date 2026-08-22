package dev.nexoclient.nexomod.hud;

import java.util.EnumMap;
import java.util.Map;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The part of the armour bar that has to remember things between frames.
 *
 * <p>{@link NexoArmorBar} can read everything else off the player in the frame
 * it draws — how much armour, what it is made of, what it is enchanted with.
 * Three effects cannot work that way, because each is about a <em>change</em>:
 * a hit that just landed, a repair that just happened, a piece that is about to
 * break. Those need a previous tick to compare against, and this holds it.
 *
 * <h2>No mixins, and no packet handling</h2>
 *
 * <p>The damage type reaches the client on its own: the server sends a damage
 * event packet, {@code ClientPacketListener} hands it to
 * {@code Entity.handleDamageEvent}, and {@code LivingEntity} stores it where
 * {@link LocalPlayer#getLastDamageSource()} can read it for the next forty
 * ticks. So "what hit me" needs nothing but a tick handler noticing that
 * {@code hurtTime} went up.
 *
 * <p>Sampled on the client tick rather than per frame, like
 * {@link NexoDamageNumbers}: none of these change more than twenty times a
 * second, and a per-frame comparison would do the same work several times over
 * and find the same answer.
 */
public final class NexoArmorBarEffects {
	/** How long a damage reaction lasts, and how much of that it shakes for. */
	private static final long FEEDBACK_TICKS = 10L;
	private static final long SHAKE_TICKS = 6L;
	/** A repair flash: four phases of this many ticks, lit on the first and third. */
	private static final long REPAIR_PHASE_TICKS = 3L;
	private static final long REPAIR_TICKS = REPAIR_PHASE_TICKS * 4L;
	private static final int REPAIR_ALPHA = 0xB0;

	/** Damaged this far or worse is "about to break". */
	private static final float NEARLY_BROKEN = 0.92F;

	private static final int NO_COLOR = 0;
	private static final int FIRE = 0xFFFF8A2B;
	private static final int EXPLOSION = 0xFFFFE066;
	private static final int PROJECTILE = 0xFF8FD3FF;
	private static final int FALL = 0xFFC0A090;
	private static final int GENERIC = 0xFFFFFFFF;

	private static final EquipmentSlot[] ARMOR = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	private static final Map<EquipmentSlot, Item> lastItem = new EnumMap<>(EquipmentSlot.class);
	private static final Map<EquipmentSlot, Integer> lastDamage = new EnumMap<>(EquipmentSlot.class);

	private static int lastHurtTime;
	private static long feedbackAt = Long.MIN_VALUE;
	private static int feedbackColor = NO_COLOR;
	private static long repairAt = Long.MIN_VALUE;

	private NexoArmorBarEffects() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client.player));
		// Remembered durabilities from another world would read as a repair the
		// first time this player's own armour was sampled.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());
	}

	private static void clear() {
		lastItem.clear();
		lastDamage.clear();
		lastHurtTime = 0;
		feedbackAt = Long.MIN_VALUE;
		feedbackColor = NO_COLOR;
		repairAt = Long.MIN_VALUE;
	}

	/** A tick count that keeps running while the game is paused or hitching. */
	static long ticks() {
		return System.currentTimeMillis() / 50L;
	}

	private static void tick(LocalPlayer player) {
		if (player == null) {
			return;
		}
		sampleDamage(player);
		sampleRepairs(player);
	}

	/**
	 * A <em>rise</em> in {@code hurtTime} is the tick a hit landed. Comparing
	 * against {@code hurtDuration} instead would misfire: the two are equal on
	 * the tick of the hit and again on the tick after, so a second hit landing
	 * during the flash would go unnoticed.
	 */
	private static void sampleDamage(LocalPlayer player) {
		int hurtTime = player.hurtTime;
		if (hurtTime > lastHurtTime) {
			int color = classify(player.getLastDamageSource());
			if (color != NO_COLOR) {
				feedbackColor = color;
				feedbackAt = ticks();
			}
		}
		lastHurtTime = hurtTime;
	}

	/**
	 * Armour that lost damage since last tick was repaired — by Mending, an
	 * anvil, or a grindstone. The item identity is checked alongside the value
	 * because swapping in a fresher piece also lowers the number, and calling
	 * that a repair would flash the bar every time you changed armour.
	 */
	private static void sampleRepairs(LocalPlayer player) {
		for (EquipmentSlot slot : ARMOR) {
			ItemStack stack = player.getItemBySlot(slot);
			Item item = stack.isEmpty() ? null : stack.getItem();
			int damage = stack.isEmpty() ? 0 : stack.getDamageValue();

			Item previousItem = lastItem.get(slot);
			Integer previousDamage = lastDamage.get(slot);
			if (item != null && item == previousItem && previousDamage != null && damage < previousDamage) {
				repairAt = ticks();
			}

			if (item == null) {
				lastItem.remove(slot);
				lastDamage.remove(slot);
			} else {
				lastItem.put(slot, item);
				lastDamage.put(slot, damage);
			}
		}
	}

	/**
	 * What colour to flash the bar for the hit that just landed, or
	 * {@code 0} for none.
	 *
	 * <p>Damage tagged {@code BYPASSES_ARMOR} — starvation, drowning, the void,
	 * most magic — returns nothing at all, and that is the point rather than an
	 * omission. Flashing the armour bar for damage the armour could not have
	 * stopped would claim it helped.
	 */
	static int feedbackColor(long ticks) {
		return ticks - feedbackAt < FEEDBACK_TICKS ? feedbackColor : NO_COLOR;
	}

	/** A one-pixel jolt for the first few ticks of a hit, decaying to nothing. */
	static int shake(long ticks) {
		long since = ticks - feedbackAt;
		if (since < 0 || since >= SHAKE_TICKS || feedbackColor == NO_COLOR) {
			return 0;
		}
		return (since % 2 == 0) ? 1 : -1;
	}

	/** Alpha of the repair flash, 0 when it is not running or is between blinks. */
	static int repairAlpha(long ticks) {
		long since = ticks - repairAt;
		if (since < 0 || since >= REPAIR_TICKS) {
			return 0;
		}
		return (since / REPAIR_PHASE_TICKS) % 2 == 0 ? REPAIR_ALPHA : 0;
	}

	static boolean nearlyBroken(ItemStack stack) {
		if (stack.isEmpty() || !stack.isDamageableItem() || stack.getMaxDamage() <= 0) {
			return false;
		}
		return stack.getDamageValue() / (float) stack.getMaxDamage() >= NEARLY_BROKEN;
	}

	private static int classify(DamageSource source) {
		if (source == null) {
			return GENERIC;
		}
		if (source.is(DamageTypeTags.BYPASSES_ARMOR)) {
			return NO_COLOR;
		}
		if (source.is(DamageTypeTags.IS_FIRE)) {
			return FIRE;
		}
		if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			return EXPLOSION;
		}
		if (source.is(DamageTypeTags.IS_PROJECTILE)) {
			return PROJECTILE;
		}
		if (source.is(DamageTypeTags.IS_FALL)) {
			return FALL;
		}
		return GENERIC;
	}
}
