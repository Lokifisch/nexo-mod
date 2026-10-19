package dev.nexoclient.nexomod.chat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import dev.nexoclient.nexomod.hud.NexoSecondChatHud;

/**
 * Chat tweaks (timestamps, longer history, compact repeats) and the routing
 * decision for the second chat. Kept in its own properties file rather than
 * {@code NexoConfig}: nine fields that nothing else reads.
 */
public final class NexoChatTweaks {
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-chat.properties");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	public static final int DEFAULT_MAX_LINES = 100;

	public static boolean timestamps;
	public static boolean compact;
	public static int maxLines = DEFAULT_MAX_LINES;
	public static boolean secondEnabled;
	public static boolean keepCopy;
	public static int secondLines = 8;
	public static String prefixes = "";

	private static String[] parsedFor;
	private static String[] parsed = new String[0];

	static {
		load();
	}

	private NexoChatTweaks() {
	}

	private static void load() {
		Properties props = new Properties();
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				props.load(reader);
			} catch (IOException ignored) {
				// defaults
			}
		}
		timestamps = Boolean.parseBoolean(props.getProperty("timestamps", "false"));
		compact = Boolean.parseBoolean(props.getProperty("compact", "false"));
		maxLines = clamp(props.getProperty("maxLines"), 100, 2000, DEFAULT_MAX_LINES);
		secondEnabled = Boolean.parseBoolean(props.getProperty("secondEnabled", "false"));
		keepCopy = Boolean.parseBoolean(props.getProperty("keepCopy", "false"));
		secondLines = clamp(props.getProperty("secondLines"), 3, 20, 8);
		prefixes = props.getProperty("prefixes", "");
	}

	public static void save() {
		Properties props = new Properties();
		props.setProperty("timestamps", Boolean.toString(timestamps));
		props.setProperty("compact", Boolean.toString(compact));
		props.setProperty("maxLines", Integer.toString(maxLines));
		props.setProperty("secondEnabled", Boolean.toString(secondEnabled));
		props.setProperty("keepCopy", Boolean.toString(keepCopy));
		props.setProperty("secondLines", Integer.toString(secondLines));
		props.setProperty("prefixes", prefixes);
		try (Writer writer = Files.newBufferedWriter(PATH)) {
			props.store(writer, "Nexo chat tweaks");
		} catch (IOException ignored) {
			// best effort
		}
	}

	private static int clamp(String raw, int min, int max, int fallback) {
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/** Comma-separated prefixes, lowercased; re-parsed only when the string changes. */
	private static String[] tags() {
		if (parsedFor != null && parsedFor.length == 1 && parsedFor[0].equals(prefixes)) {
			return parsed;
		}
		String[] raw = prefixes.split(",");
		java.util.List<String> out = new java.util.ArrayList<>();
		for (String part : raw) {
			String tag = part.trim().toLowerCase(java.util.Locale.ROOT);
			if (!tag.isEmpty()) {
				out.add(tag);
			}
		}
		parsed = out.toArray(new String[0]);
		parsedFor = new String[]{prefixes};
		return parsed;
	}

	/** True when the message belongs in the second chat (and was handed to it). */
	public static boolean route(Component content) {
		if (!secondEnabled || prefixes.isEmpty()) {
			return false;
		}
		String[] tags = tags();
		if (tags.length == 0) {
			return false;
		}
		String plain = content.getString().toLowerCase(java.util.Locale.ROOT);
		for (String tag : tags) {
			if (plain.contains(tag)) {
				NexoSecondChatHud.INSTANCE.add(stamp(content));
				return !keepCopy;
			}
		}
		return false;
	}

	/** "[HH:mm] " in front of the line when timestamps are on. */
	public static Component stamp(Component content) {
		if (!timestamps) {
			return content;
		}
		MutableComponent out = Component.literal("[" + TIME.format(LocalTime.now()) + "] ").withStyle(ChatFormatting.GRAY);
		return out.append(content);
	}
}
