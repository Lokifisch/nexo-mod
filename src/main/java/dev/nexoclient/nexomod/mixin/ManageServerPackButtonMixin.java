package dev.nexoclient.nexomod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nexoclient.nexomod.profiles.NexoServerProfiles;
import dev.nexoclient.nexomod.servers.NexoServerPacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** "Copy texture pack to local" on the Edit Server Info screen, under Cancel. */
@Mixin(ManageServerScreen.class)
public abstract class ManageServerPackButtonMixin extends Screen {
	@Shadow private EditBox ipEdit;

	protected ManageServerPackButtonMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void nexomod$addPackButton(CallbackInfo ci) {
		int bottom = 0;
		int left = width / 2 - 100;
		for (var child : children()) {
			if (child instanceof Button b) {
				bottom = Math.max(bottom, b.getY() + b.getHeight());
				left = b.getX();
			}
		}
		// Read when pressed, so an address edited on this screen is the one used.
		Button pack = Button.builder(Component.translatable("nexomod.profiles.pack"), button -> {
			String host = NexoServerProfiles.normalize(ipEdit.getValue());
			button.active = false;
			button.setMessage(Component.translatable("nexomod.profiles.pack.busy"));
			NexoServerPacks.copyToLocal(host).whenComplete((name, err) -> Minecraft.getInstance().execute(() -> {
				button.active = true;
				button.setMessage(err == null
						? Component.translatable("nexomod.profiles.pack.done", name)
						: Component.translatable(String.valueOf(err.getMessage()).contains("hash mismatch")
								? "nexomod.profiles.pack.mismatch" : "nexomod.profiles.pack.failed"));
			}));
		}).bounds(left, bottom + 4, 200, 20).build();
		pack.active = NexoServerPacks.get(NexoServerProfiles.normalize(ipEdit.getValue())) != null;
		addRenderableWidget(pack);
	}
}
