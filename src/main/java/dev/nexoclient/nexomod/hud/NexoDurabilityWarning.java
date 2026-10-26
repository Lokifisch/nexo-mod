package dev.nexoclient.nexomod.hud;

import java.util.EnumSet;
import java.util.Set;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * One chime and an actionbar line when a worn armour piece or the held tool
 * drops to {@link #WARN_PERCENT} durability. Re-arms per slot once the item is
 * above {@link #REARM_PERCENT} again or swapped for something undamageable, so
 * a repaired or replaced piece warns again later but a worn one doesn't nag.
 * Plain display of the player's own gear — the Tactical macro trigger is the
 * automation version of this.
 */
public final class NexoDurabilityWarning {
	private static final int WARN_PERCENT = 10;
	private static final int REARM_PERCENT = 15;
	private static final EquipmentSlot[] SLOTS = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND
	};

	private static final Set<EquipmentSlot> warned = EnumSet.noneOf(EquipmentSlot.class);

	private NexoDurabilityWarning() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(NexoDurabilityWarning::tick);
	}

	private static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || !NexoConfig.get().durabilityWarningEnabled() || client.level == null
				|| client.level.getGameTime() % 10 != 0) {
			return;
		}
		for (EquipmentSlot slot : SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			if (!stack.isDamageableItem()) {
				warned.remove(slot);
				continue;
			}
			int left = 100 - Math.round(100.0F * stack.getDamageValue() / stack.getMaxDamage());
			if (left > REARM_PERCENT) {
				warned.remove(slot);
			} else if (left <= WARN_PERCENT && warned.add(slot)) {
				player.sendOverlayMessage(Component.translatable("nexomod.durability.warning", stack.getHoverName()));
				client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.0F));
			}
		}
	}
}
