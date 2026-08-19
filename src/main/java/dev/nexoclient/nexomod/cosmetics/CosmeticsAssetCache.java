package dev.nexoclient.nexomod.cosmetics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.NexoMod;

/**
 * Cosmetic textures, fetched once per id and kept as a GPU texture for the
 * rest of the session.
 *
 * <p>{@link #texture(int)} is called from the render thread (the
 * {@code FeatureRenderer} asks it once per visible cosmetic per frame), so it
 * must never block on network I/O itself — a miss kicks the fetch onto this
 * cache's own small worker pool and returns null for this frame; the texture
 * simply is not drawn until a later frame sees the id present in
 * {@link #loaded}.
 *
 * <p>Downloaded lazily — the first time something actually needs to render
 * cosmetic {@code id} — rather than up front for the whole catalog, since
 * most sessions only ever need textures for whichever handful of players are
 * both nearby and wearing something. Also mirrored to disk under the config
 * dir, keyed by id, so a second launch does not re-download art that has not
 * changed; catalog entries are immutable once approved (see
 * {@code CosmeticsService}'s review flow), so there is no staleness question
 * an ETag would need to answer.
 */
final class CosmeticsAssetCache {
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("nexomod-cosmetics-assets");

	private final CosmeticsServiceClient service;
	private final ExecutorService fetchers = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "nexo-cosmetics-asset");
		thread.setDaemon(true);
		return thread;
	});

	/** cosmeticId -> registered texture identifier, once loaded. */
	private final Map<Integer, Identifier> loaded = new ConcurrentHashMap<>();
	/** cosmeticId -> in-flight fetch, so a texture requested by several visible players is fetched once. */
	private final Map<Integer, Boolean> fetching = new ConcurrentHashMap<>();

	CosmeticsAssetCache(CosmeticsServiceClient service) {
		this.service = service;
	}

	/** The registered texture for `cosmeticId`, or null if it is not loaded yet (a fetch may now be in flight). */
	Identifier texture(int cosmeticId) {
		Identifier existing = loaded.get(cosmeticId);
		if (existing != null) {
			return existing;
		}
		if (fetching.putIfAbsent(cosmeticId, Boolean.TRUE) == null) {
			fetchers.execute(() -> fetch(cosmeticId));
		}
		return null;
	}

	void shutdown() {
		fetchers.shutdownNow();
	}

	private void fetch(int cosmeticId) {
		byte[] data = readFromDisk(cosmeticId);
		if (data == null) {
			data = download(cosmeticId);
			if (data != null) {
				writeToDisk(cosmeticId, data);
			}
		}
		if (data == null) {
			fetching.remove(cosmeticId);
			return;
		}
		register(cosmeticId, data);
	}

	private byte[] download(int cosmeticId) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(service.url("/asset/" + cosmeticId)))
					.timeout(CosmeticsServiceClient.TIMEOUT)
					.header("User-Agent", CosmeticsServiceClient.USER_AGENT)
					.GET()
					.build();
			HttpResponse<byte[]> response = service.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) {
				NexoMod.LOGGER.debug("[nexomod] Cosmetics asset {} fetch returned {}.", cosmeticId, response.statusCode());
				return null;
			}
			return response.body();
		} catch (IOException e) {
			NexoMod.LOGGER.debug("[nexomod] Cosmetics asset {} fetch failed.", cosmeticId, e);
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/**
	 * Decoding and GPU upload must happen on the render thread; scheduled back
	 * onto it here. The {@link NativeImage} is deliberately not closed after
	 * this method — {@link DynamicTexture} takes ownership of it for the life
	 * of the texture and closes it itself when the texture is disposed.
	 */
	private void register(int cosmeticId, byte[] data) {
		Minecraft.getInstance().execute(() -> {
			try {
				NativeImage image = NativeImage.read(data);
				Identifier id = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "cosmetic_" + cosmeticId);
				Minecraft.getInstance().getTextureManager()
						.register(id, new DynamicTexture(() -> "nexo cosmetic " + cosmeticId, image));
				loaded.put(cosmeticId, id);
			} catch (IOException e) {
				NexoMod.LOGGER.warn("[nexomod] Cosmetics asset {} was not a decodable image.", cosmeticId, e);
			} finally {
				fetching.remove(cosmeticId);
			}
		});
	}

	private byte[] readFromDisk(int cosmeticId) {
		Path path = DIR.resolve(cosmeticId + ".png");
		if (!Files.exists(path)) {
			return null;
		}
		try {
			return Files.readAllBytes(path);
		} catch (IOException e) {
			return null;
		}
	}

	private void writeToDisk(int cosmeticId, byte[] data) {
		try {
			Files.createDirectories(DIR);
			Files.write(DIR.resolve(cosmeticId + ".png"), data);
		} catch (IOException e) {
			NexoMod.LOGGER.warn("[nexomod] Could not cache cosmetics asset {}.", cosmeticId, e);
		}
	}
}
