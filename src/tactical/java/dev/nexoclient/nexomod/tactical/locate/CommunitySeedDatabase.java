package dev.nexoclient.nexomod.tactical.locate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * The public SeedcrackerX seed spreadsheet, as a source for {@link SeedIndex}.
 *
 * <p>The direction of the lookup is the design, same as the badge roster: the
 * <em>whole</em> sheet is downloaded (at most weekly, cached on disk) and the
 * server is matched against it locally. Asking a remote service "do you have a
 * seed for this address?" would tell it which servers the player joins; this
 * way the only request that ever leaves is the same one for everybody.
 *
 * <p>A hit is saved only when the index has nothing for the server yet — a seed
 * the player typed or SeedcrackerX found is never replaced by a spreadsheet row.
 * ponytail: the sheet is a third party's, so a deleted or restructured sheet
 * silently ends this feature (the cache keeps serving until then); mirror it
 * somewhere we control if that becomes a real problem.
 */
final class CommunitySeedDatabase {
	private static final URI SHEET = URI.create(
			"https://docs.google.com/spreadsheets/d/1tuQiE-0leW88em9OHbZnH-RFNhVqgoHhIt9WQbeqqWw/export?format=csv");
	private static final Duration MAX_AGE = Duration.ofDays(7);
	private static final Path CACHE = FabricLoader.getInstance().getConfigDir().resolve("nexomod-seed-database.csv");
	private static final String HEADER = "Server Ip";
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

	private record Row(String host, String ip, int port, long seed, String version, long date) {
	}

	private record Match(Row newest, int distinctSeeds) {
	}

	private CommunitySeedDatabase() {
	}

	/** Call on joining a multiplayer server. Does its work on {@code worker}; never blocks the caller. */
	static void onJoin(Minecraft client, SocketAddress remote, Executor worker) {
		if (!NexoConfig.get().seedDatabaseEnabled()) {
			return;
		}
		Optional<SeedIndex.Place> place = SeedIndex.currentPlace();
		ServerData server = client.getCurrentServer();
		if (place.isEmpty() || place.get().world() || server == null || SeedIndex.get().find(place.get().key()).isPresent()) {
			return;
		}
		String typed = server.ip;
		worker.execute(() -> {
			try {
				Optional<Match> match = lookup(typed, remote);
				if (match.isEmpty()) {
					return;
				}
				Row row = match.get().newest();
				WorldgenVersion version = WorldgenVersion.forGameVersion(row.version()).orElse(null);
				SeedIndex.Place p = place.get();
				// Re-checked here: the player may have typed one in while the sheet downloaded.
				synchronized (SeedIndex.get()) {
					if (SeedIndex.get().find(p.key()).isPresent()) {
						return;
					}
					SeedIndex.get().put(p.key(), p.label(), p.address(), false, row.seed(), SeedIndex.Source.COMMUNITY, version);
				}
				String generator = WorldgenVersion.of(version == null ? null : version.label()).label();
				int many = match.get().distinctSeeds();
				client.execute(() -> {
					if (client.player != null) {
						client.gui.getChat().addClientSystemMessage(many > 1
								? Component.translatable("nexomod.locate.fromCommunityMany", p.label(), many, row.seed(), generator)
								: Component.translatable("nexomod.locate.fromCommunity", p.label(), row.seed(), generator));
					}
				});
			} catch (Exception e) {
				NexoMod.LOGGER.warn("[nexomod] Community seed lookup failed.", e);
			}
		});
	}

	private static Optional<Match> lookup(String typedAddress, SocketAddress remote) throws IOException, InterruptedException {
		String csv = loadCsv();
		if (csv == null) {
			return Optional.empty();
		}
		String host = typedAddress.toLowerCase(Locale.ROOT).trim();
		int typedPort = -1;
		int colon = host.lastIndexOf(':');
		if (colon > 0 && host.indexOf(':') == colon) {
			typedPort = parsePort(host.substring(colon + 1));
			host = host.substring(0, colon);
		}
		host = stripDot(host);
		String remoteIp = remote instanceof InetSocketAddress inet && inet.getAddress() != null
				? inet.getAddress().getHostAddress() : null;

		List<Row> hits = new ArrayList<>();
		for (Row row : parse(csv)) {
			boolean byName = row.host().equals(host) || row.ip().equals(host);
			boolean byIp = remoteIp != null && row.ip().equals(remoteIp);
			// An explicit non-default port in the address picks between servers on one host.
			boolean portOk = typedPort <= 0 || typedPort == 25565 || typedPort == row.port();
			if ((byName || byIp) && portOk) {
				hits.add(row);
			}
		}
		if (hits.isEmpty()) {
			return Optional.empty();
		}
		Row newest = hits.get(0);
		for (Row row : hits) {
			// Later row wins ties: the sheet is appended to, so file order is age order.
			if (row.date() >= newest.date()) {
				newest = row;
			}
		}
		return Optional.of(new Match(newest, (int) hits.stream().map(Row::seed).distinct().count()));
	}

