package dev.nexoclient.nexomod.profiles;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/**
 * Per-server module profiles: "on this server, these modules are off."
 *
 * <h2>Why per server at all</h2>
 *
 * <p>The modules that are fine on an anarchy server are the ones that get you
 * banned on a practice server, and the ones you want on a practice server
 * (aim assist off, hitboxes off, freecam off) are the ones you turn off by hand
 * every single time you join. This is that hand-work, stored per host and
 * applied once, at join, before anything has had a chance to fire.
 *
 * <h2>Tri-state, not a snapshot</h2>
 *
 * <p>An entry stores only the modules the profile actually forces. Absent means
 * "leave whatever the global config says" — so a profile that only disables
 * three combat modules does not silently pin every HUD to whatever it happened
 * to be the day it was created. Removing a module from a profile is therefore
 * a real operation (cycle back to "leave alone"), not a copy of the current
 * value.
 *
 * <h2>Where the modules come from</h2>
 *
 * <p>{@code src/main} cannot name a {@code src/tactical} module, so the same
 * seam the QoL menu uses applies here: modules register themselves by id, with
 * a getter and a setter. Main registers its own in {@link #registerBuiltins()};
 * the Tactical initialiser adds the rest.
 */
public final class NexoServerProfiles {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/profiles");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("nexomod-server-profiles.json");

	private static final List<Module> MODULES = new ArrayList<>();
	/** Host → module id → forced state. Insertion-ordered so the file reads the way it was built. */
	private static final Map<String, Map<String, Boolean>> PROFILES = new LinkedHashMap<>();

	private NexoServerProfiles() {
	}

	/**
	 * @param id    stable key written to the file — never a display name
	 * @param label what the edit screen shows; supplied at registration because
	 *              the translation keys live with the modules, not here
	 */
	public record Module(String id, Component label, BooleanSupplier get, Consumer<Boolean> set) {
	}

