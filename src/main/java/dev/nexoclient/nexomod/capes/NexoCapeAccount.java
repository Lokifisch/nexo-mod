package dev.nexoclient.nexomod.capes;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.emotes.NexoEmoteAccount;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.util.NexoPaths;

/**
 * This player's side of Nexo capes: choosing one, favorites, reports, a friend's
 * code, and staying in step with the launcher.
 *
 * <p>The launcher and the game share {@code capes-shared.json} in the launcher's data
 * folder: each writes what the account wears when it changes it, and each watches
 * the file, so a cape picked in one shows in the other within a second. The same
 * file lists uploads still waiting for review, which is the only time this checks
 * the account in the background: to say when one is decided.
 */
public final class NexoCapeAccount {
	public record Cape(String id, String code, String name, String uploader, int favorites, int uses, String status, String reason, int reports) {
		public boolean approved() {
			return status == null || "approved".equals(status);
		}
	}

	public record Mine(List<Cape> mine, Set<String> favorites, String worn, int limit, boolean admin) {
	}

	public record Review(List<Cape> pending, List<Cape> reported, List<Cape> approved) {
	}

	/** One file per account (dashless UUID), so the launcher and the game on different accounts never share one. */
	private static final Path SHARED_DIR = NexoPaths.sharedDataDir().resolve("capes-shared");
	private static final long WATCH_TICKS = 20;
	private static final long DECISION_MILLIS = 5 * 60 * 1000L;
	private static long ticks;
	private static volatile FileTime sharedSeen;
	private static volatile UUID sharedOwner;
	/**
	 * Counts this player's choices (wear, delete). A reply that started before the latest
	 * choice describes the past, and must not undo it: an account read still in flight
	 * when Wear is pressed would otherwise put the old cape back.
	 */
	private static final java.util.concurrent.atomic.AtomicInteger CHOICES = new java.util.concurrent.atomic.AtomicInteger();
	/** Uploads still waiting, by id, as the shared file lists them for this account. */
	private static volatile Set<String> waiting = Set.of();
	private static long nextDecisionCheck;
	private static volatile boolean checking;

	private NexoCapeAccount() {
	}

	// --- The service ----------------------------------------------------------

