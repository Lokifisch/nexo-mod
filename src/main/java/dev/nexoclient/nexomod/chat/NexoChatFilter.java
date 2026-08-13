package dev.nexoclient.nexomod.chat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.nativecore.NexoNative;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * The auto chat filter: the persisted pattern list plus the one native filter
 * handle it is compiled into.
 *
 * <h2>Fail-open, deliberately</h2>
 *
 * <p>{@link #test} answers {@link NexoNative#FILTER_ALLOW} for every case that
 * is not an unambiguous match: no native library, no handle, a handle that
 * failed to build, and the {@code -1} {@code filterTest} returns on error. This
 * is the opposite of the policy the log scrubber uses, and the contract spells
 * out why — chat that silently vanishes is a bug nobody reports, chat that
 * wrongly appears is one everybody does. A broken filter must degrade to "no
 * filtering", never to "no chat".
 *
 * <h2>One handle, rebuilt on edit</h2>
 *
 * <p>The native surface has {@code filterAddPattern} but no remove, so editing a
 * rule means building a fresh handle and destroying the old one. That happens on
 * {@link #save()} — i.e. when a settings screen closes — not per message, so the
 * per-message path stays a single {@code filterTest} call.
 *
 * <p>Compile errors are collected per pattern index in {@link #errors()} so the
 * filter screen can put the message next to the field the user just typed into,
 * which is the reason {@code filterAddPattern} rejects a bad regex rather than
 * accepting it and never matching.
 */
public final class NexoChatFilter {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST_TYPE = new TypeToken<List<NexoChatPattern>>() {}.getType();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-chat-filters.json");

	private static NexoChatFilter instance;

	private final List<NexoChatPattern> patterns;
	private final Map<Integer, String> errors = new HashMap<>();

	private long handle = NexoNative.INVALID_HANDLE;
	private boolean dirty = true;

	private NexoChatFilter(List<NexoChatPattern> patterns) {
		this.patterns = patterns;
	}

	public static synchronized NexoChatFilter get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/** Live list; mutate it and call {@link #save()}. */
	public List<NexoChatPattern> patterns() {
		return patterns;
	}

	/** Regex compile errors from the last {@link #rebuild()}, keyed by index in {@link #patterns()}. */
	public Map<Integer, String> errors() {
		return Collections.unmodifiableMap(errors);
	}

	public void add(NexoChatPattern pattern) {
		patterns.add(pattern);
		save();
	}

	public void remove(int index) {
		if (index >= 0 && index < patterns.size()) {
			patterns.remove(index);
			save();
		}
	}

	/** Persists the list and marks the compiled handle stale. */
	public void save() {
		dirty = true;
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(patterns, LIST_TYPE, writer);
			}
		} catch (IOException e) {
			NexoMod.LOGGER.warn("Failed to save {}", PATH, e);
		}
	}

	/**
	 * Classifies one message.
	 *
	 * @return {@link NexoNative#FILTER_HIDE}, {@link NexoNative#FILTER_HIGHLIGHT}
	 *         or {@link NexoNative#FILTER_ALLOW}; never anything else, and
	 *         {@code FILTER_ALLOW} for every failure mode
	 */
	public int test(String message) {
		if (!NexoConfig.get().chatFilterEnabled() || !NexoNative.isAvailable() || message.isEmpty()) {
			return NexoNative.FILTER_ALLOW;
		}
		long filter = handle();
		if (filter == NexoNative.INVALID_HANDLE) {
			return NexoNative.FILTER_ALLOW;
		}
		int result = NexoNative.filterTest(filter, message);
		// Anything negative is the documented failure sentinel; anything above
		// HIGHLIGHT would be a value from a newer library this build does not
		// understand. Both mean "show it".
		return result < NexoNative.FILTER_ALLOW || result > NexoNative.FILTER_HIGHLIGHT
				? NexoNative.FILTER_ALLOW
				: result;
	}

	/** True when at least one enabled, non-empty rule exists — used to skip the whole path cheaply. */
	public boolean hasActiveRules() {
		for (NexoChatPattern pattern : patterns) {
			if (pattern.enabled && !pattern.regex.isBlank()) {
				return true;
			}
		}
		return false;
	}

	private synchronized long handle() {
		if (dirty) {
			rebuild();
		}
		return handle;
	}

	/**
	 * Compiles the enabled rules into a fresh native handle.
	 *
	 * <p>Built first and swapped in afterwards so a rebuild that fails halfway
	 * leaves the previous, working filter in place rather than an empty one —
	 * "the filter you edited stopped filtering" is a much more confusing failure
	 * than "your new rule was rejected".
	 */
	public synchronized void rebuild() {
		dirty = false;
		errors.clear();
		if (!NexoNative.isAvailable()) {
			return;
		}
		long built = NexoNative.filterCreate();
		if (built == NexoNative.INVALID_HANDLE) {
			NexoMod.LOGGER.warn("[nexomod] Chat filter disabled: {}", NexoNative.lastErrorOrUnknown());
			return;
		}
		for (int i = 0; i < patterns.size(); i++) {
			NexoChatPattern pattern = patterns.get(i);
			if (!pattern.enabled || pattern.regex.isBlank()) {
				continue;
			}
			if (!NexoNative.filterAddPattern(built, pattern.regex, pattern.action)) {
				errors.put(i, NexoNative.lastErrorOrUnknown());
			}
		}
		long previous = handle;
		handle = built;
		if (previous != NexoNative.INVALID_HANDLE) {
			NexoNative.filterDestroy(previous);
		}
	}

	/** Called from {@code ClientLifecycleEvents.CLIENT_STOPPING}. */
	public static synchronized void closeIfOpen() {
		if (instance == null || instance.handle == NexoNative.INVALID_HANDLE) {
			return;
		}
		NexoNative.filterDestroy(instance.handle);
		instance.handle = NexoNative.INVALID_HANDLE;
		instance.dirty = true;
	}

	/** The style applied to a {@link NexoNative#FILTER_HIGHLIGHT} match. */
	public static Component highlight(Component message) {
		return Component.empty().append(message).withStyle(style -> style
				.withColor(net.minecraft.ChatFormatting.YELLOW)
				.withBold(true));
	}

	private static NexoChatFilter load() {
		if (!Files.exists(PATH)) {
			return new NexoChatFilter(new ArrayList<>());
		}
		try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			List<NexoChatPattern> loaded = GSON.fromJson(reader, LIST_TYPE);
			return new NexoChatFilter(loaded != null ? loaded : new ArrayList<>());
		} catch (IOException | JsonParseException e) {
			NexoMod.LOGGER.warn("Failed to read {}, starting with no chat filters", PATH, e);
			return new NexoChatFilter(new ArrayList<>());
		}
	}
}
