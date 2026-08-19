package dev.nexoclient.nexomod.hud;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoStyle;

/**
 * Active potion effects as plain text lines — name, amplifier, remaining
 * time — rather than vanilla's small icon row. No icon rendering: that needs
 * the sprite-atlas lookups vanilla's own HUD uses internally, which is a
 * fair bit of extra machinery for what a legible timer already delivers on
 * its own. Text is also what actually reads at a glance while streaming,
 * which is the whole reason this exists instead of just leaving vanilla's
 * icons on screen.
 */
public final class NexoPotionHud implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "potion_hud");
	private static final int LINE_HEIGHT = 10;
	private static final int NOMINAL_WIDTH = 120;
	private static final int NOMINAL_ROWS = 6;
	private static final int EDGE_MARGIN = 4;

	private static final String[] ROMAN = {"", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"};

	private NexoPotionHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.MOB_EFFECTS, ID, new NexoPotionHud());
	}

	/** Where this element draws right now — shared by rendering and the layout editor. */
	public static ScreenRectangle resolveBounds(int guiWidth, int guiHeight) {
		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.POTION);
		float scale = override != null ? override.scale : 1f;
		int width = Math.round(NOMINAL_WIDTH * scale);
		int height = Math.round(NOMINAL_ROWS * LINE_HEIGHT * scale);
		int x = override != null ? override.x : guiWidth - EDGE_MARGIN - width;
		int y = override != null ? override.y : EDGE_MARGIN;
		return NexoHudBounds.clamp(x, y, width, height, guiWidth, guiHeight);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (NexoHudVisibility.hidden()) {
			return;
		}
		if (!NexoConfig.get().potionHudEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || client.options.hideGui) {
			return;
		}
		List<MobEffectInstance> effects = new ArrayList<>(player.getActiveEffects());
		if (effects.isEmpty()) {
			return;
		}

		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.POTION);
		float scale = override != null ? override.scale : 1f;
		ScreenRectangle bounds = resolveBounds(graphics.guiWidth(), graphics.guiHeight());
		Font font = client.font;
		int lineHeight = Math.round(LINE_HEIGHT * scale);

		int y = bounds.top();
		for (MobEffectInstance effect : effects) {
			Component line = effectLine(effect);
			graphics.text(font, line, bounds.left(), y, NexoStyle.TEXT_PRIMARY);
			y += lineHeight;
		}
	}

	private static Component effectLine(MobEffectInstance effect) {
		Component name = effect.getEffect().value().getDisplayName();
		String amplifier = effect.getAmplifier() < ROMAN.length ? ROMAN[effect.getAmplifier()] : " +" + effect.getAmplifier();
		String duration = effect.isInfiniteDuration() ? "∞" : durationText(effect.getDuration());
		return name.copy().append(amplifier + " - " + duration);
	}

	private static String durationText(int ticks) {
		int totalSeconds = Math.max(0, ticks / 20);
		return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
	}
}
