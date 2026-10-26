package dev.nexoclient.nexomod.tactical.combat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.EntityHitResult;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.tactical.freecam.NexoFreecam;

/**
 * Auto Shield Break. When the crosshair is on a target who is blocking with a
 * shield, it swaps to an axe and hits them — an axe hit has a chance to disable
 * a shield — then swaps back to whatever the player was holding.
 *
 * <p>Reads the vanilla crosshair pick ({@code Minecraft#hitResult}), which is
 * already limited to attack reach, so "on a target" means "in reach" — the same
 * mechanism the trigger bot uses. It acts with input the player did not give,
 * which is why it lives in the Tactical jar. Off by default.
 *
 * <p>The hotbar slot is put back on the same tick, so the player is never left
 * holding the axe; the swing is throttled by {@code shieldBreakerCooldown}.
 */
public final class NexoShieldBreaker {
	private static int cooldown;

	private NexoShieldBreaker() {
	}

	public static void register() {
		// START, not END: the swing must land against the target checked this tick.
		ClientTickEvents.START_CLIENT_TICK.register(NexoShieldBreaker::tick);
	}

	private static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			cooldown = 0;
			return;
		}
		NexoConfig config = NexoConfig.get();
		if (!config.shieldBreakerEnabled() || client.gui.screen() != null || !player.isAlive()
				|| player.isSpectator() || player.isUsingItem() || NexoFreecam.active()
				|| client.gameMode == null || client.getConnection() == null) {
			cooldown = 0;
			return;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		if (!(client.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity target)) {
			return;
		}
		if (target == player || target == player.getVehicle() || !target.isAlive() || target.isSpectator()
				|| target instanceof ArmorStand || !target.isBlocking()) {
			return;
		}
		int axe = axeSlot(player);
		if (axe < 0) {
			return;
		}
		int saved = player.getInventory().getSelectedSlot();
		NexoAuraUtil.selectSlot(player, axe);
		client.gameMode.attack(player, target);
		NexoAuraUtil.selectSlot(player, saved);
		cooldown = config.shieldBreakerCooldown();
	}

	private static int axeSlot(LocalPlayer player) {
		for (int i = 0; i < 9; i++) {
			if (player.getInventory().getItem(i).is(ItemTags.AXES)) {
				return i;
			}
		}
		return -1;
	}
}
