package dev.nexoclient.nexomod.tactical.combat;

/**
 * The arithmetic behind an explosion's damage, mirroring the server's
 * {@code ExplosionDamageCalculator} and {@code CombatRules} so a candidate
 * placement can be scored for self-damage before a crystal is placed.
 *
 * <p>No Minecraft imports on purpose — this is plain arithmetic and can be
 * unit-tested (or {@code javac}'d) without a game classpath, exactly like
 * {@code NexoMouseModel}. Self-damage here deliberately assumes zero toughness
 * and no protection enchantment: it over-estimates the damage the player will
 * take, so a placement that clears the safety threshold is safe to make.
 */
public final class NexoExplosionMath {
	private NexoExplosionMath() {
	}

	/**
	 * Raw explosion damage in half-hearts, before armour and enchantments —
	 * the same number {@code getEntityDamageAmount} produces. {@code power} is
	 * the explosion power (6.0 for an end crystal); {@code seenPercent} is the
	 * fraction of the entity the blast reaches (0..1).
	 */
	public static float rawDamage(float power, double distanceSqr, float seenPercent) {
		float size = power * 2.0F;
		double distance = Math.sqrt(distanceSqr) / (double) size;
		double g = (1.0D - distance) * (double) seenPercent;
		float damage = (float) ((g * g + g) / 2.0D * 7.0D * (double) size + 1.0D);
		return Math.max(0.0F, damage);
	}

	/**
	 * Armour reduction, mirroring {@code CombatRules.getDamageAfterAbsorb} with
	 * toughness treated as 0 and no weapon-item modifier. Returns the damage
	 * that actually reaches health.
	 */
	public static float afterArmor(float amount, float armorValue) {
		float f = 2.0F; // 2 + toughness/4, toughness assumed 0
		float g = clamp(armorValue - amount / f, armorValue * 0.2F, 20.0F);
		return amount * (1.0F - g / 25.0F);
	}

	private static float clamp(float value, float min, float max) {
		return value < min ? min : Math.min(value, max);
	}
}
