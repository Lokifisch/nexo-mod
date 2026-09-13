package dev.nexoclient.nexomod.paperserver;

import java.time.Instant;

/**
 * The wire format for {@code <serverDir>/nexo-paper-server.json} — written
 * and read by both Nexo Mod (this class) and Nexo Client (a mirroring Rust
 * struct), so either side can start, observe, or stop a server the other
 * side created. See {@code Mod/docs/PAPER-SERVER-REGISTRY.md}.
 *
 * <p>Field names are the wire format: Gson serialises record components by
 * name and the launcher's Rust structs are renamed to match, same
 * convention as {@code auth.AccountStore}'s {@code StoredAccount}.
 */
public record PaperServerRecord(
		int schemaVersion,
		String id,
		String name,
		String minecraftVersion,
		Integer paperBuild,
		String levelName,
		int port,
		Rcon rcon,
		Eula eula,
		String status,
		Owner owner,
		SourceWorld sourceWorld,
		FriendHosting friendHosting,
		long createdAtEpochSecond,
		long updatedAtEpochSecond) {

	public static final int CURRENT_SCHEMA_VERSION = 1;

	/** {@link #status} values. Plain strings, not a Gson enum, to match the documented wire format exactly. */
	public static final class Status {
		public static final String CONVERTING = "converting";
		public static final String STOPPED = "stopped";
		public static final String STARTING = "starting";
		public static final String RUNNING = "running";
		public static final String STOPPING = "stopping";
		public static final String ERROR = "error";

		private Status() {}
	}

	public record Rcon(int port, String password) {}

	public record Eula(boolean accepted, Long acceptedAtEpochSecond, String acceptedBy) {
		public static final Eula UNACCEPTED = new Eula(false, null, null);
	}

	/** {@code startedBy} is {@code "mod"} or {@code "launcher"}; null fields mean no owning process is known. */
	public record Owner(String startedBy, Long pid, String hostname, Long startedAtEpochSecond) {
		public static final Owner NONE = new Owner(null, null, null, null);
	}

	/**
	 * {@code originalSavePath} is an absolute, platform-native path — the
	 * Rust side manages multiple instances, each with its own {@code saves/},
	 * so a bare name isn't enough for it to find the world back on its own.
	 * {@code originalSaveId} stays as the human-readable label (the world
	 * folder's own name).
	 */
	public record SourceWorld(String originalSaveId, String originalSavePath, long convertedAtEpochSecond) {}

	public record FriendHosting(boolean tunnelActive, String domain) {
		public static final FriendHosting INACTIVE = new FriendHosting(false, null);
	}

	/** A fresh, unconverted, unstarted record — the state a new server registration starts in. */
	public static PaperServerRecord create(String id, String name, String minecraftVersion, String levelName,
			int port, Rcon rcon, SourceWorld sourceWorld) {
		long now = Instant.now().getEpochSecond();
		return new PaperServerRecord(CURRENT_SCHEMA_VERSION, id, name, minecraftVersion, null, levelName, port,
				rcon, Eula.UNACCEPTED, Status.CONVERTING, Owner.NONE, sourceWorld, FriendHosting.INACTIVE, now, now);
	}

	public PaperServerRecord withStatus(String newStatus) {
		return new PaperServerRecord(schemaVersion, id, name, minecraftVersion, paperBuild, levelName, port, rcon,
				eula, newStatus, owner, sourceWorld, friendHosting, createdAtEpochSecond, Instant.now().getEpochSecond());
	}

	public PaperServerRecord withPaperBuild(int build) {
		return new PaperServerRecord(schemaVersion, id, name, minecraftVersion, build, levelName, port, rcon, eula,
				status, owner, sourceWorld, friendHosting, createdAtEpochSecond, Instant.now().getEpochSecond());
	}

	public PaperServerRecord withEulaAccepted(String acceptedBy) {
		Eula accepted = new Eula(true, Instant.now().getEpochSecond(), acceptedBy);
		return new PaperServerRecord(schemaVersion, id, name, minecraftVersion, paperBuild, levelName, port, rcon,
				accepted, status, owner, sourceWorld, friendHosting, createdAtEpochSecond, Instant.now().getEpochSecond());
	}

	public PaperServerRecord withOwner(Owner newOwner) {
		return new PaperServerRecord(schemaVersion, id, name, minecraftVersion, paperBuild, levelName, port, rcon,
				eula, status, newOwner, sourceWorld, friendHosting, createdAtEpochSecond, Instant.now().getEpochSecond());
	}

	public PaperServerRecord withFriendHosting(FriendHosting newFriendHosting) {
		return new PaperServerRecord(schemaVersion, id, name, minecraftVersion, paperBuild, levelName, port, rcon,
				eula, status, owner, sourceWorld, newFriendHosting, createdAtEpochSecond, Instant.now().getEpochSecond());
	}
}
