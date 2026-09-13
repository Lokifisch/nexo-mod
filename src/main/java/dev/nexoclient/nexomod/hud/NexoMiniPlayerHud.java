package dev.nexoclient.nexomod.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.miniplayer.NexoMiniPlayer;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoShapes;
import dev.nexoclient.nexomod.screen.NexoStyle;

/**
 * "Now playing" card for whatever {@link NexoMiniPlayer} last read off the
 * desktop's MPRIS bus — title, artist/source and a progress bar in a rounded
 * panel with an accent tab that slowly cycles the brand palette while
 * something is actually playing (a flat, dim tab while paused), the same
 * "alive vs. idle" language {@code NexoModuleRow}'s own accent bar uses.
 *
 * <p>Bottom-left by default — every other corner is already spoken for
 * (potion effects top-right, armor/stats near the hotbar) — but fully
 * draggable/resizable like every other Nexo HUD element; see the
 * {@code MINI_PLAYER} entry in {@link NexoHudLayout} and
 * {@code NexoHudEditorScreen}'s {@code NEXO_ELEMENTS}.
 *
 * <p>Title and artist are ellipsized to whatever width is actually left
 * inside the card ({@link #ellipsize}) rather than drawn raw — an unclipped
 * {@code graphics.text} call happily paints past the card's own edge for
 * anything with a long enough name.
 */
public final class NexoMiniPlayerHud implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "mini_player_hud");
	private static final String ELLIPSIS = "…";

	private static final int EDGE_MARGIN = 4;
	private static final int NOMINAL_WIDTH = 200;
	private static final int PADDING = 6;
	private static final int TITLE_ROW = 10;
	private static final int ARTIST_ROW = 9;
	private static final int BAR_GAP = 4;
	private static final int BAR_HEIGHT = 3;
	private static final int ACCENT_WIDTH = 3;
	private static final int CORNER_RADIUS = 6;
	private static final int NOMINAL_HEIGHT = PADDING * 2 + TITLE_ROW + ARTIST_ROW + BAR_GAP + BAR_HEIGHT;
	/** One full brand-color sweep of the accent tab while something is playing. */
	private static final long ACCENT_CYCLE_MS = 6000L;
	/** Subtly lighter than {@link NexoStyle#PANEL_BG}, for a top-to-bottom depth gradient rather than a flat fill. */
	private static final int PANEL_TOP = 0xE81C1C30;

	private NexoMiniPlayerHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, ID, new NexoMiniPlayerHud());
	}

	/** Where this element draws right now — shared by rendering and the layout editor. */
	public static ScreenRectangle resolveBounds(int guiWidth, int guiHeight) {
		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.MINI_PLAYER);
		float scale = override != null ? override.scale : 1f;
		int width = Math.round(NOMINAL_WIDTH * scale);
		int height = Math.round(NOMINAL_HEIGHT * scale);
		int x = override != null ? override.x : EDGE_MARGIN;
		int y = override != null ? override.y : guiHeight - EDGE_MARGIN - height;
		return NexoHudBounds.clamp(x, y, width, height, guiWidth, guiHeight);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (NexoHudVisibility.hidden() || !NexoConfig.get().miniPlayerEnabled()) {
			return;
		}
		NexoMiniPlayer.NowPlaying track = NexoMiniPlayer.current();
		if (track == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.options.hideGui) {
			return;
		}

		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.MINI_PLAYER);
		float scale = override != null ? override.scale : 1f;
		ScreenRectangle bounds = resolveBounds(graphics.guiWidth(), graphics.guiHeight());

		int padding = Math.round(PADDING * scale);
		int titleRow = Math.round(TITLE_ROW * scale);
		int artistRow = Math.round(ARTIST_ROW * scale);
		int barGap = Math.round(BAR_GAP * scale);
		int barHeight = Math.max(1, Math.round(BAR_HEIGHT * scale));
		int accentWidth = Math.max(1, Math.round(ACCENT_WIDTH * scale));
		int radius = Math.round(CORNER_RADIUS * scale);

		NexoShapes.fillRoundedGradient(graphics, bounds.left(), bounds.top(), bounds.right(), bounds.bottom(),
				PANEL_TOP, NexoStyle.PANEL_BG, radius);

		int accentX = bounds.left() + padding;
		int accentY = bounds.top() + padding;
		int accentHeight = titleRow + artistRow;
		int accentColor = track.playing()
				? NexoStyle.cycle(System.currentTimeMillis(), ACCENT_CYCLE_MS)
				: NexoStyle.TEXT_DISABLED;
		NexoShapes.fillRounded(graphics, accentX, accentY, accentX + accentWidth, accentY + accentHeight,
				accentColor, accentWidth / 2);

		Font font = client.font;
		int textX = accentX + accentWidth + padding;
		int textRight = bounds.right() - padding;
		int textBudget = textRight - textX;
		graphics.text(font, ellipsize(font, track.title(), textBudget), textX, accentY, NexoStyle.TEXT_PRIMARY);
		String subtitle = track.artist().isEmpty() ? track.source() : track.artist();
		graphics.text(font, ellipsize(font, subtitle, textBudget), textX, accentY + titleRow, NexoStyle.TEXT_SECONDARY);

		int barX = accentX;
		int barY = accentY + accentHeight + barGap;
		int barWidth = textRight - barX;
		int barRadius = barHeight / 2;
		NexoShapes.fillRounded(graphics, barX, barY, barX + barWidth, barY + barHeight, NexoStyle.PANEL_BG, barRadius);
		if (track.lengthMicros() > 0 && barWidth > 0) {
			float fraction = Mth.clamp((float) track.positionMicros() / track.lengthMicros(), 0F, 1F);
			int filled = Math.round(barWidth * fraction);
			if (filled > 0) {
				NexoShapes.fillRounded(graphics, barX, barY, barX + filled, barY + barHeight,
						track.playing() ? NexoStyle.MINT : NexoStyle.TEXT_DISABLED, barRadius);
			}
		}
	}

	/** {@code text}, or the longest prefix that fits `maxWidth` pixels plus an ellipsis. */
	private static String ellipsize(Font font, String text, int maxWidth) {
		if (maxWidth <= 0 || font.width(text) <= maxWidth) {
			return text;
		}
		int budget = maxWidth - font.width(ELLIPSIS);
		if (budget <= 0) {
			return ELLIPSIS;
		}
		return font.plainSubstrByWidth(text, budget, false) + ELLIPSIS;
	}
}
