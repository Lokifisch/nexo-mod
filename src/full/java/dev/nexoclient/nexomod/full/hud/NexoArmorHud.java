package dev.nexoclient.nexomod.full.hud;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Armour and off-hand durability, as a column against the right edge.
 *
 * <h2>Why the registry and not a {@code Gui} mixin</h2>
 *
 * <p>26.1 draws the HUD from a registry of named elements rather than from one
 * {@code render} method, and Fabric's {@code HudElementRegistry} is the
 * supported way in. Registering as an element means the layer order is declared
 * rather than fought over: this attaches after
 * {@link VanillaHudElements#ARMOR_BAR}, so it sits with the vanilla armour
 * display and under chat, and any other mod doing the same thing lands
 * predictably relative to it. A mixin on {@code Gui} would be an unordered pile
 * of injections into one method.
 *
 * <h2>Why the right edge</h2>
 *
 * <p>Everything vanilla puts near the hotbar — armour bar, health, hunger, air,
 * experience, held-item name — is centred, and everything else is on the left
 * (chat) or the top (boss bars, scoreboard is right but higher up). The right
 * edge at mid-height is the one place a five-row column does not have to
 * negotiate with something already there.
 */
public final class NexoArmorHud implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "armor_hud");

	private static final int ICON = 16;
	private static final int ROW_HEIGHT = 18;
	private static final int EDGE_MARGIN = 4;
	private static final int TEXT_GAP = 4;

	/** Top to bottom, the order the pieces sit on the body. */
	private static final EquipmentSlot[] ARMOR = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	private static final int COLOR_OK = 0xFFE8E8E8;
	private static final int COLOR_WARN = 0xFFFF5555;
	private static final int COLOR_UNBREAKABLE = 0xFF9C5CFF;

	private NexoArmorHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.ARMOR_BAR, ID, new NexoArmorHud());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (NexoHudVisibility.hidden()) {
			return;
		}
		NexoConfig config = NexoConfig.get();
		if (!config.armorHudEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || client.options.hideGui) {
			return;
		}

		List<ItemStack> rows = new ArrayList<>(5);
		for (EquipmentSlot slot : ARMOR) {
			ItemStack stack = player.getItemBySlot(slot);
			if (!stack.isEmpty()) {
				rows.add(stack);
			}
		}
		if (config.armorHudOffhandEnabled()) {
			ItemStack offhand = player.getOffhandItem();
			if (!offhand.isEmpty()) {
				rows.add(offhand);
			}
		}
		if (rows.isEmpty()) {
			return;
		}

		Font font = client.font;
		int iconX = graphics.guiWidth() - EDGE_MARGIN - ICON;
		int y = (graphics.guiHeight() - (rows.size() * ROW_HEIGHT)) / 2;

		for (ItemStack stack : rows) {
			// item() draws the model, itemDecorations() draws the stack count
			// and the vanilla damage bar — the bar is worth keeping even next
			// to a percentage, since it is the thing the eye reads without
			// stopping.
			graphics.item(player, stack, iconX, y, 0);
			graphics.itemDecorations(font, stack, iconX, y);

			Component label = durabilityLabel(stack);
			if (label != null) {
				int width = font.width(label);
				graphics.text(font, label, iconX - TEXT_GAP - width, y + ((ICON - font.lineHeight) / 2) + 1,
						colorFor(stack, config.armorHudWarnPercent()));
			}
			y += ROW_HEIGHT;
		}
	}

	/**
	 * Remaining durability as a percentage, or null for an item that cannot
	 * break. A percentage is used rather than "137/250" because the number that
	 * matters is "how much is left", and the maximum differs per material — 250
	 * is nearly full for netherite and nearly gone for a golden helmet.
	 */
	private static Component durabilityLabel(ItemStack stack) {
		if (!stack.isDamageableItem()) {
			return null;
		}
		return Component.literal(remainingPercent(stack) + "%");
	}

	private static int remainingPercent(ItemStack stack) {
		int max = stack.getMaxDamage();
		if (max <= 0) {
			return 100;
		}
		int remaining = max - stack.getDamageValue();
		// Rounded down, so 0% means "one more hit" rather than "already broken".
		return Math.clamp((remaining * 100) / max, 0, 100);
	}

	private static int colorFor(ItemStack stack, int warnPercent) {
		if (!stack.isDamageableItem()) {
			return COLOR_UNBREAKABLE;
		}
		return remainingPercent(stack) <= warnPercent ? COLOR_WARN : COLOR_OK;
	}
}
