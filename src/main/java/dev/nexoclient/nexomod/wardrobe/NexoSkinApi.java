package dev.nexoclient.nexomod.wardrobe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The Mojang calls behind the wardrobe. Everything here blocks, so callers run
 * it off the render thread. The access token is only ever sent to the hardcoded
 * api.minecraftservices.com over HTTPS, by a client that does not follow
 * redirects, and it is never put in an exception or a log line.
 */
public final class NexoSkinApi {
	private static final String SERVICES = "https://api.minecraftservices.com/minecraft/profile";
	private static final Duration TIMEOUT = Duration.ofSeconds(15);
	private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	/** Carries the token and talks to fixed hosts. */
	private static final HttpClient API = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NEVER).build();
	/** For a URL the player typed: image hosts redirect. NORMAL never goes from https down to http. */
	private static final HttpClient DOWNLOADS = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	/** A failure with a translation key to show the player. */
	public static final class ApiException extends RuntimeException {
		public ApiException(String key) {
			super(key, null, false, false);
		}
	}

	public record FetchedSkin(byte[] png, boolean slim) {
	}

	public record Cape(String id, String name, String url, boolean active) {
	}

	private NexoSkinApi() {
	}

	/** A session with a real token; offline accounts and the dev client have none. */
	public static boolean hasToken(String token) {
		return token != null && token.length() >= 20;
	}

	public static byte[] fromUrl(String url) {
		URI uri;
		try {
			uri = new URI(url.strip());
		} catch (URISyntaxException e) {
			throw new ApiException("nexomod.wardrobe.error.badUrl");
		}
		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
			throw new ApiException("nexomod.wardrobe.error.badUrl");
		}
		return download(DOWNLOADS, uri);
	}

	public static FetchedSkin fromUsername(String name) {
		if (!USERNAME.matcher(name).matches()) {
			throw new ApiException("nexomod.wardrobe.error.noPlayer");
		}
		HttpResponse<String> found = text(API, get("https://api.mojang.com/users/profiles/minecraft/" + name));
		if (found.statusCode() != 200) {
			throw new ApiException(found.statusCode() == 404 || found.statusCode() == 204
					? "nexomod.wardrobe.error.noPlayer" : "nexomod.wardrobe.error.network");
		}
		try {
			String id = JsonParser.parseString(found.body()).getAsJsonObject().get("id").getAsString();
			if (!id.matches("[0-9a-fA-F]{32}")) {
				throw new ApiException("nexomod.wardrobe.error.network");
			}
			HttpResponse<String> profile = text(API, get("https://sessionserver.mojang.com/session/minecraft/profile/" + id));
			if (profile.statusCode() != 200) {
				throw new ApiException("nexomod.wardrobe.error.network");
			}
			for (JsonElement property : JsonParser.parseString(profile.body()).getAsJsonObject().getAsJsonArray("properties")) {
				JsonObject object = property.getAsJsonObject();
				if (!"textures".equals(object.get("name").getAsString())) {
					continue;
				}
				String decoded = new String(Base64.getDecoder().decode(object.get("value").getAsString()), StandardCharsets.UTF_8);
				JsonObject textures = JsonParser.parseString(decoded).getAsJsonObject().getAsJsonObject("textures");
				if (textures == null || !textures.has("SKIN")) {
					break;
				}
				JsonObject skin = textures.getAsJsonObject("SKIN");
				boolean slim = skin.has("metadata") && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
				return new FetchedSkin(fromMojangTextures(skin.get("url").getAsString()), slim);
			}
		} catch (ApiException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new ApiException("nexomod.wardrobe.error.network");
		}
		throw new ApiException("nexomod.wardrobe.error.noSkin");
	}

	/** Mojang's texture URLs are plain http; only that host is accepted, and over https. */
	public static byte[] fromMojangTextures(String url) {
		URI uri = URI.create(url);
		if (!"textures.minecraft.net".equalsIgnoreCase(uri.getHost()) || uri.getRawPath() == null) {
			throw new ApiException("nexomod.wardrobe.error.network");
		}
		return download(API, URI.create("https://textures.minecraft.net" + uri.getRawPath()));
	}

	/** The account's capes, with the one it wears marked. */
	public static List<Cape> capes(String token) {
		HttpResponse<String> response = text(API, authed(SERVICES, token).GET().build());
		check(response.statusCode());
		List<Cape> capes = new ArrayList<>();
		try {
			JsonObject profile = JsonParser.parseString(response.body()).getAsJsonObject();
			if (profile.has("capes")) {
				for (JsonElement element : profile.getAsJsonArray("capes")) {
					JsonObject cape = element.getAsJsonObject();
					capes.add(new Cape(cape.get("id").getAsString(), NexoSkinLibrary.cleanName(cape.get("alias").isJsonNull() ? "Cape" : cape.get("alias").getAsString()),
							cape.get("url").getAsString(), "ACTIVE".equals(cape.get("state").getAsString())));
				}
			}
		} catch (RuntimeException e) {
			throw new ApiException("nexomod.wardrobe.error.network");
		}
		return capes;
	}

	/** Shows this cape on the account; an empty id hides whichever one is on. */
	public static void setCape(String capeId, String token) {
		HttpRequest.Builder request = authed(SERVICES + "/capes/active", token);
		if (capeId.isEmpty()) {
			request.DELETE();
		} else {
			JsonObject body = new JsonObject();
			body.addProperty("capeId", capeId);
			request.header("Content-Type", "application/json")
					.PUT(HttpRequest.BodyPublishers.ofString(body.toString()));
		}
		check(text(API, request.build()).statusCode());
	}

	/** Uploads a skin to the account. */
	public static void wear(byte[] png, boolean slim, String token) {
		String boundary = "nexo" + UUID.randomUUID().toString().replace("-", "");
		ByteArrayOutputStream body = new ByteArrayOutputStream(png.length + 512);
		body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"
				+ (slim ? "slim" : "classic") + "\r\n--" + boundary
				+ "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
		body.writeBytes(png);
		body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
		check(text(API, authed(SERVICES + "/skins", token)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build()).statusCode());
	}

	private static void check(int status) {
		if (status == 401 || status == 403) {
			throw new ApiException("nexomod.wardrobe.error.session");
		}
		if (status == 400) {
			throw new ApiException("nexomod.wardrobe.error.rejected");
		}
		if (status == 429) {
			throw new ApiException("nexomod.wardrobe.error.rateLimit");
		}
		if (status < 200 || status >= 300) {
			throw new ApiException("nexomod.wardrobe.error.network");
		}
	}

	private static HttpRequest get(String url) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", "nexomod-wardrobe/1.0").GET().build();
	}

	private static HttpRequest.Builder authed(String url, String token) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", "nexomod-wardrobe/1.0")
				.header("Authorization", "Bearer " + token);
	}

	private static HttpResponse<String> text(HttpClient client, HttpRequest request) {
		try {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new ApiException("nexomod.wardrobe.error.network");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException("nexomod.wardrobe.error.network");
		}
	}

	private static byte[] download(HttpClient client, URI uri) {
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("User-Agent", "nexomod-wardrobe/1.0").GET().build();
		try {
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream in = response.body()) {
				if (response.statusCode() != 200) {
					throw new ApiException("nexomod.wardrobe.error.download");
				}
				// The request timeout stops at the headers: this bounds a server dripping out the body.
				CompletableFuture.delayedExecutor(TIMEOUT.toSeconds(), TimeUnit.SECONDS).execute(() -> {
					try {
						in.close();
					} catch (IOException ignored) {
					}
				});
				byte[] bytes = in.readNBytes(NexoSkinLibrary.MAX_FILE + 1);
				if (bytes.length > NexoSkinLibrary.MAX_FILE) {
					throw new ApiException("nexomod.wardrobe.error.badImage");
				}
				return bytes;
			}
		} catch (IOException | IllegalArgumentException e) {
			throw new ApiException("nexomod.wardrobe.error.download");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException("nexomod.wardrobe.error.download");
		}
	}
}
