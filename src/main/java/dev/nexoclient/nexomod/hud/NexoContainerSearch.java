package dev.nexoclient.nexomod.hud;

import java.util.Locale;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

import dev.nexoclient.nexomod.mixin.AbstractContainerScreenAccessor;
import dev.nexoclient.nexomod.mixin.ScreenWidgetInvoker;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * A search box above any container screen. While it has text, every slot whose
 * item name doesn't contain it is dimmed — a client-side overlay on what the
 * screen already shows, nothing is sent or hidden.
 */
public final class NexoContainerSearch {
	private static final int DIM = 0xB0000000;

	private NexoContainerSearch() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof AbstractContainerScreen<?> container)
					|| !(screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen)
					|| !NexoConfig.get().containerSearchEnabled()) {
				return;
			}
			AbstractContainerScreenAccessor pos = (AbstractContainerScreenAccessor) container;
			EditBox box = new EditBox(client.font, pos.nexomod$getLeftPos(), Math.max(2, pos.nexomod$getTopPos() - 20), 110, 16,
					Component.translatable("nexomod.qol.containerSearch"));
			box.setHint(Component.translatable("nexomod.containerSearch.hint"));
			box.setMaxLength(40);
			((ScreenWidgetInvoker) screen).nexomod$addRenderableWidget(box);
			// A box added from outside doesn't get vanilla's own-field guard, so without this
			// typing "e" closes the chest and digits swap hotbar slots. Esc leaves the box
			// first (so the next Esc/E acts on the chest again) instead of being swallowed.
			ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
				if (!box.isFocused() || !box.canConsumeInput()) {
					return true;
				}
				if (event.key() == 256) {
					screen.setFocused(null);
				} else {
					box.keyPressed(event);
				}
				return false;
			});
			// Clicking anywhere but the box also leaves it, so a slot click doesn't leave keys trapped.
			ScreenMouseEvents.beforeMouseClick(screen).register((s, click) -> {
				if (box.isFocused() && !box.isMouseOver(click.x(), click.y())) {
					screen.setFocused(null);
				}
			});
			ScreenEvents.afterExtract(screen).register((s, graphics, mx, my, dt) -> {
				String needle = box.getValue().strip().toLowerCase(Locale.ROOT);
				if (needle.isEmpty()) {
					return;
				}
				for (Slot slot : container.getMenu().slots) {
					if (!slot.getItem().isEmpty()
							&& slot.getItem().getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle)) {
						continue;
					}
					int x = pos.nexomod$getLeftPos() + slot.x;
					int y = pos.nexomod$getTopPos() + slot.y;
					graphics.fill(x, y, x + 16, y + 16, DIM);
				}
			});
		});
	}
}
