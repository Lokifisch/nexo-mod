package dev.nexoclient.nexomod.mixin;

import java.time.Instant;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;

import dev.nexoclient.nexomod.chat.NexoChatCapture;

/**
 * Supplies the sender name the chat archive cannot get anywhere else.
 *
 * <p>{@code ChatComponent.addMessage} — where the archiving happens — receives
 * only the finished {@code Component}. Who sent it is not recoverable from that:
 * the chat-type decoration is server-configurable, so parsing a name back out of
 * "&lt;Steve&gt; hi" works on a vanilla server and nowhere else.
 * {@code ChatListener.showMessageToPlayer} is the one client-side method that
 * holds the {@code GameProfile} and then calls straight into
 * {@code ChatComponent.addPlayerMessage} — confirmed by reading the 26.1
 * bytecode, where both {@code addPlayerMessage} call sites sit inside this
 * method.
 *
 * <p>HEAD parks the name, RETURN clears it. RETURN rather than TAIL so both exit
 * paths are covered: the method returns early when the message is blocked or
 * fully filtered, and a name left behind would be attributed to whatever system
 * message came next.
 *
 * <p>Delayed messages (the server's chat-delay setting) are re-run through this
 * same method rather than replayed elsewhere, so the park-and-clear window still
 * contains the matching {@code addMessage} call.
 */
@Mixin(ChatListener.class)
public class ChatSenderCaptureMixin {
	@Inject(
			method = "showMessageToPlayer(Lnet/minecraft/network/chat/ChatType$Bound;Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/network/chat/Component;Lcom/mojang/authlib/GameProfile;ZLjava/time/Instant;)Z",
			at = @At("HEAD"))
	private void nexomod$captureSender(ChatType.Bound bound, PlayerChatMessage message, Component decorated,
			GameProfile profile, boolean overlay, Instant timestamp, CallbackInfoReturnable<Boolean> cir) {
		NexoChatCapture.beginPlayerMessage(profile == null ? null : profile.name());
	}

	@Inject(
			method = "showMessageToPlayer(Lnet/minecraft/network/chat/ChatType$Bound;Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/network/chat/Component;Lcom/mojang/authlib/GameProfile;ZLjava/time/Instant;)Z",
			at = @At("RETURN"))
	private void nexomod$releaseSender(ChatType.Bound bound, PlayerChatMessage message, Component decorated,
			GameProfile profile, boolean overlay, Instant timestamp, CallbackInfoReturnable<Boolean> cir) {
		NexoChatCapture.endPlayerMessage();
	}
}
