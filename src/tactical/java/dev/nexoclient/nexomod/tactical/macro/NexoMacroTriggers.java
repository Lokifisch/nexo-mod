package dev.nexoclient.nexomod.tactical.macro;

import java.util.HashMap;
import java.util.Map;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import dev.nexoclient.nexomod.macro.NexoMacro;
import dev.nexoclient.nexomod.macro.NexoMacroConfig;
import dev.nexoclient.nexomod.macro.NexoMacroDispatcher;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Evaluates the trigger list once per client tick.
 *
 * <h2>Why this is the full jar and macros are not</h2>
 *
 * <p>The split follows what pulls the trigger. A macro is a key the player
 * pressed and the mod typing what they would have typed, which is convenience;
 * that is why {@code dev.nexoclient.nexomod.macro} is in {@code src/main} and
 * ships in both jars. A rule that acts on world state with no player input is
 * automation, and lives here.
 *
 * <h2>The three guards, and why each one is necessary</h2>
 *
 * <ol>
 * <li><b>Edge, not level.</b> A rule fires on the tick its condition flips from
 *     false to true. Firing while the condition merely <em>is</em> true would
 *     mean one action every tick for as long as a tool stays damaged, which is
 *     the timer-driven automation this feature is explicitly not.</li>
 * <li><b>Cooldown.</b> A condition that oscillates around its threshold — health
 *     regenerating past it and back — would otherwise fire on every crossing.
 *     The rule stays armed only after the condition has gone false again
 *     <em>and</em> the cooldown has elapsed.</li>
 * <li><b>No screen open, player in world.</b> Same rule the keybind dispatcher
 *     uses. It keeps a rule from sending chat while the player is typing chat,
 *     and stops anything happening during the loading screen.</li>
 * </ol>
 *
 * <p>The actions are chat lines, commands, and switching the selected hotbar
 * slot. Nothing here synthesises a click, a swing or a key press, so a rule
 * cannot mine, attack, place, or use an item.
 */
public final class NexoMacroTriggers {
	private static final int HOTBAR_SIZE = 9;

	/** Whether each rule's condition was true last tick, keyed by identity of the rule object. */
	private static final Map<NexoMacroTrigger, Boolean> armed = new HashMap<>();
	/** When each rule last fired, in {@code System.currentTimeMillis()}. */
	private static final Map<NexoMacroTrigger, Long> lastFired = new HashMap<>();

	private NexoMacroTriggers() {
	}

	/** Registered from {@code NexoTacticalFeatures}. */
	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(NexoMacroTriggers::tick);
	}

	private static void tick(Minecraft client) {
		if (!NexoConfig.get().macroTriggersEnabled()) {
			return;
		}
		LocalPlayer player = client.player;
		if (player == null || client.getConnection() == null || client.screen != null) {
			return;
		}

		long now = System.currentTimeMillis();
		for (NexoMacroTrigger trigger : NexoMacroTriggerConfig.get().triggers()) {
			if (!trigger.enabled) {
				continue;
			}
			boolean satisfied = evaluate(player, trigger);
			boolean wasSatisfied = armed.getOrDefault(trigger, Boolean.FALSE);
			armed.put(trigger, satisfied);
			if (!satisfied || wasSatisfied) {
				continue;
			}
			Long previous = lastFired.get(trigger);
			if (previous != null && now - previous < trigger.cooldownSeconds * 1000L) {
				continue;
			}
			lastFired.put(trigger, now);
			run(client, player, trigger);
		}
	}

	private static boolean evaluate(LocalPlayer player, NexoMacroTrigger trigger) {
		return switch (trigger.condition) {
			case TOOL_LOW -> {
				ItemStack held = player.getMainHandItem();
				yield held.isDamageableItem() && remainingPercent(held) <= trigger.threshold;
			}
			case HEALTH_LOW -> {
				float max = player.getMaxHealth();
				yield max > 0.0F && (player.getHealth() / max) * 100.0F <= trigger.threshold;
			}
			case HUNGER_LOW -> player.getFoodData().getFoodLevel() <= trigger.threshold;
			case INVENTORY_FULL -> isInventoryFull(player);
		};
	}

	private static void run(Minecraft client, LocalPlayer player, NexoMacroTrigger trigger) {
		if (trigger.condition == NexoMacroTrigger.Condition.TOOL_LOW && trigger.swapTool) {
			selectReplacement(player);
		}
		NexoMacro macro = findMacro(trigger.macroId);
		if (macro != null) {
			NexoMacroDispatcher.runNow(client, macro);
		}
	}

	/**
	 * Selects the hotbar slot holding the healthiest item of the same kind as
	 * the one about to break.
	 *
	 * <p>Deliberately only {@code Inventory.setSelectedSlot}: that is the same
	 * client-side state the scroll wheel and the number keys write, and
	 * {@code MultiPlayerGameMode.tick()} syncs it to the server with the same
	 * {@code ServerboundSetCarriedItemPacket} it would send for a scroll —
	 * verified in the 26.1 bytecode, where {@code ensureHasSentCarriedItem} is
	 * the first call in that tick. Moving items between slots would be a
	 * container interaction, which is a different thing entirely and is not done
	 * here.
	 */
	private static void selectReplacement(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		ItemStack held = player.getMainHandItem();
		int current = inventory.getSelectedSlot();
		int best = -1;
		int bestRemaining = remainingPercent(held);

		for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
			if (slot == current) {
				continue;
			}
			ItemStack candidate = inventory.getItem(slot);
			// Same item, so a pickaxe is replaced by a pickaxe and never by
			// whatever else happens to be in the hotbar. Compared by Item
			// identity rather than ItemStack.is(...): 26.1 only kept the
			// Predicate<Holder<Item>> overload, and Items are singletons.
			if (candidate.isEmpty() || candidate.getItem() != held.getItem()) {
				continue;
			}
			int remaining = candidate.isDamageableItem() ? remainingPercent(candidate) : 100;
			if (remaining > bestRemaining) {
				bestRemaining = remaining;
				best = slot;
			}
		}
		if (best >= 0) {
			inventory.setSelectedSlot(best);
		}
	}

	private static boolean isInventoryFull(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		for (ItemStack stack : inventory.getNonEquipmentItems()) {
			if (stack.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private static int remainingPercent(ItemStack stack) {
		int max = stack.getMaxDamage();
		if (max <= 0) {
			return 100;
		}
		return Math.clamp(((max - stack.getDamageValue()) * 100) / max, 0, 100);
	}

	private static NexoMacro findMacro(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		for (NexoMacro macro : NexoMacroConfig.get().macros()) {
			if (id.equals(macro.id)) {
				return macro;
			}
		}
		return null;
	}
}
