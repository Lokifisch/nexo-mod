package dev.nexoclient.nexomod.servers;

/**
 * One favourite in the quick-switch list.
 *
 * <p>Mutable fields with a no-arg constructor, matching
 * {@link dev.nexoclient.nexomod.macro.NexoMacro}: the edit screen writes
 * straight into the object a row is bound to, and Gson round-trips it without a
 * type adapter.
 *
 * <p>Deliberately <em>not</em> a {@code ServerData}. That class carries a live
 * ping, a MOTD, an icon and a resource-pack decision, all of which are state
 * about one connection attempt rather than about the entry; storing it would
 * mean persisting a stale ping and re-deriving the parts that matter anyway. A
 * {@code ServerData} is built fresh at connect time from these two strings.
 */
public final class NexoServerEntry {
	/** What the button says. Falls back to the address when left empty. */
	public String name = "";
	/** Host, optionally {@code host:port} — parsed by {@code ServerAddress.parseString}. */
	public String address = "";

	public NexoServerEntry() {
	}

	public NexoServerEntry(String name, String address) {
		this.name = name;
		this.address = address;
	}

	public String displayName() {
		return name == null || name.isBlank() ? address : name;
	}

	public boolean isUsable() {
		return address != null && !address.isBlank();
	}
}
