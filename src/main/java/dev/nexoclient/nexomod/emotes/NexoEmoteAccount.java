package dev.nexoclient.nexomod.emotes;

import java.io.ByteArrayInputStream;
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
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zigythebird.playeranimcore.animation.Animation;
import com.zigythebird.playeranimcore.loading.UniversalAnimLoader;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.auth.AccountStore;
import dev.nexoclient.nexomod.net.MojangIdentityProof;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * The shop side of emotes: sign-in, coins, what this account owns, and fetching
 * animation files. All of it off unless the player turned the shop on. The Mojang
 * token only ever goes to Mojang (see {@link MojangIdentityProof}).
 */
public final class NexoEmoteAccount {
	static final String BASE_URL = devOverride("NEXOMOD_EMOTE_API", "https://nexomc.dev/nexo/emotes/api/v1");
	private static final Path SESSION = FabricLoader.getInstance().getConfigDir().resolve("nexomod-emote-session.bin");
	private static final Path CACHE = FabricLoader.getInstance().getConfigDir().resolve("nexomod-emotes");
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final String USER_AGENT = "nexomod-emotes/1.0";
	private static final int MAX_FILE = 256 * 1024;
	private static final long HEARTBEAT_MILLIS = 5 * 60 * 1000L;
	/** Heartbeats need someone at the keyboard this recently. */
	private static final long IDLE_MILLIS = 3 * 60 * 1000L;

	/**
	 * The service's public keys by id. To rotate, add the next one here a release
	 * before the service starts signing with it. {@code deploy.sh} prints the current one.
	 */
	public static final Map<Integer, PublicKey> TICKET_KEYS = NexoEmoteTicket.keys(Map.of(
			1, "0bd89dc3ae4d1a2b2f1cf026858b23937b662d61aa931a05981be5ca976f7e90"));

	/** {@code listPrice} is the normal price while a deal runs, else 0; {@code endsAt} is when this offer stops, else 0. */
	public record ShopItem(String id, String name, int price, String rarity, int listPrice, long endsAt) {
	}

	/** Today's progress, as the service counts it. */
	public record Earn(int playtime, int playtimeCap, boolean daily, int dailyCoins, int streak, int streakDays, int streakCoins) {
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NEVER).build();
	private static final Map<String, Animation> ANIMATIONS = new ConcurrentHashMap<>();
	private static final Map<String, NexoEmotePreview> PREVIEWS = new ConcurrentHashMap<>();
	private static final Map<String, Boolean> FETCHING = new ConcurrentHashMap<>();
	/** When a failed fetch may be tried again: re-announces arrive every few seconds. */
	private static final Map<String, Long> RETRY_AFTER = new ConcurrentHashMap<>();
	private static final Map<String, String> PURCHASES = new ConcurrentHashMap<>();
	private static final long RETRY_MILLIS = 60_000L;
	/** Tickets last 24 hours; renewed well before that, and after a failure. */
	private static final long REFRESH_MILLIS = 6 * 3600 * 1000L;
	private static long refreshAt;
	/** Bumped by an erase, so a refresh already running can't sign back in or restore what it read. */
	private static volatile int generation;
	private static volatile boolean erasing;

	private static volatile String token;
	private static volatile UUID tokenOwner;
	private static volatile int balance;
	private static volatile Earn earn;
	private static volatile List<ShopItem> shop = List.of();
	private static volatile List<NexoEmotes.Emote> owned = List.of();
	private static volatile Map<String, byte[]> tickets = Map.of();
	private static volatile boolean online;
	private static long lastBeat;
	private static long lastInput;
	private static double lastMouseX;

	private NexoEmoteAccount() {
	}

	private static String devOverride(String name, String fallback) {
		String value = FabricLoader.getInstance().isDevelopmentEnvironment() ? System.getenv(name) : null;
		return value != null ? value : fallback;
	}

	public static boolean online() {
		return online;
	}

	public static int balance() {
		return balance;
	}

