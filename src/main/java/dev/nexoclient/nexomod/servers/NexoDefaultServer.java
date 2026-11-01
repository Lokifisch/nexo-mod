package dev.nexoclient.nexomod.servers;

import java.util.Locale;

/**
 * NexoSMP, added to the multiplayer list once. After that it is the player's like any
 * other entry: {@code NexoConfig#defaultServerAdded} stays set, so deleting it sticks.
 *
 * <p>No Minecraft imports, so {@code tools/defaultserver-check} can run it with a plain
 * {@code javac}. {@code NexoTitleExtras} does the adding, and keeps it off the title card.
 */
public final class NexoDefaultServer {
	public static final String NAME = "NexoSMP";
	public static final String ADDRESS = "smp.nexomc.dev";

	private NexoDefaultServer() {}

	/** Whether a list entry already points at the server, however it was typed. */
	public static boolean isSameServer(String address) {
		return address != null && canonical(address).equals(ADDRESS);
	}

	private static String canonical(String address) {
		String clean = address.strip().toLowerCase(Locale.ROOT);
		if (clean.endsWith(":25565")) {
			clean = clean.substring(0, clean.length() - ":25565".length());
		}
		return clean.endsWith(".") ? clean.substring(0, clean.length() - 1) : clean;
	}
}
