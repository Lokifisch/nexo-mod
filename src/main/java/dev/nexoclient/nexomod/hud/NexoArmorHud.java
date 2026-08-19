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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Armour and off-hand durability, as a column against the right edge.
 *
 * <p>Moved here from {@code src/tactical} — it started as a Tactical-only
 * "advantage" feature, but it only ever surfaces information already visible
 * in the inventory screen, the same category macros and the badge already
 * sit in, so there is no reason for it to stay full-jar-exclusive once it
 * has its own settings surface (the QoL menu) instead of piggybacking on
 * {@code NexoTacticalFeatureScreen}.
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
 * <h2>Bounds and the layout editor</h2>
 *
 * <p>{@link #resolveBounds} uses a fixed nominal row count ({@link
 * #NOMINAL_ROWS}) rather than however many pieces are actually equipped
 * right now — a draggable box in {@code NexoHudEditorScreen} has to have a
 * stable size, and centering on the live row count would also make the
 * whole column visibly hop vertically every time a piece is put on or taken
 * off. Centering on a fixed maximum is incidentally steadier than the old
 * per-row centering was.
 */
public final class NexoArmorHud implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "armor_hud");

	private static final int ICON = 16;
	private static final int ROW_HEIGHT = 18;
	private static final int EDGE_MARGIN = 4;
	private static final int TEXT_GAP = 4;
	private static final int TEXT_WIDTH_ESTIMATE = 30;

	/** Armour slots (4) plus main hand and off-hand — the most rows this ever draws at once. */
	private static final int NOMINAL_ROWS = 6;
	private static final int NOMINAL_WIDTH = ICON + TEXT_GAP + TEXT_WIDTH_ESTIMATE;
	private static final int NOMINAL_HEIGHT = ROW_HEIGHT * NOMINAL_ROWS;

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

	/** Where this element draws right now — shared by rendering and the layout editor. */
	public static ScreenRectangle resolveBounds(int guiWidth, int guiHeight) {
		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.ARMOR);
		float scale = override != null ? override.scale : 1f;
		int width = Math.round(NOMINAL_WIDTH * scale);
		int height = Math.round(NOMINAL_HEIGHT * scale);
		int x = override != null ? override.x : guiWidth - EDGE_MARGIN - width;
		int y = override != null ? override.y : (guiHeight - height) / 2;
		return NexoHudBounds.clamp(x, y, width, height, guiWidth, guiHeight);
	}

	/**
	 * Logged only when the outcome actually changes, not every frame — this
	 * runs inside a HUD element's render path, so an unconditional log line
	 * here would mean thousands of log lines per second. Temporary: remove
	 * once the "renders nothing despite being enabled" report is confirmed
	 * fixed.
	 */
	private static String lastDiagnostic = "";

	private static void diagnose(String reason) {
		if (!reason.equals(lastDiagnostic)) {
			lastDiagnostic = reason;
			NexoMod.LOGGER.info("[nexomod] ArmorHud: {}", reason);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (NexoHudVisibility.hidden()) {
			diagnose("not drawing: NexoHudVisibility.hidden() is true (screenshot toggle or ghost mode)");
			return;
		}
		NexoConfig config = NexoConfig.get();
		if (!config.armorHudEnabled()) {
			diagnose("not drawing: armorHudEnabled is false");
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null) {
			diagnose("not drawing: client.player is null");
			return;
		}
		if (client.options.hideGui) {
			diagnose("not drawing: F1 hide-GUI is on");
			return;
		}

		List<ItemStack> rows = new ArrayList<>(6);
		for (EquipmentSlot slot : ARMOR) {
			ItemStack stack = player.getItemBySlot(slot);
			if (!stack.isEmpty()) {
				rows.add(stack);
			}
		}
		if (config.armorHudHeldItemEnabled()) {
			ItemStack mainHand = player.getMainHandItem();
			if (!mainHand.isEmpty()) {
				rows.add(mainHand);
			}
		}
		if (config.armorHudOffhandEnabled()) {
			ItemStack offhand = player.getOffhandItem();
			if (!offhand.isEmpty()) {
				rows.add(offhand);
			}
		}
		if (rows.isEmpty()) {
			diagnose("not drawing: no armor, held, or off-hand item equipped");
			return;
		}

		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.ARMOR);
		float scale = override != null ? override.scale : 1f;
		ScreenRectangle bounds = resolveBounds(graphics.guiWidth(), graphics.guiHeight());
		diagnose("drawing " + rows.size() + " row(s) at bounds=" + bounds
				+ " guiSize=" + graphics.guiWidth() + "x" + graphics.guiHeight()
				+ " override=" + (override == null ? "none" : override.x + "," + override.y + "@" + override.scale));
		int icon = Math.round(ICON * scale);
		int rowHeight = Math.round(ROW_HEIGHT * scale);
		int textGap = Math.round(TEXT_GAP * scale);

		Font font = client.font;
		int iconX = bounds.right() - icon;
		int y = bounds.top() + (bounds.height() - rows.size() * rowHeight) / 2;

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
				graphics.text(font, label, iconX - textGap - width, y + ((icon - font.lineHeight) / 2) + 1,
						colorFor(stack, config.armorHudWarnPercent()));
			}
			y += rowHeight;
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
