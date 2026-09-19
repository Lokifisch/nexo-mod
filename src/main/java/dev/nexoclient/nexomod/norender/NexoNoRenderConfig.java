package dev.nexoclient.nexomod.norender;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

/**
 * Persisted state for the No Render feature: which entity type registry
 * paths are manually hidden, and Dynamic mode's on/off and FPS threshold.
 * Deliberately its own small file rather than folded into the big
 * {@code NexoConfig} — same reasoning as {@code NexoStatsConfig}: the data
 * is a set of ids plus a couple of settings, nothing else needs it.
 *
 * <p>Only the manual set is read by {@link NexoNoRender#isHidden}; Dynamic's
 * own hidden set is intentionally never persisted — see {@code NexoNoRender}.
 */
public final class NexoNoRenderConfig {
	public static final int DYNAMIC_FPS_THRESHOLD_MIN = 5;
	public static final int DYNAMIC_FPS_THRESHOLD_MAX = 60;
	private static final int DEFAULT_DYNAMIC_FPS_THRESHOLD = 30;

	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/norender");
	private static final Gson GSON = new Gson();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-norender.json");

	private static NexoNoRenderConfig instance;

	private static final class Data {
		Set<String> manuallyHidden = new HashSet<>();
		boolean dynamicEnabled;
		int dynamicFpsThreshold = DEFAULT_DYNAMIC_FPS_THRESHOLD;
	}

	private final Data data;

	private NexoNoRenderConfig(Data data) {
		this.data = data;
	}

	public static synchronized NexoNoRenderConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public boolean isManuallyHidden(String entityTypePath) {
		return data.manuallyHidden.contains(entityTypePath);
	}

	public Set<String> manuallyHidden() {
		return Set.copyOf(data.manuallyHidden);
	}

	public void setManuallyHidden(String entityTypePath, boolean hidden) {
		if (hidden ? data.manuallyHidden.add(entityTypePath) : data.manuallyHidden.remove(entityTypePath)) {
			save();
		}
	}

	public boolean dynamicEnabled() {
		return data.dynamicEnabled;
	}

	public void setDynamicEnabled(boolean enabled) {
		if (data.dynamicEnabled != enabled) {
			data.dynamicEnabled = enabled;
			save();
		}
	}

	public int dynamicFpsThreshold() {
		return data.dynamicFpsThreshold;
	}

	public void setDynamicFpsThreshold(int threshold) {
		data.dynamicFpsThreshold = Mth.clamp(threshold, DYNAMIC_FPS_THRESHOLD_MIN, DYNAMIC_FPS_THRESHOLD_MAX);
		save();
	}

	private void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(data, Data.class, writer);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to save {}", PATH, e);
		}
	}

	private static NexoNoRenderConfig load() {
		if (!Files.exists(PATH)) {
			return new NexoNoRenderConfig(new Data());
		}
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			Data loaded = GSON.fromJson(reader, Data.class);
			return new NexoNoRenderConfig(loaded != null ? loaded : new Data());
		} catch (IOException e) {
			LOGGER.warn("Failed to read {}, using defaults", PATH, e);
			return new NexoNoRenderConfig(new Data());
		}
	}
}
