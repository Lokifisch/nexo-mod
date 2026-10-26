package dev.nexoclient.nexomod.tactical.combat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.hud.NexoComboCounter;
import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * The tick-driven combat helpers: auto totem, hitbox display (see {@code HitboxDisplayMixin}), threat warning,
 * and the crit indicator's HUD element. Everything the player does not trigger
 * with a key lives in the Tactical jar, and each of these is off by default.
 *
 * <p>Auto totem is the one place Tactical moves items between slots for the
 * player (the macro triggers deliberately never do): a vanilla
 * {@code SWAP}-with-off-hand container click, the same packet the F key sends
 * from an open inventory.
 */
public final class NexoCombatAssist {
	private static final Identifier CRIT_ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "crit_indicator");
	private static final int THREAT_RANGE = 12;
	/** Ticks between auto-totem swaps, so a server that refuses one is not spammed. */
	private static final int TOTEM_COOLDOWN_TICKS = 4;
	private static final int OFFHAND_BUTTON = 40;

	private static final long WARN_COOLDOWN_MS = 10_000;

	private static final Set<UUID> armedNearby = new HashSet<>();
	private static final java.util.Map<UUID, Long> lastWarned = new java.util.HashMap<>();
	private static int totemCooldown;
	/** Ticks the off-hand has been empty, and the delay this pop has to wait out. */
	private static int emptyTicks;
	private static int swapDelayTicks;

	private NexoCombatAssist() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(NexoCombatAssist::tick);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, CRIT_ID, NexoCombatAssist::drawCrit);
	}

	private static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			armedNearby.clear();
			return;
		}
		NexoConfig config = NexoConfig.get();
		if (config.threatWarningEnabled()) {
			threatWarning(client, player);
		} else {
			armedNearby.clear();
		}
		if (totemCooldown > 0) {
			totemCooldown--;
		} else if (config.autoTotemEnabled()) {
			autoTotem(client, player);
		}
	}

	private static void autoTotem(Minecraft client, LocalPlayer player) {
		if (client.gui.screen() != null || client.gameMode == null || player.isSpectator()
				|| !player.getOffhandItem().isEmpty()) {
			emptyTicks = 0;
			swapDelayTicks = 0;
			return;
		}
		// The swap must not land on the same tick the totem pops: a container click
		// arriving 0-50 ms after the off-hand empties, every single time, is the
		// signature auto-totem checks look for. 100-300 ms is a human who noticed
		// and pressed F.
		if (swapDelayTicks == 0) {
			swapDelayTicks = 2 + ThreadLocalRandom.current().nextInt(5);
			emptyTicks = 0;
		}
		if (++emptyTicks < swapDelayTicks) {
			return;
		}
		for (int slot = 0; slot < 36; slot++) {
			if (player.getInventory().getItem(slot).is(Items.TOTEM_OF_UNDYING)) {
				// Inventory index -> menu slot: hotbar is 36-44, main inventory 9-35 map to themselves.
				int menuSlot = slot < 9 ? slot + 36 : slot;
				client.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, OFFHAND_BUTTON,
						ContainerInput.SWAP, player);
				totemCooldown = TOTEM_COOLDOWN_TICKS;
				emptyTicks = 0;
				swapDelayTicks = 0;
				return;
			}
		}
	}

	private static void threatWarning(Minecraft client, LocalPlayer player) {
		Set<UUID> now = new HashSet<>();
		for (Player other : client.level.players()) {
			if (other == player || other.isSpectator() || !isArmed(other)) {
				continue;
			}
			double distance = other.distanceTo(player);
			if (distance > THREAT_RANGE) {
				continue;
			}
			now.add(other.getUUID());
			long time = System.currentTimeMillis();
			if (!armedNearby.contains(other.getUUID()) && time - lastWarned.getOrDefault(other.getUUID(), 0L) > WARN_COOLDOWN_MS) {
				lastWarned.put(other.getUUID(), time);
				player.sendOverlayMessage(Component.translatable("nexomod.threat.warning",
						other.getName(), Math.round(distance)));
			}
		}
		armedNearby.clear();
		armedNearby.addAll(now);
	}

	private static boolean isArmed(Player other) {
		ItemStack held = other.getMainHandItem();
		return held.is(ItemTags.SWORDS) || held.is(ItemTags.AXES) || held.is(ItemTags.SPEARS)
				|| held.is(Items.MACE) || held.is(Items.BOW) || held.is(Items.CROSSBOW) || held.is(Items.TRIDENT);
	}

	/** Two green squares flanking the crosshair while a charged swing would crit. */
	private static void drawCrit(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || !NexoConfig.get().critIndicatorEnabled() || NexoHudVisibility.hidden()
				|| Minecraft.getInstance().gui.hud.isHidden()
				|| !(Minecraft.getInstance().hitResult instanceof net.minecraft.world.phys.EntityHitResult hit
					&& hit.getEntity() instanceof net.minecraft.world.entity.LivingEntity)
				|| player.getAttackStrengthScale(0.0F) < 0.9F || !NexoComboCounter.isCritical(player)) {
			return;
		}
		int cx = graphics.guiWidth() / 2;
		int cy = graphics.guiHeight() / 2;
		graphics.fill(cx - 12, cy - 1, cx - 9, cy + 2, 0xFF55FF55);
		graphics.fill(cx + 9, cy - 1, cx + 12, cy + 2, 0xFF55FF55);
	}
}