	public static List<Cape> catalog(String sort) throws IOException, InterruptedException {
		HttpResponse<String> response = NexoCapes.http().send(NexoCapes.request("/capes?sort=" + URLEncoder.encode(sort, StandardCharsets.UTF_8)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException(NexoCapes.refusal(response));
		}
		return capes(JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("capes"));
	}

	/** The approved cape a code names, typed however: {@code 3F9A-2C1B}, {@code 3f9a2c1b}. */
	public static Cape byCode(String code) throws IOException, InterruptedException {
		String raw = code.replaceAll("[\\s-]", "").toLowerCase(java.util.Locale.ROOT);
		if (!raw.matches("[0-9a-f]{8}")) {
			throw new IOException("a cape code is 8 letters and digits, like 3F9A-2C1B");
		}
		HttpResponse<String> response = NexoCapes.http().send(NexoCapes.request("/capes/code/" + raw).GET().build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException(NexoCapes.refusal(response));
		}
		return cape(JsonParser.parseString(response.body()).getAsJsonObject());
	}

	public static Mine me() throws IOException, InterruptedException {
		int at = CHOICES.get();
		UUID owner = owner();
		return remember(mine(NexoCapes.authed("/capes/me", HttpRequest.Builder::GET)), at, owner);
	}

	/** Wears an approved cape, or the account's own vanilla one again with null. */
	public static Mine wear(String cape) throws IOException, InterruptedException {
		int at = CHOICES.incrementAndGet();
		UUID owner = owner();
		JsonObject body = new JsonObject();
		body.add("cape", cape == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(cape));
		return remember(mine(NexoCapes.authed("/capes/wear", b -> b.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())))), at, owner);
	}

	public static Mine favorite(String cape, boolean on) throws IOException, InterruptedException {
		String path = "/capes/" + checked(cape) + "/favorite";
		int at = CHOICES.get();
		UUID owner = owner();
		return remember(mine(NexoCapes.authed(path, b -> on ? b.PUT(HttpRequest.BodyPublishers.noBody()) : b.DELETE())), at, owner);
	}

	public static Mine delete(String cape) throws IOException, InterruptedException {
		int at = CHOICES.incrementAndGet();
		UUID owner = owner();
		return remember(mine(NexoCapes.authed("/capes/" + checked(cape), HttpRequest.Builder::DELETE)), at, owner);
	}

	public static void report(String cape) throws IOException, InterruptedException {
		NexoCapes.authed("/capes/" + checked(cape) + "/report", b -> b.POST(HttpRequest.BodyPublishers.noBody()));
	}

	public static Review review() throws IOException, InterruptedException {
		return parseReview(NexoCapes.authed("/capes/review", HttpRequest.Builder::GET));
	}

	public static Review review(String cape, boolean approve) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("approve", approve);
		return parseReview(NexoCapes.authed("/capes/" + checked(cape) + "/review", b -> b.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))));
	}

	private static String checked(String cape) {
		if (cape == null || !cape.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("not a cape id");
		}
		return cape;
	}

	private static Review parseReview(String reply) {
		JsonObject body = JsonParser.parseString(reply).getAsJsonObject();
		JsonArray reported = body.has("reported") ? body.getAsJsonArray("reported") : new JsonArray();
		return new Review(capes(body.getAsJsonArray("pending")), capes(reported), capes(body.getAsJsonArray("approved")));
	}

	private static Mine mine(String reply) {
		JsonObject body = JsonParser.parseString(reply).getAsJsonObject();
		Set<String> favorites = new HashSet<>();
		body.getAsJsonArray("favorites").forEach(id -> favorites.add(id.getAsString()));
		JsonElement worn = body.get("worn");
		String wornId = worn == null || worn.isJsonNull() ? null : worn.getAsString();
		return new Mine(capes(body.getAsJsonArray("mine")), Set.copyOf(favorites), wornId, body.get("limit").getAsInt(), body.get("admin").getAsBoolean());
	}

	private static List<Cape> capes(JsonArray array) {
		List<Cape> out = new ArrayList<>();
		for (JsonElement element : array) {
			Cape cape = cape(element.getAsJsonObject());
			if (cape != null) {
				out.add(cape);
			}
		}
		return List.copyOf(out);
	}

	private static Cape cape(JsonObject item) {
		String id = item.get("id").getAsString();
		if (!id.matches("[0-9a-f]{64}")) {
			return null;
		}
		return new Cape(id, text(item, "code", 9), text(item, "name", 32), text(item, "uploader", 16), number(item, "favorites"), number(item, "uses"),
				text(item, "status", 16), text(item, "reason", 200), number(item, "reports"));
	}

	private static String text(JsonObject item, String key, int max) {
		JsonElement value = item.get(key);
		if (value == null || value.isJsonNull()) {
			return null;
		}
		String clean = value.getAsString().replaceAll("\\p{Cntrl}|\\p{Cf}|§.?", "").strip();
		return clean.length() > max ? clean.substring(0, max) : clean;
	}

	private static int number(JsonObject item, String key) {
		JsonElement value = item.get(key);
		return value == null || value.isJsonNull() ? 0 : value.getAsInt();
	}

	// --- In step with the launcher -------------------------------------------

	/**
	 * Shows the choice on this player now, and tells the launcher, unless a newer choice
	 * was made while this reply was on its way or the account has changed since it was asked.
	 */
	private static Mine remember(Mine mine, int at, UUID owner) {
		if (owner == null || CHOICES.get() != at) {
			return mine;
		}
		List<String> pending = mine.mine().stream().filter(c -> "pending".equals(c.status())).map(Cape::id).toList();
		Minecraft.getInstance().execute(() -> {
			if (owner.equals(owner()) && CHOICES.get() == at) {
				NexoCapes.wearLocally(owner, mine.worn());
				waiting = Set.copyOf(pending);
			}
		});
		write(owner, mine.worn(), pending);
		return mine;
	}

	private static UUID owner() {
		var user = Minecraft.getInstance().getUser();
		return user == null ? null : user.getProfileId();
	}

	private static Path shared(UUID owner) {
		return SHARED_DIR.resolve(owner.toString().replace("-", "") + ".json");
	}

	/** Records this account's cape and waiting uploads, only if that changes the file. */
	private static synchronized void write(UUID owner, String worn, List<String> pending) {
		Path file = shared(owner);
		try {
			JsonObject root = new JsonObject();
			root.add("worn", worn == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(worn));
			JsonArray ids = new JsonArray();
			pending.forEach(ids::add);
			root.add("pending", ids);
			if (Files.exists(file)) {
				try {
					if (JsonParser.parseString(Files.readString(file)).equals(root)) {
						return;
					}
				} catch (RuntimeException unreadable) {
					// Replaced below: it is this account's own file.
				}
			}
			Files.createDirectories(SHARED_DIR);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp-mod");
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(root));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			if (owner.equals(sharedOwner)) {
				sharedSeen = Files.getLastModifiedTime(file);
			}
		} catch (IOException | RuntimeException e) {
			NexoMod.LOGGER.debug("[nexomod] Capes: could not update the file shared with the launcher.", e);
		}
	}

	/** Every second: has the launcher changed the shared file? Every few minutes while uploads wait: are they decided? */
	static void tick(Minecraft mc) {
		if (++ticks % WATCH_TICKS != 0 || !NexoConfig.get().nexoCapesEnabled()) {
			return;
		}
		UUID owner = owner();
		if (owner == null) {
			return;
		}
		try {
			Path file = shared(owner);
			FileTime modified = Files.exists(file) ? Files.getLastModifiedTime(file) : null;
			if (!owner.equals(sharedOwner)) {
				// Another account: what was known about the last one says nothing about this one.
				sharedOwner = owner;
				sharedSeen = null;
				waiting = Set.of();
			}
			if (modified != null && !modified.equals(sharedSeen)) {
				sharedSeen = modified;
				readShared(owner, file);
			}
		} catch (IOException | RuntimeException e) {
			NexoMod.LOGGER.debug("[nexomod] Capes: could not read the file shared with the launcher.", e);
		}
		long now = System.currentTimeMillis();
		if (!waiting.isEmpty() && !checking && now >= nextDecisionCheck && NexoEmoteAccount.canSignIn()) {
			nextDecisionCheck = now + DECISION_MILLIS;
			checkDecisions(mc);
		}
	}

	private static void readShared(UUID owner, Path file) throws IOException {
		JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		JsonElement worn = root.get("worn");
		NexoCapes.wearLocally(owner, worn == null || worn.isJsonNull() ? null : worn.getAsString());
		Set<String> pending = new HashSet<>();
		if (root.has("pending") && root.get("pending").isJsonArray()) {
			root.getAsJsonArray("pending").forEach(id -> pending.add(id.getAsString()));
		}
		waiting = Set.copyOf(pending);
	}

	private static void checkDecisions(Minecraft mc) {
		Set<String> watched = waiting;
		checking = true;
		CompletableFuture.supplyAsync(() -> {
			try {
				return me();
			} catch (IOException e) {
				throw new IllegalStateException(e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}, Util.nonCriticalIoPool()).whenComplete((mine, error) -> mc.execute(() -> {
			checking = false;
			if (mine == null) {
				return;
			}
			Map<String, Cape> byId = new HashMap<>();
			mine.mine().forEach(c -> byId.put(c.id(), c));
			for (String id : watched) {
				Cape cape = byId.get(id);
				if (cape == null || "pending".equals(cape.status())) {
					continue;
				}
				boolean approved = cape.approved();
				Component title = Component.literal(approved ? "Cape approved" : "Cape rejected");
				Component line = Component.literal(approved ? "\"" + cape.name() + "\" can be worn now."
						: "\"" + cape.name() + "\"" + (cape.reason() != null ? ": " + cape.reason() : " was not approved."));
				SystemToast.addOrUpdate(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, line);
			}
		}));
	}
}
