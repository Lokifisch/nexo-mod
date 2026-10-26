package dev.nexoclient.nexomod.tactical.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.tactical.aim.NexoMouseModel;
import dev.nexoclient.nexomod.tactical.knockback.NexoKnockbackAngle;

/**
 * Knockback Displacement. In 26.3 only the <em>extra</em> knockback (the
 * sprint-hit bonus and the Knockback enchantment, {@code causeExtraKnockback})
 * is aimed with the attacker's yaw. The base knockback of every hit
 * ({@code dealDefaultKnockback}) is aimed from the damage source's position to
 * the target and cannot be changed from the client. So this only steers the
 * bonus: it sends yaw plus the chosen offset (180° by default, set with the
 * knockback-angle key, see {@link NexoKnockbackAngle}), lets the attack go out, then sends the real yaw. On
 * a hit with no bonus (not sprinting, no Knockback enchant) it does nothing.
 * The player's own rotation is never touched, only what the server is told.
 */
@Mixin(MultiPlayerGameMode.class)
public class KnockbackDisplacementMixin {
	@Shadow
	private ClientPacketListener connection;

	/** Whether this attack sent the flipped yaw and so owes the server the real one. */
	@Unique
	private boolean nexomod$flipped;

	@Inject(method = "attack", at = @At("HEAD"))
	private void nexomod$flick(Player player, Entity target, CallbackInfo ci) {
		nexomod$flipped = false;
		if (!(target instanceof LivingEntity) || !NexoConfig.get().knockbackDisplacementEnabled()) {
			return;
		}
		// The yaw-aimed bonus exists only on a charged sprint hit (vanilla's own
		// "strong" cut-off is 0.9). Without one there is nothing to redirect, so
		// say so instead of silently doing nothing, and send no packets.
		if (!player.isSprinting() || player.getAttackStrengthScale(0.5F) <= 0.9F) {
			player.sendOverlayMessage(Component.translatable("nexomod.knockback.noBonus"));
			return;
		}
		nexomod$flipped = true;
		nexomod$sendYaw(player, Mth.wrapDegrees(player.getYRot() + NexoKnockbackAngle.current()));
	}

	@Inject(method = "attack", at = @At("RETURN"))
	private void nexomod$flickBack(Player player, Entity target, CallbackInfo ci) {
		if (nexomod$flipped) {
			nexomod$flipped = false;
			nexomod$sendYaw(player, player.getYRot());
			NexoKnockbackAngle.consume();
		}
	}

	/**
	 * The flick is a single-tick jump to {@code yaw + angle}, which no mouse
	 * produces: the delta lands off the vanilla mouse grid and a rotation check
	 * flags it immediately. Snapping the sent angle to that grid makes the jump
	 * the size a fast hand could actually have made — it does not make a
	 * 180-degree flick invisible (see the class note), only grid-legal.
	 */
	private void nexomod$sendYaw(Player player, float yaw) {
		double quantum = NexoMouseModel.quantum(Minecraft.getInstance().options.sensitivity().get());
		connection.send(new ServerboundMovePlayerPacket.Rot(NexoMouseModel.snap(Mth.wrapDegrees(yaw), quantum),
				player.getXRot(), player.onGround(), player.horizontalCollision));
	}
}
