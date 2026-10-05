package dev.nexoclient.nexomod.paperserver.hangar;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import com.google.gson.Gson;

/**
 * PaperMC's own plugin repository (hangar.papermc.io), verified live against
 * the real API on 2026-08-22 — the exact shape used here, not guessed.
 *
 * <p>Installing a plugin is arbitrary code execution with the server
 * process's full privileges. No sandboxing is attempted, and this client
 * deliberately exposes {@code author}/{@code reviewState}/{@code category}
 * on every DTO so the UI can (and must) show them before an install
 * proceeds — see {@code screen.NexoPaperPluginBrowserScreen}.
 */
public final class HangarClient {
	private static final String BASE_URL = "https://hangar.papermc.io/api/v1";
	private static final String USER_AGENT = "nexomod-paperserver/1.0 (github.com/Lokifisch/nexo-client)";
	private static final Duration TIMEOUT = Duration.ofSeconds(15);
	private static final Gson GSON = new Gson();
	private static final int MAX_JAR_BYTES = 64 * 1024 * 1024;
	private static final java.util.regex.Pattern JAR_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9._-]+\\.jar");
	private static final java.util.regex.Pattern PATH_PART = java.util.regex.Pattern.compile("[A-Za-z0-9._-]+");

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(TIMEOUT)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	public record Namespace(String owner, String slug) {}

	public record Project(long id, String name, Namespace namespace, String category, String description, String visibility) {}

	private record SearchResult(List<Project> result) {}

	public record FileInfo(String name, long sizeBytes, String sha256Hash) {}

	public record Download(FileInfo fileInfo, String downloadUrl) {}

	public record Version(long id, String name, String author, String reviewState, String visibility, Map<String, Download> downloads) {
		public Download paperDownload() {
			Download download = downloads == null ? null : downloads.get("PAPER");
			if (download == null) {
				throw new NoSuchElementException("Hangar version " + name + " has no PAPER download");
			}
			return download;
		}
	}

	private record VersionSearchResult(List<Version> result) {}

	public List<Project> search(String query, int limit) throws IOException, InterruptedException {
		String url = BASE_URL + "/projects?limit=" + limit + "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
		HttpResponse<String> response = get(url);
		if (response.statusCode() != 200) {
			throw new IOException("Hangar search returned " + response.statusCode());
		}
		SearchResult parsed = GSON.fromJson(response.body(), SearchResult.class);
		return parsed == null || parsed.result() == null ? List.of() : parsed.result();
	}

	/** Newest first, per Hangar's own ordering. */
	public List<Version> versions(String owner, String slug, int limit) throws IOException, InterruptedException {
		if (owner == null || slug == null || !PATH_PART.matcher(owner).matches() || !PATH_PART.matcher(slug).matches()
				|| owner.equals("..") || slug.equals("..")) {
			throw new IOException("Invalid Hangar project namespace");
		}
		String url = BASE_URL + "/projects/" + URLEncoder.encode(owner, StandardCharsets.UTF_8) + "/"
				+ URLEncoder.encode(slug, StandardCharsets.UTF_8) + "/versions?limit=" + limit;
		HttpResponse<String> response = get(url);
		if (response.statusCode() != 200) {
			throw new IOException("Hangar versions returned " + response.statusCode());
		}
		VersionSearchResult parsed = GSON.fromJson(response.body(), VersionSearchResult.class);
		return parsed == null || parsed.result() == null ? List.of() : parsed.result();
	}

	/**
	 * Resolves a server-supplied file name to a path directly inside {@code pluginsDir}; anything that is not a
	 * plain {@code name.jar} is rejected, so a malicious listing cannot write outside the plugins folder.
	 */
	private static final java.util.regex.Pattern WINDOWS_RESERVED =
			java.util.regex.Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");

	public static Path safePluginPath(Path pluginsDir, String fileName) throws IOException {
		if (fileName == null || !JAR_NAME.matcher(fileName).matches()) {
			throw new IOException("Refusing unsafe plugin file name: " + fileName);
		}
		if (WINDOWS_RESERVED.matcher(fileName).matches()) {
			throw new IOException("Refusing unsafe plugin file name: " + fileName);
		}
		Path dest = pluginsDir.resolve(fileName).normalize();
		if (!dest.startsWith(pluginsDir.normalize()) || !pluginsDir.normalize().equals(dest.getParent())) {
			throw new IOException("Refusing unsafe plugin file name: " + fileName);
		}
		return dest;
	}

	/** Downloads and SHA-256-verifies the plugin jar, writing it to {@code dest} only once verified. */
	public void downloadPlugin(Version version, Path dest) throws IOException, InterruptedException {
		Download download = version.paperDownload();
		if (download == null || download.fileInfo() == null || download.downloadUrl() == null) {
			throw new IOException("This version has no direct download (hosted externally)");
		}
		URI uri = URI.create(download.downloadUrl());
		if (!"https".equalsIgnoreCase(uri.getScheme())) {
			throw new IOException("Plugin download URL is not https");
		}
		if (download.fileInfo().sizeBytes() > MAX_JAR_BYTES) {
			throw new IOException("Plugin jar exceeds " + MAX_JAR_BYTES / 1024 / 1024 + " MB limit");
		}
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofMinutes(2))
				.header("User-Agent", USER_AGENT)
				.GET()
				.build();
		HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		byte[] body;
		try (java.io.InputStream in = response.body()) {
			if (response.statusCode() != 200) {
				throw new IOException("Plugin download returned " + response.statusCode());
			}
			body = in.readNBytes(MAX_JAR_BYTES + 1);
		}
		if (body.length > MAX_JAR_BYTES) {
			throw new IOException("Plugin jar exceeds " + MAX_JAR_BYTES / 1024 / 1024 + " MB limit");
		}
		verifySha256(body, download.fileInfo().sha256Hash());

		Files.createDirectories(dest.getParent());
		Path temp = dest.resolveSibling(dest.getFileName() + ".part");
		Files.write(temp, body);
		Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private HttpResponse<String> get(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(TIMEOUT)
				.header("User-Agent", USER_AGENT)
				.GET()
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static void verifySha256(byte[] data, String expectedHex) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String actualHex = java.util.HexFormat.of().formatHex(digest.digest(data));
			if (!actualHex.equalsIgnoreCase(expectedHex)) {
				throw new IOException("Plugin jar SHA-256 mismatch: expected " + expectedHex + ", got " + actualHex);
			}
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 is mandated by the JCA spec", e);
		}
	}
}
