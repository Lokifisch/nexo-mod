package dev.nexoclient.nexomod.hud;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Custom position/scale for HUD elements dragged in {@code NexoHudEditorScreen}.
 *
 * <p>Deliberately sparse: an element with no entry here just keeps computing
 * its own built-in default position (centered under the crosshair, hugging
 * the hotbar, on the right edge — whatever that element's own layout formula
 * already does). Dragging one in the editor is what creates an entry, and
 * only for that one element — this is an override map, not a replacement for
 * every element's sensible default.
 *
 * <p>Position is stored as absolute pixels on the resolution it was set at,
 * not anchored to a corner. ponytail: a corner-relative offset would survive
 * a big resolution change more gracefully (an element dragged near an edge
 * would stay near that edge); absolute pixels can drift or clip off-screen
 * after a large resolution jump. Rendering clamps the box back on-screen as
 * a cheap safety net (see each element's {@code resolveBounds}), but a real
 * fix is corner-anchored storage — worth doing if this ever bites, not
 * before.
 */
public final class NexoHudLayout {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/hudlayout");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type MAP_TYPE = new TypeToken<Map<Element, Position>>() {
	}.getType();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-hud-layout.json");

	/** Every HUD element the editor can drag. Adding one here is the only step needed to make it editable. */
	public enum Element {
		KEYSTROKES, CPS, ARMOR, STATS, POTION, COMBO, ACTIONBAR_LOG, PICKUP_LOG
	}

	/** Top-left pixel position plus a size multiplier, applied to the element's own nominal size. */
	public static final class Position {
		public int x;
		public int y;
		public float scale = 1.0f;

		public Position() {
		}

		public Position(int x, int y, float scale) {
			this.x = x;
			this.y = y;
			this.scale = scale;
		}
	}

	private static NexoHudLayout instance;

	private final Map<Element, Position> overrides;

	private NexoHudLayout(Map<Element, Position> overrides) {
		this.overrides = overrides;
	}

	public static synchronized NexoHudLayout get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** The stored override for `element`, or null if it still uses its own default layout. */
	public Position get(Element element) {
		return overrides.get(element);
	}

	/**
	 * Updates the live in-memory position only — deliberately does not write
	 * to disk. {@code NexoHudEditorScreen} calls this once per {@code
	 * mouseDragged} event, dozens of times over the course of one drag; a
	 * disk write on every one of those would be needless I/O and could stutter
	 * the drag. Call {@link #save()} once the gesture ends.
	 */
	public void set(Element element, Position position) {
		overrides.put(element, position);
	}

	public void reset(Element element) {
		if (overrides.remove(element) != null) {
			save();
		}
	}

	public void resetAll() {
		if (!overrides.isEmpty()) {
			overrides.clear();
			save();
		}
	}

	/** Persists whatever {@link #set} has changed since the last save. */
	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(overrides, MAP_TYPE, writer);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to save {}", PATH, e);
		}
	}

	private static NexoHudLayout load() {
		if (!Files.exists(PATH)) {
			return new NexoHudLayout(new EnumMap<>(Element.class));
		}
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			Map<Element, Position> loaded = GSON.fromJson(reader, MAP_TYPE);
			return new NexoHudLayout(loaded != null ? new EnumMap<>(loaded) : new EnumMap<>(Element.class));
		} catch (IOException e) {
			LOGGER.warn("Failed to read {}, starting with default HUD layout", PATH, e);
			return new NexoHudLayout(new EnumMap<>(Element.class));
		}
	}
}
