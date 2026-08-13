package dev.nexoclient.nexomod.chat;

/**
 * One row of the native chat database, in the exact field order
 * {@code KIND_CHAT_SEARCH} records use (see {@code rust-core/FFI_CONTRACT.md}
 * §3).
 *
 * <p>The order is load-bearing: payload records are read positionally, and
 * pulling the fields in a different order yields plausible-looking garbage
 * rather than an exception. Keeping the record's component order identical to
 * the wire order is what makes {@link NexoChatSearch}'s decode loop checkable by
 * eye against the contract.
 *
 * @param timestamp {@code System.currentTimeMillis()} at the moment the message
 *                  was displayed, not any server-side send time — the client
 *                  never learns the latter
 * @param server    where the message was seen; {@link #SINGLEPLAYER} for a local
 *                  world, so the column is never empty and searches can filter
 *                  on it
 * @param sender    the player who sent it, or one of the
 *                  {@link NexoChatHistory#SENDER_SYSTEM_SERVER} /
 *                  {@link NexoChatHistory#SENDER_SYSTEM_CLIENT} markers
 * @param message   the fully decorated text as it appeared on screen, flattened
 *                  to a string
 */
public record NexoChatMessage(long timestamp, String server, String sender, String message) {
	/** The {@code server} value used when there is no server — a single-player world. */
	public static final String SINGLEPLAYER = "singleplayer";
}
