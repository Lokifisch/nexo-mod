package dev.nexoclient.nexomod.wardrobe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.mixin.SkinTextureDownloaderInvoker;

/**
 * The player's skins and outfits: an index plus one PNG per skin under
 * {@code config/nexomod-wardrobe/}. Nothing here leaves this machine until the
 * Wear button uploads a skin.
 */
public final class NexoSkinLibrary {
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("nexomod-wardrobe");
	private static final Path INDEX = DIR.resolve("index.json");
	private static final Gson GSON = new Gson();
	public static final int MAX_FILE = 256 * 1024;
	private static final int MAX_CAPE_SIDE = 512;

	public static final class Skin {
		public String id;
		public String name;
		public boolean slim;
		public boolean favorite;
	}

	public static final class Outfit {
		public String name;
		public String skinId;
		public String capeId;
	}

	private static final class Index {
		List<Skin> skins = new ArrayList<>();
		List<Outfit> outfits = new ArrayList<>();
		/** The account's skin has been offered once; deleting it later must not bring it back. */
		boolean imported;
	}

	private static Index index;
	private static boolean loadFailed;
	private static final Map<String, Identifier> TEXTURES = new HashMap<>();

	private NexoSkinLibrary() {
	}

	private static Index index() {
		if (index == null) {
			index = new Index();
			try {
				if (Files.exists(INDEX)) {
					Index read = GSON.fromJson(Files.readString(INDEX, StandardCharsets.UTF_8), Index.class);
					if (read != null) {
						index.imported = read.imported;
						// The id names a file: an edited index must not point outside the folder.
						if (read.skins != null) {
							read.skins.stream().filter(s -> s != null && s.id != null && s.id.matches("[0-9a-f]{32}"))
									.forEach(s -> {
										s.name = cleanName(s.name);
										index.skins.add(s);
									});
						}
						if (read.outfits != null) {
							read.outfits.stream().filter(o -> o != null && find(o.skinId) != null).forEach(o -> {
								o.name = cleanName(o.name);
								o.capeId = o.capeId == null ? "" : o.capeId;
								index.outfits.add(o);
							});
						}
					}
				}
			} catch (IOException | RuntimeException e) {
				NexoMod.LOGGER.warn("[nexomod] Wardrobe: could not read the skin index.", e);
				loadFailed = true;
				backUpBrokenIndex();
			}
			relistOrphans();
		}
		return index;
	}

	/** The next save replaces the index, so a file that would not parse is kept for the player. */
	private static void backUpBrokenIndex() {
		try {
			Files.copy(INDEX, DIR.resolve("index.json.broken-" + System.currentTimeMillis()));
		} catch (IOException e) {
			NexoMod.LOGGER.warn("[nexomod] Wardrobe: could not back up the broken index.", e);
		}
	}

	/** Skins whose index entry is gone: their files are still the player's. */
	private static void relistOrphans() {
		if (!Files.isDirectory(DIR)) {
			return;
		}
		try (var files = Files.list(DIR)) {
			files.map(file -> file.getFileName().toString())
					.filter(name -> name.matches("[0-9a-f]{32}\\.png"))
					.sorted()
					.forEach(name -> {
						String id = name.substring(0, 32);
						if (find(id) == null) {
							Skin skin = new Skin();
							skin.id = id;
							skin.name = "Recovered";
							index.skins.add(skin);
						}
					});
		} catch (IOException e) {
			NexoMod.LOGGER.warn("[nexomod] Wardrobe: could not list the skin folder.", e);
		}
	}

	/** True once, ever, when the empty library should get the account's own skin. */
	public static boolean claimAutoImport() {
		Index current = index();
		if (loadFailed || current.imported || !current.skins.isEmpty()) {
			return false;
		}
		current.imported = true;
		save();
		return true;
	}

	public static List<Skin> skins() {
		return index().skins;
	}

	public static List<Outfit> outfits() {
		return index().outfits;
	}

