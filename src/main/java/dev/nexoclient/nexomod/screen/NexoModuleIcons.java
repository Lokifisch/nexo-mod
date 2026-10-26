package dev.nexoclient.nexomod.screen;

import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The icon each QoL-menu card shows: a vanilla item, picked by the module's
 * translation key. Items rather than drawn glyphs because they are already in
 * every player's game, read instantly as Minecraft, and cost no asset to ship.
 *
 * <p>Keyed by the name's translation key so neither the menu's long list of
 * {@code addRow} calls nor Tactical's {@link NexoQolModules} registrations have
 * to carry an icon argument. A module missing from the map gets
 * {@link #FALLBACK} rather than an empty card.
 */
public final class NexoModuleIcons {
	private NexoModuleIcons() {}

	private static final Item FALLBACK = Items.COMPARATOR;

	private static final Map<String, Item> BY_KEY = Map.ofEntries(
			Map.entry("nexomod.qol.keystrokes", Items.OAK_BUTTON),
			Map.entry("nexomod.qol.cps", Items.CLOCK),
			Map.entry("nexomod.settings.armorHud.enabled", Items.DIAMOND_CHESTPLATE),
			Map.entry("nexomod.settings.armorBar.enabled", Items.IRON_CHESTPLATE),
			Map.entry("nexomod.qol.potatoMode", Items.POTATO),
			Map.entry("nexomod.stats.title", Items.OBSERVER),
			Map.entry("nexomod.qol.potion", Items.POTION),
			Map.entry("nexomod.qol.combo", Items.IRON_SWORD),
			Map.entry("nexomod.qol.targetHud", Items.TARGET),
			Map.entry("nexomod.qol.reach", Items.BOW),
			Map.entry("nexomod.qol.totemPops", Items.TOTEM_OF_UNDYING),
			Map.entry("nexomod.qol.hitOverlay", Items.REDSTONE),
			Map.entry("nexomod.qol.durability", Items.ANVIL),
			Map.entry("nexomod.qol.autoReconnect", Items.ENDER_PEARL),
			Map.entry("nexomod.qol.stackCount", Items.BUNDLE),
			Map.entry("nexomod.qol.containerSearch", Items.NAME_TAG),
			Map.entry("nexomod.qol.actionbarLog", Items.PAPER),
			Map.entry("nexomod.qol.pickupLog", Items.HOPPER),
			Map.entry("nexomod.qol.chat", Items.WRITABLE_BOOK),
			Map.entry("nexomod.qol.inventoryHud", Items.CHEST),
			Map.entry("nexomod.qol.miniPlayer", Items.JUKEBOX),
			Map.entry("nexomod.qol.damageNumbers", Items.GOLDEN_SWORD),
			Map.entry("nexomod.qol.hudCleaner", Items.SPONGE),
			Map.entry("nexomod.qol.toggleSprint", Items.SUGAR),
			Map.entry("nexomod.qol.toggleSneak", Items.LEATHER_BOOTS),
			Map.entry("nexomod.qol.screenshotCopy", Items.PAINTING),
			Map.entry("nexomod.qol.zoom", Items.SPYGLASS),
			Map.entry("nexomod.qol.crosshair", Items.ARROW),
			Map.entry("nexomod.qol.noRender", Items.BARRIER),
			// Tactical's modules, registered through NexoQolModules.
			Map.entry("nexomod.qol.chunkBorder", Items.FILLED_MAP),
			Map.entry("nexomod.qol.tntTimer", Items.TNT),
			Map.entry("nexomod.qol.lightOverlay", Items.TORCH),
			Map.entry("nexomod.qol.freecam", Items.ENDER_EYE),
			Map.entry("nexomod.qol.aimAssist", Items.CROSSBOW),
			Map.entry("nexomod.qol.rangeIndicator", Items.RECOVERY_COMPASS),
			Map.entry("nexomod.qol.triggerBot", Items.REPEATER),
			Map.entry("nexomod.qol.fullbright", Items.GLOWSTONE_DUST),
			Map.entry("nexomod.qol.knockback", Items.STICK),
			Map.entry("nexomod.qol.hitboxes", Items.GLASS),
			Map.entry("nexomod.qol.critIndicator", Items.BLAZE_ROD),
			Map.entry("nexomod.qol.wTap", Items.ARMOR_STAND),
			Map.entry("nexomod.qol.threat", Items.BELL),
			Map.entry("nexomod.qol.autoTotem", Items.TOTEM_OF_UNDYING),
			Map.entry("nexomod.qol.crystalAura", Items.END_CRYSTAL),
			Map.entry("nexomod.qol.anchorAura", Items.RESPAWN_ANCHOR),
			Map.entry("nexomod.qol.shieldBreaker", Items.IRON_AXE),
			Map.entry("nexomod.qol.oreHighlight", Items.DIAMOND_ORE),
			Map.entry("nexomod.qol.fallWarning", Items.FEATHER),
			Map.entry("nexomod.qol.playerRadar", Items.COMPASS),
			Map.entry("nexomod.qol.combatLog", Items.WRITTEN_BOOK),
			Map.entry("nexomod.qol.serverView", Items.ITEM_FRAME),
			Map.entry("nexomod.qol.trajectory", Items.SNOWBALL));

	/** The icon for a module named {@code name}. */
	public static ItemStack forName(Component name) {
		Item item = name.getContents() instanceof TranslatableContents translatable
				? BY_KEY.getOrDefault(translatable.getKey(), FALLBACK)
				: FALLBACK;
		return new ItemStack(item);
	}
}