	/** Null until the service has said, or when signed out. */
	public static Earn earn() {
		return earn;
	}

	public static List<ShopItem> shop() {
		return shop;
	}

	public static List<NexoEmotes.Emote> ownedEmotes() {
		return owned;
	}

	public static byte[] ticket(String emote) {
		return tickets.getOrDefault(emote, new byte[0]);
	}

	/** Whether this session has anything to sign in with: offline accounts get starters only. */
	public static boolean canSignIn() {
		User user = MojangIdentityProof.currentUser();
		return user != null && user.getAccessToken().length() >= 20;
	}

	private static <T> void background(java.util.function.Supplier<T> work, Consumer<T> done, Consumer<String> failed) {
		Minecraft mc = Minecraft.getInstance();
		CompletableFuture.supplyAsync(work, Util.nonCriticalIoPool()).whenComplete((result, error) -> mc.execute(() -> {
			if (error == null) {
				done.accept(result);
			} else if (failed != null) {
				Throwable cause = error.getCause() != null ? error.getCause() : error;
				failed.accept(cause instanceof ShopError shopError ? shopError.getMessage() : "nexomod.emote.error.network");
			}
		}));
	}

	private static final class ShopError extends RuntimeException {
		ShopError(String key) {
			super(key, null, false, false);
		}
	}

	// --- HTTP ---------------------------------------------------------------

