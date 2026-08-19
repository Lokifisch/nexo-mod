package dev.nexoclient.nexomod.hud;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoStyle;

/**
 * The classic W/A/S/D + click keystrokes box, for recording/streaming —
 * plus, below it, whatever custom keys the player added in
 * {@code NexoKeystrokesConfigScreen} (see {@link NexoKeystrokesConfig} for
 * why those are raw key codes rather than something more structured).
 *
 * <p>Reads {@code Options}' actual key mappings ({@code keyUp}/{@code
 * keyLeft}/{@code keyDown}/{@code keyRight}/{@code keyAttack}/{@code keyUse})
 * for the built-in cluster rather than raw scancodes, so a player who
 * rebound movement still gets correct highlight state — the on-screen
 * letters stay fixed WASD/LMB/RMB labels regardless. Custom entries have no
 * such mapping to read, so they poll {@code InputConstants.isKeyDown}
 * directly against the stored code.
 *
 * <p>Every box is laid out from one top-left origin in local, unscaled
 * coordinates ({@link #layout}), then {@link #resolveBounds} places that
 * whole block at either its default position (bottom-center) or a custom
 * one dragged in {@code NexoHudEditorScreen}.
 */
public final class NexoKeystrokesHud implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "keystrokes_hud");

	private static final int BOX = 18;
	private static final int GAP = 2;
	private static final int CLICK_GAP = GAP * 3;
	private static final int CLICK_EXTRA = 8;
	private static final int BOTTOM_MARGIN = 58;
	private static final int KEY_DOWN_BG = 0xE63CFFB0;
	private static final int CUSTOM_PER_ROW = 8;
	private static final int CUSTOM_ROW_GAP = GAP * 3;

	private static final int WASD_WIDTH = BOX * 3 + GAP * 2;
	private static final int CLICK_WIDTH = BOX + CLICK_EXTRA;
	private static final int CLUSTER_WIDTH = CLICK_WIDTH * 2 + CLICK_GAP * 2 + WASD_WIDTH;
	private static final int CLUSTER_HEIGHT = BOX * 2 + GAP;

	/** Computed once per call, shared by {@link #resolveBounds} and {@link #extractRenderState}. */
	private record Layout(boolean showCluster, int customRows, int customCols, int width, int height) {
	}

	private NexoKeystrokesHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, ID, new NexoKeystrokesHud());
	}

	private static Layout layout() {
		NexoKeystrokesConfig config = NexoKeystrokesConfig.get();
		boolean showCluster = config.showDefaultCluster();
		int customCount = config.customEntries().size();
		int cols = Math.min(customCount, CUSTOM_PER_ROW);
		int rows = customCount == 0 ? 0 : (customCount + CUSTOM_PER_ROW - 1) / CUSTOM_PER_ROW;
		int customWidth = cols == 0 ? 0 : cols * BOX + (cols - 1) * GAP;
		int customHeight = rows == 0 ? 0 : rows * BOX + (rows - 1) * GAP;

		int width = Math.max(showCluster ? CLUSTER_WIDTH : 0, customWidth);
		int height = (showCluster ? CLUSTER_HEIGHT : 0) + (rows > 0 ? (showCluster ? CUSTOM_ROW_GAP : 0) + customHeight : 0);
		// Never fully empty: an all-off configuration still needs a grabbable box in the editor.
		width = Math.max(width, BOX);
		height = Math.max(height, BOX);
		return new Layout(showCluster, rows, cols, width, height);
	}

	/** Where this element draws right now — shared by rendering and the layout editor. */
	public static ScreenRectangle resolveBounds(int guiWidth, int guiHeight) {
		Layout layout = layout();
		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.KEYSTROKES);
		float scale = override != null ? override.scale : 1f;
		int width = Math.round(layout.width() * scale);
		int height = Math.round(layout.height() * scale);
		int x = override != null ? override.x : guiWidth / 2 - width / 2;
		int y = override != null ? override.y : guiHeight - BOTTOM_MARGIN - height;
		return NexoHudBounds.clamp(x, y, width, height, guiWidth, guiHeight);
	}

	/** Temporary: confirms extractRenderState is ever called at all — see NexoArmorHud's diagnose(). */
	private static boolean loggedFirstCall = false;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (!loggedFirstCall) {
			loggedFirstCall = true;
			NexoMod.LOGGER.info("[nexomod] KeystrokesHud.extractRenderState is being called.");
		}
		if (NexoHudVisibility.hidden()) {
			return;
		}
		if (!NexoConfig.get().keystrokesHudEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options == null || client.options.hideGui) {
			return;
		}

		Layout layout = layout();
		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.KEYSTROKES);
		float scale = override != null ? override.scale : 1f;
		ScreenRectangle bounds = resolveBounds(graphics.guiWidth(), graphics.guiHeight());

		int cursorY = bounds.top();
		if (layout.showCluster()) {
			int clusterWidth = Math.round(CLUSTER_WIDTH * scale);
			int originX = bounds.left() + (Math.round(layout.width() * scale) - clusterWidth) / 2;
			layoutCluster(graphics, client.font, client.options, originX, cursorY, scale);
			cursorY += Math.round(CLUSTER_HEIGHT * scale) + (layout.customRows() > 0 ? Math.round(CUSTOM_ROW_GAP * scale) : 0);
		}
		if (layout.customRows() > 0) {
			layoutCustom(graphics, client.font, bounds, layout, cursorY, scale);
		}
	}

	private void layoutCluster(GuiGraphicsExtractor graphics, Font font, Options options,
			int originX, int originY, float scale) {
		int box = Math.round(BOX * scale);
		int gap = Math.round(GAP * scale);
		int clickWidth = Math.round(CLICK_WIDTH * scale);
		int clickGap = Math.round(CLICK_GAP * scale);
		int wasdWidth = Math.round(WASD_WIDTH * scale);
		int wasdOffsetX = clickWidth + clickGap;

		key(graphics, font, options.keyUp.isDown(), "W", originX + wasdOffsetX + box + gap, originY, box, box);
		key(graphics, font, options.keyLeft.isDown(), "A", originX + wasdOffsetX, originY + box + gap, box, box);
		key(graphics, font, options.keyDown.isDown(), "S", originX + wasdOffsetX + box + gap, originY + box + gap, box, box);
		key(graphics, font, options.keyRight.isDown(), "D", originX + wasdOffsetX + (box + gap) * 2, originY + box + gap, box, box);
		key(graphics, font, options.keyAttack.isDown(), "LMB", originX, originY + box + gap, clickWidth, box);
		key(graphics, font, options.keyUse.isDown(), "RMB", originX + wasdOffsetX + wasdWidth + clickGap,
				originY + box + gap, clickWidth, box);
	}

	private void layoutCustom(GuiGraphicsExtractor graphics, Font font, ScreenRectangle bounds, Layout layout,
			int startY, float scale) {
		List<NexoKeystrokesConfig.KeyEntry> entries = NexoKeystrokesConfig.get().customEntries();
		int box = Math.round(BOX * scale);
		int gap = Math.round(GAP * scale);
		int rowWidth = layout.customCols() * box + (layout.customCols() - 1) * gap;
		int originX = bounds.left() + (Math.round(layout.width() * scale) - rowWidth) / 2;

		int col = 0;
		int y = startY;
		Minecraft client = Minecraft.getInstance();
		for (NexoKeystrokesConfig.KeyEntry entry : entries) {
			int x = originX + col * (box + gap);
			boolean down = entry.keyCode >= 0 && InputConstants.isKeyDown(client.getWindow(), entry.keyCode);
			key(graphics, font, down, entry.label, x, y, box, box);
			col++;
			if (col >= layout.customCols()) {
				col = 0;
				y += box + gap;
			}
		}
	}

	private void key(GuiGraphicsExtractor graphics, Font font, boolean down, String label,
			int x, int y, int width, int height) {
		int bg = down ? KEY_DOWN_BG : NexoStyle.PANEL_BG_RAISED;
		int textColor = down ? NexoStyle.PANEL_BG : NexoStyle.TEXT_SECONDARY;

		graphics.fill(x, y, x + width, y + height, bg);
		Component text = Component.literal(label);
		int textWidth = font.width(text);
		graphics.text(font, text, x + (width - textWidth) / 2, y + (height - font.lineHeight) / 2 + 1, textColor);
	}
}
