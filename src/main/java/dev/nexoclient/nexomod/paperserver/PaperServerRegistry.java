package dev.nexoclient.nexomod.paperserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import dev.nexoclient.nexomod.util.NexoPaths;

/**
 * Reads and writes {@code nexo-paper-server.json}, one per hosted server, at
 * {@code <sharedDataDir>/paper-servers/<id>/}. Written and read from both
 * this class and a mirroring Rust module in Nexo Client — see
 * {@code Mod/docs/PAPER-SERVER-REGISTRY.md}.
 */
public final class PaperServerRegistry {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/paperserver");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path ROOT = NexoPaths.sharedDataDir().resolve("paper-servers");

	private PaperServerRegistry() {}

	public static Path serverDir(String id) {
		return ROOT.resolve(id);
	}

	private static Path recordFile(String id) {
		return serverDir(id).resolve("nexo-paper-server.json");
	}

	public static Optional<PaperServerRecord> read(String id) {
		Path file = recordFile(id);
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			return Optional.ofNullable(GSON.fromJson(json, PaperServerRecord.class));
		} catch (IOException | JsonParseException e) {
			LOGGER.warn("[nexomod] Could not read Paper server record {}", file, e);
			return Optional.empty();
		}
	}

	/** Every server this machine knows about, newest-created first. Skips any directory whose record fails to parse. */
	public static List<PaperServerRecord> listAll() {
		List<PaperServerRecord> records = new ArrayList<>();
		if (!Files.isDirectory(ROOT)) {
			return records;
		}
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(ROOT, Files::isDirectory)) {
			for (Path dir : dirs) {
				read(dir.getFileName().toString()).ifPresent(records::add);
			}
		} catch (IOException e) {
			LOGGER.warn("[nexomod] Could not list Paper servers under {}", ROOT, e);
		}
		records.sort(Comparator.comparingLong(PaperServerRecord::createdAtEpochSecond).reversed());
		return records;
	}

	/**
	 * ponytail: no cross-process lock. Start/stop are user-triggered from
	 * either the mod or the launcher, but not truly concurrent in practice —
	 * upgrade to a real lock file if testing ever shows the two racing.
	 */
	public static void write(PaperServerRecord record) {
		Path file = recordFile(record.id());
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(record), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOGGER.error("[nexomod] Could not write Paper server record {}", file, e);
		}
	}
}