	/** Called from {@code NexoMod.onInitializeClient()}, after the builtins are registered. */
	public static void register() {
		load();
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> apply(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> restore());
		// A crash mid-session leaves the forced values in the saved config. The previous values were
		// written to PENDING at apply time; put them back once every module (tactical ones register
		// from a later entrypoint) exists.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			readPending();
			restore();
		});
	}

	public static void registerModule(String id, Component label, BooleanSupplier get, Consumer<Boolean> set) {
		for (Module module : MODULES) {
			if (module.id().equals(id)) {
				return;
			}
		}
		MODULES.add(new Module(id, label, get, set));
	}

	/** The modules that live in {@code src/main}; tactical ones arrive from the full initialiser. */
	public static void registerBuiltins() {
		var config = dev.nexoclient.nexomod.screen.NexoConfig.get();
		registerModule("cpsCounter", Component.translatable("nexomod.qol.cps"),
				config::cpsCounterEnabled, config::setCpsCounterEnabled);
		registerModule("keystrokesHud", Component.translatable("nexomod.qol.keystrokes"),
				config::keystrokesHudEnabled, config::setKeystrokesHudEnabled);
		registerModule("comboCounter", Component.translatable("nexomod.qol.combo"),
				config::comboCounterEnabled, config::setComboCounterEnabled);
		registerModule("potionHud", Component.translatable("nexomod.qol.potion"),
				config::potionHudEnabled, config::setPotionHudEnabled);
		registerModule("targetHud", Component.translatable("nexomod.qol.targetHud"),
				config::targetHudEnabled, config::setTargetHudEnabled);
		registerModule("damageNumbers", Component.translatable("nexomod.qol.damageNumbers"),
				config::damageNumbersEnabled, config::setDamageNumbersEnabled);
		registerModule("inventoryHud", Component.translatable("nexomod.qol.inventoryHud"),
				config::inventoryHudEnabled, config::setInventoryHudEnabled);
		registerModule("miniPlayer", Component.translatable("nexomod.qol.miniPlayer"),
				config::miniPlayerEnabled, config::setMiniPlayerEnabled);
		registerModule("statsHud", Component.translatable("nexomod.stats.title"),
				config::statsHudEnabled, config::setStatsHudEnabled);
		registerModule("autoReconnect", Component.translatable("nexomod.qol.autoReconnect"),
				config::autoReconnectEnabled, config::setAutoReconnectEnabled);
		registerModule("hitOverlay", Component.translatable("nexomod.qol.hitOverlay"),
				config::hitOverlayEnabled, config::setHitOverlayEnabled);
	}

	public static List<Module> modules() {
		return List.copyOf(MODULES);
	}

	// ------------------------------------------------------------------
	// Storage
	// ------------------------------------------------------------------

	private static void load() {
		PROFILES.clear();
		if (!Files.exists(FILE)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			Map<String, Map<String, Boolean>> read = GSON.fromJson(reader,
					new TypeToken<LinkedHashMap<String, LinkedHashMap<String, Boolean>>>() {
					}.getType());
			if (read != null) {
				read.forEach((host, modules) -> {
					if (host != null && modules != null) {
						Map<String, Boolean> clean = new LinkedHashMap<>();
						// A null value (hand-edited file) would NPE on unboxing at join.
						modules.forEach((id, state) -> {
							if (id != null && state != null) {
								clean.put(id, state);
							}
						});
						PROFILES.put(normalize(host), clean);
					}
				});
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("[nexomod] Could not read {}: {}", FILE.getFileName(), e.toString());
			PROFILES.clear();
			// Keep the unreadable file: the next save() would otherwise overwrite it with an empty map.
			try {
				Files.move(FILE, FILE.resolveSibling(FILE.getFileName() + ".bak"),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException moveError) {
				LOGGER.warn("[nexomod] Could not back up {}: {}", FILE.getFileName(), moveError.toString());
			}
		}
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(PROFILES, writer);
			}
			try {
				Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
						java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOGGER.warn("[nexomod] Could not write {}: {}", FILE.getFileName(), e.toString());
		}
	}

	/** Lowercase and trimmed, so "MC.Example.com " and "mc.example.com" are one profile. */
	private static String normalize(String host) {
		String h = host.trim().toLowerCase(Locale.ROOT);
		// "mc.x.com", "mc.x.com." and "mc.x.com:25565" are the same server.
		if (h.endsWith(":25565")) {
			h = h.substring(0, h.length() - 6);
		}
		while (h.endsWith(".")) {
			h = h.substring(0, h.length() - 1);
		}
		return h;
	}

	/** The address of the server currently joined, or null in singleplayer and LAN. */
	public static String currentHost() {
		ServerData server = Minecraft.getInstance().getCurrentServer();
		if (server == null || server.ip == null || server.ip.isBlank()) {
			return null;
		}
		return normalize(server.ip);
	}

	public static List<String> hosts() {
		List<String> hosts = new ArrayList<>(PROFILES.keySet());
		hosts.sort(String.CASE_INSENSITIVE_ORDER);
		return hosts;
	}

	/** Null when the profile does not force this module either way. */
	public static Boolean state(String host, String moduleId) {
		Map<String, Boolean> profile = PROFILES.get(normalize(host));
		return profile == null ? null : profile.get(moduleId);
	}

	/** Creates the profile if it does not exist yet, so it shows up in the list while empty. */
	public static void ensureProfile(String host) {
		if (PROFILES.putIfAbsent(normalize(host), new LinkedHashMap<>()) == null) {
			save();
		}
	}

	public static void setState(String host, String moduleId, Boolean state) {
		Map<String, Boolean> profile = PROFILES.computeIfAbsent(normalize(host), ignored -> new LinkedHashMap<>());
		if (state == null) {
			profile.remove(moduleId);
		} else {
			profile.put(moduleId, state);
		}
		save();
	}

	public static void removeProfile(String host) {
		if (PROFILES.remove(normalize(host)) != null) {
			save();
		}
	}

	// ------------------------------------------------------------------
	// Application
	// ------------------------------------------------------------------

	/** What each forced module held before this session's profile changed it, restored on disconnect. */
	private static final Map<String, Boolean> SAVED = new LinkedHashMap<>();

	/**
	 * Runs once per join, before the first tick of the new world. A profile
	 * describes the session, not the global config: whatever it forces is put
	 * back on disconnect, so "force off" on one server cannot leak into the next
	 * server or into singleplayer.
	 */
	private static void apply(Minecraft client) {
		try {
			applyProfile(client);
		} catch (RuntimeException e) {
			// A bad module must not abort the other JOIN listeners.
			LOGGER.warn("[nexomod] Could not apply the server profile: {}", e.toString());
		}
	}

	private static final Path PENDING = FabricLoader.getInstance().getConfigDir()
			.resolve("nexomod-server-profiles.pending.json");

	private static void restore() {
		SAVED.forEach((id, previous) -> {
			Module module = find(id);
			if (module != null && module.get().getAsBoolean() != previous) {
				module.set().accept(previous);
			}
		});
		SAVED.clear();
		try {
			Files.deleteIfExists(PENDING);
		} catch (IOException e) {
			LOGGER.warn("[nexomod] Could not remove {}: {}", PENDING.getFileName(), e.toString());
		}
	}

	private static void writePending() {
		try (Writer writer = Files.newBufferedWriter(PENDING, StandardCharsets.UTF_8)) {
			GSON.toJson(SAVED, writer);
		} catch (IOException e) {
			LOGGER.warn("[nexomod] Could not write {}: {}", PENDING.getFileName(), e.toString());
		}
	}

	private static void readPending() {
		if (!Files.exists(PENDING)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(PENDING, StandardCharsets.UTF_8)) {
			Map<String, Boolean> read = GSON.fromJson(reader,
					new TypeToken<LinkedHashMap<String, Boolean>>() {
					}.getType());
			if (read != null) {
				read.forEach((id, previous) -> {
					if (id != null && previous != null) {
						SAVED.putIfAbsent(id, previous);
					}
				});
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("[nexomod] Could not read {}: {}", PENDING.getFileName(), e.toString());
		}
	}

	private static void applyProfile(Minecraft client) {
		restore();
		String host = currentHost();
		if (host == null) {
			return;
		}
		Map<String, Boolean> profile = PROFILES.get(host);
		if (profile == null || profile.isEmpty()) {
			return;
		}
		int applied = 0;
		for (Map.Entry<String, Boolean> entry : profile.entrySet()) {
			Module module = find(entry.getKey());
			if (module == null) {
				// A profile written by a build that had a module this one does not.
				continue;
			}
			if (module.get().getAsBoolean() != entry.getValue()) {
				SAVED.put(module.id(), !entry.getValue());
				module.set().accept(entry.getValue());
				applied++;
			}
		}
		if (applied > 0) {
			writePending();
			LOGGER.info("[nexomod] Applied {} module override(s) for {}", applied, host);
			if (client.player != null) {
				client.player.sendOverlayMessage(
						Component.translatable("nexomod.profiles.applied", host, applied));
			}
		}
	}

	private static Module find(String id) {
		for (Module module : MODULES) {
			if (module.id().equals(id)) {
				return module;
			}
		}
		return null;
	}
}