	/** The sheet, from the weekly-refreshed cache; the stale cache if the download fails; null if neither. */
	private static String loadCsv() throws IOException, InterruptedException {
		boolean fresh = Files.exists(CACHE)
				&& Duration.between(Files.getLastModifiedTime(CACHE).toInstant(), Instant.now()).compareTo(MAX_AGE) < 0;
		if (!fresh) {
			try {
				download();
			} catch (IOException | RuntimeException e) {
				NexoMod.LOGGER.warn("[nexomod] Could not refresh the community seed list: {}", e.toString());
			}
		}
		return Files.exists(CACHE) ? Files.readString(CACHE, StandardCharsets.UTF_8) : null;
	}

	private static void download() throws IOException, InterruptedException {
		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				// Google answers the export URL with a redirect to a content host.
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(SHEET)
				.timeout(Duration.ofSeconds(30)).header("User-Agent", "NexoMod").GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		// A login or error page is HTML and would otherwise replace a good cache.
		if (response.statusCode() != 200 || !response.body().startsWith(HEADER)) {
			throw new IOException("unexpected response: HTTP " + response.statusCode());
		}
		Files.createDirectories(CACHE.getParent());
		Path temp = CACHE.resolveSibling(CACHE.getFileName() + ".tmp");
		Files.writeString(temp, response.body(), StandardCharsets.UTF_8);
		Files.move(temp, CACHE, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Columns: ip ("host/ip:port"), dimension, seed (trailing "L"), version, user, date. Overworld rows only. */
	private static List<Row> parse(String csv) {
		List<Row> rows = new ArrayList<>();
		boolean first = true;
		for (List<String> fields : csvRecords(csv)) {
			if (first) {
				first = false;
				continue;
			}
			try {
				if (fields.size() < 6 || !(fields.get(1).equals("overworld") || fields.get(1).equals("world"))) {
					continue;
				}
				String address = fields.get(0);
				int slash = address.lastIndexOf('/');
				String ipPort = address.substring(slash + 1);
				int colon = ipPort.lastIndexOf(':');
				String seedText = fields.get(2).trim();
				if (seedText.endsWith("L")) {
					seedText = seedText.substring(0, seedText.length() - 1);
				}
				rows.add(new Row(
						stripDot((slash > 0 ? address.substring(0, slash) : "").toLowerCase(Locale.ROOT)),
						colon > 0 ? ipPort.substring(0, colon) : ipPort,
						colon > 0 ? parsePort(ipPort.substring(colon + 1)) : -1,
						Long.parseLong(seedText), fields.get(3), parseDate(fields.get(5))));
			} catch (RuntimeException e) {
				// One hand-typed bad row is not worth the other eight thousand.
			}
		}
		return rows;
	}

	private static long parseDate(String text) {
		String trimmed = text.trim();
		try {
			return LocalDateTime.parse(trimmed, DATE_TIME).toEpochSecond(ZoneOffset.UTC);
		} catch (RuntimeException e) {
			try {
				return LocalDate.parse(trimmed, DATE).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
			} catch (RuntimeException e2) {
				return 0;
			}
		}
	}

	private static int parsePort(String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String stripDot(String host) {
		return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
	}

	/** RFC 4180 records: quoted fields may hold commas, doubled quotes and newlines. */
	private static List<List<String>> csvRecords(String csv) {
		List<List<String>> records = new ArrayList<>();
		List<String> fields = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < csv.length(); i++) {
			char c = csv.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
					field.append('"');
					i++;
				} else if (c == '"') {
					quoted = false;
				} else {
					field.append(c);
				}
			} else if (c == '"') {
				quoted = true;
			} else if (c == ',') {
				fields.add(field.toString());
				field.setLength(0);
			} else if (c == '\n' || c == '\r') {
				if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
					i++;
				}
				fields.add(field.toString());
				field.setLength(0);
				records.add(fields);
				fields = new ArrayList<>();
			} else {
				field.append(c);
			}
		}
		if (field.length() > 0 || !fields.isEmpty()) {
			fields.add(field.toString());
			records.add(fields);
		}
		return records;
	}
}
