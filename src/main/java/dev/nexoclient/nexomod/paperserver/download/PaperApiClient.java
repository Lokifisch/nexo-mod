package dev.nexoclient.nexomod.paperserver.download;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Resolves and downloads PaperMC server jars via the Fill API
 * ({@code fill.papermc.io/v3}). The older {@code api.papermc.io/v2} is
 * retired (410 Gone as of 2026-08-22) — verified live, not assumed.
 *
 * <p>Same {@code java.net.http.HttpClient}-per-feature convention as
 * {@code badge.BadgeService}; this feature doesn't share that class since
 * it talks to a different service with different response shapes.
 */
public final class PaperApiClient {
	private static final String BASE_URL = "https://fill.papermc.io/v3";
	private static final String USER_AGENT = "nexomod-paperserver/1.0 (github.com/Lokifisch/nexo-client)";
	private static final Duration TIMEOUT = Duration.ofSeconds(15);
	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(TIMEOUT)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	public record Download(String name, String url, String sha256, long size) {}

	public record Build(int id, String channel, Download serverJar) {}

	/** Newest first. Empty (not an error) if this Minecraft version has no published Paper build yet. */
	public List<Build> stableBuilds(String minecraftVersion) throws IOException, InterruptedException {
		URI uri = URI.create(BASE_URL + "/projects/paper/versions/" + minecraftVersion + "/builds?channel=STABLE");
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(TIMEOUT)
				.header("User-Agent", USER_AGENT)
				.GET()
				.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 404) {
			return List.of();
		}
		if (response.statusCode() != 200) {
			throw new IOException("Fill API returned " + response.statusCode() + " for Paper " + minecraftVersion);
		}
		return parseBuilds(response.body());
	}

	public Build latestStableBuild(String minecraftVersion) throws IOException, InterruptedException {
		List<Build> builds = stableBuilds(minecraftVersion);
		if (builds.isEmpty()) {
			throw new IOException("No published Paper build for Minecraft " + minecraftVersion + " yet");
		}
		return builds.get(0);
	}

	/** Downloads and SHA-256-verifies the jar, writing it to {@code dest} only once verified. */
	public void downloadJar(Build build, Path dest) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(build.serverJar().url()))
				.timeout(DOWNLOAD_TIMEOUT)
				.header("User-Agent", USER_AGENT)
				.GET()
				.build();
		HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("Paper jar download returned " + response.statusCode());
		}

		byte[] body = response.body();
		verifySha256(body, build.serverJar().sha256());

		Files.createDirectories(dest.getParent());
		Path temp = dest.resolveSibling(dest.getFileName() + ".part");
		Files.write(temp, body);
		Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static void verifySha256(byte[] data, String expectedHex) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String actualHex = HexFormat.of().formatHex(digest.digest(data));
			if (!actualHex.equalsIgnoreCase(expectedHex)) {
				throw new IOException("Paper jar SHA-256 mismatch: expected " + expectedHex + ", got " + actualHex);
			}
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 is mandated by the JCA spec", e);
		}
	}

	private static List<Build> parseBuilds(String json) {
		JsonArray array = JsonParser.parseString(json).getAsJsonArray();
		List<Build> builds = new ArrayList<>();
		for (JsonElement element : array) {
			JsonObject obj = element.getAsJsonObject();
			int id = obj.get("id").getAsInt();
			String channel = obj.get("channel").getAsString();
			JsonObject serverDefault = obj.getAsJsonObject("downloads").getAsJsonObject("server:default");
			if (serverDefault == null) {
				continue;
			}
			String name = serverDefault.get("name").getAsString();
			String url = serverDefault.get("url").getAsString();
			String sha256 = serverDefault.getAsJsonObject("checksums").get("sha256").getAsString();
			long size = serverDefault.get("size").getAsLong();
			builds.add(new Build(id, channel, new Download(name, url, sha256, size)));
		}
		// Fill already lists newest-first; sorted defensively rather than trusted blindly.
		builds.sort((a, b) -> Integer.compare(b.id(), a.id()));
		return builds;
	}
}
