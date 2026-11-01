package dev.nexoclient.nexomod.title;

/**
 * The window's title: "Nexo Client 26.3 Hugosmp", naming the server rather than
 * vanilla's "Multiplayer (3rd-party Server)".
 *
 * <p>No Minecraft imports, so {@code tools/windowtitle-check} can run it with a plain
 * {@code javac}. {@code WindowTitleMixin} supplies what's read from the game.
 */
public final class NexoWindowTitle {
	/** Well inside what window systems accept, and past what a title bar can show. */
	private static final int PLACE_LIMIT = 64;

	private NexoWindowTitle() {}

	/** {@code place} is what the player is connected to, or null at the menus. */
	public static String title(String version, String place) {
		String clean = clean(place);
		return clean.isEmpty() ? "Nexo Client " + version : "Nexo Client " + version + " " + clean;
	}

	/** What the clean-screenshot and ghost switches show: nothing about Nexo or the server. */
	public static String plainTitle(String version) {
		return "Minecraft " + version;
	}

	/**
	 * A server's name from the list, or null if it has none of its own. Never its
	 * address: that can be a friend's home IP, and window titles end up in screen
	 * shares. Quick Servers and Discord joins use the address as the name, and Direct
	 * Connect gives every server {@code defaultName}.
	 */
	public static String serverPlace(String name, String address, String defaultName) {
		return name == null || name.isBlank() || name.equals(defaultName) || name.equals(address) ? null : name;
	}

	/**
	 * These names come from LAN broadcasts, Realm owners and downloaded worlds, and
	 * vanilla never puts them in a title. Formatting and control characters go, and
	 * the length is capped: a 64 KiB name overflows the stack the title is encoded on,
	 * which crashes the client.
	 */
	private static String clean(String place) {
		if (place == null) {
			return "";
		}
		String clean = place.replaceAll("\u00a7.|[\\p{Cc}\\p{Cf}]", "").strip();
		if (clean.codePointCount(0, clean.length()) > PLACE_LIMIT) {
			clean = clean.substring(0, clean.offsetByCodePoints(0, PLACE_LIMIT)).strip();
		}
		return clean;
	}
}
