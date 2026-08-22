package dev.nexoclient.nexomod.hud;

import java.util.List;
import java.util.function.BooleanSupplier;

import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * The vanilla HUD pieces Nexo takes over: each one can be hidden, and each one
 * can be dragged somewhere else.
 *
 * <h2>Moving something we didn't draw</h2>
 *
 * <p>These are rendered by vanilla code that computes its own absolute
 * coordinates from the screen size — there is no position to override. What
 * there is, is a matrix: {@code NexoHudCleaner} wraps each element and
 * translates the pose by the difference between the saved position and where
 * vanilla would have put it, then delegates. Everything the element draws moves
 * with it, including pieces whose size we could never predict, like a scoreboard
 * sized to its longest line.
 *
 * <h2>Why the anchors are approximations</h2>
 *
 * <p>{@link Entry#resolveBounds} needs a rectangle for the layout editor to
 * hand a drag handle for, but a scoreboard's real size depends on its contents
 * and a boss bar's on how many bosses are in range. These are vanilla's own
 * anchor points with a representative size — close enough to grab and drag, and
 * the offset that comes out is exact regardless of whether the size guess was.
 * That is also why the drag box may not hug the element perfectly: it is a
 * handle, not an outline.
 */
public final class NexoVanillaHud {
	private NexoVanillaHud() {
	}

	/**
	 * @param anchor where vanilla draws this, used as the zero point the saved
	 *               position is measured against
	 */
	public record Entry(Identifier id, NexoHudLayout.Element slot, Component label,
			BooleanSupplier hidden, int nominalWidth, int nominalHeight, Anchor anchor) {

		/** The rectangle the editor drags — the saved override if there is one, else vanilla's own spot. */
		public ScreenRectangle resolveBounds(int guiWidth, int guiHeight) {
			NexoHudLayout.Position override = NexoHudLayout.get().get(slot);
			float scale = override != null ? override.scale : 1f;
			int width = Math.round(nominalWidth * scale);
			int height = Math.round(nominalHeight * scale);
			int x = override != null ? override.x : anchor.x(guiWidth, guiHeight, nominalWidth, nominalHeight);
			int y = override != null ? override.y : anchor.y(guiWidth, guiHeight, nominalWidth, nominalHeight);
			return NexoHudBounds.clamp(x, y, width, height, guiWidth, guiHeight);
		}

		/** How far to shift vanilla's drawing, or zeroes when this element has never been moved. */
		public int[] offset(int guiWidth, int guiHeight) {
			NexoHudLayout.Position override = NexoHudLayout.get().get(slot);
			if (override == null) {
				return NO_OFFSET;
			}
			return new int[]{
					override.x - anchor.x(guiWidth, guiHeight, nominalWidth, nominalHeight),
					override.y - anchor.y(guiWidth, guiHeight, nominalWidth, nominalHeight)
			};
		}

		public float scale() {
			NexoHudLayout.Position override = NexoHudLayout.get().get(slot);
			return override != null ? override.scale : 1f;
		}
	}

	/** Vanilla's own placement for an element, as a function of the screen size. */
	public interface Anchor {
		int x(int guiWidth, int guiHeight, int width, int height);

		int y(int guiWidth, int guiHeight, int width, int height);
	}

	private static final int[] NO_OFFSET = {0, 0};

	/**
	 * Ordered as they appear in the HUD Cleaner screen. Sizes and anchors mirror
	 * vanilla's own layout in {@code Gui}: the scoreboard hangs off the right edge
	 * at mid-height, boss bars stack from the top centre, the overlay message sits
	 * just above the hotbar, and effect icons run from the top-right corner.
	 */
	public static final List<Entry> ENTRIES = List.of(
			new Entry(VanillaHudElements.OVERLAY_MESSAGE, NexoHudLayout.Element.VANILLA_ACTIONBAR,
					Component.translatable("nexomod.qol.hudCleaner.actionbar"),
					() -> NexoConfig.get().hideVanillaActionbar(), 160, 12,
					new Anchor() {
						@Override
						public int x(int guiWidth, int guiHeight, int width, int height) {
							return (guiWidth - width) / 2;
						}

						@Override
						public int y(int guiWidth, int guiHeight, int width, int height) {
							return guiHeight - 68;
						}
					}),
			new Entry(VanillaHudElements.MOB_EFFECTS, NexoHudLayout.Element.VANILLA_POTIONS,
					Component.translatable("nexomod.qol.hudCleaner.potionIcons"),
					() -> NexoConfig.get().hideVanillaPotionIcons(), 100, 26,
					new Anchor() {
						@Override
						public int x(int guiWidth, int guiHeight, int width, int height) {
							return guiWidth - width - 2;
						}

						@Override
						public int y(int guiWidth, int guiHeight, int width, int height) {
							return 2;
						}
					}),
			new Entry(VanillaHudElements.SCOREBOARD, NexoHudLayout.Element.VANILLA_SCOREBOARD,
					Component.translatable("nexomod.qol.hudCleaner.scoreboard"),
					() -> NexoConfig.get().hideScoreboard(), 90, 80,
					new Anchor() {
						@Override
						public int x(int guiWidth, int guiHeight, int width, int height) {
							return guiWidth - width - 3;
						}

						@Override
						public int y(int guiWidth, int guiHeight, int width, int height) {
							return guiHeight / 2 - height / 2;
						}
					}),
			new Entry(VanillaHudElements.BOSS_BAR, NexoHudLayout.Element.VANILLA_BOSS_BAR,
					Component.translatable("nexomod.qol.hudCleaner.bossBars"),
					() -> NexoConfig.get().hideBossBars(), 182, 20,
					new Anchor() {
						@Override
						public int x(int guiWidth, int guiHeight, int width, int height) {
							return (guiWidth - width) / 2;
						}

						@Override
						public int y(int guiWidth, int guiHeight, int width, int height) {
							return 12;
						}
					}));
}