	private static HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create(BASE_URL + path)).timeout(TIMEOUT).header("User-Agent", USER_AGENT);
	}

	private static String send(HttpRequest request) {
		try {
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 401) {
				token = null;
				throw new ShopError("nexomod.emote.error.session");
			}
			if (response.statusCode() == 409) {
				String code = JsonParser.parseString(response.body()).getAsJsonObject().get("error").getAsString();
				throw new ShopError("not_enough_coins".equals(code) ? "nexomod.emote.error.coins" : "nexomod.emote.error.refused");
			}
			if (response.statusCode() != 200) {
				throw new ShopError("nexomod.emote.error.network");
			}
			return response.body();
		} catch (IOException e) {
			throw new ShopError("nexomod.emote.error.network");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ShopError("nexomod.emote.error.network");
		} catch (IllegalStateException e) {
			throw new ShopError("nexomod.emote.error.network");
		}
	}

	private static String authed(String path, java.util.function.UnaryOperator<HttpRequest.Builder> method) {
		String current = token;
		if (current == null) {
			throw new ShopError("nexomod.emote.error.session");
		}
		return send(method.apply(request(path).header("Authorization", "Bearer " + current)).build());
	}

	// --- Session ------------------------------------------------------------

	private static String signIn(User user, int started) throws IOException, InterruptedException {
		if (started != generation) {
			throw new ShopError("nexomod.emote.error.session");
		}
		MojangIdentityProof proof = new MojangIdentityProof(HTTP, TIMEOUT, USER_AGENT);
		String serverId = proof.requestChallenge(BASE_URL + "/challenge");
		if (serverId == null) {
			throw new ShopError("nexomod.emote.error.network");
		}
		if (!proof.joinWithMojang(user, serverId)) {
			throw new ShopError("nexomod.emote.error.mojang");
		}
		JsonObject body = new JsonObject();
		body.addProperty("username", user.getName());
		body.addProperty("uuid", user.getProfileId().toString());
		body.addProperty("server_id", serverId);
		String reply = send(request("/session").header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
		String fresh = JsonParser.parseString(reply).getAsJsonObject().get("token").getAsString();
		if (started != generation) {
			throw new ShopError("nexomod.emote.error.session");
		}
		save(user.getProfileId(), fresh);
		return fresh;
	}

	private static void save(UUID owner, String value) {
		try {
			Files.write(SESSION, AccountStore.sealLocal((owner + "\n" + value).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			NexoMod.LOGGER.warn("[nexomod] Emotes: could not store the session.");
		}
	}

	private static void loadSaved(UUID owner) {
		if (owner.equals(tokenOwner) && token != null) {
			return;
		}
		token = null;
		tokenOwner = owner;
		try {
			if (Files.exists(SESSION)) {
				String[] parts = new String(AccountStore.openLocal(Files.readAllBytes(SESSION)), StandardCharsets.UTF_8).split("\n", 2);
				if (parts.length == 2 && parts[0].equals(owner.toString())) {
					token = parts[1];
				}
			}
		} catch (Exception e) {
			NexoMod.LOGGER.debug("[nexomod] Emotes: ignoring an unreadable session.");
		}
	}

	/** Turns the shop on or refreshes it: sign in if needed, then read everything. */
	public static void refresh(Consumer<String> failed, Runnable done) {
		User user = MojangIdentityProof.currentUser();
		boolean signIn = canSignIn() && !erasing;
		int started = generation;
		background(() -> {
			List<ShopItem> items = readCatalog();
			Me me = null;
			if (signIn) {
				loadSaved(user.getProfileId());
				me = signedInMe(user, started);
			}
			return new Fresh(items, me);
		}, fresh -> {
			shop = fresh.items();
			if (fresh.me() != null && started == generation) {
				apply(fresh.me());
				online = true;
			}
			if (done != null) {
				done.run();
			}
		}, failed);
	}

	/** Reads the account, signing in first if there is no session or it has expired. */
	private static Me signedInMe(User user, int started) {
		try {
			if (token == null) {
				token = signIn(user, started);
			}
			try {
				return readMe();
			} catch (ShopError expired) {
				if (!"nexomod.emote.error.session".equals(expired.getMessage())) {
					throw expired;
				}
				token = signIn(user, started);
				return readMe();
			}
		} catch (IOException e) {
			throw new ShopError("nexomod.emote.error.network");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ShopError("nexomod.emote.error.network");
		}
	}

	private record Fresh(List<ShopItem> items, Me me) {
	}

	private record Me(int balance, Earn earn, List<NexoEmotes.Emote> owned, Map<String, byte[]> tickets) {
	}

	private static List<ShopItem> readCatalog() {
		List<ShopItem> items = new ArrayList<>();
		try {
			for (JsonElement element : JsonParser.parseString(send(request("/catalog").GET().build())).getAsJsonObject().getAsJsonArray("emotes")) {
				JsonObject item = element.getAsJsonObject();
				String id = item.get("id").getAsString();
				if (id.matches("[0-9a-f]{64}")) {
					int listPrice = item.has("list_price") ? item.get("list_price").getAsInt() : 0;
					long endsAt = item.has("ends_in") ? System.currentTimeMillis() + item.get("ends_in").getAsLong() * 1000L : 0L;
					items.add(new ShopItem(id, clean(item.get("name").getAsString()), item.get("price").getAsInt(), clean(item.get("rarity").getAsString()), listPrice, endsAt));
				}
			}
		} catch (IllegalStateException | NullPointerException | ClassCastException e) {
			throw new ShopError("nexomod.emote.error.network");
		}
		return items;
	}

	private static Me readMe() {
		return parseMe(authed("/me", HttpRequest.Builder::GET));
	}

	private static Me parseMe(String reply) {
		try {
			JsonObject body = JsonParser.parseString(reply).getAsJsonObject();
			List<NexoEmotes.Emote> emotes = new ArrayList<>();
			for (JsonElement element : body.getAsJsonArray("owned")) {
				JsonObject item = element.getAsJsonObject();
				String id = item.get("id").getAsString();
				if (id.matches("[0-9a-f]{64}")) {
					emotes.add(new NexoEmotes.Emote(id, Component.literal(clean(item.get("name").getAsString())), NexoEmotes.Pose.NEUTRAL, false));
				}
			}
			Map<String, byte[]> signed = new ConcurrentHashMap<>();
			for (Map.Entry<String, JsonElement> entry : body.getAsJsonObject("tickets").entrySet()) {
				signed.put(entry.getKey(), HexFormat.of().parseHex(entry.getValue().getAsString()));
			}
			return new Me(body.get("balance").getAsInt(), parseEarn(body), List.copyOf(emotes), signed);
		} catch (IllegalArgumentException | IllegalStateException | NullPointerException | ClassCastException e) {
			throw new ShopError("nexomod.emote.error.network");
		}
	}

	/** Only ever decorates the shop, so a reply without it must not fail the sign-in. */
	private static Earn parseEarn(JsonObject body) {
		try {
			JsonObject e = body.getAsJsonObject("earn");
			return new Earn(e.get("playtime").getAsInt(), e.get("playtime_cap").getAsInt(), e.get("daily").getAsBoolean(),
					e.get("daily_coins").getAsInt(), e.get("streak").getAsInt(), e.get("streak_days").getAsInt(), e.get("streak_coins").getAsInt());
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String clean(String text) {
		String clean = text.replaceAll("\\p{Cntrl}|\\p{Cf}|§.?", "").strip();
		return clean.length() > 32 ? clean.substring(0, 32) : clean;
	}

	private static void apply(Me me) {
		balance = me.balance();
		earn = me.earn();
		owned = me.owned();
		tickets = me.tickets();
		me.owned().forEach(emote -> animation(emote.id()));
	}

	public static void buy(ShopItem item, Consumer<String> failed, Runnable done) {
		// Kept until it succeeds: a retry after a lost reply must not be a second purchase.
		String purchase = PURCHASES.computeIfAbsent(item.id(), id -> UUID.randomUUID().toString());
		background(() -> {
			JsonObject body = new JsonObject();
			body.addProperty("emote", item.id());
			body.addProperty("purchase_id", purchase);
			body.addProperty("expected_price", item.price());
			return parseMe(authed("/purchase", builder -> builder.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body.toString()))));
		}, me -> {
			PURCHASES.remove(item.id());
			apply(me);
			done.run();
		}, key -> {
			failed.accept(key);
			// A reply lost after the charge, or a changed price: show what is true now.
			refresh(null, null);
		});
	}

	/** Deletes the wallet and everything else the service holds about this account. */
	public static void deleteAccount(Consumer<String> failed, Runnable done) {
		erasing = true;
		generation++;
		background(() -> authed("/account", HttpRequest.Builder::DELETE), reply -> {
			erasing = false;
			generation++;
			token = null;
			online = false;
			balance = 0;
			earn = null;
			owned = List.of();
			tickets = Map.of();
			try {
				Files.deleteIfExists(SESSION);
			} catch (IOException e) {
				NexoMod.LOGGER.warn("[nexomod] Emotes: could not delete the stored session.");
			}
			done.run();
		}, key -> {
			erasing = false;
			failed.accept(key);
		});
	}

	// --- Rewards ------------------------------------------------------------

	/** Called every client tick: one heartbeat per five minutes of real play. */
	static void tick(Minecraft mc) {
		if (!NexoConfig.get().emoteShopEnabled() || mc.player == null) {
			return;
		}
		long now = System.currentTimeMillis();
		// Switching accounts: nothing of the old one's may be spent or shown as this one's.
		User user = MojangIdentityProof.currentUser();
		if (online && (user == null || !user.getProfileId().equals(tokenOwner))) {
			online = false;
			balance = 0;
			earn = null;
			owned = List.of();
			tickets = Map.of();
			refreshAt = 0L;
		}
		if (now >= refreshAt && canSignIn()) {
			// Also how a game restart gets its account back without opening the wardrobe.
			refreshAt = now + (online ? REFRESH_MILLIS : RETRY_MILLIS * 5);
			refresh(null, null);
		}
		if (!online) {
			return;
		}
		double mouse = mc.mouseHandler.xpos();
		if (mouse != lastMouseX || mc.options.keyUp.isDown() || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown()) {
			lastMouseX = mouse;
			lastInput = now;
		}
		if (now - lastBeat < HEARTBEAT_MILLIS || now - lastInput > IDLE_MILLIS) {
			return;
		}
		lastBeat = now;
		background(() -> authed("/heartbeat", builder -> builder.POST(HttpRequest.BodyPublishers.noBody())), reply -> {
			JsonObject body = JsonParser.parseString(reply).getAsJsonObject();
			balance = body.get("balance").getAsInt();
			Earn progress = parseEarn(body);
			if (progress != null) {
				earn = progress;
			}
			int earned = body.get("earned").getAsInt();
			// Only for the daily and streak rewards: five coins every five minutes would nag.
			if (earned > 5) {
				net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.gui.toastManager(),
						net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
						Component.translatable("nexomod.emote.earned", earned), Component.translatable("nexomod.emote.balance", balance));
			}
		}, key -> {
			// An ended session signs in again instead of failing quietly forever.
			if ("nexomod.emote.error.session".equals(key)) {
				refreshAt = 0L;
			}
		});
	}

	// --- Files --------------------------------------------------------------

	/** A shop emote's menu preview if its file is here yet; asks for the file otherwise. */
	public static NexoEmotePreview preview(String id) {
		animation(id);
		return PREVIEWS.get(id);
	}

	/** A shop emote's animation if it is here yet; asks for it otherwise. */
	public static Animation animation(String id) {
		Animation animation = ANIMATIONS.get(id);
		if (animation == null && System.currentTimeMillis() >= RETRY_AFTER.getOrDefault(id, 0L) && FETCHING.putIfAbsent(id, true) == null) {
			CompletableFuture.runAsync(() -> {
				try {
					fetchNow(id);
					if (!ANIMATIONS.containsKey(id)) {
						RETRY_AFTER.put(id, System.currentTimeMillis() + RETRY_MILLIS);
					}
				} finally {
					FETCHING.remove(id);
				}
			}, Util.nonCriticalIoPool());
		}
		return animation;
	}

	/** From the cache or the service, checked against the hash that names it. Blocking. */
	private static void fetchNow(String id) {
		if (ANIMATIONS.containsKey(id) || !id.matches("[0-9a-f]{64}")) {
			return;
		}
		try {
			Files.createDirectories(CACHE);
			Path cached = CACHE.resolve(id + ".json");
			byte[] data = null;
			if (Files.exists(cached)) {
				data = Files.readAllBytes(cached);
				if (!id.equals(sha256(data))) {
					Files.delete(cached);
					data = null;
				}
			}
			if (data == null) {
				HttpResponse<InputStream> response = HTTP.send(request("/files/" + id).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
				try (InputStream in = response.body()) {
					if (response.statusCode() != 200) {
						return;
					}
					// The request's timeout stops at the headers; this bounds a stalled body.
					CompletableFuture.delayedExecutor(TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
						try {
							in.close();
						} catch (IOException ignored) {
						}
					});
					data = in.readNBytes(MAX_FILE + 1);
				}
				if (data.length > MAX_FILE || !id.equals(sha256(data))) {
					return;
				}
				Files.write(cached, data);
			}
			// The same rules the service applied on upload, in case it is ever not the one answering.
			if (!NexoEmoteFileCheck.safe(data)) {
				Files.deleteIfExists(cached);
				return;
			}
			var loaded = UniversalAnimLoader.loadAnimations(new ByteArrayInputStream(data));
			if (loaded.size() == 1) {
				ANIMATIONS.put(id, loaded.values().iterator().next());
				try {
					PREVIEWS.put(id, NexoEmotePreview.parse(data));
				} catch (RuntimeException e) {
					// It still plays; the shop just shows it standing still.
					NexoMod.LOGGER.debug("[nexomod] Emotes: no preview for an animation.", e);
				}
			}
		} catch (IOException | RuntimeException e) {
			NexoMod.LOGGER.debug("[nexomod] Emotes: could not fetch an animation.", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}
}
