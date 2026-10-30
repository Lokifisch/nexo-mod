package dev.nexoclient.nexomod.screen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.title.NexoTitleExtras;
import dev.nexoclient.nexomod.wardrobe.NexoSkinApi;
import dev.nexoclient.nexomod.wardrobe.NexoSkinLibrary;
import dev.nexoclient.nexomod.wardrobe.NexoSkinLibrary.Outfit;
import dev.nexoclient.nexomod.wardrobe.NexoSkinLibrary.Skin;

/**
 * The wardrobe: a library of skins, saved outfits and the account's capes, with
 * a 3D preview. Skins live on this machine until Wear uploads one to Mojang.
 * Capes are the account's real ones, the only kind other players can see.
 */
public class NexoWardrobeScreen extends Screen {
	private static final int RAIL = 104;
	private static final int HEADER = 30;
	private static final int CARD_MIN = 84;
	private static final int CARD_HEIGHT = 112;
	private static final int GAP = 6;
	private static final int HEADING = 16;

	private enum Tab { SKINS, OUTFITS, CAPES }

	private record Item(Component heading, String name, String sub, Supplier<Identifier> skin, boolean slim, Identifier cape,
			boolean back, boolean selected, boolean favorite, Runnable click) {
	}

	private record Placed(Item item, int y) {
	}

	/** Downloaded cape textures by cape id, kept for the session. */
	private static final Map<String, Identifier> CAPE_TEXTURES = new HashMap<>();
	/*
	 * Item textures, not ItemStacks: until a world has been loaded this session item
	 * components are unbound and making a stack throws, and this screen and its
	 * title-screen button both show before that.
	 */
	private static final Identifier CHESTPLATE = Identifier.withDefaultNamespace("textures/item/leather_chestplate.png");
	private static final Identifier ARMOR_STAND = Identifier.withDefaultNamespace("textures/item/armor_stand.png");
	private static final Identifier ELYTRA = Identifier.withDefaultNamespace("textures/item/elytra.png");
	private static final int LEATHER_TINT = 0xFFA0653F;
	private static final Identifier WORN = Identifier.fromNamespaceAndPath("nexomod", "wardrobe/worn");
	private static Model.Simple wide;
	private static Model.Simple slimModel;
	private static Model.Simple capeModel;

	private final Screen parent;
	private Tab tab = Tab.SKINS;
	private Skin selected;
	private Outfit selectedOutfit;
	private String search = "";
	private int scroll;
	private int maxScroll;
	private boolean menuOpen;
	private final List<Chip> menuChips = new ArrayList<>();
	private boolean deleteArmed;
	private float rotX = 5F;
	private float rotY = -25F;
	private boolean draggingSlider;
	private boolean draggingModel;
	private Component status;
	private boolean statusError;
	private boolean busy;

	private List<NexoSkinApi.Cape> capes;
	private String activeCape = "";
	/** Whether the account's capes were really read: until then an outfit must not claim "no cape". */
	private boolean capesKnown;
	private String previewCape = "";
	private Component capesMessage;

	private int px0, py0, px1, py1;
	/** The preview column: narrower in a small window, so the grid keeps room. */
	private int previewWidth = 214;
	private boolean searchShown;
	private int gridX0, gridY0, gridX1, gridY1;
	private int modelX0, modelY0, modelX1, modelY1;
	private int sliderX0, sliderX1, sliderY;
	private int menuX0, menuY0, menuX1, menuY1;

	public NexoWardrobeScreen(Screen parent) {
		super(Component.translatable("nexomod.wardrobe.title"));
		this.parent = parent;
		List<Skin> skins = NexoSkinLibrary.skins();
		selected = skins.isEmpty() ? null : skins.get(0);
		String token = Minecraft.getInstance().getUser().getAccessToken();
		if (NexoSkinApi.hasToken(token)) {
			loadCapes(token);
			if (NexoSkinLibrary.claimAutoImport()) {
				importCurrent(false);
			}
		} else {
			capes = List.of();
			capesMessage = Component.translatable("nexomod.wardrobe.capes.signIn");
		}
	}

