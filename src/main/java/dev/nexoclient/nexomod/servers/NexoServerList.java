package dev.nexoclient.nexomod.servers;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

import dev.nexoclient.nexomod.NexoMod;

/**
 * The persisted quick-switch favourites list.
 *
 * <h2>Why not read vanilla's {@code servers.dat}</h2>
 *
 * <p>Vanilla already keeps a server list, and reusing it was the obvious first
 * idea. It is the wrong one: {@code ServerList} is loaded and saved by
 * {@code JoinMultiplayerScreen}, which writes the whole file back on close, so a
 * quick-switch entry added here would either be clobbered by the next visit to
 * the multiplayer screen or would have to be written through that screen's own
 * lifecycle from a keybind that runs while no screen is open. It is also the
 * player's own list — reordering or pruning it to make a five-entry switcher
 * usable would edit something they curate by hand.
 *
 * <p>So this is a separate, deliberately tiny file with the same shape as the
 * macro list next door: plain JSON, no secrets in it, loaded once and saved on
 * every edit.
 */
public final class NexoServerList {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST_TYPE = new TypeToken<List<NexoServerEntry>>() {}.getType();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-servers.json");

	private static NexoServerList instance;

	private final List<NexoServerEntry> entries;

	private NexoServerList(List<NexoServerEntry> entries) {
		this.entries = entries;
	}

	public static synchronized NexoServerList get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** Live list; mutate it and call {@link #save()}, or use the helpers below. */
	public List<NexoServerEntry> entries() {
		return entries;
	}

	public void add(NexoServerEntry entry) {
		entries.add(entry);
		save();
	}

	public void remove(NexoServerEntry entry) {
		entries.remove(entry);
		save();
	}

	/** Moves an entry one slot towards the front; the order here is the order the switcher shows. */
	public void moveUp(NexoServerEntry entry) {
		int index = entries.indexOf(entry);
		if (index > 0) {
			entries.remove(index);
			entries.add(index - 1, entry);
			save();
		}
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(entries, LIST_TYPE, writer);
			}
		} catch (IOException e) {
			NexoMod.LOGGER.warn("Failed to save {}", PATH, e);
		}
	}

	private static NexoServerList load() {
		if (!Files.exists(PATH)) {
			return new NexoServerList(new ArrayList<>());
		}
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			List<NexoServerEntry> loaded = GSON.fromJson(reader, LIST_TYPE);
			return new NexoServerList(loaded != null ? loaded : new ArrayList<>());
		} catch (IOException | JsonParseException e) {
			NexoMod.LOGGER.warn("Failed to read {}, starting with no quick-switch servers", PATH, e);
			return new NexoServerList(new ArrayList<>());
		}
	}
}
