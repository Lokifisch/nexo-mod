package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

import dev.nexoclient.nexomod.chat.NexoChatCapture;

/**
 * Archives every chat line and applies the auto filter to it.
 *
 * <h2>Why this method</h2>
 *
 * <p>{@code ChatComponent} has three public ways in — {@code addPlayerMessage},
 * {@code addServerSystemMessage}, {@code addClientSystemMessage} — and all three
 * call this one private {@code addMessage}. Verified by reading the 26.1 class
 * file: the private overload is the only place {@code GuiMessage} objects are
 * built, so hooking it once catches everything on screen exactly once, including
 * lines other mods inject through the public methods.
 *
 * <h2>Why two injections instead of one</h2>
 *
 * <p>Hiding needs to cancel the method, and an {@code @Inject} can do that but
 * cannot change an argument. Highlighting needs to replace the {@code Component}
 * argument, which is what {@code @ModifyVariable} is for and which no
 * {@code @Inject} can do. They are independent: at most one of the two fires for
 * any given message, since a rule resolves to hide <em>or</em> highlight.
 *
 * <p>Both sit at HEAD, and Mixin does not promise an order between them. It does
 * not matter here — the only value the modifier changes is the component's
 * style, {@code getString()} is identical either way, so the recorded text and
 * the filter verdict come out the same whichever runs first.
 *
 * <p>{@code index = 1} is the {@code Component} argument: slot 0 is {@code this}
 * on an instance method, so the first declared parameter is 1.
 */
@Mixin(ChatComponent.class)
public class ChatMessageInterceptMixin {
	@Inject(
			method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"),
			cancellable = true)
	private void nexomod$recordAndFilter(Component content, MessageSignature signature, GuiMessageSource source,
			GuiMessageTag tag, CallbackInfo ci) {
		if (NexoChatCapture.interceptAndRecord(content, source)) {
			ci.cancel();
		}
	}

	@ModifyVariable(
			method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"),
			argsOnly = true,
			index = 1)
	private Component nexomod$highlight(Component content) {
		return NexoChatCapture.decorate(content);
	}
}
