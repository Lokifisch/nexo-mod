package dev.nexoclient.nexomod.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoStyle;

/**
 * The player under the crosshair, kept for {@link #HOLD_MS} after you look away:
 * health, armour, held item and active effects. Everything shown is data the
 * server already syncs to every client that can see the player, so this adds
 * no information the vanilla client doesn't have, only a place to read it.
 * Sits at a fixed spot right of the crosshair — not in the layout editor.
 */
public final class NexoTargetHud implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "target_hud");
	private static final long HOLD_MS = 5000;
	private static final int MAX_EFFECTS = 4;

	private static LivingEntity target;
	private static long seen;

	private NexoTargetHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ID, new NexoTargetHud());
	}

	/** Nominal size of the box the layout editor drags: room for a name, three stats and a few effects. */
	private static final int NOMINAL_WIDTH = 130;
	private static final int NOMINAL_HEIGHT = 85;

	/** Where the panel draws: the saved position, else just right of the crosshair. */
	public static ScreenRectangle resolveBounds(int guiWidth, int guiHeight) {
		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.TARGET);
		float scale = override != null ? override.scale : 1f;
		int width = Math.round(NOMINAL_WIDTH * scale);
		int height = Math.round(NOMINAL_HEIGHT * scale);
		int x = override != null ? override.x : guiWidth / 2 + 36;
		int y = override != null ? override.y : guiHeight / 2 - 23;
		return NexoHudBounds.clamp(x, y, width, height, guiWidth, guiHeight);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (!NexoConfig.get().targetHudEnabled() || NexoHudVisibility.hidden()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.gui.hud.isHidden()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (client.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player other
				&& other != client.player) {
			target = other;
			seen = now;
		}
		if (target == null || target.isRemoved() || now - seen > HOLD_MS) {
			target = null;
			return;
		}

		List<Component> lines = new ArrayList<>();
		lines.add(target.getName());
		String health = String.format(Locale.ROOT, "%.1f", target.getHealth() + target.getAbsorptionAmount());
		lines.add(Component.translatable("nexomod.targetHud.health", health));
		lines.add(Component.translatable("nexomod.targetHud.armor", Math.round(target.getArmorValue())));
		if (!target.getMainHandItem().isEmpty()) {
			lines.add(target.getMainHandItem().getHoverName());
		}
		int shown = 0;
		for (MobEffectInstance effect : target.getActiveEffects()) {
			if (shown++ == MAX_EFFECTS) {
				break;
			}
			int seconds = effect.getDuration() / 20;
			String time = effect.isInfiniteDuration() ? "∞" : "%d:%02d".formatted(seconds / 60, seconds % 60);
			lines.add(Component.empty().append(effect.getEffect().value().getDisplayName())
					.append(" " + (effect.getAmplifier() + 1) + " " + time));
		}

		int width = 0;
		for (Component line : lines) {
			width = Math.max(width, client.font.width(line));
		}
		int lineHeight = client.font.lineHeight + 1;
		// Drawn in the box's own coordinates from its top-left corner, scaled by
		// the layout editor's size, so it lands wherever the box was dragged.
		ScreenRectangle bounds = resolveBounds(graphics.guiWidth(), graphics.guiHeight());
		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.TARGET);
		float scale = override != null ? override.scale : 1f;
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(bounds.left(), bounds.top());
		pose.scale(scale, scale);
		graphics.fill(0, 0, width + 8, lines.size() * lineHeight + 5, 0x90000000);
		for (int i = 0; i < lines.size(); i++) {
			graphics.text(client.font, lines.get(i), 4, 3 + i * lineHeight,
					i == 0 ? NexoStyle.TEXT_ACTIVE_ACCENT : 0xFFFFFFFF);
		}
		pose.popMatrix();
	}
}
