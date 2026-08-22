package dev.nexoclient.nexomod.hud;

import org.joml.Matrix3x2fStack;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoShapes;
import dev.nexoclient.nexomod.screen.NexoStyle;

/**
 * The 27 main inventory slots as a compact 9×3 grid, so you can see what you
 * are carrying without opening the inventory and standing still for it.
 *
 * <p>The hotbar is deliberately excluded — it is already on screen, and
 * repeating it would just make this element half redundant. That is why the
 * source range starts at {@link #FIRST_MAIN_SLOT}: vanilla's non-equipment
 * list is 36 entries with the hotbar occupying 0–8 and the three main rows
 * 9–35.
 *
 * <p>Nominal size is <em>fixed</em> at the full 9×3 grid even when the pack is
 * nearly empty. A box that shrank to fit its contents would move under the
 * cursor while being dragged in the layout editor, and the editor's drag math
 * assumes the box it grabbed is the box it keeps.
 */
public final class NexoInventoryHud implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "inventory_hud");
	private static final int FIRST_MAIN_SLOT = 9;
	private static final int COLUMNS = 9;
	private static final int ROWS = 3;
	private static final int SLOT = 18;
	private static final int NOMINAL_WIDTH = COLUMNS * SLOT;
	private static final int NOMINAL_HEIGHT = ROWS * SLOT;
	private static final int EDGE_MARGIN = 4;

	private NexoInventoryHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, ID, new NexoInventoryHud());
	}

	/** Where this element draws right now — shared by rendering and the layout editor. */
	public static ScreenRectangle resolveBounds(int guiWidth, int guiHeight) {
		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.INVENTORY);
		float scale = override != null ? override.scale : 1f;
		int width = Math.round(NOMINAL_WIDTH * scale);
		int height = Math.round(NOMINAL_HEIGHT * scale);
		// Default: bottom-right, clear of the hotbar in the middle and of the
		// armor HUD's column on the right edge.
		int x = override != null ? override.x : guiWidth - width - EDGE_MARGIN;
		int y = override != null ? override.y : guiHeight - height - EDGE_MARGIN - 40;
		return NexoHudBounds.clamp(x, y, width, height, guiWidth, guiHeight);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (NexoHudVisibility.hidden()) {
			return;
		}
		if (!NexoConfig.get().inventoryHudEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || client.options.hideGui) {
			return;
		}

		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.INVENTORY);
		float scale = override != null ? override.scale : 1f;
		ScreenRectangle bounds = resolveBounds(graphics.guiWidth(), graphics.guiHeight());
		// Both the panel and the per-slot squares come off together: leaving the
		// slot squares behind a hidden panel would read as a half-drawn HUD rather
		// than a deliberate look, and "invisible" means the items float.
		boolean background = NexoConfig.get().inventoryHudBackgroundEnabled();

		if (background) {
			NexoShapes.fillRounded(graphics, bounds.left() - 2, bounds.top() - 2,
					bounds.right() + 2, bounds.bottom() + 2, NexoStyle.PANEL_BG, 4);
		}

		// graphics.item() draws at a fixed 16×16 with no scale parameter, so the
		// element's scale override has to come from the matrix. Coordinates are
		// pre-divided so the scaled result lands on the bounds computed above.
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.scale(scale, scale);
		int originX = Math.round(bounds.left() / scale);
		int originY = Math.round(bounds.top() / scale);

		NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
		for (int row = 0; row < ROWS; row++) {
			for (int column = 0; column < COLUMNS; column++) {
				int slot = FIRST_MAIN_SLOT + row * COLUMNS + column;
				if (slot >= items.size()) {
					continue;
				}
				int x = originX + column * SLOT + 1;
				int y = originY + row * SLOT + 1;
				if (background) {
					graphics.fill(x - 1, y - 1, x + SLOT - 1, y + SLOT - 1, NexoStyle.PANEL_BG_RAISED);
				}

				ItemStack stack = items.get(slot);
				if (stack.isEmpty()) {
					continue;
				}
				graphics.item(stack, x, y);
				graphics.itemDecorations(client.font, stack, x, y);
			}
		}
		pose.popMatrix();
	}
}
