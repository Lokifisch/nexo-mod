package dev.nexoclient.nexomod.hud;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix3x2fStack;

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

	/** Armour slots (4) plus main hand and off-hand — the most cells this ever draws at once. */
	private static final int NOMINAL_ROWS = 6;
	private static final int NOMINAL_WIDTH = ICON + TEXT_GAP + TEXT_WIDTH_ESTIMATE;
	private static final int NOMINAL_HEIGHT = ROW_HEIGHT * NOMINAL_ROWS;
	/**
	 * Laid out along a line instead of down a column, a cell is the icon with its
	 * label centred underneath rather than beside it — putting the text to the
	 * side would make the bar {@code 6 × (16 + 4 + 30)} = 300px wide.
	 */
	private static final int H_CELL_WIDTH = ICON + 4;
	private static final int H_CELL_HEIGHT = ICON + 10;
	/** Durability text under a horizontal icon is drawn this much smaller — see drawHorizontal. */
	private static final float H_LABEL_SCALE = 0.5F;

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

	/**
	 * Where this element draws right now — shared by rendering and the layout
	 * editor. The nominal box swaps its axes with the orientation, and the default
	 * position follows: a column hugs the right edge, a bar centres itself just
	 * above the hotbar, which is the "lying along the bottom" placement horizontal
	 * mode exists for. A saved override still wins over both.
	 */
	public static ScreenRectangle resolveBounds(int guiWidth, int guiHeight) {
		boolean horizontal = NexoConfig.get().armorHudOrientation() == NexoConfig.ArmorOrientation.HORIZONTAL;
		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.ARMOR);
		float scale = override != null ? override.scale : 1f;
		int width = Math.round((horizontal ? H_CELL_WIDTH * NOMINAL_ROWS : NOMINAL_WIDTH) * scale);
		int height = Math.round((horizontal ? H_CELL_HEIGHT : NOMINAL_HEIGHT) * scale);
		int x;
		int y;
		if (override != null) {
			x = override.x;
			y = override.y;
		} else if (horizontal) {
			x = (guiWidth - width) / 2;
			// Clear of the hotbar (22px) and the status bars stacked above it.
			y = guiHeight - height - 22 - EDGE_MARGIN - 12;
		} else {
			x = guiWidth - EDGE_MARGIN - width;
			y = (guiHeight - height) / 2;
		}
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
		if (config.armorHudOrientation() == NexoConfig.ArmorOrientation.HORIZONTAL) {
			drawHorizontal(graphics, client, player, rows, bounds, scale, config);
		} else {
			drawVertical(graphics, client, player, rows, bounds, scale, config);
		}
	}

	/** A column against an edge: icon on the right, durability text to its left. */
	private static void drawVertical(GuiGraphicsExtractor graphics, Minecraft client, LocalPlayer player,
			List<ItemStack> rows, ScreenRectangle bounds, float scale, NexoConfig config) {
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

			Component label = durabilityLabel(stack, config.armorHudDurabilityMode());
			if (label != null) {
				int width = font.width(label);
				graphics.text(font, label, iconX - textGap - width, y + ((icon - font.lineHeight) / 2) + 1,
						colorFor(stack, config.armorHudWarnPercent()));
			}
			y += rowHeight;
		}
	}

	/**
	 * A bar along a line: icons side by side with their labels centred
	 * underneath. The label is drawn at half scale because "1561/1561" under a
	 * 16px icon is otherwise three times wider than the icon it belongs to, and
	 * the whole point of this mode is a strip that fits under the hotbar.
	 */
	private static void drawHorizontal(GuiGraphicsExtractor graphics, Minecraft client, LocalPlayer player,
			List<ItemStack> rows, ScreenRectangle bounds, float scale, NexoConfig config) {
		int icon = Math.round(ICON * scale);
		int cellWidth = Math.round(H_CELL_WIDTH * scale);
		Font font = client.font;

		int x = bounds.left() + (bounds.width() - rows.size() * cellWidth) / 2;
		int y = bounds.top();

		for (ItemStack stack : rows) {
			int iconX = x + (cellWidth - icon) / 2;
			graphics.item(player, stack, iconX, y, 0);
			graphics.itemDecorations(font, stack, iconX, y);

			Component label = durabilityLabel(stack, config.armorHudDurabilityMode());
			if (label != null) {
				Matrix3x2fStack pose = graphics.pose();
				pose.pushMatrix();
				pose.scale(H_LABEL_SCALE, H_LABEL_SCALE);
				int width = Math.round(font.width(label) * H_LABEL_SCALE * scale);
				int labelX = x + (cellWidth - width) / 2;
				int labelY = y + icon + 1;
				graphics.text(font, label,
						Math.round(labelX / H_LABEL_SCALE), Math.round(labelY / H_LABEL_SCALE),
						colorFor(stack, config.armorHudWarnPercent()));
				pose.popMatrix();
			}
			x += cellWidth;
		}
	}

	/**
	 * Remaining durability in whichever form the config asks for, or null when
	 * there is nothing to say — an item that cannot break, or
	 * {@link NexoConfig.ArmorDurabilityMode#NONE}.
	 */
	private static Component durabilityLabel(ItemStack stack, NexoConfig.ArmorDurabilityMode mode) {
		if (!stack.isDamageableItem() || mode == NexoConfig.ArmorDurabilityMode.NONE) {
			return null;
		}
		if (mode == NexoConfig.ArmorDurabilityMode.VALUES) {
			int max = stack.getMaxDamage();
			return Component.literal((max - stack.getDamageValue()) + "/" + max);
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
