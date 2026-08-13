package dev.nexoclient.nexomod.full.macro;

/**
 * One state-triggered rule: a condition on the player, and what to do the moment
 * it becomes true.
 *
 * <p>Mutable fields and a no-arg constructor, matching
 * {@link dev.nexoclient.nexomod.macro.NexoMacro} — the editor writes into the
 * object a row is bound to and Gson round-trips it without an adapter.
 *
 * <h2>What a trigger deliberately cannot do</h2>
 *
 * <p>There is no interval, no repeat and no "while" — a rule fires on the
 * <em>edge</em> where its condition flips from false to true, once, and cannot
 * fire again until the condition has gone false and the cooldown has elapsed.
 * The actions available are the ones a macro already has (chat lines and
 * commands) plus switching the selected hotbar slot, which is the same thing the
 * scroll wheel does. Nothing here synthesises a click, a swing or a key press,
 * so no rule can mine, attack or use an item on the player's behalf.
 */
public final class NexoMacroTrigger {
	public enum Condition {
		/** The held item is damageable and its remaining durability has fallen to the threshold. */
		TOOL_LOW,
		/** Health has fallen to the threshold, as a percentage of maximum. */
		HEALTH_LOW,
		/** Food level has fallen to the threshold, out of 20. */
		HUNGER_LOW,
		/** Every slot in the main inventory holds something. */
		INVENTORY_FULL
	}

	/** {@link dev.nexoclient.nexomod.macro.NexoMacro#id} of the macro to run, or empty for none. */
	public String macroId = "";
	public Condition condition = Condition.TOOL_LOW;
	/** Percent for {@code TOOL_LOW}/{@code HEALTH_LOW}, points out of 20 for {@code HUNGER_LOW}, unused otherwise. */
	public int threshold = 10;
	/**
	 * For {@code TOOL_LOW}: also select the healthiest hotbar slot holding the
	 * same kind of item. Only a hotbar selection, never an inventory move.
	 */
	public boolean swapTool = true;
	/** Seconds before this rule may fire again, on top of the condition having to go false first. */
	public int cooldownSeconds = 10;
	public boolean enabled = true;

	public NexoMacroTrigger() {
	}
}
