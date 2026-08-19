package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import dev.nexoclient.nexomod.hud.NexoFadingLogHud;

/**
 * Feeds the pickup log HUD. {@code ClientPacketListener.handleTakeItemEntity}
 * is the packet the server sends when any item entity is collected — there is
 * no Fabric event for it, and {@code PlayerPickItemEvents} is pick-block /
 * pick-entity (middle-click), a different mechanic entirely.
 *
 * <p>HEAD, before vanilla's own handling removes the item entity from the
 * level — the {@link ItemStack} is only readable while it's still there.
 * Only the local player's own pickups are logged; the packet also fires for
 * other nearby players collecting items.
 */
@Mixin(ClientPacketListener.class)
public class PickupLogMixin {
	@Inject(
			method = "handleTakeItemEntity(Lnet/minecraft/network/protocol/game/ClientboundTakeItemEntityPacket;)V",
			at = @At("HEAD"))
	private void nexomod$capturePickup(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null || packet.getPlayerId() != client.player.getId()) {
			return;
		}
		Entity entity = client.level.getEntity(packet.getItemId());
		if (!(entity instanceof ItemEntity itemEntity)) {
			return;
		}
		ItemStack stack = itemEntity.getItem();
		if (stack.isEmpty()) {
			return;
		}
		NexoFadingLogHud.PICKUPS.record(stack.getHoverName().copy().append(" x" + packet.getAmount()));
	}
}
