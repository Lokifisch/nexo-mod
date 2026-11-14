package dev.nexoclient.nexomod.servers;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Remembers the resource pack each server pushed (host → url + sha1) and copies it into
 * {@code resourcepacks/} on request. Copying again re-downloads, so it doubles as the resync.
 */
public final class NexoServerPacks {
	public record Pack(String url, String sha1) {
	}

	private static final long MAX = 256L << 20;
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("nexomod-server-packs.json");
	private static final Map<String, Pack> PACKS = new LinkedHashMap<>();
	private static final Gson GSON = new Gson();

	static {
		try (Reader in = Files.newBufferedReader(FILE)) {
			Map<String, Pack> read = GSON.fromJson(in, new TypeToken<Map<String, Pack>>() { }.getType());
			if (read != null) {
				PACKS.putAll(read);
			}
		} catch (IOException | RuntimeException ignored) {
			// missing or unreadable: start empty
		}
	}

	private NexoServerPacks() {
	}

	public static synchronized Pack get(String host) {
		return PACKS.get(host);
	}

	public static synchronized void remember(String host, String url, String sha1) {
		Pack pack = new Pack(url, sha1 == null ? "" : sha1);
		if (pack.equals(PACKS.put(host, pack))) {
			return;
		}
		if (PACKS.size() > 200) {
			PACKS.remove(PACKS.keySet().iterator().next());
		}
		Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
		try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(PACKS, out);
			out.close();
			Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
			// kept in memory for this session
		}
	}

	private static boolean privateHost(String host) throws IOException {
		for (java.net.InetAddress a : java.net.InetAddress.getAllByName(host)) {
			if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
					|| a.isMulticastAddress() || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc)) {
				return true;
			}
		}
		return false;
	}

	/** Downloads the host's pack to {@code resourcepacks/nexo-<host>.zip}; completes with that file name. */
	public static CompletableFuture<String> copyToLocal(String host) {
		Pack pack = get(host);
		if (pack == null || !pack.url().startsWith("https://")) {
			return CompletableFuture.failedFuture(new IOException("no https pack recorded"));
		}
		Path dir = Minecraft.getInstance().getResourcePackDirectory();
		String safe = host.replaceAll("[^a-z0-9.-]", "_");
		String name = "nexo-" + safe.substring(0, Math.min(safe.length(), 40)) + "-"
				+ Integer.toHexString(host.hashCode()) + ".zip";
		return CompletableFuture.supplyAsync(() -> {
			try {
				HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
						.connectTimeout(Duration.ofSeconds(15)).build();
				URI uri = URI.create(pack.url());
				HttpResponse<java.io.InputStream> res;
				for (int hops = 0;; hops++) {
					if (hops > 5 || !"https".equals(uri.getScheme()) || uri.getHost() == null || privateHost(uri.getHost())) {
						throw new IOException("blocked address");
					}
					res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).build(),
							HttpResponse.BodyHandlers.ofInputStream());
					if (res.statusCode() / 100 != 3) {
						break;
					}
					res.body().close();
					uri = uri.resolve(res.headers().firstValue("location").orElseThrow(() -> new IOException("bad redirect")));
				}
				if (res.statusCode() != 200) {
					throw new IOException("HTTP " + res.statusCode());
				}
				Files.createDirectories(dir);
				Path part = dir.resolve(name + ".part");
				MessageDigest sha = MessageDigest.getInstance("SHA-1");
				long total = 0;
				try (var in = res.body(); var out = Files.newOutputStream(part)) {
					byte[] buf = new byte[65536];
					for (int n; (n = in.read(buf)) > 0;) {
						total += n;
						if (total > MAX) {
							throw new IOException("over 256 MB");
						}
						sha.update(buf, 0, n);
						out.write(buf, 0, n);
					}
				} catch (IOException e) {
					Files.deleteIfExists(part);
					throw e;
				}
				if (!pack.sha1().isEmpty() && !HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(pack.sha1())) {
					Files.deleteIfExists(part);
					throw new IOException("hash mismatch");
				}
				try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(part.toFile())) {
					if (zip.getEntry("pack.mcmeta") == null) {
						throw new IOException("not a resource pack");
					}
				} catch (IOException e) {
					Files.deleteIfExists(part);
					throw e;
				}
				Files.move(part, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
				return name;
			} catch (IOException | InterruptedException | java.security.NoSuchAlgorithmException e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		});
	}
}
