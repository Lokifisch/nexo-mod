package dev.nexoclient.nexomod.tactical.macro;

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
 * The persisted trigger list.
 *
 * <p>Its own file rather than a section of {@code nexomod-macros.json}: macros
 * are in both jars and triggers are not, and a light jar that loaded the macro
 * file would have to preserve a block of JSON describing automation it cannot
 * run. Two files means the light jar never has to know this one exists.
 */
public final class NexoMacroTriggerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST_TYPE = new TypeToken<List<NexoMacroTrigger>>() {}.getType();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-macro-triggers.json");

	private static NexoMacroTriggerConfig instance;

	private final List<NexoMacroTrigger> triggers;

	private NexoMacroTriggerConfig(List<NexoMacroTrigger> triggers) {
		this.triggers = triggers;
	}

	public static synchronized NexoMacroTriggerConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** Live list; mutate it and call {@link #save()}. */
	public List<NexoMacroTrigger> triggers() {
		return triggers;
	}

	public void add(NexoMacroTrigger trigger) {
		triggers.add(trigger);
		save();
	}

	public void remove(NexoMacroTrigger trigger) {
		triggers.remove(trigger);
		save();
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(triggers, LIST_TYPE, writer);
			}
		} catch (IOException e) {
			NexoMod.LOGGER.warn("Failed to save {}", PATH, e);
		}
	}

	private static NexoMacroTriggerConfig load() {
		if (!Files.exists(PATH)) {
			return new NexoMacroTriggerConfig(new ArrayList<>());
		}
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			List<NexoMacroTrigger> loaded = GSON.fromJson(reader, LIST_TYPE);
			return new NexoMacroTriggerConfig(loaded != null ? loaded : new ArrayList<>());
		} catch (IOException | JsonParseException e) {
			NexoMod.LOGGER.warn("Failed to read {}, starting with no macro triggers", PATH, e);
			return new NexoMacroTriggerConfig(new ArrayList<>());
		}
	}
}
