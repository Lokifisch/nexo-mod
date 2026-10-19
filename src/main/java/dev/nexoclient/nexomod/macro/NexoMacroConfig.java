package dev.nexoclient.nexomod.macro;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

import dev.nexoclient.nexomod.util.LegacyGlfwKeys;

/** Persisted list of user-defined macros, stored as plain JSON (there's no secret data here, unlike accounts). */
public final class NexoMacroConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/macro");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST_TYPE = new TypeToken<List<NexoMacro>>() {}.getType();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-macros.json");

	private static NexoMacroConfig instance;

	private final List<NexoMacro> macros;

	private NexoMacroConfig(List<NexoMacro> macros) {
		this.macros = macros;
	}

	public static synchronized NexoMacroConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public List<NexoMacro> macros() {
		return macros;
	}

	public void addMacro(NexoMacro macro) {
		macros.add(macro);
		save();
	}

	public void removeMacro(NexoMacro macro) {
		macros.remove(macro);
		save();
	}

	/** Call after mutating a macro's fields directly (the editor screen does this) to persist the change. */
	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(macros, LIST_TYPE, writer);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to save {}", PATH, e);
		}
	}

	private static NexoMacroConfig load() {
		if (!Files.exists(PATH)) {
			return new NexoMacroConfig(new ArrayList<>());
		}
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			JsonArray json = JsonParser.parseReader(reader).getAsJsonArray();
			List<NexoMacro> macros = new ArrayList<>();
			boolean migrated = false;
			for (JsonElement element : json) {
				if (!element.isJsonObject()) {
					continue;
				}
				NexoMacro macro = GSON.fromJson(element, NexoMacro.class);
				if (!element.getAsJsonObject().has("keyCodes")) {
					String name = LegacyGlfwKeys.name(macro.keyCode);
					macro.keyCode = name == null ? -1 : InputConstants.getKey(name).getValue();
					migrated = true;
				}
				macros.add(macro);
			}
			NexoMacroConfig config = new NexoMacroConfig(macros);
			if (migrated) {
				config.save();
			}
			return config;
		} catch (IOException | RuntimeException e) {
			// RuntimeException: an empty or hand-broken file, which shouldn't
			// take the game down with it.
			LOGGER.warn("Failed to read {}, starting with no macros", PATH, e);
			return new NexoMacroConfig(new ArrayList<>());
		}
	}
}
