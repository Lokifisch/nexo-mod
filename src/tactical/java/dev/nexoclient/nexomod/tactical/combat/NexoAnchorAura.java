package dev.nexoclient.nexomod.tactical.combat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.tactical.freecam.NexoFreecam;

/**
 * Auto Anchor. When the player places a respawn anchor, this turns to it,
 * charges it with glowstone, switches back to the anchor, and clicks it so it
 * explodes — the place, charge and trigger a player would perform by hand,
 * driven off world state. Off by default.
 *
 * <p>The anchor to detonate comes from {@link #onAnchorPlaced}, which a mixin on
 * {@code MultiPlayerGameMode.useItemOn} calls the moment the player places one.
 * It deliberately does <em>not</em> scan for anchors, so a pile of pre-existing
 * anchors in reach is ignored — only the one just placed is touched.
 *
 * <p>Each click is preceded by a look tick: the turn is applied one tick, the
 * click goes out the next. Since rotation syncs through the vanilla movement
 * packet a tick behind, a same-tick turn-and-click would sometimes fire while
 * the server still thought the player faced away. Respawn anchors only explode
 * outside the Nether, so the module refuses to act there.
 */
public final class NexoAnchorAura {
	private static final int PENDING_TIMEOUT_TICKS = 12;

	private enum Stage {
		IDLE, LOOK, CHARGE, TRIGGER
	}

	private static Stage stage = Stage.IDLE;
	private static BlockPos anchorPos;
	private static int waitTicks;
	private static int cooldown;
	/** Hotbar slot to return to once the anchor is detonated; -1 while idle. */
	private static int savedSlot = -1;

	private NexoAnchorAura() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(NexoAnchorAura::tick);
	}

	/**
	 * Called from the placement mixin the moment the player puts a respawn
	 * anchor down. Starts a look-charge-trigger cycle on exactly this block.
	 */
	public static void onAnchorPlaced(LocalPlayer player, BlockPos pos) {
		anchorPos = pos;
		savedSlot = player.getInventory().getSelectedSlot();
		stage = Stage.LOOK;
		waitTicks = 0;
		cooldown = 0;
	}

	private static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			reset(null);
			return;
		}
		NexoConfig config = NexoConfig.get();
		if (!config.anchorAuraEnabled() || client.gui.screen() != null || !player.isAlive() || player.isSpectator()
				|| player.isUsingItem() || NexoFreecam.active() || client.gameMode == null || client.getConnection() == null) {
			reset(player);
			return;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		if (Level.NETHER.equals(client.level.dimension())) {
			reset(player);
			return;
		}
		switch (stage) {
			case IDLE -> {
			}
			case LOOK -> look(client, player, config);
			case CHARGE -> charge(client, player, config);
			case TRIGGER -> trigger(client, player, config);
		}
	}

	private static void look(Minecraft client, LocalPlayer player, NexoConfig config) {
		// Face the anchor this tick; the click runs after the cooldown so the
		// rotation has reached the server first.
		NexoAuraUtil.lookAt(client, player, NexoAuraUtil.topFaceCenter(anchorPos));
		stage = Stage.CHARGE;
		cooldown = config.anchorAuraDelay();
	}

	private static void charge(Minecraft client, LocalPlayer player, NexoConfig config) {
		if (!client.level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
			// The server hasn't confirmed the placement yet (or it was destroyed).
			if (++waitTicks > PENDING_TIMEOUT_TICKS) {
				reset(player);
			}
			return;
		}
		int slot = NexoAuraUtil.findSlot(player, Items.GLOWSTONE);
		if (slot < 0) {
			reset(player);
			return;
		}
		NexoAuraUtil.selectSlot(player, slot);
		client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, NexoAuraUtil.topFaceHit(anchorPos));
		stage = Stage.TRIGGER;
		cooldown = config.anchorAuraDelay();
	}

	private static void trigger(Minecraft client, LocalPlayer player, NexoConfig config) {
		if (!client.level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
			reset(player);
			return;
		}
		// Switch back to the anchor itself (or any non-glowstone hand) and click.
		int slot = NexoAuraUtil.findSlot(player, Items.RESPAWN_ANCHOR);
		if (slot < 0) {
			slot = NexoAuraUtil.nonFuelSlot(player);
		}
		if (slot < 0) {
			reset(player);
			return;
		}
		NexoAuraUtil.selectSlot(player, slot);
		client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, NexoAuraUtil.topFaceHit(anchorPos));
		reset(player);
		cooldown = config.anchorAuraDelay();
	}

	private static void reset(LocalPlayer player) {
		if (player != null && savedSlot >= 0) {
			NexoAuraUtil.selectSlot(player, savedSlot);
		}
		stage = Stage.IDLE;
		anchorPos = null;
		waitTicks = 0;
		cooldown = 0;
		savedSlot = -1;
	}
}
