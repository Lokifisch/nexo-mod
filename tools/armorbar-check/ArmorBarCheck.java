package dev.nexoclient.nexomod.hud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Asserts {@link NexoArmorBarLayout} against hand-worked examples.
 *
 * <p>The armour bar's one piece of real arithmetic is deciding which piece owns
 * which half of which icon. It is all off-by-ones, and every way of getting it
 * wrong is invisible: the bar still fills to the right length, one half of one
 * icon is just the wrong colour. Nobody would catch that in review and nobody
 * would notice it in game either, which is exactly the shape of thing worth a
 * check.
 *
 * <p>Run through {@code tools/armorbar-check/run.sh}. Unlike
 * {@code tools/geometry-check}, this needs no Minecraft on the classpath —
 * {@code NexoArmorBarLayout} imports nothing but {@code java.util.Arrays},
 * which is the reason it is a class of its own. Same house style as
 * {@code tools/badge-format-check}: a checked-in main class, because this repo
 * has no Java test framework.
 */
public final class ArmorBarCheck {
	private static final List<String> FAILURES = new ArrayList<>();

	public static void main(String[] args) {
		twoPiecesShareAnIcon();
		aFullRowNeverSplits();
		anOddTotalEndsOnAHalf();
		overflowCollapsesIntoPips();
		nothingWornDrawsNothing();

		if (!FAILURES.isEmpty()) {
			FAILURES.forEach(failure -> System.out.println("FAIL: " + failure));
			System.out.println(FAILURES.size() + " check(s) failed");
			System.exit(1);
		}
		System.out.println("OK: the armour point split matches every hand-worked value.");
	}

	/**
	 * A diamond chestplate (8) and iron boots (2).
	 *
	 * <p>Ten points, so five icons. The chestplate owns points 0-7 and the boots
	 * 8-9, which lands the boundary on an icon edge — icon 4 is wholly boots.
	 * Shift the chestplate by one and the boundary moves inside an icon, which
	 * is the case the whole half-icon path exists for, so both are checked.
	 */
	private static void twoPiecesShareAnIcon() {
		int[] halves = halves(new int[]{8, 2});
		expect("aligned: icon 3 left", halves[6], 0);
		expect("aligned: icon 3 right", halves[7], 0);
		expect("aligned: icon 4 left", halves[8], 1);
		expect("aligned: icon 4 right", halves[9], 1);
		expect("aligned: icon 5 is empty", halves[10], -1);

		// Seven-point chestplate against three-point boots: the seam now falls
		// mid-icon, so icon 3 is half one piece and half the other.
		int[] straddling = halves(new int[]{7, 3});
		expect("straddling: icon 3 left", straddling[6], 0);
		expect("straddling: icon 3 right", straddling[7], 1);
		if (straddling[6] == straddling[7]) {
			FAILURES.add("a seam falling mid-icon did not produce a split icon");
		}
	}

	/** Exactly twenty points fills the row with no half and no leftover. */
	private static void aFullRowNeverSplits() {
		int[] halves = halves(new int[]{5, 8, 5, 2});
		for (int i = 0; i < halves.length; i++) {
			if (halves[i] < 0) {
				FAILURES.add("a full row left half " + i + " empty");
				break;
			}
		}
		expect("a full row has no overflow pips", NexoArmorBarLayout.overflowRows(20), 0);
	}

	/** An odd total leaves the last drawn icon half-lit and everything after it empty. */
	private static void anOddTotalEndsOnAHalf() {
		int[] halves = halves(new int[]{5});
		expect("icon 2 left is owned", halves[4], 0);
		expect("icon 2 right is empty", halves[5], -1);
		expect("icon 3 left is empty", halves[6], -1);
	}

	/**
	 * Twenty-five points: one row is complete and collapses into a pip, and the
	 * visible row shows the remaining five.
	 */
	private static void overflowCollapsesIntoPips() {
		expect("25 points is one pip", NexoArmorBarLayout.overflowRows(25), 1);
		expect("40 points is one pip", NexoArmorBarLayout.overflowRows(40), 1);
		expect("41 points is two pips", NexoArmorBarLayout.overflowRows(41), 2);
		expect("60 points is two pips", NexoArmorBarLayout.overflowRows(60), 2);

		int[] halves = halves(new int[]{25});
		expect("icon 0 left shows the overflow row", halves[0], 0);
		expect("the visible row holds five points: icon 2 right", halves[5], -1);
		expect("the visible row holds five points: icon 2 left", halves[4], 0);
	}

	private static void nothingWornDrawsNothing() {
		expect("no armour is no points", NexoArmorBarLayout.owners(new int[0]).length, 0);
		expect("no armour is no pips", NexoArmorBarLayout.overflowRows(0), 0);
		int[] halves = NexoArmorBarLayout.visibleHalves(new int[0]);
		for (int half : halves) {
			if (half != -1) {
				FAILURES.add("an empty bar reported an owned half: " + Arrays.toString(halves));
				break;
			}
		}
	}

	// -- helpers ---------------------------------------------------------------

	private static int[] halves(int[] defense) {
		return NexoArmorBarLayout.visibleHalves(NexoArmorBarLayout.owners(defense));
	}

	private static void expect(String what, int actual, int wanted) {
		if (actual != wanted) {
			FAILURES.add(what + " was " + actual + ", expected " + wanted);
		}
	}

	private ArmorBarCheck() {
	}
}
