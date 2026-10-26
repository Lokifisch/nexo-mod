package dev.nexoclient.nexomod.title;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import dev.nexoclient.nexomod.NexoMod;

/**
 * The newest Nexo Mod release's notes, for the title screen's "What's new"
 * card.
 *
 * <p>Fetched at most once per session from GitHub's release list — not
 * {@code /releases/latest}, which skips prereleases, and every Nexo release
 * so far is one — and cached on disk, so an offline start still shows the
 * last notes it saw. The release body is someone else's markdown: it is
 * reduced to plain lines here and only ever drawn as text, never as links.
 */
public final class NexoWhatsNew {
	private static final String URL = "https://api.github.com/repos/Lokifisch/nexo-mod/releases?per_page=5";
	private static final Path CACHE = FabricLoader.getInstance().getConfigDir().resolve("nexomod-whatsnew.json");
	private static final int MAX_LINES = 8;
	private static final int MAX_BODY = 64 * 1024;
	private static final Gson GSON = new Gson();

	/** A release tag and its first few plain lines. */
	public record Notes(String tag, List<String> lines) {
	}

	private static volatile Notes notes;
	private static boolean requested;

	private NexoWhatsNew() {
	}

	/** The notes to show, or null if there are none yet. The first call starts the fetch. */
	public static Notes get() {
		if (!dev.nexoclient.nexomod.screen.NexoConfig.get().titleNetworkEnabled()) {
			return null;
		}
		if (!requested) {
			requested = true;
			notes = readCache();
			fetch();
		}
		return notes;
	}

	private static void fetch() {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", "nexomod-whatsnew/1.0")
				.header("Accept", "application/vnd.github+json")
				.GET()
				.build();
		client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
			if (error != null || response.statusCode() != 200 || response.body().length() > 2 * 1024 * 1024) {
				return;
			}
			try {
				Notes fresh = parse(response.body());
				if (fresh != null) {
					notes = fresh;
					Files.writeString(CACHE, GSON.toJson(fresh), StandardCharsets.UTF_8);
				}
			} catch (IOException | RuntimeException e) {
				NexoMod.LOGGER.debug("[nexomod] What's new: could not read the release list.", e);
			}
		});
	}

	private static Notes parse(String body) {
		JsonArray releases = JsonParser.parseString(body).getAsJsonArray();
		for (JsonElement element : releases) {
			JsonObject release = element.getAsJsonObject();
			if (release.has("draft") && release.get("draft").getAsBoolean()) {
				continue;
			}
			String tag = release.has("tag_name") ? release.get("tag_name").getAsString() : "";
			String text = release.has("body") && !release.get("body").isJsonNull() ? release.get("body").getAsString() : "";
			return new Notes(clip(tag.replaceAll("\\p{Cntrl}|\\p{Cf}|§.?", ""), 32), plainLines(text));
		}
		return null;
	}

	/** Markdown to a handful of plain lines: headings, bullets and emphasis kept as text, links as their label. */
	static List<String> plainLines(String markdown) {
		if (markdown.length() > MAX_BODY) {
			markdown = markdown.substring(0, MAX_BODY);
		}
		List<String> lines = new ArrayList<>();
		for (String raw : markdown.split("\\R")) {
			// Clamped before the regexes, which backtrack badly on a long line of brackets.
			String line = clip(raw, 1024).strip();
			if (line.isEmpty() || line.startsWith("```") || line.startsWith("<!--") || line.startsWith("|")) {
				continue;
			}
			boolean bullet = line.startsWith("- ") || line.startsWith("* ");
			line = line.replaceFirst("^#+\\s*", "").replaceFirst("^[-*]\\s+", "");
			line = line.replaceAll("!\\[[^\\]]*]\\([^)]*\\)", "")
					.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1")
					.replaceAll("[*_`]", "")
					// Control and format characters, and Minecraft's own § formatting codes.
					.replaceAll("\\p{Cntrl}|\\p{Cf}|§.?", "")
					.strip();
			if (line.isEmpty()) {
				continue;
			}
			lines.add(clip(bullet ? "• " + line : line, 300));
			if (lines.size() == MAX_LINES) {
				break;
			}
		}
		return lines;
	}

	private static String clip(String text, int max) {
		return text.length() > max ? text.substring(0, max) : text;
	}

	private static Notes readCache() {
		try {
			if (Files.exists(CACHE)) {
				Notes cached = GSON.fromJson(Files.readString(CACHE, StandardCharsets.UTF_8), Notes.class);
				if (cached != null && cached.tag() != null && cached.lines() != null) {
					return new Notes(clip(cached.tag().replace("§", ""), 32), cached.lines().stream().limit(MAX_LINES)
							.map(line -> clip(String.valueOf(line).replace("§", ""), 300)).toList());
				}
			}
		} catch (IOException | RuntimeException e) {
			NexoMod.LOGGER.debug("[nexomod] What's new: ignoring an unreadable cache.", e);
		}
		return null;
	}
}