	public static Skin find(String id) {
		for (Skin skin : index().skins) {
			if (skin.id.equals(id)) {
				return skin;
			}
		}
		return null;
	}

	public static String cleanName(String name) {
		String clean = name == null ? "" : name.replaceAll("\\p{Cntrl}|\\p{Cf}|§.?", "").strip();
		clean = clean.length() > 32 ? clean.substring(0, 32) : clean;
		return clean.isEmpty() ? "Skin" : clean;
	}

	/** Throws unless this is a PNG Minecraft can use as a skin. The size comes from the header, before any decoding. */
	public static void checkSkin(byte[] png) {
		if (!NexoPngCheck.isSkin(png, MAX_FILE)) {
			throw new NexoSkinApi.ApiException("nexomod.wardrobe.error.badImage");
		}
	}

	public static byte[] readFile(Path file) {
		try (var in = Files.newInputStream(file)) {
			byte[] bytes = in.readNBytes(MAX_FILE + 1);
			if (bytes.length > MAX_FILE) {
				throw new NexoSkinApi.ApiException("nexomod.wardrobe.error.badImage");
			}
			return bytes;
		} catch (IOException e) {
			throw new NexoSkinApi.ApiException("nexomod.wardrobe.error.badImage");
		}
	}

	public static Skin add(byte[] png, String name, boolean slim) throws IOException {
		checkSkin(png);
		Skin skin = new Skin();
		skin.id = UUID.randomUUID().toString().replace("-", "");
		skin.name = cleanName(name);
		skin.slim = slim;
		Files.createDirectories(DIR);
		Files.write(file(skin), png);
		index().skins.add(skin);
		save();
		return skin;
	}

	public static void remove(Skin skin) {
		index().skins.remove(skin);
		index().outfits.removeIf(outfit -> outfit.skinId.equals(skin.id));
		try {
			Files.deleteIfExists(file(skin));
		} catch (IOException e) {
			NexoMod.LOGGER.warn("[nexomod] Wardrobe: could not delete a skin file.", e);
		}
		Identifier texture = TEXTURES.remove(skin.id);
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(texture);
		}
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(DIR);
			Path temp = DIR.resolve("index.json.tmp");
			Files.writeString(temp, GSON.toJson(index()), StandardCharsets.UTF_8);
			Files.move(temp, INDEX, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NexoMod.LOGGER.warn("[nexomod] Wardrobe: could not save the skin index.", e);
		}
	}

	public static byte[] bytes(Skin skin) throws IOException {
		return readFile(file(skin));
	}

	private static Path file(Skin skin) {
		return DIR.resolve(skin.id + ".png");
	}

	/** This skin's texture, loaded on first use. Render thread only. Null if its file is unusable. */
	public static Identifier texture(Skin skin) {
		if (TEXTURES.containsKey(skin.id)) {
			return TEXTURES.get(skin.id);
		}
		Identifier id = null;
		try {
			id = registerSkin(Identifier.fromNamespaceAndPath("nexomod", "wardrobe/" + skin.id), bytes(skin));
		} catch (IOException | RuntimeException e) {
			NexoMod.LOGGER.warn("[nexomod] Wardrobe: could not load a skin.", e);
		}
		TEXTURES.put(skin.id, id);
		return id;
	}

	/** Registers skin PNG bytes as a texture, old 64x32 layouts converted. Render thread only. */
	public static Identifier registerSkin(Identifier id, byte[] png) throws IOException {
		checkSkin(png);
		NativeImage image = SkinTextureDownloaderInvoker.nexomod$processLegacySkin(NativeImage.read(png), "wardrobe");
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
		return id;
	}

	/** Registers a cape PNG as a texture. Render thread only. */
	public static Identifier registerCape(Identifier id, byte[] png) throws IOException {
		if (!NexoPngCheck.fits(png, MAX_FILE, MAX_CAPE_SIDE)) {
			throw new NexoSkinApi.ApiException("nexomod.wardrobe.error.badImage");
		}
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, NativeImage.read(png)));
		return id;
	}

}
