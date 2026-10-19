package dev.nexoclient.nexomod.util;

/** Spot-checks LegacyGlfwKeys against GLFW's documented values, one per range. */
public final class KeycodeCheck {
	public static void main(String[] args) {
		check(65, "key.keyboard.a");
		check(90, "key.keyboard.z");
		check(48, "key.keyboard.0");
		check(96, "key.keyboard.grave.accent");
		check(266, "key.keyboard.page.up");
		check(290, "key.keyboard.f1");
		check(313, "key.keyboard.f24");
		check(320, "key.keyboard.keypad.0");
		check(340, "key.keyboard.left.shift");
		check(341, "key.keyboard.left.control");
		check(-1, null);
		check(314, null);
		System.out.println("keycode-check: ok");
	}

	private static void check(int glfw, String want) {
		String got = LegacyGlfwKeys.name(glfw);
		if (want == null ? got != null : !want.equals(got)) {
			throw new AssertionError(glfw + ": want " + want + ", got " + got);
		}
	}
}
