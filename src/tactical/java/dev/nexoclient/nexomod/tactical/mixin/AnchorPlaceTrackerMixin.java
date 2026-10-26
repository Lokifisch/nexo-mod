package dev.nexoclient.nexomod.tactical.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

import dev.nexoclient.nexomod.tactical.combat.NexoAnchorAura;

/**
 * Detects when the player places a respawn anchor, so Auto Anchor only
 * detonates the anchor just placed instead of every anchor within reach.
 *
 * <p>The test is: the held item is an anchor and the clicked block is not
 * already an anchor. The latter excludes the aura's own trigger click, which
 * clicks the anchor block itself — so only genuine placements reach
 * {@link NexoAnchorAura#onAnchorPlaced}.
 */
@Mixin(MultiPlayerGameMode.class)
public class AnchorPlaceTrackerMixin {
	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void nexomod$trackAnchorPlace(LocalPlayer player, InteractionHand hand, BlockHitResult result,
			CallbackInfoReturnable<InteractionResult> cir) {
		if (player == null || result == null) {
			return;
		}
		ItemStack held = player.getItemInHand(hand);
		if (!held.is(Items.RESPAWN_ANCHOR)) {
			return;
		}
		Level level = player.level();
		if (level == null || level.getBlockState(result.getBlockPos()).is(Blocks.RESPAWN_ANCHOR)) {
			return;
		}
		NexoAnchorAura.onAnchorPlaced(player, result.getBlockPos().relative(result.getDirection()));
	}
}
