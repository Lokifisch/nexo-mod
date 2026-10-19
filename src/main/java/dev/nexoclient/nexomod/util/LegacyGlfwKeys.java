package dev.nexoclient.nexomod.util;

/**
 * Translates a GLFW key code, as configs written before 26.3 stored them, to
 * Minecraft's stable key name ({@code key.keyboard.page.up}).
 *
 * <p>26.3 replaced GLFW with SDL, and {@code InputConstants.KEY_*} became SDL
 * scancodes ({@code KEY_A} went from 65 to 4). A raw int saved by an older
 * build therefore names a different key, or none — so the keystrokes and
 * macro configs pass old codes through here and back into
 * {@code InputConstants.getKey(name)}, the same names {@code options.txt} has
 * always used. Imports nothing from Minecraft so
 * {@code tools/keycode-check/run.sh} can check it with plain {@code javac}.
 */
public final class LegacyGlfwKeys {
	private LegacyGlfwKeys() {}

	/** {@code null} for a code GLFW never defined (or {@code -1}, "unbound"). */
	public static String name(int glfw) {
		String n = switch (glfw) {
			case 32 -> "space";
			case 39 -> "apostrophe";
			case 44 -> "comma";
			case 45 -> "minus";
			case 46 -> "period";
			case 47 -> "slash";
			case 59 -> "semicolon";
			case 61 -> "equal";
			case 91 -> "left.bracket";
			case 92 -> "backslash";
			case 93 -> "right.bracket";
			case 96 -> "grave.accent";
			case 161 -> "world.1";
			case 162 -> "world.2";
			case 256 -> "escape";
			case 257 -> "enter";
			case 258 -> "tab";
			case 259 -> "backspace";
			case 260 -> "insert";
			case 261 -> "delete";
			case 262 -> "right";
			case 263 -> "left";
			case 264 -> "down";
			case 265 -> "up";
			case 266 -> "page.up";
			case 267 -> "page.down";
			case 268 -> "home";
			case 269 -> "end";
			case 280 -> "caps.lock";
			case 281 -> "scroll.lock";
			case 282 -> "num.lock";
			case 283 -> "print.screen";
			case 284 -> "pause";
			case 330 -> "keypad.decimal";
			case 331 -> "keypad.divide";
			case 332 -> "keypad.multiply";
			case 333 -> "keypad.subtract";
			case 334 -> "keypad.add";
			case 335 -> "keypad.enter";
			case 336 -> "keypad.equal";
			case 340 -> "left.shift";
			case 341 -> "left.control";
			case 342 -> "left.alt";
			case 343 -> "left.win";
			case 344 -> "right.shift";
			case 345 -> "right.control";
			case 346 -> "right.alt";
			case 347 -> "right.win";
			case 348 -> "menu";
			default -> {
				if (glfw >= 48 && glfw <= 57) yield String.valueOf((char) glfw);
				if (glfw >= 65 && glfw <= 90) yield String.valueOf((char) (glfw + 32));
				if (glfw >= 290 && glfw <= 313) yield "f" + (glfw - 289);
				if (glfw >= 320 && glfw <= 329) yield "keypad." + (glfw - 320);
				yield null;
			}
		};
		return n == null ? null : "key.keyboard." + n;
	}
}
