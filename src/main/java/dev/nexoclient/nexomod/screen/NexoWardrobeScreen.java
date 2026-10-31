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
import dev.nexoclient.nexomod.emotes.NexoEmoteAccount;
import dev.nexoclient.nexomod.emotes.NexoEmotePreview;
import dev.nexoclient.nexomod.emotes.NexoEmotes;
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

	private enum Tab { SKINS, OUTFITS, EMOTES, CAPES, SHOP }

	/** {@code top} marks a card's rarity: a colour along its top edge, {@link #RAINBOW} for the logo's ramp, 0 for none. */
	private record Item(Component heading, String name, String sub, Supplier<Identifier> skin, boolean slim, Identifier cape,
			boolean back, boolean selected, boolean favorite, Runnable click, NexoEmotes.Pose pose, int subColor, int top, String badge) {
		Item(Component heading, String name, String sub, Supplier<Identifier> skin, boolean slim, Identifier cape,
				boolean back, boolean selected, boolean favorite, Runnable click, NexoEmotes.Pose pose) {
			this(heading, name, sub, skin, slim, cape, back, selected, favorite, click, pose, NexoStyle.MUTED, 0, null);
		}
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
	private static final Identifier NOTE = Identifier.withDefaultNamespace("textures/item/totem_of_undying.png");
	private static final Identifier ELYTRA = Identifier.withDefaultNamespace("textures/item/elytra.png");
	private static final Identifier EMERALD = Identifier.withDefaultNamespace("textures/item/emerald.png");
	private static final Identifier COIN = Identifier.withDefaultNamespace("textures/item/gold_nugget.png");
	private static final int RAINBOW = 1;
	private static final int RARE = 0xFF22B8FF;
	private static final int EPIC = 0xFFFF3CAC;
	private static final int OWNED = 0xFF2CFF7A;
	/** What a day can pay when the service hasn't said: the plan's 120 of playtime and 20 daily. */
	private static final int DAY_COINS = 140;
	private static final int STRIP = 32;
	private static final int FILTERS = 24;
	private static final int LEATHER_TINT = 0xFFA0653F;
	private static final Identifier WORN = Identifier.fromNamespaceAndPath("nexomod", "wardrobe/worn");
	private static Model.Simple wide;
	private static Model.Simple slimModel;
	private static Model.Simple capeModel;
	/** Re-posed every frame, unlike {@link #POSED}: only the shop's one big preview moves. */
	private static Model.Simple liveWide;
	private static Model.Simple liveSlim;
	private static final Map<PoseKey, Model.Simple> POSED = new HashMap<>();

	private final Screen parent;
	private Tab tab = Tab.SKINS;
	private Skin selected;
	private Outfit selectedOutfit;
	private NexoEmotes.Pose previewPose;
	private String search = "";
	private int scroll;
	private int maxScroll;
	private boolean menuOpen;
	private final List<Chip> menuChips = new ArrayList<>();
	private boolean deleteArmed;
	private long deleteArmedAt;
	private static final long DELETE_ARM_MILLIS = 5000L;
	/** A second click this soon is a double-click, not a decision. */
	private static final long CONFIRM_GAP_MILLIS = 400L;
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
	private int searchRight;
	/** The shop emote awaiting its second click. */
	private String armedBuy;
	private long armedBuyAt;
	private boolean shopRefreshing;
	private long shopCheckedAt;
	private String shopSelected;
	/** Null shows everything, otherwise a rarity or {@code "owned"}. */
	private String shopFilter;
	private boolean shopLoaded;
	private Component shopError;
	private Primary buyPrimary;
	private Chip buyFlat;
	private Chip retry;
	private boolean showStrip;
	private int stripY;
	private int consentX, consentY, consentWidth;
	private int gridX0, gridY0, gridX1, gridY1;
	private int modelX0, modelY0, modelX1, modelY1;
	private int sliderX0, sliderX1, sliderY;
	private int menuX0, menuY0, menuX1, menuY1;

	public NexoWardrobeScreen(Screen parent) {
		super(Component.translatable("nexomod.wardrobe.title"));
		this.parent = parent;
		List<Skin> skins = NexoSkinLibrary.skins();
		selected = skins.isEmpty() ? null : skins.get(0);
		if (NexoSkinLibrary.saveBlocked()) {
			say(Component.translatable("nexomod.wardrobe.error.index"), true);
		}
		String token = Minecraft.getInstance().getUser().getAccessToken();
		if (NexoSkinApi.hasToken(token)) {
			loadCapes(token);
			if (NexoSkinLibrary.claimAutoImport()) {
				autoImport();
			}
		} else {
			capes = List.of();
			capesMessage = Component.translatable("nexomod.wardrobe.capes.signIn");
		}
	}

	/** Opens on the shop tab. */
	public static NexoWardrobeScreen shop(Screen parent) {
		NexoWardrobeScreen screen = new NexoWardrobeScreen(parent);
		screen.tab = Tab.SHOP;
		screen.refreshShopIfOn();
		return screen;
	}

	private boolean consent() {
		return tab == Tab.SHOP && !NexoConfig.get().emoteShopEnabled();
	}

	@Override
	protected void init() {
		menuChips.clear();
		px0 = Math.max(8, (width - 900) / 2);
		px1 = width - px0;
		py0 = Math.max(8, (height - 500) / 2);
		py1 = height - py0;
		// The shop's grid also carries the earning strip and the filters, so it gets more of the width.
		previewWidth = Mth.clamp((px1 - px0) * (tab == Tab.SHOP ? 30 : 36) / 100, 150, 214);
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
		y = addTab(Tab.EMOTES, "nexomod.wardrobe.tab.emotes", NOTE, 0xFFFFFFFF, y);
		y = addTab(Tab.CAPES, "nexomod.wardrobe.tab.capes", ELYTRA, 0xFFFFFFFF, y);
		y = addTab(Tab.SHOP, "nexomod.emote.shop", EMERALD, 0xFFFFFFFF, y);
		if (consent()) {
			initConsent();
			return;
		}
		if (tab == Tab.SHOP) {
			initShopRail(y);
			initShopGrid();
		}

		// Header: search, and on Skins the add button. Outfits get theirs from the preview.
		int headerRight = gridX1;
		if (tab == Tab.EMOTES) {
			// It reaches out to Nexo's service, so it is the player's call, and starts off.
			Chip sync = new Chip(headerRight - 44, py0 + 6, 44, 18, Component.translatable("nexomod.emote.sync"),
					() -> NexoConfig.get().emoteSyncEnabled(), () -> NexoConfig.get().setEmoteSyncEnabled(!NexoConfig.get().emoteSyncEnabled()));
			sync.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("nexomod.emote.sync.tooltip")));
			addRenderableWidget(sync);
			headerRight -= 50;
		}
		if (tab == Tab.SHOP) {
			// Room for the balance.
			headerRight -= 76;
		}
		if (tab == Tab.SKINS) {
			addRenderableWidget(new Primary(headerRight - 72, py0 + 6, 72, 18, Component.translatable("nexomod.wardrobe.add"), () -> setMenu(!menuOpen)));
			headerRight -= 78;
		}
		// Only when it fits beside the heading.
		searchShown = headerRight - gridX0 >= 150;
		searchRight = headerRight;
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
		if (tab == Tab.SHOP) {
			initShopPreview(previewX0, inX0, inX1);
			return;
		}
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
		addRenderableWidget(new Chip(inX0 + (w + 4) * 3, row, w, 18, Component.translatable("nexomod.wardrobe.delete"), () -> deleteArmed && Util.getMillis() - deleteArmedAt <= DELETE_ARM_MILLIS, this::delete));
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
		addMenuItem(3, "nexomod.wardrobe.add.current", this::importCurrent);
	}

	private void initShopRail(int tabsEnd) {
		int y = py1 - 6 - 18;
		int x = px0 + 6;
		if (NexoEmoteAccount.online() && y - 22 >= tabsEnd) {
			addRenderableWidget(new Chip(x, y, RAIL - 12, 18, Component.translatable("nexomod.emote.erase"),
					() -> deleteArmed && Util.getMillis() - deleteArmedAt <= DELETE_ARM_MILLIS, this::delete));
			y -= 22;
		}
		if (y >= tabsEnd) {
			addRenderableWidget(new Chip(x, y, RAIL - 12, 18, Component.translatable("nexomod.emote.turnOff"), () -> false, () -> {
				NexoConfig.get().setEmoteShopEnabled(false);
				rebuildWidgets();
			}));
		}
	}

	/** The earning strip and filters take the top of the grid, if the window leaves them room. */
	private void initShopGrid() {
		int width = gridX1 - gridX0;
		showStrip = width >= 180;
		stripY = py0 + HEADER + 1;
		if (showStrip) {
			gridY0 = stripY + STRIP + 6;
			retry = addRenderableWidget(new Chip(gridX1 - 48, stripY + 7, 48, 18, Component.translatable("nexomod.emote.retry"), () -> false, this::refreshShop));
			retry.visible = false;
		}
		if (width >= 180) {
			String[] keys = {"nexomod.emote.filter.all", "nexomod.emote.rarity.common", "nexomod.emote.rarity.rare", "nexomod.emote.rarity.epic", "nexomod.emote.owned"};
			String[] values = {null, "common", "rare", "epic", "owned"};
			int w = (width - 4 * 4) / 5;
			for (int i = 0; i < 5; i++) {
				String value = values[i];
				addRenderableWidget(new Chip(gridX0 + i * (w + 4), gridY0, w, 18, Component.translatable(keys[i]),
						() -> java.util.Objects.equals(shopFilter, value), () -> {
							shopFilter = value;
							scroll = 0;
						}));
			}
			gridY0 += FILTERS;
		}
	}

	private void initShopPreview(int previewX0, int inX0, int inX1) {
		int bottom = py1 - 8;
		// Both exist, and which one shows follows what can be done with the selected emote.
		buyPrimary = addRenderableWidget(new Primary(inX0, bottom - 20, inX1 - inX0, 20, Component.empty(), this::pressBuy));
		buyFlat = addRenderableWidget(new Chip(inX0, bottom - 20, inX1 - inX0, 20, Component.empty(), () -> armedBuy != null, this::pressBuy));
		sliderX0 = inX0 + 44;
		sliderX1 = inX1;
		sliderY = bottom - 20 - 24 - 10;
		modelX0 = previewX0 + 1;
		modelY0 = py0 + HEADER + 1;
		modelX1 = px1 - 1;
		modelY1 = sliderY - 8;
	}

	private record ConsentLine(FormattedCharSequence text, int color, int after) {
	}

	private List<ConsentLine> consentLines() {
		List<ConsentLine> lines = new ArrayList<>();
		addConsent(lines, Component.translatable("nexomod.emote.consent.title").withStyle(style -> style.withBold(true)), NexoStyle.TEXT_PRIMARY, 6);
		addConsent(lines, Component.translatable("nexomod.emote.consent.body"), NexoStyle.MUTED, 10);
		addConsent(lines, Component.translatable("nexomod.emote.consent.stores"), NexoStyle.TEXT_PRIMARY, 2);
		for (int i = 1; i <= 3; i++) {
			addConsent(lines, Component.literal("- ").append(Component.translatable("nexomod.emote.consent.item" + i)), NexoStyle.MUTED, i == 3 ? 8 : 1);
		}
		addConsent(lines, Component.translatable("nexomod.emote.consent.foot"), NexoStyle.MUTED, 10);
		return lines;
	}

	private void addConsent(List<ConsentLine> lines, Component text, int color, int after) {
		List<FormattedCharSequence> split = font.split(text, consentWidth);
		for (int i = 0; i < split.size(); i++) {
			lines.add(new ConsentLine(split.get(i), color, i == split.size() - 1 ? after : 0));
		}
	}

	private void initConsent() {
		int x0 = px0 + RAIL + 16;
		int x1 = px1 - 16;
		boolean model = x1 - x0 >= 360;
		consentX = model ? x0 + 130 : x0;
		consentWidth = Math.min(x1 - consentX, 300);
		int height = 26;
		for (ConsentLine line : consentLines()) {
			height += 10 + line.after();
		}
		consentY = Math.max(py0 + 8, (py0 + py1 - height) / 2);
		int buttons = consentY + height - 22;
		int half = (consentWidth - 4) * 3 / 5;
		addRenderableWidget(new Primary(consentX, buttons, half, 20, Component.translatable("nexomod.emote.consent.on"), () -> {
			NexoConfig.get().setEmoteShopEnabled(true);
			refreshShop();
			rebuildWidgets();
		}));
		addRenderableWidget(NexoButton.builder(Component.translatable("nexomod.emote.consent.no"), () -> {
			tab = Tab.SKINS;
			rebuildWidgets();
		}).pos(consentX + half + 4, buttons).size(consentWidth - half - 4, 20).build());
	}

	private int addTab(Tab target, String key, Identifier icon, int tint, int y) {
		addRenderableWidget(new Chip(px0 + 6, y, RAIL - 12, 24, Component.translatable(key), () -> tab == target, () -> {
			tab = target;
			scroll = 0;
			deleteArmed = false;
			armedBuy = null;
			say(null, false);
			refreshShopIfOn();
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

	/** The menu's entry: the skin the account has now, through the naming dialog. */
	private void importCurrent() {
		Minecraft mc = Minecraft.getInstance();
		String name = mc.getUser().getName();
		if (!NexoSkinApi.hasToken(mc.getUser().getAccessToken())) {
			say(Component.translatable("nexomod.wardrobe.error.signIn"), true);
			return;
		}
		background(() -> NexoSkinApi.fromUsername(name), skin -> offerSkin(skin.png(), name, skin.slim()));
	}

	private record AutoImport(NexoSkinApi.FetchedSkin skin, boolean retry) {
	}

	/** First open with an empty library: adds the account's skin, without a word if that fails. */
	private void autoImport() {
		String name = Minecraft.getInstance().getUser().getName();
		this.<AutoImport>background(() -> {
			try {
				return new AutoImport(NexoSkinApi.fromUsername(name), false);
			} catch (NexoSkinApi.ApiException e) {
				// Only a network failure can come out differently next time.
				return new AutoImport(null, "nexomod.wardrobe.error.network".equals(e.getMessage()));
			}
		}, result -> {
			if (result.skin() == null) {
				if (!result.retry()) {
					NexoSkinLibrary.markImported();
				}
				return;
			}
			try {
				select(NexoSkinLibrary.add(result.skin().png(), name, result.skin().slim()));
				NexoSkinLibrary.markImported();
			} catch (IOException | NexoSkinApi.ApiException e) {
				NexoMod.LOGGER.warn("[nexomod] Wardrobe: could not save the current skin.");
			}
		}, NexoSkinLibrary::autoImportDone);
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
					if (selectedOutfit == null || selectedOutfit.capeId == null) {
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

	private void refreshShopIfOn() {
		if (tab == Tab.SHOP && NexoConfig.get().emoteShopEnabled()) {
			refreshShop();
		}
	}

	private void refreshShop() {
		if (shopRefreshing) {
			return;
		}
		shopRefreshing = true;
		NexoEmoteAccount.refresh(key -> {
			shopRefreshing = false;
			shopLoaded = true;
			shopError = Component.translatable(key);
		}, () -> {
			shopRefreshing = false;
			shopLoaded = true;
			shopError = null;
			// The sign-in decides whether the rail offers Erase.
			if (minecraft != null && minecraft.gui.screen() == this) {
				rebuildWidgets();
			}
		});
	}

	private boolean owns(String id) {
		return NexoEmoteAccount.ownedEmotes().stream().anyMatch(emote -> emote.id().equals(id));
	}

	/** What the filter and search leave, with owned emotes the shop has since retired under Owned. */
	private List<NexoEmoteAccount.ShopItem> shopItems() {
		List<NexoEmoteAccount.ShopItem> items = new ArrayList<>();
		for (NexoEmoteAccount.ShopItem item : NexoEmoteAccount.shop()) {
			boolean fits = shopFilter == null || ("owned".equals(shopFilter) ? owns(item.id()) : shopFilter.equals(item.rarity()));
			if (fits && matches(item.name())) {
				items.add(item);
			}
		}
		if ("owned".equals(shopFilter)) {
			for (NexoEmotes.Emote emote : NexoEmoteAccount.ownedEmotes()) {
				String name = emote.name().getString();
				if (matches(name) && NexoEmoteAccount.shop().stream().noneMatch(item -> item.id().equals(emote.id()))) {
					items.add(new NexoEmoteAccount.ShopItem(emote.id(), name, 0, "", 0, 0L));
				}
			}
		}
		return items;
	}

	/** The selected emote, or the first one showing if that was never chosen or is filtered away. */
	private NexoEmoteAccount.ShopItem currentShopItem() {
		List<NexoEmoteAccount.ShopItem> items = shopItems();
		return items.stream().filter(item -> item.id().equals(shopSelected)).findFirst().orElse(items.isEmpty() ? null : items.get(0));
	}

	private void pressBuy() {
		NexoEmoteAccount.ShopItem item = currentShopItem();
		if (item == null || owns(item.id()) || !NexoEmoteAccount.online() || item.price() > NexoEmoteAccount.balance()) {
			return;
		}
		// Two clicks: coins take a long time to earn. Armed at this price, for a moment.
		long now = Util.getMillis();
		if (!armKey(item).equals(armedBuy)) {
			armedBuy = armKey(item);
			armedBuyAt = now;
			return;
		}
		if (now - armedBuyAt < CONFIRM_GAP_MILLIS) {
			return;
		}
		armedBuy = null;
		NexoEmoteAccount.buy(item, key -> say(Component.translatable(key), true),
				() -> say(Component.translatable("nexomod.emote.bought", item.name()), false));
	}

	/** With the price: a refresh that changes it must take the arm away, not buy at the new one. */
	private static String armKey(NexoEmoteAccount.ShopItem item) {
		return item.id() + "@" + item.price();
	}

	private Component buyLabel(NexoEmoteAccount.ShopItem item) {
		if (item == null) {
			return Component.translatable("nexomod.emote.buy.none");
		}
		if (owns(item.id())) {
			return Component.translatable("nexomod.emote.owned");
		}
		if (!NexoEmoteAccount.online()) {
			return Component.translatable(NexoEmoteAccount.canSignIn() ? "nexomod.emote.buy.offline" : "nexomod.emote.buy.signIn");
		}
		int need = item.price() - NexoEmoteAccount.balance();
		if (need > 0) {
			return Component.translatable("nexomod.emote.buy.need", need);
		}
		return Component.translatable(armKey(item).equals(armedBuy) ? "nexomod.emote.buy.again" : "nexomod.emote.buy", item.price());
	}

	private boolean canStartBuying(NexoEmoteAccount.ShopItem item) {
		return item != null && !owns(item.id()) && NexoEmoteAccount.online() && item.price() <= NexoEmoteAccount.balance() && !armKey(item).equals(armedBuy);
	}

	private Component shopNote(NexoEmoteAccount.ShopItem item) {
		if (item == null) {
			return Component.translatable("nexomod.emote.note.none");
		}
		if (owns(item.id())) {
			// The wheel holds the first eight, and bought ones come first.
			boolean onWheel = NexoEmotes.owned().stream().limit(8).anyMatch(emote -> emote.id().equals(item.id()));
			return onWheel ? Component.translatable("nexomod.emote.note.wheel", NexoEmotes.wheelKey().getTranslatedKeyMessage())
					: Component.translatable("nexomod.emote.note.tab");
		}
		if (!NexoEmoteAccount.online()) {
			return Component.empty();
		}
		int need = item.price() - NexoEmoteAccount.balance();
		if (need > 0) {
			NexoEmoteAccount.Earn earn = NexoEmoteAccount.earn();
			int perDay = earn != null ? earn.playtimeCap() + earn.dailyCoins() : DAY_COINS;
			return Component.translatable("nexomod.emote.note.need", Math.ceilDiv(need, Math.max(1, perDay)));
		}
		return Component.translatable("nexomod.emote.note.afford", item.price(), -need);
	}

	private static int percentOff(NexoEmoteAccount.ShopItem item) {
		return Math.max(1, Math.round((1F - (float) item.price() / item.listPrice()) * 100F));
	}

	/** Days and hours, or hours and minutes: a countdown, so only the big units matter. */
	private static String timeLeft(long millis) {
		long minutes = Math.max(1L, millis / 60_000L);
		if (minutes >= 24 * 60) {
			return minutes / (24 * 60) + "d " + minutes / 60 % 24 + "h";
		}
		return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m";
	}

	/** The line above the preview for a deal or a limited sale, or null for an ordinary emote. */
	private static Component offerLine(NexoEmoteAccount.ShopItem item) {
		if (item == null || item.endsAt() <= 0L) {
			return null;
		}
		String left = timeLeft(item.endsAt() - System.currentTimeMillis());
		return item.listPrice() > 0 ? Component.translatable("nexomod.emote.deal", percentOff(item), left, item.listPrice())
				: Component.translatable("nexomod.emote.limited.ends", left);
	}

	private static int rarityColor(String rarity) {
		return switch (rarity) {
			case "rare" -> RARE;
			case "epic" -> EPIC;
			default -> NexoStyle.MUTED;
		};
	}

	private static Component rarityName(String rarity) {
		return switch (rarity) {
			case "common", "rare", "epic" -> Component.translatable("nexomod.emote.rarity." + rarity);
			default -> Component.empty();
		};
	}

	private Item shopItem(NexoEmoteAccount.ShopItem item, NexoEmoteAccount.ShopItem current, Supplier<Identifier> body, boolean slim) {
		boolean has = owns(item.id());
		NexoEmotePreview preview = NexoEmoteAccount.preview(item.id());
		NexoEmotes.Pose pose = NexoEmotes.Pose.NEUTRAL;
		if (preview != null) {
			float[] arms = preview.thumbnail();
			pose = new NexoEmotes.Pose(arms[0], arms[1], arms[2], arms[3]);
		}
		String sub = has ? Component.translatable("nexomod.emote.owned").getString() : String.valueOf(item.price());
		int subColor = has ? OWNED : item.listPrice() > 0 ? EPIC : item.price() > NexoEmoteAccount.balance() ? NexoStyle.MUTED : NexoStyle.TEXT_PRIMARY;
		String badge = has ? null : item.listPrice() > 0 ? "-" + percentOff(item) + "%"
				: item.endsAt() > 0L ? Component.translatable("nexomod.emote.limited").getString() : null;
		int top = "epic".equals(item.rarity()) ? RAINBOW : "rare".equals(item.rarity()) ? RARE : 0;
		return new Item(null, item.name(), sub, body, slim, null, false, item.equals(current), false, () -> {
			shopSelected = item.id();
			armedBuy = null;
			deleteArmed = false;
			say(null, false);
			rotY = -25F;
		}, pose, subColor, top, badge);
	}

	/** Two clicks: the first arms it, the second removes the selected outfit on Outfits, the skin on Skins. */
	private void delete() {
		boolean emoteAccount = tab == Tab.SHOP && NexoConfig.get().emoteShopEnabled() && NexoEmoteAccount.online();
		boolean target = emoteAccount || (tab == Tab.SKINS ? selected != null : tab == Tab.OUTFITS && selectedOutfit != null);
		if (!target) {
			deleteArmed = false;
			return;
		}
		// An arm left standing must not turn a much later click into a deletion.
		if (!deleteArmed || Util.getMillis() - deleteArmedAt > DELETE_ARM_MILLIS) {
			deleteArmed = true;
			deleteArmedAt = Util.getMillis();
			if (emoteAccount) {
				say(Component.translatable("nexomod.emote.deleteAccount"), true);
			}
			return;
		}
		// Losing an account takes a decision, not a double-click.
		if (emoteAccount && Util.getMillis() - deleteArmedAt < CONFIRM_GAP_MILLIS) {
			return;
		}
		deleteArmed = false;
		if (emoteAccount) {
			NexoEmoteAccount.deleteAccount(key -> say(Component.translatable(key), true), () -> {
				NexoConfig.get().setEmoteShopEnabled(false);
				say(Component.translatable("nexomod.emote.deleted"), false);
				rebuildWidgets();
			});
			return;
		}
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
				}, null);
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
							// No recorded cape: saved while capes were unknown, so keep what the account has.
							previewCape = outfit.capeId != null ? outfit.capeId : activeCape;
						}, null));
			}
		} else if (tab == Tab.EMOTES) {
			Skin worn = selected;
			Supplier<Identifier> body = () -> worn == null ? playerSkin() : NexoSkinLibrary.texture(worn);
			boolean slim = worn != null ? worn.slim : playerSlim();
			for (NexoEmotes.Emote emote : NexoEmotes.owned()) {
				if (matches(emote.name().getString())) {
					items.add(new Item(null, emote.name().getString(), "", body, slim, null, false, emote.pose() == previewPose, false, () -> {
						previewPose = emote.pose();
						armedBuy = null;
						deleteArmed = false;
						NexoEmotes.play(emote);
					}, emote.pose()));
				}
			}
		} else if (tab == Tab.SHOP) {
			Skin worn = selected;
			Supplier<Identifier> body = () -> worn == null ? playerSkin() : NexoSkinLibrary.texture(worn);
			boolean slim = worn != null ? worn.slim : playerSlim();
			NexoEmoteAccount.ShopItem current = currentShopItem();
			shopItems().forEach(item -> items.add(shopItem(item, current, body, slim)));
		} else if (capes != null && !capes.isEmpty()) {
			Skin worn = selected;
			Supplier<Identifier> body = () -> worn == null ? null : NexoSkinLibrary.texture(worn);
			boolean slim = selected != null && selected.slim;
			items.add(new Item(null, Component.translatable("nexomod.wardrobe.capes.off").getString(), "", body, slim, null, false,
					previewCape.isEmpty(), false, () -> previewCape = "", null));
			for (NexoSkinApi.Cape cape : capes) {
				if (matches(cape.name())) {
					items.add(new Item(null, cape.name(), cape.id().equals(activeCape) ? Component.translatable("nexomod.wardrobe.capes.on").getString() : "",
							body, slim, capeTexture(cape.id()), true, cape.id().equals(previewCape), false, () -> {
								previewCape = cape.id();
								rotY = 155F;
							}, null));
				}
			}
		}
		return items;
	}

	private static Item heading(String key) {
		return new Item(Component.translatable(key), null, null, null, false, null, false, false, false, null, null);
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
			capeModel.root().getChild("body").getChild("cape").xRot = -0.1F;
			liveWide = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER), RenderTypes::entityTranslucent);
			liveSlim = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER_SLIM), RenderTypes::entityTranslucent);
		}
	}

	/**
	 * The 3D player, with its cape. Each model is drawn as its own picture with no
	 * shared depth, so whichever is nearer the viewer has to go second.
	 */
	static void drawModel(GuiGraphicsExtractor graphics, Identifier skin, boolean slim, Identifier cape, float rotX, float rotY, int x0, int y0, int x1, int y1) {
		drawModel(graphics, skin, slim, cape, null, rotX, rotY, x0, y0, x1, y1);
	}

	/** The signed-in player's own skin in a world, for screens with no library skin picked. */
	private static Identifier playerSkin() {
		var player = Minecraft.getInstance().player;
		return player == null ? null : player.getSkin().body().texturePath();
	}

	private static boolean playerSlim() {
		var player = Minecraft.getInstance().player;
		return player != null && player.getSkin().model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
	}

	/** The local player's skin in a pose: the emote wheel's slots. */
	static void drawPosed(GuiGraphicsExtractor graphics, NexoEmotes.Pose pose, float rotX, float rotY, int x0, int y0, int x1, int y1) {
		drawModel(graphics, playerSkin(), playerSlim(), null, pose, rotX, rotY, x0, y0, x1, y1);
	}

	/** One baked model per pose: a picture renders after this frame, so a shared model cannot be re-posed between cards. */
	private static Model.Simple posed(boolean slim, NexoEmotes.Pose pose) {
		return POSED.computeIfAbsent(new PoseKey(slim, pose), key -> {
			var layer = slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER;
			Model.Simple model = new Model.Simple(Minecraft.getInstance().getEntityModels().bakeLayer(layer), RenderTypes::entityTranslucent);
			var right = model.root().getChild("right_arm");
			right.xRot = pose.rightArmX();
			right.zRot = pose.rightArmZ();
			var left = model.root().getChild("left_arm");
			left.xRot = pose.leftArmX();
			left.zRot = pose.leftArmZ();
			return model;
		});
	}

	private record PoseKey(boolean slim, NexoEmotes.Pose pose) {
	}

	private static void drawModel(GuiGraphicsExtractor graphics, Identifier skin, boolean slim, Identifier cape, NexoEmotes.Pose pose, float rotX, float rotY, int x0, int y0, int x1, int y1) {
		// A zero or negative size would crash the texture it is drawn into; a tiny window can ask for one.
		if (x1 <= x0 || y1 <= y0) {
			return;
		}
		bake();
		float scale = 0.97F * (y1 - y0) / 2.125F;
		Model.Simple player = pose != null ? posed(slim, pose) : slim ? slimModel : wide;
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
		if (consent()) {
			graphics.text(font, title.copy().withStyle(style -> style.withBold(true)), px0 + 28, py0 + 11, NexoStyle.TEXT_PRIMARY);
			drawConsent(graphics);
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			return;
		}
		graphics.fill(previewX0, py0 + 1, previewX0 + 1, py1 - 1, NexoStyle.LINE);
		graphics.fill(px0 + RAIL + 1, py0 + HEADER, px1 - 1, py0 + HEADER + 1, NexoStyle.LINE);
		graphics.fill(previewX0 + 1, py0 + HEADER + 1, px1 - 1, py1 - 1, 0xFF0A0A0D);
		graphics.text(font, title.copy().withStyle(style -> style.withBold(true)), px0 + 28, py0 + 11, NexoStyle.TEXT_PRIMARY);
		drawGridHeader(graphics);
		if (tab == Tab.SHOP) {
			drawStrip(graphics);
			updateBuyButton();
		}
		drawGrid(graphics, mouseX, mouseY);
		drawPreview(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void drawGridHeader(GuiGraphicsExtractor graphics) {
		int count = switch (tab) {
			case SKINS -> NexoSkinLibrary.skins().size();
			case OUTFITS -> NexoSkinLibrary.outfits().size();
			case EMOTES -> NexoEmotes.owned().size();
			case CAPES -> capes == null ? 0 : capes.size();
			case SHOP -> NexoEmoteAccount.shop().size();
		};
		String key = switch (tab) {
			case SKINS -> "nexomod.wardrobe.tab.skins";
			case OUTFITS -> "nexomod.wardrobe.tab.outfits";
			case EMOTES -> "nexomod.wardrobe.tab.emotes";
			case CAPES -> "nexomod.wardrobe.tab.capes";
			case SHOP -> "nexomod.emote.shop";
		};
		Component heading = Component.translatable(key).withStyle(style -> style.withBold(true));
		graphics.text(font, heading, gridX0, py0 + 11, NexoStyle.TEXT_PRIMARY);
		graphics.text(font, String.valueOf(count), gridX0 + font.width(heading) + 6, py0 + 11, NexoStyle.MUTED);
		if (tab == Tab.SHOP && NexoEmoteAccount.online()) {
			String coins = String.valueOf(NexoEmoteAccount.balance());
			int textX = gridX1 - font.width(coins);
			graphics.text(font, coins, textX, py0 + 11, NexoStyle.TEXT_PRIMARY);
			drawIcon(graphics, COIN, 0xFFFFFFFF, textX - 18, py0 + 7);
		}
		int boxRight = searchRight;
		if (searchShown) {
			NexoShapes.outlined(graphics, boxRight - 92, py0 + 6, boxRight, py0 + 24, NexoStyle.FIELD, NexoStyle.CONTROL, 3);
		}
	}

	private void updateBuyButton() {
		long clock = System.currentTimeMillis();
		// An offer that ran out here: ask what is true now, but not every frame if the clocks disagree.
		if (clock - shopCheckedAt > 30_000L && NexoEmoteAccount.shop().stream().anyMatch(offer -> offer.endsAt() > 0L && offer.endsAt() <= clock)) {
			shopCheckedAt = clock;
			refreshShop();
		}
		NexoEmoteAccount.ShopItem item = currentShopItem();
		if (armedBuy != null && (item == null || !armedBuy.equals(armKey(item)) || Util.getMillis() - armedBuyAt > DELETE_ARM_MILLIS)) {
			armedBuy = null;
		}
		boolean primary = canStartBuying(item);
		Component label = buyLabel(item);
		buyPrimary.visible = primary;
		buyPrimary.setMessage(label);
		buyFlat.visible = !primary;
		buyFlat.setMessage(label);
	}

	/** How coins are earned, or why that is not happening right now. */
	private void drawStrip(GuiGraphicsExtractor graphics) {
		if (!showStrip) {
			return;
		}
		retry.visible = shopError != null;
		graphics.fill(gridX0 - 8, stripY + STRIP, gridX1 + 8, stripY + STRIP + 1, NexoStyle.LINE);
		NexoEmoteAccount.Earn earn = NexoEmoteAccount.earn();
		if (shopError != null || !(NexoEmoteAccount.online() || NexoEmoteAccount.canSignIn())) {
			Component message = shopError != null ? shopError : Component.translatable("nexomod.emote.earn.signIn");
			int y = stripY + STRIP / 2 - 9;
			for (FormattedCharSequence line : font.split(message, gridX1 - gridX0 - (shopError != null ? 56 : 0))) {
				graphics.text(font, line, gridX0, y, shopError != null ? NexoStyle.DANGER : NexoStyle.MUTED);
				y += 10;
			}
			return;
		}
		if (earn == null) {
			return;
		}
		int width = (gridX1 - gridX0 - 16) / 3;
		int x = gridX0;
		drawEarn(graphics, x, width, "nexomod.emote.earn.playtime", earn.playtime() + "/" + earn.playtimeCap(),
				Component.translatable("nexomod.emote.earn.playtime.sub"));
		float played = earn.playtimeCap() > 0 ? Math.min(1F, (float) earn.playtime() / earn.playtimeCap()) : 0F;
		graphics.fill(x, stripY + 13, x + width, stripY + 15, NexoStyle.CONTROL);
		graphics.fill(x, stripY + 13, x + (int) (width * played), stripY + 15, NexoStyle.ACCENT);
		x += width + 8;
		drawEarn(graphics, x, width, "nexomod.emote.earn.daily", "+" + earn.dailyCoins(),
				Component.translatable(earn.daily() ? "nexomod.emote.earn.daily.done" : "nexomod.emote.earn.daily.todo"));
		graphics.fill(x, stripY + 13, x + width, stripY + 15, earn.daily() ? NexoStyle.ACCENT : NexoStyle.CONTROL);
		x += width + 8;
		drawEarn(graphics, x, width, "nexomod.emote.earn.streak", earn.streak() + "/" + earn.streakDays(),
				Component.translatable("nexomod.emote.earn.streak.sub", earn.streakCoins(), earn.streakDays()));
		int days = Mth.clamp(earn.streakDays(), 1, 31);
		int day = (width + 2) / days;
		for (int i = 0; i < days; i++) {
			graphics.fill(x + i * day, stripY + 13, x + i * day + day - 2, stripY + 15, i < earn.streak() ? NexoStyle.ACCENT : NexoStyle.CONTROL);
		}
	}

	private void drawEarn(GuiGraphicsExtractor graphics, int x, int width, String label, String value, Component sub) {
		int valueWidth = font.width(value);
		graphics.text(font, font.plainSubstrByWidth(Component.translatable(label).getString(), width - valueWidth - 2), x, stripY + 3, NexoStyle.MUTED);
		graphics.text(font, value, x + width - valueWidth, stripY + 3, NexoStyle.TEXT_PRIMARY);
		graphics.text(font, font.plainSubstrByWidth(sub.getString(), width), x, stripY + 19, NexoStyle.MUTED);
	}

	private void drawConsent(GuiGraphicsExtractor graphics) {
		if (consentX > px0 + RAIL + 16) {
			Identifier skin = selected != null ? NexoSkinLibrary.texture(selected) : playerSkin();
			boolean slim = selected != null ? selected.slim : playerSlim();
			int cy = (py0 + py1) / 2;
			NexoShapes.glow(graphics, consentX - 96, cy + 62, consentX - 24, cy + 68, NexoStyle.ACCENT, 3, 0.14F);
			drawModel(graphics, skin, slim, null, NexoEmotes.STARTERS.get(1).pose(), 5F, -25F, consentX - 124, cy - 70, consentX + 4, cy + 70);
		}
		int y = consentY;
		for (ConsentLine line : consentLines()) {
			graphics.text(font, line.text(), consentX, y, line.color());
			y += 10 + line.after();
		}
		// What erasing the account leaves behind says it worked.
		if (status != null) {
			graphics.text(font, status, consentX, py1 - 16, statusError ? NexoStyle.DANGER : NexoStyle.MUTED);
		}
	}

	private void drawGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<Item> items = items();
		List<Placed> placed = place(items);
		if (items.isEmpty()) {
			Component message = switch (tab) {
				case SKINS -> Component.translatable(search.isBlank() ? "nexomod.wardrobe.empty.skins" : "nexomod.wardrobe.empty.search");
				case OUTFITS -> Component.translatable("nexomod.wardrobe.empty.outfits");
				case EMOTES -> Component.translatable("nexomod.wardrobe.empty.search");
				case CAPES -> capesMessage != null ? capesMessage : Component.translatable("nexomod.wardrobe.loading");
				case SHOP -> !NexoEmoteAccount.shop().isEmpty() || !search.isBlank() ? Component.translatable("nexomod.wardrobe.empty.search")
						: shopError != null ? Component.translatable("nexomod.emote.emptyOffline")
						: shopLoaded ? Component.translatable("nexomod.emote.empty") : Component.translatable("nexomod.wardrobe.loading");
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
			if (item.top() == RAINBOW) {
				NexoShapes.fillLogoRamp(graphics, x0 + 1, x1 - 1, x1 - 1, y0 + 1, y0 + 3, 0xFF);
			} else if (item.top() != 0) {
				graphics.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 3, item.top());
			}
			drawModel(graphics, item.skin().get(), item.slim(), item.cape(), item.pose(), 5F, item.back() ? 155F : -25F, x0 + 6, y0 + 4, x1 - 6, y1 - 18);
			if (item.favorite()) {
				graphics.text(font, "♥", x1 - 11, y0 + 5, 0xFFFF3CAC);
			}
			if (item.badge() != null) {
				graphics.text(font, item.badge(), x1 - 5 - font.width(item.badge()), y0 + 6, EPIC);
			}
			graphics.fill(x0 + 1, y1 - 16, x1 - 1, y1 - 15, NexoStyle.LINE);
			int subWidth = item.sub().isEmpty() ? 0 : font.width(item.sub()) + 4;
			graphics.text(font, font.plainSubstrByWidth(item.name(), x1 - x0 - 10 - subWidth), x0 + 5, y1 - 12, NexoStyle.TEXT_PRIMARY);
			if (subWidth > 0) {
				graphics.text(font, item.sub(), x1 - 4 - font.width(item.sub()), y1 - 12, item.subColor());
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
		NexoEmoteAccount.ShopItem shopItem = tab == Tab.SHOP ? currentShopItem() : null;
		if (tab == Tab.SHOP) {
			if (shopItem != null) {
				Component rarity = rarityName(shopItem.rarity());
				int rarityWidth = font.width(rarity);
				graphics.text(font, Component.literal(font.plainSubstrByWidth(shopItem.name(), previewWidth - 24 - rarityWidth)).withStyle(style -> style.withBold(true)), previewX0 + 8, py0 + 11, NexoStyle.TEXT_PRIMARY);
				graphics.text(font, rarity, px1 - 8 - rarityWidth, py0 + 11, rarityColor(shopItem.rarity()));
			}
		} else {
			String name = selected == null ? "" : font.plainSubstrByWidth(selected.name, previewWidth - 60);
			graphics.centeredText(font, Component.literal(name).withStyle(style -> style.withBold(true)), centre, py0 + 11, NexoStyle.TEXT_PRIMARY);
		}

		// A tall, narrow window must not grow the model out of its column.
		int height = Math.min(modelY1 - modelY0 - 12, previewWidth * 3 / 2);
		int floorY = modelY1 - 12;
		int modelTop = floorY - height;
		NexoShapes.glow(graphics, centre - 44, floorY - 3, centre + 44, floorY + 3, NexoStyle.ACCENT, 3, 0.14F);
		if (tab == Tab.SHOP) {
			Identifier skin = selected != null ? NexoSkinLibrary.texture(selected) : playerSkin();
			boolean slim = selected != null ? selected.slim : playerSlim();
			NexoEmotePreview preview = shopItem == null ? null : NexoEmoteAccount.preview(shopItem.id());
			drawLive(graphics, skin, slim, preview, centre, modelTop, height);
			Component offer = shopItem == null || owns(shopItem.id()) ? null : offerLine(shopItem);
			if (offer != null) {
				graphics.centeredText(font, offer, centre, py0 + HEADER + 5, EPIC);
			}
		} else if (selected != null) {
			int w = height * 2 / 3;
			drawModel(graphics, NexoSkinLibrary.texture(selected), selected.slim, capeTexture(previewCape), tab == Tab.EMOTES ? previewPose : null, rotX, rotY,
					centre - w, modelTop, centre + w, floorY);
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

		Component message = status != null || tab != Tab.SHOP ? status : shopNote(shopItem);
		if (message != null) {
			int y = py1 - 8 - 20 - 24;
			int color = statusError ? NexoStyle.DANGER : NexoStyle.MUTED;
			for (FormattedCharSequence line : font.split(message, previewWidth - 16)) {
				graphics.text(font, line, previewX0 + 8, y, color);
				y += 10;
			}
		}
	}

	/** The shop's preview: the selected emote playing on the player's skin. */
	private void drawLive(GuiGraphicsExtractor graphics, Identifier skin, boolean slim, NexoEmotePreview preview, int centre, int top, int height) {
		// Below two, the width comes out zero, which would crash the texture it is drawn into.
		if (skin == null || height < 2) {
			return;
		}
		bake();
		Model.Simple model = slim ? liveSlim : liveWide;
		// Modulo: a float of milliseconds since boot gets too coarse for smooth motion within days.
		float position = preview == null ? 0F : preview.position((Util.getMillis() % 3_600_000L) / 1000F);
		for (String part : List.of("head", "body", "right_arm", "left_arm", "right_leg", "left_leg")) {
			float[] degrees = preview == null ? new float[3] : preview.rotation(part, position);
			var bone = model.root().getChild(part);
			bone.xRot = degrees[0] * Mth.DEG_TO_RAD;
			bone.yRot = degrees[1] * Mth.DEG_TO_RAD;
			bone.zRot = degrees[2] * Mth.DEG_TO_RAD;
		}
		int w = height * 2 / 3;
		graphics.skin(model, skin, 0.97F * height / 2.125F, rotX, rotY, -1.0625F, centre - w, top, centre + w, top + height);
	}

	// --- Input ----------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || consent()) {
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
