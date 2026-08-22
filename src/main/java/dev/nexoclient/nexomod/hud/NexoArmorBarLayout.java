package dev.nexoclient.nexomod.hud;

import java.util.Arrays;

/**
 * Which armour point belongs to which piece, and which of them the row on
 * screen is showing.
 *
 * <p>This is the idea the whole bar rests on: armour is counted in points, an
 * icon is worth two of them, and the two need not come from the same piece.
 * Diamond boots and iron leggings therefore meet in the middle of one icon, and
 * drawing that icon half cyan and half grey is the only honest thing to do with
 * it.
 *
 * <p>Split out of {@link NexoArmorBar} and kept free of every Minecraft type on
 * purpose. It is pure arithmetic with off-by-one traps in it — a wrong bound
 * here draws the wrong piece's colour on one half of one icon, which nobody
 * would ever notice in review — so {@code tools/armorbar-check} exercises it
 * directly. Because nothing here imports Minecraft, that check is a plain
 * {@code javac} and needs no Gradle-resolved classpath, unlike
 * {@code tools/geometry-check}.
 */
public final class NexoArmorBarLayout {
	/** Ten icons, two armour points each — the most one row can show. */
	public static final int POINTS_PER_ROW = 20;
	public static final int ICONS_PER_ROW = POINTS_PER_ROW / 2;

	/** No armour point behind this half of an icon. */
	public static final int EMPTY = -1;

	private NexoArmorBarLayout() {
	}

	/**
	 * @param defense armour points per worn piece, in the order they are worn
	 * @return {@code owner[p]} is the index into {@code defense} that granted
	 *         armour point {@code p}
	 */
	public static int[] owners(int[] defense) {
		int total = 0;
		for (int points : defense) {
			total += Math.max(points, 0);
		}
		int[] owner = new int[total];
		int at = 0;
		for (int piece = 0; piece < defense.length; piece++) {
			for (int i = 0; i < defense[piece]; i++) {
				owner[at++] = piece;
			}
		}
		return owner;
	}

	/**
	 * The ten icons of the row actually on screen, as owner indices per half:
	 * {@code halves[2i]} is icon {@code i}'s left half, {@code halves[2i + 1]}
	 * its right, and {@link #EMPTY} means that half has nothing behind it.
	 *
	 * <p>Past twenty points the bar does not grow a second row. It sits directly
	 * under the health bar, so growing upward would shove every status bar above
	 * it and leave the whole left stack jumping about as armour changed. The
	 * completed rows collapse into pips instead (see {@link #overflowRows}) and
	 * this shows the remainder, which is the part still changing.
	 */
	public static int[] visibleHalves(int[] owner) {
		int[] halves = new int[POINTS_PER_ROW];
		Arrays.fill(halves, EMPTY);
		int first = overflowRows(owner.length) * POINTS_PER_ROW;
		for (int i = 0; i < halves.length; i++) {
			int point = first + i;
			if (point < owner.length) {
				halves[i] = owner[point];
			}
		}
		return halves;
	}

	/**
	 * Rows already filled behind the visible one — the number of stack pips.
	 *
	 * <p>Exactly twenty points is one full row and no pips, not an empty row
	 * plus a pip, which is why this counts from {@code total - 1}.
	 */
	public static int overflowRows(int totalPoints) {
		return totalPoints <= 0 ? 0 : (totalPoints - 1) / POINTS_PER_ROW;
	}
}
