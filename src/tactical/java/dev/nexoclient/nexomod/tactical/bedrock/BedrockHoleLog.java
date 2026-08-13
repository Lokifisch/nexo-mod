package dev.nexoclient.nexomod.tactical.bedrock;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The record of which holes have already been announced, kept on disk.
 *
 * Without it every join re-announces everything, because a fresh session has no
 * idea the last one already scanned this ground: chunk results live only in
 * memory, so rejoining a base means a screen of notifications for holes found
 * days ago. A hole is announced once, ever, per world.
 *
 * Records are keyed by world <em>and</em> dimension, so the same coordinates in
 * the Nether and in another save are different entries. Singleplayer worlds are
 * identified by their save folder rather than their display name, since two
 * worlds can share a name; servers by address.
 *
 * Positions are stored as plain {@code "x y z"} text — the file is meant to be
 * readable and editable, and deleting it (or one world's list) is the way to make
 * the finder announce those holes again.
 */
public final class BedrockHoleLog {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/bedrockHoles");
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-bedrock-holes.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * Positions kept per world before the oldest are dropped. Reaching this only
	 * costs a repeat announcement of something found very long ago.
	 */
	private static final int MAX_PER_WORLD = 4096;

	/** Shape of the file. A wrapper object rather than a bare map, so fields can be added later. */
	private static final class Stored {
		Map<String, List<String>> worlds = new LinkedHashMap<>();
	}

	private static Stored stored;
	private static String openKey = "";
	private static LinkedHashSet<String> open = new LinkedHashSet<>();
	private static boolean dirty;

	private BedrockHoleLog() {
	}

	/** Identity of the world the player is in, or null when that can't be determined yet. */
	public static String worldKey(Minecraft client) {
		if (client.level == null) {
			return null;
		}
		String world;
		if (client.hasSingleplayerServer()) {
			IntegratedServer server = client.getSingleplayerServer();
			if (server == null) {
				return null;
			}
			world = "local/" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
		} else {
			ServerData serverData = client.getCurrentServer();
			world = "server/" + (serverData != null ? serverData.ip : "unknown");
		}
		return world + "/" + client.level.dimension().identifier();
	}

	/** Switches to a world's records, writing back the previous world's first. Same key twice is a no-op. */
	public static void openWorld(String key) {
		if (key == null || key.equals(openKey)) {
			return;
		}
		flush();
		load();
		openKey = key;
		open = new LinkedHashSet<>(stored.worlds.getOrDefault(key, List.of()));
	}

	/**
	 * @return true when this position hadn't been recorded yet — that is, when the
	 *         find is worth announcing. Recording it is part of the same call so a
	 *         caller can't check and forget to store.
	 */
	public static boolean markFound(BlockPos pos) {
		if (openKey.isEmpty()) {
			return false;
		}
		if (!open.add(pos.getX() + " " + pos.getY() + " " + pos.getZ())) {
			return false;
		}
		Iterator<String> oldest = open.iterator();
		while (open.size() > MAX_PER_WORLD && oldest.hasNext()) {
			oldest.next();
			oldest.remove();
		}
		dirty = true;
		return true;
	}

	/** Writes pending records out. Cheap when nothing changed, so it can be called on a timer. */
	public static void flush() {
		if (!dirty) {
			return;
		}
		load();
		stored.worlds.put(openKey, new ArrayList<>(open));
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(stored, writer);
			}
			dirty = false;
		} catch (IOException e) {
			// Keep the records in memory and try again on the next flush: losing
			// them only means re-announcing, so this is never worth interrupting
			// the player over.
			LOGGER.warn("Failed to write {}", PATH, e);
		}
	}

	private static void load() {
		if (stored != null) {
			return;
		}
		stored = new Stored();
		if (!Files.exists(PATH)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			Stored read = GSON.fromJson(reader, Stored.class);
			if (read != null && read.worlds != null) {
				stored.worlds = read.worlds;
			}
		} catch (IOException | JsonParseException e) {
			LOGGER.warn("Failed to read {}, starting with no recorded holes", PATH, e);
		}
	}
}
