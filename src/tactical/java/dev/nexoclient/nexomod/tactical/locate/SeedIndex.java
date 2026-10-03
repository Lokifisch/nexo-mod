package dev.nexoclient.nexomod.tactical.locate;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;

/**
 * The seed a player has told Nexo about, per server or world — what
 * {@link NexoLocateCommand} computes structure positions from.
 *
 * <p>A seed is not a secret from its owner, but it is what lets anyone find
 * every structure in a world, so this file is plain JSON on the player's own
 * disk and is never sent anywhere. Singleplayer worlds are recorded
 * automatically on join (the integrated server knows its own seed); a
 * multiplayer seed only ever gets here because the player typed it.
 */
public final class SeedIndex {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/seeds");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST_TYPE = new TypeToken<List<Entry>>() {}.getType();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-seeds.json");

	private static SeedIndex instance;

	public static final class Entry {
		/** {@code mp:<address>} or {@code sp:<level name>} — what "the same place" means. */
		public String key;
		/** Server name or world name, as shown in the index. */
		public String label;
		/** Empty for worlds. */
		public String address = "";
		public boolean world;
		public long seed;
		/** {@link WorldgenVersion#label()} of the server's generator; null in entries saved before this existed. */
		public String version;
		/** {@link Source} name; null in entries saved before sources existed, which were all typed in. */
		public String source;
		public long updated;
	}

	/** Where a seed came from, so the index can say how far to trust it. */
	public enum Source {
		MANUAL, SINGLEPLAYER, SEEDCRACKER, COMMUNITY;

		public static Source of(String name) {
			try {
				return name == null ? MANUAL : valueOf(name);
			} catch (IllegalArgumentException e) {
				return MANUAL;
			}
		}
	}

	private final List<Entry> entries;

	private SeedIndex(List<Entry> entries) {
		this.entries = entries;
	}

	public static synchronized SeedIndex get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** Servers first, then worlds, each alphabetical — the order the index screen shows. */
	public synchronized List<Entry> sorted() {
		List<Entry> copy = new ArrayList<>(entries);
		copy.sort(Comparator.comparing((Entry e) -> e.world)
				.thenComparing(e -> e.label == null ? "" : e.label.toLowerCase(Locale.ROOT)));
		return copy;
	}

	public synchronized Optional<Entry> find(String key) {
		return entries.stream().filter(e -> e.key.equals(key)).findFirst();
	}

	public synchronized void put(String key, String label, String address, boolean world, long seed, Source source, WorldgenVersion version) {
		Entry entry = find(key).orElseGet(() -> {
			Entry created = new Entry();
			created.key = key;
			entries.add(created);
			return created;
		});
		entry.label = label;
		entry.address = address;
		entry.world = world;
		entry.seed = seed;
		entry.source = source.name();
		if (version != null) {
			entry.version = version.label();
		}
		entry.updated = System.currentTimeMillis();
		save();
	}

	public synchronized void remove(Entry entry) {
		entries.remove(entry);
		save();
	}

	public synchronized void setVersion(Entry entry, WorldgenVersion version) {
		entry.version = version.label();
		save();
	}

	public synchronized void setSeed(Entry entry, long seed) {
		entry.seed = seed;
		entry.source = Source.MANUAL.name();
		entry.updated = System.currentTimeMillis();
		save();
	}

	/** The server or world the player is in right now, or empty on the title screen. */
	public static Optional<Place> currentPlace() {
		Minecraft client = Minecraft.getInstance();
		MinecraftServer integrated = client.getSingleplayerServer();
		if (integrated != null) {
			String name = integrated.getWorldData().getLevelName();
			return Optional.of(new Place("sp:" + name, name, "", true));
		}
		ServerData server = client.getCurrentServer();
		if (server != null && server.ip != null) {
			String label = server.name == null || server.name.isBlank() ? server.ip : server.name;
			return Optional.of(new Place("mp:" + server.ip.toLowerCase(Locale.ROOT), label, server.ip, false));
		}
		return Optional.empty();
	}

	public record Place(String key, String label, String address, boolean world) {
	}

	/** Same rule as the world-creation screen: a number is the seed, anything else is its Java string hash. */
	public static long parseSeed(String text) {
		String trimmed = text.trim();
		try {
			return Long.parseLong(trimmed);
		} catch (NumberFormatException e) {
			return trimmed.hashCode();
		}
	}

	public static OptionalLong tryParseSeed(String text) {
		return text == null || text.isBlank() ? OptionalLong.empty() : OptionalLong.of(parseSeed(text));
	}

	private void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(entries, LIST_TYPE, writer);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to save {}", PATH, e);
		}
	}

	private static SeedIndex load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				List<Entry> loaded = GSON.fromJson(reader, LIST_TYPE);
				if (loaded != null) {
					loaded.removeIf(e -> e == null || e.key == null);
					return new SeedIndex(new ArrayList<>(loaded));
				}
			} catch (IOException | JsonParseException e) {
				LOGGER.warn("Failed to read {}, starting empty", PATH, e);
			}
		}
		return new SeedIndex(new ArrayList<>());
	}
}
