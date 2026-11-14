package dev.nexoclient.nexomod.whois;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoWhoisScreen;

/**
 * {@code /whois <name> [chat|gui]}: the player's name and skin history, from crafty.gg.
 *
 * <p>With no mode the setting decides (menu by default). The lookup only runs when the
 * player types the command. It sends the name they typed (their own, with no argument) and,
 * as any request does, their IP address.
 */
public final class NexoWhois {
	private static final String API = "https://api.crafty.gg/api/v2/players/";
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final int MAX_BODY = 2_000_000;
	private static final int MAX_ENTRIES = 50;
	private static final Pattern UUID = Pattern.compile("[0-9a-fA-F-]{32,36}");
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	public record NameEntry(String name, String changedAt) {
	}

	public record Skin(String hash, byte[] png, boolean slim, String changedAt) {
	}

	public record Cape(String name, boolean current) {
	}

	public record Profile(String uuid, String name, String created, int nameChanges, String rarity,
			List<NameEntry> names, List<Skin> skins, List<Cape> capes) {
	}

	/** A failure with a message fit to show the player. */
	public static final class LookupException extends RuntimeException {
		LookupException(String message) {
			super(message);
		}
	}

	private NexoWhois() {
	}

	public static void register() {
		// A separate command, not "/whois default": a player can be called anything, including "default".
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(ClientCommands.literal("whoisdefault")
				.executes(ctx -> setDefault(ctx.getSource(), null))
				.then(ClientCommands.literal("chat").executes(ctx -> setDefault(ctx.getSource(), true)))
				.then(ClientCommands.literal("gui").executes(ctx -> setDefault(ctx.getSource(), false)))));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(ClientCommands.literal("whois")
				.executes(ctx -> run(ctx.getSource(), null, null))
				.then(ClientCommands.argument("name", StringArgumentType.word())
						.suggests((ctx, builder) -> {
							String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
							for (String online : ctx.getSource().getOnlinePlayerNames()) {
								if (online.toLowerCase(Locale.ROOT).startsWith(typed)) {
									builder.suggest(online);
								}
							}
							return builder.buildFuture();
						})
						.executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "name"), null))
						.then(ClientCommands.literal("chat").executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true)))
						.then(ClientCommands.literal("gui").executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))))));
	}

	private static int setDefault(FabricClientCommandSource source, Boolean chat) {
		if (chat != null) {
			NexoConfig.get().setWhoisChatDefault(chat);
		}
		source.sendFeedback(Component.literal("/whois opens in " + (NexoConfig.get().whoisChatDefault() ? "chat" : "the menu") + " by default.")
				.withStyle(ChatFormatting.GRAY));
		return 1;
	}

	/** {@code chat} null means the setting decides. */
	private static int run(FabricClientCommandSource source, String name, Boolean chat) {
		Minecraft mc = Minecraft.getInstance();
		String target = name != null ? name : mc.getUser().getName();
		boolean toChat = chat != null ? chat : NexoConfig.get().whoisChatDefault();
		if (!NAME.matcher(target).matches()) {
			source.sendError(Component.literal("That is not a valid Minecraft name."));
			return 0;
		}
		source.sendFeedback(Component.literal("Looking up " + target + "…").withStyle(ChatFormatting.GRAY));
		fetch(target).whenComplete((profile, error) -> mc.execute(() -> {
			if (error != null) {
				Throwable cause = error.getCause() != null ? error.getCause() : error;
				String message = cause instanceof LookupException ? cause.getMessage() : "Lookup failed.";
				source.sendError(Component.literal(message));
			} else if (toChat) {
				printToChat(source, profile);
			} else {
				mc.gui.setScreen(new NexoWhoisScreen(mc.gui.screen(), profile));
			}
		}));
		return 1;
	}

	public static CompletableFuture<Profile> fetch(String name) {
		if (!NAME.matcher(name).matches()) {
			return CompletableFuture.failedFuture(new LookupException("That is not a valid Minecraft name."));
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(API + name))
				.timeout(Duration.ofSeconds(12))
				.header("Accept", "application/json")
				.header("User-Agent", "nexomod-whois/1.0")
				.GET().build();
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenApply(response -> {
			int status = response.statusCode();
			if (status == 404) {
				throw new LookupException("No player called " + name + ".");
			}
			if (status == 403 || status == 429) {
				throw new LookupException("crafty.gg refused the request (" + status + "). Try again later.");
			}
			if (status != 200) {
				throw new LookupException("crafty.gg answered with an error (" + status + ").");
			}
			try (java.io.InputStream in = response.body()) {
				byte[] body = in.readNBytes(MAX_BODY + 1);
				if (body.length > MAX_BODY) {
					throw new LookupException("crafty.gg sent a reply that is too large.");
				}
				return parse(new String(body, java.nio.charset.StandardCharsets.UTF_8));
			} catch (LookupException e) {
				throw e;
			} catch (java.io.IOException | RuntimeException e) {
				throw new LookupException("crafty.gg sent a reply this version does not understand.");
			}
		});
	}

	static Profile parse(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		if (!root.has("success") || !root.get("success").getAsBoolean()) {
			throw new LookupException("No such player.");
		}
		JsonObject data = root.getAsJsonObject("data");

		List<NameEntry> names = new ArrayList<>();
		for (JsonElement e : array(data, "usernames")) {
			JsonObject o = e.getAsJsonObject();
			String n = clean(str(o, "username"));
			if (n != null && !bool(o, "hidden") && names.size() < MAX_ENTRIES) {
				names.add(new NameEntry(n, str(o, "changed_at")));
			}
		}
		// Newest first; the original name has no change date, so it goes last.
		names.sort(Comparator.comparing((NameEntry n) -> instant(n.changedAt())).reversed());

		List<Skin> skins = new ArrayList<>();
		for (JsonElement e : array(data, "skins")) {
			JsonObject o = e.getAsJsonObject();
			String texture = str(o, "texture");
			if (texture == null || bool(o, "hidden") || skins.size() >= MAX_ENTRIES) {
				continue;
			}
			try {
				skins.add(new Skin(str(o, "hash"), Base64.getDecoder().decode(texture), bool(o, "slim"), str(o, "changed_at")));
			} catch (IllegalArgumentException bad) {
				// One undecodable skin is skipped, not the whole lookup.
			}
		}
		skins.sort(Comparator.comparing((Skin k) -> instant(k.changedAt())).reversed());

		List<Cape> capes = new ArrayList<>();
		for (JsonElement e : array(data, "capes")) {
			JsonObject o = e.getAsJsonObject();
			String n = clean(str(o, "name"));
			if (n != null && capes.size() < MAX_ENTRIES) {
				capes.add(new Cape(n, bool(o, "current")));
			}
		}

		String rarity = null;
		if (data.has("rarity") && data.get("rarity").isJsonObject()) {
			rarity = clean(str(data.getAsJsonObject("rarity"), "tier"));
		}
		String uuid = str(data, "uuid");
		String username = str(data, "username");
		if (uuid == null || !UUID.matcher(uuid).matches() || username == null || !NAME.matcher(username).matches()) {
			throw new LookupException("crafty.gg sent a reply this version does not understand.");
		}
		return new Profile(uuid, username, str(data, "created_at"),
				data.has("name_changes") && !data.get("name_changes").isJsonNull() ? data.get("name_changes").getAsInt() : 0,
				rarity, names, skins, capes);
	}

	/** Server text shown to the player: no formatting codes or control characters, bounded length. */
	private static String clean(String text) {
		if (text == null) {
			return null;
		}
		String out = text.replaceAll("[\\p{Cntrl}\u00a7]", "");
		return out.isEmpty() ? null : out.substring(0, Math.min(out.length(), 64));
	}

	/** Instants rather than strings, since offsets differ. The original name has no date and sorts oldest. */
	private static java.time.Instant instant(String iso) {
		try {
			return iso == null ? java.time.Instant.MIN : OffsetDateTime.parse(iso).toInstant();
		} catch (RuntimeException e) {
			return java.time.Instant.MIN;
		}
	}

	private static JsonArray array(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static boolean bool(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
	}

	/** {@code 2026-06-15T05:26:58.768+02:00} becomes {@code 2026-06-15}; anything unreadable is shown as sent. */
	public static String date(String iso) {
		if (iso == null) {
			return "Original";
		}
		try {
			return OffsetDateTime.parse(iso).toLocalDate().toString();
		} catch (RuntimeException e) {
			return iso;
		}
	}

	/** {@code pink_sheep} becomes {@code Pink Sheep}. */
	public static String tier(String slug) {
		if (slug == null || slug.isEmpty()) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		for (String word : slug.split("_")) {
			if (!word.isEmpty()) {
				out.append(out.length() > 0 ? " " : "").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return out.toString();
	}

	private static void printToChat(FabricClientCommandSource source, Profile p) {
		source.sendFeedback(Component.literal("— " + p.name() + " —").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
		MutableComponent uuid = Component.literal(p.uuid()).withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.CopyToClipboard(p.uuid()))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy"))));
		source.sendFeedback(Component.literal("UUID: ").withStyle(ChatFormatting.GRAY).append(uuid));
		source.sendFeedback(line("First seen", date(p.created())));
		String tier = tier(p.rarity());
		if (tier != null) {
			source.sendFeedback(line("Rarity", tier));
		}
		source.sendFeedback(Component.literal("Names (" + p.names().size() + "):").withStyle(ChatFormatting.GRAY));
		for (NameEntry n : p.names()) {
			source.sendFeedback(Component.literal("  " + n.name() + " ").withStyle(ChatFormatting.WHITE)
					.append(Component.literal(date(n.changedAt())).withStyle(ChatFormatting.DARK_GRAY)));
		}
		source.sendFeedback(line("Skins", p.skins().size() + (p.skins().size() == 1 ? " skin" : " skins") + " on record"));
		if (!p.capes().isEmpty()) {
			StringBuilder capes = new StringBuilder();
			for (Cape c : p.capes()) {
				capes.append(capes.length() > 0 ? ", " : "").append(c.name()).append(c.current() ? " (worn)" : "");
			}
			source.sendFeedback(line("Capes", capes.toString()));
		}
	}

	private static MutableComponent line(String label, String value) {
		return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(ChatFormatting.WHITE));
	}
}