	@Override
	protected void init() {
		menuChips.clear();
		px0 = Math.max(8, (width - 900) / 2);
		px1 = width - px0;
		py0 = Math.max(8, (height - 500) / 2);
		py1 = height - py0;
		previewWidth = Mth.clamp((px1 - px0) * 36 / 100, 150, 214);
		int railX1 = px0 + RAIL;
		int previewX0 = px1 - previewWidth;
		gridX0 = railX1 + 8;
		gridX1 = previewX0 - 8;
		gridY0 = py0 + HEADER + 6;
		gridY1 = py1 - 6;

		addRenderableWidget(NexoButton.builder(Component.literal("<"), this::onClose).pos(px0 + 6, py0 + 6).size(18, 18).build());
		int y = py0 + HEADER + 4;
		y = addTab(Tab.SKINS, "nexomod.wardrobe.tab.skins", CHESTPLATE, LEATHER_TINT, y);
		y = addTab(Tab.OUTFITS, "nexomod.wardrobe.tab.outfits", ARMOR_STAND, 0xFFFFFFFF, y);
		addTab(Tab.CAPES, "nexomod.wardrobe.tab.capes", ELYTRA, 0xFFFFFFFF, y);

		// Header: search, and on Skins the add button. Outfits get theirs from the preview.
		int headerRight = gridX1;
		if (tab == Tab.SKINS) {
			addRenderableWidget(new Primary(headerRight - 72, py0 + 6, 72, 18, Component.translatable("nexomod.wardrobe.add"), () -> setMenu(!menuOpen)));
			headerRight -= 78;
		}
		// Only when it fits beside the heading.
		searchShown = headerRight - gridX0 >= 150;
		if (searchShown) {
			NexoTextField box = new NexoTextField(font, headerRight - 88, py0 + 11, 84, 10);
			box.setBordered(false);
			box.setMaxLength(32);
			box.setValue(search);
			box.setHint(Component.translatable("nexomod.wardrobe.search"));
			box.setResponder(text -> {
				search = text;
				scroll = 0;
			});
			addRenderableWidget(box);
		} else {
			search = "";
		}

		// Preview.
		int inX0 = previewX0 + 8;
		int inX1 = px1 - 8;
		addRenderableWidget(new Chip(previewX0 + 6, py0 + 6, 18, 18, Component.literal("<"), () -> false, () -> step(-1)));
		addRenderableWidget(new Chip(px1 - 24, py0 + 6, 18, 18, Component.literal(">"), () -> false, () -> step(1)));
		int bottom = py1 - 8;
		Primary wear = new Primary(inX0 + (inX1 - inX0) / 2 + 2, bottom - 20, (inX1 - inX0) / 2 - 2, 20, Component.translatable("nexomod.wardrobe.wear"), this::wear);
		addRenderableWidget(wear);
		addRenderableWidget(NexoButton.builder(Component.translatable("nexomod.wardrobe.saveOutfit"), this::saveOutfit)
				.pos(inX0, bottom - 20).size((inX1 - inX0) / 2 - 2, 20).build());
		int row = bottom - 20 - 26 - 20;
		int w = (inX1 - inX0 - 12) / 4;
		addRenderableWidget(new Chip(inX0, row, w, 18, Component.translatable("nexomod.wardrobe.wide"), () -> selected != null && !selected.slim, () -> setSlim(false)));
		addRenderableWidget(new Chip(inX0 + w + 4, row, w, 18, Component.translatable("nexomod.wardrobe.slim"), () -> selected != null && selected.slim, () -> setSlim(true)));
		addRenderableWidget(new Chip(inX0 + (w + 4) * 2, row, w, 18, Component.literal("♥"), () -> selected != null && selected.favorite, this::toggleFavorite));
		addRenderableWidget(new Chip(inX0 + (w + 4) * 3, row, w, 18, Component.translatable("nexomod.wardrobe.delete"), () -> deleteArmed, this::delete));
		sliderX0 = inX0 + 44;
		sliderX1 = inX1;
		sliderY = row - 12;
		modelX0 = previewX0 + 1;
		modelY0 = py0 + HEADER + 1;
		modelX1 = px1 - 1;
		modelY1 = sliderY - 8;

		// Add menu: last, so it sits on top of everything.
		menuX1 = gridX1;
		menuX0 = menuX1 - 130;
		menuY0 = py0 + 26;
		menuY1 = menuY0 + 4 + 4 * 20 + 4;
		addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
			if (menuOpen) {
				graphics.nextStratum();
				NexoShapes.outlined(graphics, menuX0, menuY0, menuX1, menuY1, NexoStyle.RAISED, NexoStyle.CONTROL, 5);
			}
		});
		addMenuItem(0, "nexomod.wardrobe.add.file", this::pickFile);
		addMenuItem(1, "nexomod.wardrobe.add.url", this::askUrl);
		addMenuItem(2, "nexomod.wardrobe.add.username", this::askUsername);
		addMenuItem(3, "nexomod.wardrobe.add.current", () -> importCurrent(true));
	}

	private int addTab(Tab target, String key, Identifier icon, int tint, int y) {
		addRenderableWidget(new Chip(px0 + 6, y, RAIL - 12, 24, Component.translatable(key), () -> tab == target, () -> {
			tab = target;
			scroll = 0;
			deleteArmed = false;
			setMenu(false);
			rebuildWidgets();
		}).icon(icon, tint));
		return y + 26;
	}

	private void setMenu(boolean open) {
		menuOpen = open;
		menuChips.forEach(chip -> chip.visible = open);
	}

	private void addMenuItem(int index, String key, Runnable action) {
		Chip chip = new Chip(menuX0 + 4, menuY0 + 4 + index * 20, menuX1 - menuX0 - 8, 18, Component.translatable(key), () -> false, () -> {
			setMenu(false);
			action.run();
		});
		chip.menuItem = true;
		chip.visible = menuOpen;
		menuChips.add(addRenderableWidget(chip));
	}

	static void drawIcon(GuiGraphicsExtractor graphics, Identifier texture, int tint, int x, int y) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0F, 0F, 16, 16, 16, 16, tint);
	}

	public static void drawChestplate(GuiGraphicsExtractor graphics, int x, int y) {
		drawIcon(graphics, CHESTPLATE, LEATHER_TINT, x, y);
	}

	// --- Actions --------------------------------------------------------

	void say(Component message, boolean error) {
		status = message;
		statusError = error;
	}

	private void fail(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		if (cause instanceof NexoSkinApi.ApiException) {
			say(Component.translatable(cause.getMessage()), true);
		} else {
			// Only the type: a message from the HTTP stack is not worth risking in a log.
			NexoMod.LOGGER.warn("[nexomod] Wardrobe: {}", cause.getClass().getName());
			say(Component.translatable("nexomod.wardrobe.error.network"), true);
		}
	}

	/** Runs blocking work off the render thread, then the result back on it. */
	private <T> void background(Supplier<T> work, Consumer<T> done) {
		background(work, done, () -> {
		});
	}

	private <T> void background(Supplier<T> work, Consumer<T> done, Runnable always) {
		Minecraft mc = Minecraft.getInstance();
		CompletableFuture.supplyAsync(work, Util.nonCriticalIoPool()).whenComplete((result, error) -> mc.execute(() -> {
			always.run();
			if (error != null) {
				fail(error);
				return;
			}
			try {
				done.accept(result);
			} catch (NexoSkinApi.ApiException e) {
				fail(e);
			}
		}));
	}

	private void offerSkin(byte[] png, String name, boolean slim) {
		NexoSkinLibrary.checkSkin(png);
		// The lookup may finish after the player has left: no dialog popping up in the game.
		if (minecraft == null || minecraft.gui.screen() != this) {
			return;
		}
		minecraft.gui.setScreen(new NexoWardrobeDetailsScreen(this, png, name, slim, skin -> {
			select(skin);
			tab = Tab.SKINS;
			rebuildWidgets();
			say(Component.translatable("nexomod.wardrobe.added", skin.name), false);
		}));
	}

	private static boolean dialogOpen;
	/** Never freed: SDL may call it from another thread, and it must outlive that call. */
	private static SDL_DialogFileCallback fileCallback;

	private void pickFile() {
		if (dialogOpen) {
			say(Component.translatable("nexomod.wardrobe.dialogOpen"), false);
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (fileCallback == null) {
			fileCallback = SDL_DialogFileCallback.create((userdata, filelist, filter) -> {
				// NULL on an error, a NULL first entry when the player cancelled.
				long first = filelist == 0L ? 0L : MemoryUtil.memGetAddress(filelist);
				String path = first == 0L ? null : MemoryUtil.memUTF8(first);
				Minecraft.getInstance().execute(() -> filePicked(path));
			});
		}
		dialogOpen = true;
		SDLDialog.SDL_ShowOpenFileDialog(fileCallback, 0L, mc.getWindow().handle(), null, (CharSequence) null, false);
	}

	/** Goes to whichever wardrobe is showing now, which may not be the one that asked. */
	private static void filePicked(String path) {
		dialogOpen = false;
		if (!(Minecraft.getInstance().gui.screen() instanceof NexoWardrobeScreen wardrobe)) {
			return;
		}
		if (path == null) {
			wardrobe.say(Component.translatable("nexomod.wardrobe.dropHint"), false);
			return;
		}
		try {
			wardrobe.loadFile(Path.of(path));
		} catch (java.nio.file.InvalidPathException e) {
			wardrobe.say(Component.translatable("nexomod.wardrobe.error.badImage"), true);
		}
	}

	private void loadFile(Path file) {
		background(() -> {
			byte[] png = NexoSkinLibrary.readFile(file);
			NexoSkinLibrary.checkSkin(png);
			return png;
		}, png -> {
			String name = file.getFileName().toString().replaceFirst("(?i)\\.png$", "");
			offerSkin(png, name, false);
		});
	}

	@Override
	public void onFilesDrop(List<Path> files) {
		if (!files.isEmpty()) {
			loadFile(files.get(0));
		}
	}

	private void askUrl() {
		minecraft.gui.setScreen(new NexoWardrobeInputScreen(this, Component.translatable("nexomod.wardrobe.add.url"),
				Component.translatable("nexomod.wardrobe.url.hint"), Component.translatable("nexomod.wardrobe.next"), 512, url -> {
					say(Component.translatable("nexomod.wardrobe.loading"), false);
					background(() -> {
						byte[] png = NexoSkinApi.fromUrl(url);
						NexoSkinLibrary.checkSkin(png);
						return png;
					}, png -> offerSkin(png, "Skin #" + (NexoSkinLibrary.skins().size() + 1), false));
				}));
	}

	private void askUsername() {
		minecraft.gui.setScreen(new NexoWardrobeInputScreen(this, Component.translatable("nexomod.wardrobe.add.username"),
				Component.translatable("nexomod.wardrobe.username.hint"), Component.translatable("nexomod.wardrobe.next"), 16, name -> {
					say(Component.translatable("nexomod.wardrobe.loading"), false);
					background(() -> NexoSkinApi.fromUsername(name), skin -> offerSkin(skin.png(), name, skin.slim()));
				}));
	}

	/** Adds the skin the account has now; on first open it does so unasked and quietly. */
	private void importCurrent(boolean ask) {
		Minecraft mc = Minecraft.getInstance();
		String name = mc.getUser().getName();
		if (!NexoSkinApi.hasToken(mc.getUser().getAccessToken())) {
			say(Component.translatable("nexomod.wardrobe.error.signIn"), true);
			return;
		}
		background(() -> NexoSkinApi.fromUsername(name), skin -> {
			if (ask) {
				offerSkin(skin.png(), name, skin.slim());
				return;
			}
			try {
				select(NexoSkinLibrary.add(skin.png(), name, skin.slim()));
				NexoSkinLibrary.markImported();
			} catch (IOException e) {
				NexoMod.LOGGER.warn("[nexomod] Wardrobe: could not save the current skin.", e);
			}
		}, () -> NexoSkinLibrary.autoImportDone());
	}

	private void loadCapes(String token) {
		background(() -> {
			try {
				return NexoSkinApi.capes(token);
			} catch (NexoSkinApi.ApiException e) {
				return null;
			}
		}, list -> {
			if (list == null) {
				capes = List.of();
				capesMessage = Component.translatable("nexomod.wardrobe.error.network");
				return;
			}
			capes = list;
			capesKnown = true;
			capesMessage = list.isEmpty() ? Component.translatable("nexomod.wardrobe.capes.none") : null;
			for (NexoSkinApi.Cape cape : list) {
				if (cape.active()) {
					activeCape = cape.id();
					// Not over the cape of an outfit picked while this was loading.
					if (selectedOutfit == null) {
						previewCape = cape.id();
					}
				}
				fetchCape(cape);
			}
		});
	}

	private void fetchCape(NexoSkinApi.Cape cape) {
		if (CAPE_TEXTURES.containsKey(cape.id())) {
			return;
		}
		CompletableFuture.supplyAsync(() -> NexoSkinApi.fromMojangTextures(cape.url()), Util.nonCriticalIoPool())
				.whenComplete((png, error) -> Minecraft.getInstance().execute(() -> {
					if (error != null) {
						return;
					}
					try {
						Identifier id = Identifier.fromNamespaceAndPath("nexomod", "wardrobe/cape_" + cape.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
						CAPE_TEXTURES.put(cape.id(), NexoSkinLibrary.registerCape(id, png));
					} catch (IOException | RuntimeException e) {
						NexoMod.LOGGER.warn("[nexomod] Wardrobe: could not load a cape.");
					}
				}));
	}

	private void wear() {
		String token = minecraft.getUser().getAccessToken();
		if (selected == null || busy) {
			return;
		}
		if (!NexoSkinApi.hasToken(token)) {
			say(Component.translatable("nexomod.wardrobe.error.signIn"), true);
			return;
		}
		Skin skin = selected;
		boolean slim = skin.slim;
		String cape = previewCape;
		// An outfit may name a cape from another account: Mojang would refuse it.
		boolean owned = cape.isEmpty() || (capes != null && capes.stream().anyMatch(known -> known.id().equals(cape)));
		boolean changeCape = capes != null && owned && !cape.equals(activeCape);
		java.util.UUID owner = minecraft.getUser().getProfileId();
		busy = true;
		say(Component.translatable("nexomod.wardrobe.uploading"), false);
		this.<WearResult>background(() -> {
			byte[] png;
			try {
				png = NexoSkinLibrary.bytes(skin);
				NexoSkinLibrary.checkSkin(png);
			} catch (IOException e) {
				throw new NexoSkinApi.ApiException("nexomod.wardrobe.error.badImage");
			}
			NexoSkinApi.wear(png, slim, token);
			if (changeCape) {
				try {
					NexoSkinApi.setCape(cape, token);
				} catch (NexoSkinApi.ApiException e) {
					return new WearResult(png, false);
				}
			}
			return new WearResult(png, true);
		}, result -> {
			if (changeCape && result.capeOk()) {
				activeCape = cape;
			}
			// Its own texture: the title screen must keep showing it if this skin is deleted later.
			if (minecraft.getUser().getProfileId().equals(owner)) {
				try {
					NexoTitleExtras.showWornSkin(owner, NexoSkinLibrary.registerSkin(WORN, result.png()), slim);
				} catch (IOException | RuntimeException e) {
					NexoMod.LOGGER.warn("[nexomod] Wardrobe: could not show the worn skin.");
				}
			}
			say(Component.translatable(result.capeOk() ? "nexomod.wardrobe.worn" : "nexomod.wardrobe.wornNoCape"), !result.capeOk());
		}, () -> busy = false);
	}

	private record WearResult(byte[] png, boolean capeOk) {
	}

	private void saveOutfit() {
		if (selected == null) {
			return;
		}
		Outfit outfit = new Outfit();
		outfit.name = "Outfit #" + (NexoSkinLibrary.outfits().size() + 1);
		outfit.skinId = selected.id;
		outfit.capeId = capesKnown ? previewCape : null;
		NexoSkinLibrary.outfits().add(outfit);
		NexoSkinLibrary.save();
		selectedOutfit = outfit;
		say(Component.translatable("nexomod.wardrobe.outfitSaved", outfit.name), false);
	}

	private void select(Skin skin) {
		selected = skin;
		deleteArmed = false;
	}

	private void step(int by) {
		List<Skin> skins = NexoSkinLibrary.skins();
		if (!skins.isEmpty()) {
			int at = skins.indexOf(selected);
			select(skins.get(Math.floorMod(at + by, skins.size())));
			selectedOutfit = null;
		}
	}

	private void setSlim(boolean slim) {
		if (selected != null) {
			selected.slim = slim;
			NexoSkinLibrary.save();
		}
	}

	private void toggleFavorite() {
		if (selected != null) {
			selected.favorite = !selected.favorite;
			NexoSkinLibrary.save();
		}
	}

	/** Two clicks: the first arms it, the second removes the selected outfit on Outfits, the skin on Skins. */
	private void delete() {
		boolean target = tab == Tab.SKINS ? selected != null : tab == Tab.OUTFITS && selectedOutfit != null;
		if (!target) {
			deleteArmed = false;
			return;
		}
		if (!deleteArmed) {
			deleteArmed = true;
			return;
		}
		deleteArmed = false;
		if (tab == Tab.OUTFITS) {
			NexoSkinLibrary.outfits().remove(selectedOutfit);
			NexoSkinLibrary.save();
		} else {
			NexoSkinLibrary.remove(selected);
			List<Skin> skins = NexoSkinLibrary.skins();
			selected = skins.isEmpty() ? null : skins.get(0);
		}
		selectedOutfit = null;
	}

	// --- Grid -----------------------------------------------------------

	private boolean matches(String name) {
		return search.isBlank() || name.toLowerCase(Locale.ROOT).contains(search.strip().toLowerCase(Locale.ROOT));
	}

	private Identifier capeTexture(String id) {
		return id == null || id.isEmpty() ? null : CAPE_TEXTURES.get(id);
	}

	private Item skinItem(Skin skin) {
		return new Item(null, skin.name, Component.translatable(skin.slim ? "nexomod.wardrobe.slim" : "nexomod.wardrobe.wide").getString(),
				() -> NexoSkinLibrary.texture(skin), skin.slim, null, false, skin == selected, skin.favorite, () -> {
					select(skin);
					selectedOutfit = null;
				});
	}

	private List<Item> items() {
		List<Item> items = new ArrayList<>();
		if (tab == Tab.SKINS) {
			List<Skin> skins = NexoSkinLibrary.skins().stream().filter(skin -> matches(skin.name)).toList();
			if (skins.stream().anyMatch(skin -> skin.favorite)) {
				items.add(heading("nexomod.wardrobe.favorites"));
				skins.stream().filter(skin -> skin.favorite).forEach(skin -> items.add(skinItem(skin)));
			}
			if (skins.stream().anyMatch(skin -> !skin.favorite)) {
				items.add(heading("nexomod.wardrobe.library"));
				skins.stream().filter(skin -> !skin.favorite).forEach(skin -> items.add(skinItem(skin)));
			}
		} else if (tab == Tab.OUTFITS) {
			for (Outfit outfit : NexoSkinLibrary.outfits()) {
				Skin skin = NexoSkinLibrary.find(outfit.skinId);
				if (skin == null || !matches(outfit.name)) {
					continue;
				}
				items.add(new Item(null, outfit.name, "", () -> NexoSkinLibrary.texture(skin), skin.slim, capeTexture(outfit.capeId),
						false, outfit == selectedOutfit, false, () -> {
							select(skin);
							selectedOutfit = outfit;
							// No recorded cape: saved while capes were unknown, so leave the choice alone.
							if (outfit.capeId != null) {
								previewCape = outfit.capeId;
							}
						}));
			}
		} else if (capes != null && !capes.isEmpty()) {
			Skin worn = selected;
			Supplier<Identifier> body = () -> worn == null ? null : NexoSkinLibrary.texture(worn);
			boolean slim = selected != null && selected.slim;
			items.add(new Item(null, Component.translatable("nexomod.wardrobe.capes.off").getString(), "", body, slim, null, false,
					previewCape.isEmpty(), false, () -> previewCape = ""));
			for (NexoSkinApi.Cape cape : capes) {
				if (matches(cape.name())) {
					items.add(new Item(null, cape.name(), cape.id().equals(activeCape) ? Component.translatable("nexomod.wardrobe.capes.on").getString() : "",
							body, slim, capeTexture(cape.id()), true, cape.id().equals(previewCape), false, () -> {
								previewCape = cape.id();
								rotY = 155F;
							}));
				}
			}
		}
		return items;
	}

	private static Item heading(String key) {
		return new Item(Component.translatable(key), null, null, null, false, null, false, false, false, null);
	}

	private int columns() {
		return Math.max(1, (gridX1 - gridX0 + GAP) / (CARD_MIN + GAP));
	}

	private int cardWidth() {
		int cols = columns();
		return (gridX1 - gridX0 - GAP * (cols - 1)) / cols;
	}

	private List<Placed> place(List<Item> items) {
		List<Placed> placed = new ArrayList<>();
		int cols = columns();
		int y = 0;
		int col = 0;
		for (Item item : items) {
			if (item.heading() != null) {
				if (col != 0) {
					y += CARD_HEIGHT + GAP;
					col = 0;
				}
				placed.add(new Placed(item, y));
				y += HEADING;
				continue;
			}
			placed.add(new Placed(item, y));
			if (++col == cols) {
				col = 0;
				y += CARD_HEIGHT + GAP;
			}
		}
		if (col != 0) {
			y += CARD_HEIGHT;
		}
		maxScroll = Math.max(0, y - (gridY1 - gridY0));
		scroll = Mth.clamp(scroll, 0, maxScroll);
		return placed;
	}

	/** Runs through the placed cards in grid order, with each one's screen position. */
	private void eachCard(List<Placed> placed, CardVisitor visitor) {
		int cols = columns();
		int width = cardWidth();
		int col = 0;
		int lastY = -1;
		for (Placed entry : placed) {
			if (entry.item().heading() != null) {
				col = 0;
				lastY = -1;
				continue;
			}
			if (entry.y() != lastY) {
				col = 0;
				lastY = entry.y();
			}
			int x = gridX0 + col * (width + GAP);
			visitor.visit(entry.item(), x, gridY0 + entry.y() - scroll, x + width, gridY0 + entry.y() - scroll + CARD_HEIGHT);
			col = (col + 1) % cols;
		}
	}

	private interface CardVisitor {
		void visit(Item item, int x0, int y0, int x1, int y1);
	}

	// --- Drawing --------------------------------------------------------

	private static void bake() {
		if (wide == null) {
			var models = Minecraft.getInstance().getEntityModels();
			wide = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER), RenderTypes::entityTranslucent);
			slimModel = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER_SLIM), RenderTypes::entityTranslucent);
			capeModel = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER_CAPE), RenderTypes::entitySolid);
			// Hangs slightly away from the back, as it does on a standing player.
			capeModel.root().getChild("body").getChild("cape").xRot = 0.1F;
		}
	}

	/**
	 * The 3D player, with its cape. Each model is drawn as its own picture with no
	 * shared depth, so whichever is nearer the viewer has to go second.
	 */
	static void drawModel(GuiGraphicsExtractor graphics, Identifier skin, boolean slim, Identifier cape, float rotX, float rotY, int x0, int y0, int x1, int y1) {
		// A zero or negative size would crash the texture it is drawn into; a tiny window can ask for one.
		if (x1 <= x0 || y1 <= y0) {
			return;
		}
		bake();
		float scale = 0.97F * (y1 - y0) / 2.125F;
		Model.Simple player = slim ? slimModel : wide;
		boolean capeNear = Mth.cos(rotY * Mth.DEG_TO_RAD) < 0F;
		if (cape != null && !capeNear) {
			graphics.skin(capeModel, cape, scale, rotX, rotY, -1.0625F, x0, y0, x1, y1);
		}
		if (skin != null) {
			graphics.skin(player, skin, scale, rotX, rotY, -1.0625F, x0, y0, x1, y1);
		}
		if (cape != null && capeNear) {
			graphics.skin(capeModel, cape, scale, rotX, rotY, -1.0625F, x0, y0, x1, y1);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int previewX0 = px1 - previewWidth;
		NexoShapes.outlined(graphics, px0, py0, px1, py1, NexoStyle.PANEL, NexoStyle.LINE, 8);
		graphics.fill(px0 + RAIL, py0 + 1, px0 + RAIL + 1, py1 - 1, NexoStyle.LINE);
		graphics.fill(previewX0, py0 + 1, previewX0 + 1, py1 - 1, NexoStyle.LINE);
		graphics.fill(px0 + RAIL + 1, py0 + HEADER, px1 - 1, py0 + HEADER + 1, NexoStyle.LINE);
		graphics.fill(previewX0 + 1, py0 + HEADER + 1, px1 - 1, py1 - 1, 0xFF0A0A0D);
		graphics.text(font, title.copy().withStyle(style -> style.withBold(true)), px0 + 28, py0 + 11, NexoStyle.TEXT_PRIMARY);
		drawGridHeader(graphics);
		drawGrid(graphics, mouseX, mouseY);
		drawPreview(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void drawGridHeader(GuiGraphicsExtractor graphics) {
		int count = switch (tab) {
			case SKINS -> NexoSkinLibrary.skins().size();
			case OUTFITS -> NexoSkinLibrary.outfits().size();
			case CAPES -> capes == null ? 0 : capes.size();
		};
		String key = switch (tab) {
			case SKINS -> "nexomod.wardrobe.tab.skins";
			case OUTFITS -> "nexomod.wardrobe.tab.outfits";
			case CAPES -> "nexomod.wardrobe.tab.capes";
		};
		Component heading = Component.translatable(key).withStyle(style -> style.withBold(true));
		graphics.text(font, heading, gridX0, py0 + 11, NexoStyle.TEXT_PRIMARY);
		graphics.text(font, String.valueOf(count), gridX0 + font.width(heading) + 6, py0 + 11, NexoStyle.MUTED);
		int boxRight = tab == Tab.SKINS ? gridX1 - 78 : gridX1;
		if (searchShown) {
			NexoShapes.outlined(graphics, boxRight - 92, py0 + 6, boxRight, py0 + 24, NexoStyle.FIELD, NexoStyle.CONTROL, 3);
		}
	}

	private void drawGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<Item> items = items();
		List<Placed> placed = place(items);
		if (items.isEmpty()) {
			Component message = switch (tab) {
				case SKINS -> Component.translatable(search.isBlank() ? "nexomod.wardrobe.empty.skins" : "nexomod.wardrobe.empty.search");
				case OUTFITS -> Component.translatable("nexomod.wardrobe.empty.outfits");
				case CAPES -> capesMessage != null ? capesMessage : Component.translatable("nexomod.wardrobe.loading");
			};
			int y = (gridY0 + gridY1) / 2 - 8;
			for (FormattedCharSequence line : font.split(message, gridX1 - gridX0 - 24)) {
				graphics.centeredText(font, line, (gridX0 + gridX1) / 2, y, NexoStyle.MUTED);
				y += 10;
			}
			return;
		}
		graphics.enableScissor(gridX0 - 2, gridY0, gridX1 + 2, gridY1);
		for (Placed entry : placed) {
			if (entry.item().heading() != null) {
				int y = gridY0 + entry.y() - scroll + 2;
				int w = font.width(entry.item().heading());
				int cx = (gridX0 + gridX1) / 2;
				graphics.fill(gridX0, y + 4, cx - w / 2 - 6, y + 5, NexoStyle.LINE);
				graphics.fill(cx + w / 2 + 6, y + 4, gridX1, y + 5, NexoStyle.LINE);
				graphics.centeredText(font, entry.item().heading(), cx, y, NexoStyle.MUTED);
			}
		}
		eachCard(placed, (item, x0, y0, x1, y1) -> {
			if (y1 < gridY0 || y0 > gridY1) {
				return;
			}
			boolean hot = !menuOpen && mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1 && mouseY >= gridY0 && mouseY < gridY1;
			if (item.selected()) {
				NexoShapes.glow(graphics, x0, y0, x1, y1, NexoStyle.ACCENT, 3, 0.2F);
			}
			int border = item.selected() ? NexoStyle.ACCENT : hot ? NexoStyle.mix(NexoStyle.CONTROL, NexoStyle.ACCENT, 0.5F) : NexoStyle.LINE;
			NexoShapes.outlined(graphics, x0, y0, x1, y1, item.selected() ? NexoStyle.mix(NexoStyle.RAISED, NexoStyle.ACCENT, 0.12F) : NexoStyle.RAISED, border, 5);
			drawModel(graphics, item.skin().get(), item.slim(), item.cape(), 5F, item.back() ? 155F : -25F, x0 + 6, y0 + 4, x1 - 6, y1 - 18);
			if (item.favorite()) {
				graphics.text(font, "♥", x1 - 11, y0 + 5, 0xFFFF3CAC);
			}
			graphics.fill(x0 + 1, y1 - 16, x1 - 1, y1 - 15, NexoStyle.LINE);
			int subWidth = item.sub().isEmpty() ? 0 : font.width(item.sub()) + 4;
			graphics.text(font, font.plainSubstrByWidth(item.name(), x1 - x0 - 10 - subWidth), x0 + 5, y1 - 12, NexoStyle.TEXT_PRIMARY);
			if (subWidth > 0) {
				graphics.text(font, item.sub(), x1 - 4 - font.width(item.sub()), y1 - 12, NexoStyle.MUTED);
			}
		});
		graphics.disableScissor();
		if (maxScroll > 0) {
			int track = gridY1 - gridY0;
			int thumb = Math.max(16, track * track / (track + maxScroll));
			int y = gridY0 + (track - thumb) * scroll / maxScroll;
			graphics.fill(gridX1 + 3, y, gridX1 + 5, y + thumb, NexoStyle.CONTROL);
		}
	}

	private void drawPreview(GuiGraphicsExtractor graphics) {
		int previewX0 = px1 - previewWidth;
		int centre = previewX0 + previewWidth / 2;
		String name = selected == null ? "" : font.plainSubstrByWidth(selected.name, previewWidth - 60);
		graphics.centeredText(font, Component.literal(name).withStyle(style -> style.withBold(true)), centre, py0 + 11, NexoStyle.TEXT_PRIMARY);

		int height = modelY1 - modelY0 - 12;
		int floorY = modelY1 - 12;
		NexoShapes.glow(graphics, centre - 44, floorY - 3, centre + 44, floorY + 3, NexoStyle.ACCENT, 3, 0.14F);
		if (selected != null) {
			int w = height * 2 / 3;
			drawModel(graphics, NexoSkinLibrary.texture(selected), selected.slim, capeTexture(previewCape), rotX, rotY,
					centre - w, modelY0 + 6, centre + w, modelY0 + 6 + height);
		} else {
			graphics.centeredText(font, Component.translatable("nexomod.wardrobe.empty.preview"), centre, (modelY0 + modelY1) / 2, NexoStyle.MUTED);
		}

		// Rotate slider.
		graphics.text(font, Component.translatable("nexomod.wardrobe.rotate"), previewX0 + 8, sliderY - 3, NexoStyle.MUTED);
		graphics.fill(sliderX0, sliderY, sliderX1, sliderY + 2, NexoStyle.CONTROL);
		float wrapped = Mth.wrapDegrees(rotY);
		int knob = sliderX0 + (int) ((wrapped + 180F) / 360F * (sliderX1 - sliderX0));
		graphics.fill(sliderX0, sliderY, knob, sliderY + 2, NexoStyle.ACCENT);
		NexoShapes.fillRounded(graphics, knob - 3, sliderY - 3, knob + 3, sliderY + 5, NexoStyle.TEXT_PRIMARY, 2);

		if (status != null) {
			int y = py1 - 8 - 20 - 24;
			int color = statusError ? NexoStyle.DANGER : NexoStyle.MUTED;
			for (FormattedCharSequence line : font.split(status, previewWidth - 16)) {
				graphics.text(font, line, previewX0 + 8, y, color);
				y += 10;
			}
		}
	}

	// --- Input ----------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		double x = event.x();
		double y = event.y();
		if (menuOpen) {
			setMenu(false);
			return true;
		}
		if (x >= sliderX0 - 4 && x <= sliderX1 + 4 && y >= sliderY - 5 && y <= sliderY + 7) {
			draggingSlider = true;
			moveSlider(x);
			return true;
		}
		if (x >= modelX0 && x < modelX1 && y >= modelY0 && y < modelY1) {
			draggingModel = true;
			return true;
		}
		if (x >= gridX0 && x < gridX1 && y >= gridY0 && y < gridY1) {
			boolean[] hit = {false};
			eachCard(place(items()), (item, x0, y0, x1, y1) -> {
				if (!hit[0] && x >= x0 && x < x1 && y >= y0 && y < y1) {
					hit[0] = true;
					minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1F));
					item.click().run();
				}
			});
			return hit[0];
		}
		return false;
	}

	private void moveSlider(double x) {
		rotY = (float) (Mth.clamp((x - sliderX0) / (sliderX1 - sliderX0), 0D, 1D) * 360D - 180D);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (draggingSlider) {
			moveSlider(event.x());
			return true;
		}
		if (draggingModel) {
			rotX = Mth.clamp(rotX - (float) dragY * 2.5F, -50F, 50F);
			rotY += (float) dragX * 2.5F;
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingSlider = false;
		draggingModel = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= gridX0 && mouseX < gridX1 + 6 && mouseY >= gridY0 && mouseY < gridY1) {
			scroll = Mth.clamp(scroll - (int) (scrollY * 24), 0, maxScroll);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// --- Controls -------------------------------------------------------

	/** A flat control that lights up while its state is on; an optional item icon makes it a rail tab. */
	static final class Chip extends AbstractButton {
		private final BooleanSupplier on;
		private final Runnable action;
		private Identifier icon;
		private int iconTint;
		/** An entry of the add menu: left-aligned, no outline until hovered. */
		boolean menuItem;

		Chip(int width, int height, Component label, BooleanSupplier on, Runnable action) {
			this(0, 0, width, height, label, on, action);
		}

		Chip(int x, int y, int width, int height, Component label, BooleanSupplier on, Runnable action) {
			super(x, y, width, height, label);
			this.on = on;
			this.action = action;
		}

		Chip icon(Identifier texture, int tint) {
			icon = texture;
			iconTint = tint;
			return this;
		}

		@Override
		public void onPress(InputWithModifiers input) {
			action.run();
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			var font = Minecraft.getInstance().font;
			boolean lit = on.getAsBoolean();
			boolean hot = isHoveredOrFocused();
			int x0 = getX();
			int y0 = getY();
			int x1 = getRight();
			int y1 = getBottom();
			if (lit) {
				NexoShapes.glow(graphics, x0, y0, x1, y1, NexoStyle.ACCENT, 3, 0.2F);
			}
			int fill = lit ? NexoStyle.mix(NexoStyle.FIELD, NexoStyle.ACCENT, 0.18F) : menuItem && !hot ? NexoStyle.RAISED : NexoStyle.FIELD;
			int border = lit ? NexoStyle.ACCENT : hot ? NexoStyle.mix(NexoStyle.CONTROL, NexoStyle.ACCENT, 0.6F) : menuItem ? NexoStyle.RAISED : NexoStyle.CONTROL;
			NexoShapes.outlined(graphics, x0, y0, x1, y1, fill, border, 3);
			int color = lit || hot ? NexoStyle.TEXT_PRIMARY : NexoStyle.MUTED;
			if (icon != null) {
				drawIcon(graphics, icon, iconTint, x0 + 4, y0 + (getHeight() - 16) / 2);
				graphics.text(font, getMessage(), x0 + 24, y0 + (getHeight() - 8) / 2, color);
			} else if (menuItem) {
				graphics.text(font, getMessage(), x0 + 6, y0 + (getHeight() - 8) / 2, color);
			} else {
				graphics.centeredText(font, getMessage(), (x0 + x1) / 2, y0 + (getHeight() - 8) / 2, color);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	/** The one filled control: the logo's rainbow with dark text. */
	static final class Primary extends AbstractButton {
		private final Runnable action;

		Primary(int width, int height, Component label, Runnable action) {
			this(0, 0, width, height, label, action);
		}

		Primary(int x, int y, int width, int height, Component label, Runnable action) {
			super(x, y, width, height, label);
			this.action = action;
		}

		@Override
		public void onPress(InputWithModifiers input) {
			action.run();
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			int x0 = getX();
			int y0 = getY();
			int x1 = getRight();
			int y1 = getBottom();
			NexoShapes.glow(graphics, x0, y0, x1, y1, NexoStyle.ACCENT, 3, isHoveredOrFocused() ? 0.32F : 0.2F);
			NexoShapes.fillLogoRamp(graphics, x0 + 1, x1 - 1, x1 - 1, y0, y0 + 1, 0xFF);
			NexoShapes.fillLogoRamp(graphics, x0, x1, x1, y0 + 1, y1 - 1, 0xFF);
			NexoShapes.fillLogoRamp(graphics, x0 + 1, x1 - 1, x1 - 1, y1 - 1, y1, 0xFF);
			var font = Minecraft.getInstance().font;
			Component bold = getMessage().copy().withStyle(style -> style.withBold(true));
			graphics.text(font, bold, (x0 + x1 - font.width(bold)) / 2, y0 + (getHeight() - 8) / 2 + 1, 0xFF0A0A0D, false);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}
