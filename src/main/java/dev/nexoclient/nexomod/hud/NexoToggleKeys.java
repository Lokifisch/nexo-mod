package dev.nexoclient.nexomod.hud;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoStyle;

/**
 * Toggle Sprint / Toggle Sneak: a press of the key latches it held.
 *
 * <p>The press is detected with {@code consumeClick()} (the key's own press
 * counter) because the latched key's {@code isDown()} is forced true by us and
 * so can no longer show the physical state. Re-asserting {@code setDown(true)}
 * at the start of every tick survives the key-release event that would
 * otherwise clear it.
 *
 * <p>ponytail: fixed bottom-left label, not a layout-editor element — adding
 * one means a new {@code NexoHudLayout.Element} and editor wiring.
 */
public final class NexoToggleKeys implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "toggle_keys");

	private static boolean sprintLatched;
	private static boolean sneakLatched;

	private NexoToggleKeys() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ID, new NexoToggleKeys());
		ClientTickEvents.START_CLIENT_TICK.register(NexoToggleKeys::tick);
	}

	private static void tick(Minecraft client) {
		if (client.options == null) {
			return;
		}
		NexoConfig config = NexoConfig.get();
		boolean active = client.player != null && client.gui.screen() == null;
		sprintLatched = step(client.options.keySprint, config.toggleSprintEnabled(), active, sprintLatched);
		sneakLatched = step(client.options.keyShift, config.toggleSneakEnabled(), active, sneakLatched);
	}

	private static boolean step(KeyMapping key, boolean enabled, boolean active, boolean latched) {
		if (!enabled) {
			if (latched) {
				key.setDown(false);
			}
			return false;
		}
		if (!active) {
			// Don't carry a latch across a screen/world change.
			if (latched) {
				key.setDown(false);
			}
			return false;
		}
		boolean pressed = false;
		while (key.consumeClick()) {
			pressed = !pressed;
		}
		if (pressed) {
			latched = !latched;
			if (!latched) {
				key.setDown(false);
			}
		}
		if (latched) {
			key.setDown(true);
		}
		return latched;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (NexoHudVisibility.hidden() || (!sprintLatched && !sneakLatched)) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options == null || client.gui.hud.isHidden()) {
			return;
		}
		int y = graphics.guiHeight() - 12 - client.font.lineHeight;
		if (sprintLatched) {
			graphics.text(client.font, Component.translatable("nexomod.qol.toggleSprint.label"), 4, y, NexoStyle.TEXT_SECONDARY);
			y -= client.font.lineHeight + 2;
		}
		if (sneakLatched) {
			graphics.text(client.font, Component.translatable("nexomod.qol.toggleSneak.label"), 4, y, NexoStyle.TEXT_SECONDARY);
		}
	}
}
