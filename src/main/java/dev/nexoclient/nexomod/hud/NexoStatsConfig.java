package dev.nexoclient.nexomod.hud;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

/** Which {@link NexoStatsRegistry} stat lines are currently shown — a set of stat ids, nothing more. */
public final class NexoStatsConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/stats");
	private static final Gson GSON = new Gson();
	private static final Type SET_TYPE = new TypeToken<Set<String>>() {
	}.getType();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-stats.json");

	/** On by default: FPS is the one stat almost everyone using this module wants. */
	private static final Set<String> DEFAULT_ENABLED = Set.of("fps");

	private static NexoStatsConfig instance;

	private final Set<String> enabled;

	private NexoStatsConfig(Set<String> enabled) {
		this.enabled = enabled;
	}

	public static synchronized NexoStatsConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public boolean isEnabled(String statId) {
		return enabled.contains(statId);
	}

	public void setEnabled(String statId, boolean value) {
		if (value ? enabled.add(statId) : enabled.remove(statId)) {
			save();
		}
	}

	private void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(enabled, SET_TYPE, writer);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to save {}", PATH, e);
		}
	}

	private static NexoStatsConfig load() {
		if (!Files.exists(PATH)) {
			return new NexoStatsConfig(new HashSet<>(DEFAULT_ENABLED));
		}
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			Set<String> loaded = GSON.fromJson(reader, SET_TYPE);
			return new NexoStatsConfig(loaded != null ? new HashSet<>(loaded) : new HashSet<>(DEFAULT_ENABLED));
		} catch (IOException e) {
			LOGGER.warn("Failed to read {}, using defaults", PATH, e);
			return new NexoStatsConfig(new HashSet<>(DEFAULT_ENABLED));
		}
	}
}
