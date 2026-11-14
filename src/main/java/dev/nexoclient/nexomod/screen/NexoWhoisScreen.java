package dev.nexoclient.nexomod.screen;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.wardrobe.NexoSkinLibrary;
import dev.nexoclient.nexomod.whois.NexoWhois;

/** The {@code /whois} menu: who the player is, every name they have had, every skin they have worn. */
public class NexoWhoisScreen extends Screen {
	private static final int HEADER_H = 76;
	private static final int PAD = 14;
	private static final int NAMES_W = 168;
	private static final int TILE_W = 84;
	private static final int TILE_H = 94;
	private static final int FIGURE_SCALE = 2;

	private final Screen parent;
	private final NexoWhois.Profile profile;
	/** Skin hash to its registered texture; null when that skin's file could not be read. */
	private final Map<NexoWhois.Skin, Identifier> textures = new HashMap<>();
	private boolean loaded;
	private int scroll;
	private int maxScroll;

	public NexoWhoisScreen(Screen parent, NexoWhois.Profile profile) {
		super(Component.literal(profile.name()));
		this.parent = parent;
		this.profile = profile;
	}

	@Override
	protected void init() {
		if (!loaded) {
			loaded = true;
			for (int i = 0; i < profile.skins().size(); i++) {
				NexoWhois.Skin skin = profile.skins().get(i);
				try {
					// Named from the image itself, never from server text; released in removed().
					Identifier id = Identifier.fromNamespaceAndPath("nexomod", "whois/" + i + "_" + Integer.toHexString(java.util.Arrays.hashCode(skin.png())));
					textures.put(skin, NexoSkinLibrary.registerSkin(id, skin.png()));
				} catch (java.io.IOException | RuntimeException e) {
					NexoMod.LOGGER.warn("[nexomod] Whois: could not load a skin.", e);
				}
			}
		}
		int bottom = height - 12;
		int cx = width / 2;
		addRenderableWidget(Button.builder(Component.literal("Copy UUID"), b -> minecraft.keyboardHandler.setClipboard(profile.uuid()))
				.bounds(cx - 154, bottom - 24, 150, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(cx + 4, bottom - 24, 150, 20).build());
	}

	private int panelW() {
		return Math.min(width - 24, 620);
	}

	private int panelX() {
		return (width - panelW()) / 2;
	}

	private int bodyTop() {
		return 12 + HEADER_H + 10;
	}

	private int bodyBottom() {
		return height - 12 - 24 - 8;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x0 = panelX();
		int x1 = x0 + panelW();
		NexoPanelRenderer.draw(g, x0, 12, x1, height - 12);

		// Header card.
		NexoShapes.outlined(g, x0 + 8, 20, x1 - 8, 12 + HEADER_H, NexoStyle.RAISED, NexoStyle.CONTROL, 8);
		NexoWhois.Skin current = profile.skins().isEmpty() ? null : profile.skins().get(0);
		if (current != null) {
			figure(g, textures.get(current), current.slim(), x0 + 20, 26, FIGURE_SCALE);
		}
		int tx = x0 + 20 + 16 * FIGURE_SCALE + 14;
		g.text(font, profile.name(), tx, 28, NexoStyle.TEXT_PRIMARY);
		String tier = NexoWhois.tier(profile.rarity());
		if (tier != null) {
			int w = font.width(tier) + 10;
			int bx = x1 - 20 - w;
			NexoShapes.outlined(g, bx, 26, bx + w, 39, NexoStyle.mix(NexoStyle.RAISED, NexoStyle.ACCENT, 0.25F), NexoStyle.ACCENT, 4);
			g.text(font, tier, bx + 5, 29, NexoStyle.TEXT_PRIMARY);
		}
		g.text(font, font.plainSubstrByWidth("UUID  " + profile.uuid(), x1 - 20 - tx), tx, 44, NexoStyle.TEXT_SECONDARY);
		g.text(font, "First seen  " + NexoWhois.date(profile.created()) + "    Name changes  " + profile.nameChanges(), tx, 56, NexoStyle.MUTED);
		g.text(font, profile.skins().size() + " skins    " + profile.capes().size() + " capes", tx, 68, NexoStyle.MUTED);

		// Scrolling body.
		int top = bodyTop();
		int bottom = bodyBottom();
		g.enableScissor(x0, top, x1, bottom);
		int leftX = x0 + PAD;
		int y = top - scroll;
		int namesBottom = drawNames(g, leftX, y);
		int skinsX = leftX + NAMES_W + PAD;
		int skinsBottom = drawSkins(g, skinsX, y, x1 - PAD - skinsX);
		g.disableScissor();
		maxScroll = Math.max(0, Math.max(namesBottom, skinsBottom) + scroll - bottom + 6);
		scroll = Mth.clamp(scroll, 0, maxScroll);

		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	private int drawNames(GuiGraphicsExtractor g, int x, int y) {
		g.text(font, "Username history", x, y, NexoStyle.MUTED);
		y += 14;
		for (NexoWhois.NameEntry n : profile.names()) {
			NexoShapes.outlined(g, x, y, x + NAMES_W, y + 20, NexoStyle.RAISED, NexoStyle.CONTROL, 5);
			g.text(font, font.plainSubstrByWidth(n.name(), NAMES_W - 80), x + 7, y + 6, NexoStyle.TEXT_PRIMARY);
			String date = NexoWhois.date(n.changedAt());
			g.text(font, date, x + NAMES_W - 7 - font.width(date), y + 6, NexoStyle.MUTED);
			y += 24;
		}
		if (!profile.capes().isEmpty()) {
			y += 6;
			g.text(font, "Capes", x, y, NexoStyle.MUTED);
			y += 14;
			for (NexoWhois.Cape c : profile.capes()) {
				boolean worn = c.current();
				NexoShapes.outlined(g, x, y, x + NAMES_W, y + 20, worn ? NexoStyle.mix(NexoStyle.RAISED, NexoStyle.ACCENT, 0.2F) : NexoStyle.RAISED,
						worn ? NexoStyle.ACCENT : NexoStyle.CONTROL, 5);
				g.text(font, font.plainSubstrByWidth(c.name(), NAMES_W - 14), x + 7, y + 6, NexoStyle.TEXT_PRIMARY);
				y += 24;
			}
		}
		return y;
	}

	private int drawSkins(GuiGraphicsExtractor g, int x, int y, int width) {
		g.text(font, "Skin history", x, y, NexoStyle.MUTED);
		y += 14;
		int columns = Math.max(1, (width + 6) / (TILE_W + 6));
		int i = 0;
		for (NexoWhois.Skin skin : profile.skins()) {
			int tx = x + (i % columns) * (TILE_W + 6);
			int ty = y + (i / columns) * (TILE_H + 6);
			boolean now = i == 0;
			NexoShapes.outlined(g, tx, ty, tx + TILE_W, ty + TILE_H, now ? NexoStyle.mix(NexoStyle.RAISED, NexoStyle.ACCENT, 0.15F) : NexoStyle.RAISED,
					now ? NexoStyle.ACCENT : NexoStyle.CONTROL, 6);
			figure(g, textures.get(skin), skin.slim(), tx + (TILE_W - 16 * FIGURE_SCALE) / 2, ty + 8, FIGURE_SCALE);
			g.centeredText(font, now ? "Current" : NexoWhois.date(skin.changedAt()), tx + TILE_W / 2, ty + TILE_H - 16, now ? NexoStyle.TEXT_ACTIVE_ACCENT : NexoStyle.MUTED);
			i++;
		}
		int rows = (i + columns - 1) / columns;
		return y + rows * (TILE_H + 6);
	}

	/** A front view built from the skin's own pixels: head and hat, body, both arms, both legs. */
	private static void figure(GuiGraphicsExtractor g, Identifier id, boolean slim, int x, int y, int s) {
		if (id == null) {
			NexoShapes.outlined(g, x + 4 * s, y, x + 12 * s, y + 32 * s, NexoStyle.FIELD, NexoStyle.CONTROL, 2);
			return;
		}
		int arm = slim ? 3 : 4;
		part(g, id, x, y, s, 4, 0, 8, 8, 8, 8);
		part(g, id, x, y, s, 4, 0, 8, 8, 40, 8);
		part(g, id, x, y, s, 4, 8, 8, 12, 20, 20);
		part(g, id, x, y, s, 4 - arm, 8, arm, 12, 44, 20);
		part(g, id, x, y, s, 12, 8, arm, 12, 36, 52);
		part(g, id, x, y, s, 4, 20, 4, 12, 4, 20);
		part(g, id, x, y, s, 8, 20, 4, 12, 20, 52);
	}

	private static void part(GuiGraphicsExtractor g, Identifier id, int x, int y, int s, int px, int py, int w, int h, int u, int v) {
		g.blit(RenderPipelines.GUI_TEXTURED, id, x + px * s, y + py * s, (float) u, (float) v, w * s, h * s, w, h, 64, 64);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll = Mth.clamp(scroll - (int) (scrollY * 18), 0, maxScroll);
		return true;
	}

	@Override
	public void removed() {
		textures.values().forEach(minecraft.getTextureManager()::release);
		textures.clear();
		loaded = false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
