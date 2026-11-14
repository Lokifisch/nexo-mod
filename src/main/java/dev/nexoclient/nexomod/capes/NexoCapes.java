package dev.nexoclient.nexomod.capes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.emotes.NexoEmoteAccount;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Nexo's custom capes, as other players wear them. The service publishes who
 * wears what as one public list keyed by the badge roster's 8-byte UUID hash;
 * this downloads all of it and matches locally, the same direction as badges, so
 * the service never learns whom this client is looking at.
 *
 * <p>Textures are named by the SHA-256 of the file, and every download is checked
 * against that name before it is decoded.
 */
public final class NexoCapes {
	/** Who sees the Review button. The service checks again; this only hides it from everyone else. */
	public static final Set<UUID> REVIEWERS = Set.of(UUID.fromString("4260c5e0-c7de-404b-9d3b-b1e14f7fb5f2"));

	/** Under the game folder, not config/: modpacks sync and share config. */
	private static final Path CACHE = FabricLoader.getInstance().getGameDir().resolve("nexomod-cache").resolve("capes");
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final String USER_AGENT = "nexomod-capes/1.0";
	/** The service's own ceiling for a re-encoded cape (MAX_STORED_BYTES), plus one. */
	private static final int MAX_FILE = 3 * 1024 * 1024 + 1;
	private static final long ROSTER_MILLIS = 2 * 60 * 1000L;
	private static final long RETRY_MILLIS = 5 * 60 * 1000L;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NEVER).build();

	private static volatile Map<String, String> worn = Map.of();
	private static volatile String etag;
	private static volatile boolean rosterBusy;
	private static long nextRoster;
	private static boolean rosterRead;
	private static int titleTicks;
	/**
	 * What this player wears, as last chosen here or in the launcher: shown on them at once,
	 * before the public list catches up. Unset until a choice is known.
	 */
	private static volatile boolean ownKnown;
	private static volatile String ownCape;
	/** The account that choice is for: switching accounts must not show one account's cape on another. */
	private static volatile UUID ownOwner;
	/** Textures on the GPU, at most {@link #KEEP}: the least recently drawn is released. Render thread only. */
	private static final int KEEP = 96;
	private static final Map<String, Identifier> READY = new java.util.LinkedHashMap<>(KEEP, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
			if (size() <= KEEP) {
				return false;
			}
			// Comes back from the disk cache if it is needed again.
			Minecraft.getInstance().getTextureManager().release(eldest.getValue());
			return true;
		}
	};
	private static final Map<String, Boolean> FETCHING = new ConcurrentHashMap<>();
	private static final Map<String, Long> RETRY_AFTER = new ConcurrentHashMap<>();
	/** Roster hashes by player, so rendering doesn't run SHA-256 per player per frame. */
	private static final Map<UUID, String> HASHES = new ConcurrentHashMap<>();

	private NexoCapes() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(NexoCapes::tick);
	}

	public static boolean isReviewer() {
		var user = Minecraft.getInstance().getUser();
		return user != null && REVIEWERS.contains(user.getProfileId());
	}

	private static void tick(Minecraft mc) {
		NexoCapeAccount.tick(mc);
		long now = System.currentTimeMillis();
		// In a world every few minutes. On the title screen only when the launcher's file hasn't said what this
		// player wears (given a couple of seconds to be read), and then once: a launch is not logged otherwise.
		if (mc.level == null && (rosterRead || ownKnown || ++titleTicks < 60)) {
			return;
		}
		if (!NexoConfig.get().nexoCapesEnabled() || rosterBusy || now < nextRoster) {
			return;
		}
		if (mc.level == null) {
			rosterRead = true;
		}
		rosterBusy = true;
		nextRoster = now + ROSTER_MILLIS;
		CompletableFuture.runAsync(NexoCapes::readRoster, Util.nonCriticalIoPool()).whenComplete((ok, error) -> {
			rosterBusy = false;
			if (error != null) {
				nextRoster = System.currentTimeMillis() + RETRY_MILLIS;
			}
		});
	}

	private static void readRoster() {
		try {
			HttpRequest.Builder request = request("/capes/worn").GET();
			if (etag != null) {
				request.header("If-None-Match", etag);
			}
			HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 304) {
				return;
			}
			if (response.statusCode() != 200) {
				throw new IllegalStateException("roster HTTP " + response.statusCode());
			}
			Map<String, String> fresh = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("worn").entrySet()) {
				String cape = entry.getValue().getAsString();
				if (entry.getKey().matches("[0-9a-f]{16}") && cape.matches("[0-9a-f]{64}")) {
					fresh.put(entry.getKey(), cape);
				}
			}
			worn = Map.copyOf(fresh);
			etag = response.headers().firstValue("ETag").orElse(null);
		} catch (IOException | RuntimeException e) {
			NexoMod.LOGGER.debug("[nexomod] Capes: could not read who wears what.", e);
			throw new IllegalStateException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** The custom cape texture this player wears, once it has arrived; null otherwise. Render thread. */
	public static Identifier capeFor(UUID player) {
		if (!NexoConfig.get().nexoCapesEnabled()) {
			return null;
		}
		var user = Minecraft.getInstance().getUser();
		boolean own = ownKnown && user != null && player.equals(user.getProfileId()) && player.equals(ownOwner);
		String cape = own ? ownCape : worn.get(HASHES.computeIfAbsent(player, NexoCapes::hash));
		return cape == null ? null : texture(cape, false);
	}

	/** This player now wears {@code cape} (null for none): at once here, and in the public list on its next read, which comes soon. */
	static void wearLocally(UUID owner, String cape) {
		ownCape = cape != null && cape.matches("[0-9a-f]{64}") ? cape : null;
		ownOwner = owner;
		ownKnown = true;
		nextRoster = 0L;
	}

	/** A cape's texture if it is here yet; starts fetching it otherwise. {@code authed} for unreviewed ones. */
	public static Identifier texture(String cape, boolean authed) {
		Identifier ready = READY.get(cape);
		if (ready == null && System.currentTimeMillis() >= RETRY_AFTER.getOrDefault(cape, 0L) && FETCHING.putIfAbsent(cape, true) == null) {
			CompletableFuture.runAsync(() -> {
				try {
					fetch(cape, authed);
				} catch (IOException | RuntimeException e) {
					NexoMod.LOGGER.debug("[nexomod] Capes: could not fetch a cape.", e);
					RETRY_AFTER.put(cape, System.currentTimeMillis() + RETRY_MILLIS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					FETCHING.remove(cape);
				}
			}, Util.nonCriticalIoPool());
		}
		return ready;
	}

	private static void fetch(String cape, boolean authed) throws IOException, InterruptedException {
		if (!cape.matches("[0-9a-f]{64}")) {
			return;
		}
		Files.createDirectories(CACHE);
		Path cached = CACHE.resolve(cape + ".png");
		byte[] data = Files.exists(cached) ? Files.readAllBytes(cached) : null;
		if (data == null || !cape.equals(sha256(data))) {
			String token = authed ? NexoEmoteAccount.session(null) : null;
			HttpResponse<InputStream> response = download(cape, token);
			if (authed && response.statusCode() == 401) {
				// An ended session: once more with a newer one.
				response.body().close();
				response = download(cape, NexoEmoteAccount.session(token));
			}
			try (InputStream in = response.body()) {
				if (response.statusCode() != 200) {
					throw new IOException("cape HTTP " + response.statusCode());
				}
				// The request's timeout stops at the headers; this bounds a stalled body.
				CompletableFuture.delayedExecutor(TIMEOUT.toSeconds(), TimeUnit.SECONDS).execute(() -> {
					try {
						in.close();
					} catch (IOException ignored) {
					}
				});
				data = in.readNBytes(MAX_FILE);
			}
			if (data.length >= MAX_FILE || !cape.equals(sha256(data))) {
				throw new IOException("a cape file did not match its name");
			}
			if (!authed) {
				// Unreviewed files stay off disk: they may never be approved.
				Files.write(cached, data);
			}
		}
		// Sized from the header before anything is decoded: the hash proves only that the
		// service sent it, and a PNG claiming a huge size would be decoded at that size.
		if (data.length < 24 || data[12] != 'I' || data[13] != 'H' || data[14] != 'D' || data[15] != 'R') {
			throw new IOException("not a PNG");
		}
		int w = java.nio.ByteBuffer.wrap(data, 16, 4).getInt();
		int h = java.nio.ByteBuffer.wrap(data, 20, 4).getInt();
		// Cape layouts at any power-of-two scale, as the service accepts.
		if (w != h * 2 || w < 64 || w > 1024 || Integer.bitCount(w) != 1) {
			throw new IOException("a cape of the wrong size: " + w + "x" + h);
		}
		NativeImage image = NativeImage.read(data);
		Identifier id = Identifier.fromNamespaceAndPath("nexomod", "capes/" + cape);
		Minecraft.getInstance().execute(() -> {
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
			READY.put(cape, id);
		});
	}

	private static HttpResponse<InputStream> download(String cape, String token) throws IOException, InterruptedException {
		HttpRequest.Builder request = request("/capes/files/" + cape).GET();
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
	}

	/** Dev tour only: this player wears {@code png}, and the real list is no longer read. */
	public static void devWear(UUID player, byte[] png) throws IOException {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
			return;
		}
		rosterBusy = true;
		String cape = sha256(png);
		NativeImage image = NativeImage.read(png);
		Identifier id = Identifier.fromNamespaceAndPath("nexomod", "capes/" + cape);
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
		READY.put(cape, id);
		worn = Map.of(hash(player), cape);
	}

	static String hash(UUID player) {
		return sha256(player.toString().getBytes(StandardCharsets.US_ASCII)).substring(0, 16);
	}

	private static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The service's own explanation of a refusal, when it gave one. */
	static String refusal(HttpResponse<String> response) {
		try {
			String error = JsonParser.parseString(response.body()).getAsJsonObject().get("error").getAsString();
			return error.replaceAll("\\p{Cntrl}|\\p{Cf}|§.?", "");
		} catch (RuntimeException e) {
			return "the cape service answered HTTP " + response.statusCode();
		}
	}

	static HttpClient http() {
		return HTTP;
	}

	static HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create(NexoEmoteAccount.baseUrl() + path)).timeout(TIMEOUT).header("User-Agent", USER_AGENT);
	}

	// --- Review ---------------------------------------------------------------

	public record Pending(String id, String name, boolean approved) {
	}

	/** Waiting capes first, then approved ones. Blocking. */
	static List<Pending> reviewQueue() throws IOException, InterruptedException {
		return parseQueue(authed("/capes/review", HttpRequest.Builder::GET));
	}

	static List<Pending> review(String cape, boolean approve) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("approve", approve);
		return parseQueue(authed("/capes/" + cape + "/review", builder -> builder.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))));
	}

	private static List<Pending> parseQueue(String reply) {
		JsonObject body = JsonParser.parseString(reply).getAsJsonObject();
		List<Pending> out = new ArrayList<>();
		for (String list : new String[] {"pending", "approved"}) {
			for (JsonElement element : body.getAsJsonArray(list)) {
				JsonObject cape = element.getAsJsonObject();
				String id = cape.get("id").getAsString();
				if (id.matches("[0-9a-f]{64}")) {
					String name = cape.get("name").getAsString().replaceAll("\\p{Cntrl}|\\p{Cf}|§.?", "");
					out.add(new Pending(id, name.length() > 32 ? name.substring(0, 32) : name, list.equals("approved")));
				}
			}
		}
		return out;
	}

	static String authed(String path, java.util.function.UnaryOperator<HttpRequest.Builder> method) throws IOException, InterruptedException {
		String failed = null;
		for (int attempt = 0; attempt < 2; attempt++) {
			String token = NexoEmoteAccount.session(failed);
			HttpResponse<String> response = HTTP.send(method.apply(request(path).header("Authorization", "Bearer " + token)).build(),
					HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 401 && attempt == 0) {
				failed = token;
				continue;
			}
			if (response.statusCode() != 200) {
				throw new IOException(refusal(response));
			}
			return response.body();
		}
		throw new IOException("signed out");
	}
}
