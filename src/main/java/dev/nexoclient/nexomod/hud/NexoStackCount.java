package dev.nexoclient.nexomod.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;

/** Total of the held item across the whole inventory, beside the hotbar, for stackable items only. */
public final class NexoStackCount implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "stack_count");

	private NexoStackCount() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, ID, new NexoStackCount());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || !NexoConfig.get().stackCountEnabled() || NexoHudVisibility.hidden()
				|| client.gui.hud.isHidden()) {
			return;
		}
		ItemStack held = player.getMainHandItem();
		if (held.isEmpty() || !held.isStackable()) {
			return;
		}
		int total = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (ItemStack.isSameItemSameComponents(stack, held)) {
				total += stack.getCount();
			}
		}
		Component text = Component.translatable("nexomod.stackCount.total", total);
		graphics.text(client.font, text, graphics.guiWidth() / 2 + 98, graphics.guiHeight() - 16, 0xFFFFFFFF);
	}
}
