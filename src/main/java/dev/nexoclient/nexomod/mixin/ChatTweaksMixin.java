package dev.nexoclient.nexomod.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

import dev.nexoclient.nexomod.chat.NexoChatTweaks;

/**
 * Chat tweaks on the same private {@code addMessage} funnel as
 * {@link ChatMessageInterceptMixin}. Priority 1500 so the archive/filter inject
 * runs first (the archive still sees routed lines, and a filter-hidden line is
 * never routed). Timestamp and compaction sit in a {@code @ModifyArg} on the
 * {@code GuiMessage} constructor so they only apply to lines that really get
 * added — and the archive keeps recording the plain text.
 */
@Mixin(value = ChatComponent.class, priority = 1500)
public abstract class ChatTweaksMixin {
	@Shadow
	@org.spongepowered.asm.mixin.Final
	private List<GuiMessage> allMessages;
	@Shadow
	@org.spongepowered.asm.mixin.Final
	private List<GuiMessage.Line> trimmedMessages;

	@org.spongepowered.asm.mixin.Unique
	private Component nexomod$lastAdded;
	@org.spongepowered.asm.mixin.Unique
	private String nexomod$lastPlain;
	@org.spongepowered.asm.mixin.Unique
	private int nexomod$repeat = 1;

	@Inject(
			method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"),
			cancellable = true)
	private void nexomod$route(Component content, MessageSignature signature, GuiMessageSource source,
			GuiMessageTag tag, CallbackInfo ci) {
		if (!ci.isCancelled() && NexoChatTweaks.route(content)) {
			ci.cancel();
		}
	}

	@ModifyArg(
			method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage;<init>(ILnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V"),
			index = 1)
	private Component nexomod$decorate(Component content) {
		Component result = content;
		String plain = content.getString();
		if (NexoChatTweaks.compact) {
			GuiMessage prev = allMessages.isEmpty() ? null : allMessages.getFirst();
			if (prev != null && prev.content() == nexomod$lastAdded && plain.equals(nexomod$lastPlain)) {
				allMessages.removeFirst();
				trimmedMessages.removeIf(line -> line.parent() == prev);
				nexomod$repeat++;
				result = Component.empty().append(content)
						.append(Component.literal(" (x" + nexomod$repeat + ")").withStyle(net.minecraft.ChatFormatting.GRAY));
			} else {
				nexomod$repeat = 1;
			}
		}
		// Unconditional, so switching compact on later never compares against a stale line.
		nexomod$lastPlain = plain;
		result = NexoChatTweaks.stamp(result);
		nexomod$lastAdded = result;
		return result;
	}

	@ModifyConstant(method = "addMessageToDisplayQueue", constant = @Constant(intValue = 100))
	private int nexomod$maxTrimmed(int vanilla) {
		return NexoChatTweaks.maxLines;
	}

	@ModifyConstant(method = "addMessageToQueue", constant = @Constant(intValue = 100))
	private int nexomod$maxAll(int vanilla) {
		return NexoChatTweaks.maxLines;
	}
}
