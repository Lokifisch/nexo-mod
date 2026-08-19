package dev.nexoclient.nexomod.hud;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Which keys the keystrokes HUD shows, beyond the built-in WASD+click
 * cluster — "add Shift", "add G", however many the player wants.
 *
 * <p>The built-in cluster ({@link NexoKeystrokesHud}'s classic W-above-ASD
 * cross plus LMB/RMB) is drawn from fixed code, not from this list — it's
 * proven, it's what every keystrokes overlay looks like by default, and
 * turning it into data would mean reproducing its cross layout generically
 * for no real gain. What's actually configurable is whether it shows at all
 * ({@link #showDefaultCluster}) and what gets added below it: each custom
 * entry is one box, appended in the order added, wrapping into rows.
 *
 * <p>A custom entry stores a raw GLFW key code, not a Minecraft
 * {@code KeyMapping} — Minecraft's keybinding system expects mappings
 * registered once at startup, which doesn't fit "the player adds one from a
 * screen at 2am". {@code NexoKeystrokesConfigScreen} captures the code via
 * the same key-event vanilla's own Controls screen uses to bind a key, and
 * {@link NexoKeystrokesHud} reads it back with
 * {@code InputConstants.isKeyDown}, which doesn't need a registered mapping
 * either.
 */
public final class NexoKeystrokesConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/keystrokes");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-keystrokes.json");

	/** One user-added box. `keyCode` is a {@code InputConstants.KEY_*} value. */
	public static final class KeyEntry {
		public String label;
		public int keyCode;

		public KeyEntry() {
		}

		public KeyEntry(String label, int keyCode) {
			this.label = label;
			this.keyCode = keyCode;
		}
	}

	private static final class Data {
		boolean showDefaultCluster = true;
		List<KeyEntry> customEntries = new ArrayList<>();
	}

	private static NexoKeystrokesConfig instance;

	private final Data data;

	private NexoKeystrokesConfig(Data data) {
		this.data = data;
	}

	public static synchronized NexoKeystrokesConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public boolean showDefaultCluster() {
		return data.showDefaultCluster;
	}

	public void setShowDefaultCluster(boolean show) {
		data.showDefaultCluster = show;
		save();
	}

	public List<KeyEntry> customEntries() {
		return data.customEntries;
	}

	public void addEntry(KeyEntry entry) {
		data.customEntries.add(entry);
		save();
	}

	public void removeEntry(KeyEntry entry) {
		data.customEntries.remove(entry);
		save();
	}

	/** Call after mutating an entry's fields directly (the config screen's rebind does this) to persist the change. */
	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(data, Data.class, writer);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to save {}", PATH, e);
		}
	}

	private static NexoKeystrokesConfig load() {
		if (!Files.exists(PATH)) {
			return new NexoKeystrokesConfig(new Data());
		}
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			Data loaded = GSON.fromJson(reader, Data.class);
			return new NexoKeystrokesConfig(loaded != null ? loaded : new Data());
		} catch (IOException e) {
			LOGGER.warn("Failed to read {}, starting with no custom keys", PATH, e);
			return new NexoKeystrokesConfig(new Data());
		}
	}
}
