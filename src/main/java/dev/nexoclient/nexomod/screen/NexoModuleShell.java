package dev.nexoclient.nexomod.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Matrix3x2fStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import dev.nexoclient.nexomod.hud.NexoHudLayout;

/**
 * The wide "quick settings" layout a module's settings screen takes when it
 * is opened from the right-shift menu — the "Quick settings (wide)" mockup:
 * the MOD MENU tab and a breadcrumb above one wide panel, a searchable module
 * list on the left, the module's header with its on/off switch, its settings
 * as flat rows, and a live preview of its HUD element on the right with Reset
 * and Move on screen under it.
 *
 * <p>Not a screen of its own: {@link NexoModalScreen} switches into this when
 * its parent is the right-shift menu, so every existing settings screen gets
 * the layout without being rewritten. The screen still builds its rows into
 * its layout as before; this hides the three pieces the shell replaces (the
 * title, the "Enabled" row and the Edit Layout button), stretches the rest to
 * the content column, and draws everything around them.
 */
final class NexoModuleShell {
	private static final int TOP_ROW = NexoQolOverlayScreen.TAB_HEIGHT + 6;
	private static final int HEADER_HEIGHT = 40;
	private static final int ITEM_HEIGHT = 18;
	private static final int SEARCH_HEIGHT = 16;
	private static final int PAD = 10;
	/** Room above the settings rows for the section heading. */
	private static final int CONTENT_TOP = 18;
	private static final int ROW_HEIGHT = 24;
	private static final float SMALL = 0.75F;
	private static final int DIVIDER = 0xFF1A1A21;

	/** Kept across the rebuilds that every toggle triggers, so the list doesn't jump. */
	private static int sidebarScroll;
	private static String query = "";
	private static boolean searchFocused;
	/**
	 * Set just before this shell opens the next page itself (sidebar, switch,
	 * search), so the search and scroll carry over; any other way in starts
	 * from a clear search with the shown module scrolled into view.
	 */
	private static boolean continuing;

	private final NexoModalScreen screen;
	private final NexoModules.Module module;
	private final Screen menu;
	private final List<NexoModules.Module> modules = NexoModules.all();
	/** The sidebar's rows in order: a heading (module null) or a module. */
	private final List<SidebarEntry> entries = new ArrayList<>();
	private EditBox search;
	private Component firstHeading;

	// Geometry, recomputed by layout().
	int x0, y0, x1, y1;
	private int sidebarWidth, previewWidth;

	private record SidebarEntry(Component heading, Item item) {
	}

	NexoModuleShell(NexoModalScreen screen, NexoModules.Module module, Screen menu) {
		this.screen = screen;
		this.module = module;
		this.menu = menu;
		if (!continuing) {
			query = "";
			sidebarScroll = 0;
		}
		continuing = false;
	}

	/** Panel bounds and column widths for a window of this size. */
	void layout(int width, int height) {
		int panelWidth = Math.min(width - 24, 820);
		int panelHeight = Math.min(height - 24 - TOP_ROW, 400);
		x0 = (width - panelWidth) / 2;
		x1 = x0 + panelWidth;
		y0 = (height - panelHeight + TOP_ROW) / 2;
		y1 = y0 + panelHeight;
		sidebarWidth = panelWidth < 480 ? 120 : 170;
		// The preview is the first thing to go on a narrow window: the settings
		// need the room more than a picture of the HUD does.
		previewWidth = panelWidth - sidebarWidth < 420 ? 0 : 190;
	}

	int contentX0() {
		return x0 + sidebarWidth;
	}

	int contentX1() {
		return x1 - previewWidth;
	}

	/** Width every settings row is stretched to. */
	int rowWidth() {
		return contentX1() - contentX0() - PAD * 2 - 8;
	}

	int contentTop() {
		return y0 + HEADER_HEIGHT + CONTENT_TOP;
	}

	int contentHeight() {
		return Math.max(40, y1 - 8 - contentTop());
	}

	/**
	 * Hides what the shell replaces and turns the rest into flat full-width
	 * rows. Direct children only: a nested row of buttons keeps its own layout.
	 */
	void prepare(LinearLayout content) {
		String title = screen.getTitle().getString();
		boolean[] first = {true};
		boolean[] shownAny = {false};
		content.visitChildren(element -> {
			boolean isFirst = first[0];
			first[0] = false;
			if (!(element instanceof AbstractWidget widget)) {
				return;
			}
			String key = NexoModules.keyOf(widget.getMessage());
			boolean replaced = (isFirst && widget instanceof StringWidget && widget.getMessage().getString().equals(title))
					|| (widget instanceof NexoModuleRow && "nexomod.qol.enabled".equals(key))
					|| (widget instanceof AbstractButton && "nexomod.qol.editLayout".equals(key));
			if (replaced) {
				widget.visible = false;
				widget.active = false;
				widget.setSize(0, 0);
				return;
			}
			// A heading opening the list names the first section in the drawn
			// heading slot above the rows, instead of taking a row of its own.
			if (widget instanceof NexoSectionHeading heading && !shownAny[0]) {
				firstHeading = heading.getMessage();
				widget.visible = false;
				widget.setSize(0, 0);
				return;
			}
			shownAny[0] = true;
			widget.setWidth(rowWidth());
			boolean row = widget instanceof CycleButton<?> || widget instanceof AbstractSliderButton
					|| widget instanceof NexoModuleRow
					|| (widget instanceof Button.Plain && NexoRowRenderer.parse(widget.getMessage()) != null);
			if (row) {
				NexoRowRenderer.markFlat(widget);
				if (widget.getHeight() == 20) {
					widget.setHeight(ROW_HEIGHT);
				}
			}
		});
	}

	/** Registers the shell's own widgets: tab, close, sidebar, search, switch, preview buttons. */
	void addWidgets() {
		Font font = Minecraft.getInstance().font;
		screen.shellWidget(new MenuTab(font));
		screen.shellWidget(NexoIconButton.close(NexoQolOverlayScreen.TAB_HEIGHT, Component.translatable("gui.done"),
				() -> Minecraft.getInstance().gui.setScreen(null))).setPosition(x1 - NexoQolOverlayScreen.TAB_HEIGHT, y0 - TOP_ROW);

		search = new EditBox(font, sidebarWidth - PAD * 2 - 12, SEARCH_HEIGHT - 6, Component.translatable("nexomod.qol.search"));
		search.setHint(Component.translatable("nexomod.qol.search"));
		search.setBordered(false);
		search.setValue(query);
		search.setResponder(text -> {
			if (!text.equals(query)) {
				query = text;
				sidebarScroll = 0;
				searchFocused = true;
				continuing = true;
				screen.shellRebuild();
			}
		});
		screen.shellWidget(search);
		if (searchFocused) {
			screen.shellFocus(search);
			searchFocused = false;
		}

		String needle = query.strip().toLowerCase(Locale.ROOT);
		boolean tacticalHeading = false;
		entries.add(new SidebarEntry(Component.translatable("nexomod.shell.modules"), null));
		for (NexoModules.Module m : modules) {
			if (!needle.isEmpty() && !m.name().getString().toLowerCase(Locale.ROOT).contains(needle)) {
				continue;
			}
			if (m.tactical() && !tacticalHeading) {
				tacticalHeading = true;
				entries.add(new SidebarEntry(Component.translatable("nexomod.qol.card.tactical"), null));
			}
			entries.add(new SidebarEntry(null, screen.shellWidget(new Item(m, m.key() != null && m.key().equals(module.key())))));
		}
		if (sidebarScroll == 0) {
			// Opened fresh: bring the module being shown into view.
			int index = 0;
			for (SidebarEntry entry : entries) {
				if (entry.item() != null && entry.item().selected) {
					sidebarScroll = Math.max(0, index * ITEM_HEIGHT - listHeight() / 2);
				}
				index++;
			}
		}

		screen.shellWidget(new EnabledSwitch());
		if (module.hud() != null && previewWidth > 0) {
			int half = (previewWidth - PAD * 2 - 6) / 3;
			screen.shellWidget(NexoButton.builder(Component.translatable("nexomod.shell.reset"),
							() -> NexoHudLayout.get().reset(module.hud().element()))
					.size(half, 18).build()).setPosition(x1 - previewWidth + PAD, y1 - PAD - 18);
			screen.shellWidget(NexoButton.builder(Component.translatable("nexomod.shell.move"),
							() -> Minecraft.getInstance().gui.setScreen(new NexoHudEditorScreen(screen)))
					.size(previewWidth - PAD * 2 - half - 6, 18).build())
					.setPosition(x1 - previewWidth + PAD + half + 6, y1 - PAD - 18);
		}
		placeSidebar();
	}

	private int listTop() {
		return y0 + PAD + SEARCH_HEIGHT + 6;
	}

	private int listHeight() {
		return y1 - PAD - listTop();
	}

	/** Positions the sidebar rows for the current scroll; rows outside the list are hidden. */
	private void placeSidebar() {
		int maxScroll = Math.max(0, entries.size() * ITEM_HEIGHT - listHeight());
		sidebarScroll = Math.clamp(sidebarScroll, 0, maxScroll);
		search.setPosition(x0 + PAD + 6, y0 + PAD + 4);
		int y = listTop() - sidebarScroll;
		for (SidebarEntry entry : entries) {
			if (entry.item() != null) {
				Item item = entry.item();
				item.setPosition(x0 + PAD - 2, y);
				item.setWidth(sidebarWidth - PAD * 2 + 4);
				item.visible = y >= listTop() && y + ITEM_HEIGHT <= listTop() + listHeight();
			}
			y += ITEM_HEIGHT;
		}
	}

	boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (mouseX < x0 || mouseX >= x0 + sidebarWidth || mouseY < listTop() || mouseY >= y1) {
			return false;
		}
		sidebarScroll -= (int) Math.round(scrollY * ITEM_HEIGHT * 2);
		placeSidebar();
		return true;
	}

	/** Everything that isn't a widget: panel, columns, header text, section heading, preview. */
	void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		NexoPanelRenderer.draw(graphics, x0, y0, x1, y1);

		// Breadcrumb after the MOD MENU tab.
		int tabRight = x0 + NexoQolOverlayScreen.tabWidth(font);
		int crumbY = y0 - TOP_ROW + (NexoQolOverlayScreen.TAB_HEIGHT - 8) / 2 + 1;
		graphics.text(font, "›", tabRight + 8, crumbY, NexoStyle.TEXT_DISABLED);
		graphics.text(font, module.name().copy().withStyle(s -> s.withBold(true)).getString().toUpperCase(Locale.ROOT),
				tabRight + 18, crumbY, NexoStyle.TEXT_SECONDARY);

		// Columns.
		graphics.fill(contentX0(), y0 + 1, contentX0() + 1, y1 - 1, DIVIDER);
		if (previewWidth > 0) {
			graphics.fill(contentX1(), y0 + 1, contentX1() + 1, y1 - 1, DIVIDER);
		}
		graphics.fill(contentX0() + 1, y0 + HEADER_HEIGHT, contentX1(), y0 + HEADER_HEIGHT + 1, DIVIDER);

		drawSidebar(graphics, font);
		drawHeader(graphics, font);

		small(graphics, font, (firstHeading != null ? firstHeading : Component.translatable("nexomod.shell.settings")).getString()
				.toUpperCase(Locale.ROOT), contentX0() + PAD + 4, y0 + HEADER_HEIGHT + 8, NexoStyle.ACCENT, true);

		if (previewWidth > 0) {
			drawPreview(graphics, mc, font);
		}
	}

	private void drawSidebar(GuiGraphicsExtractor graphics, Font font) {
		int sx0 = x0 + PAD;
		int sy0 = y0 + PAD;
		NexoShapes.outlined(graphics, sx0, sy0, x0 + sidebarWidth - PAD, sy0 + SEARCH_HEIGHT, NexoStyle.FIELD,
				search.isFocused() ? NexoStyle.mix(NexoStyle.CONTROL, NexoStyle.ACCENT, 0.7F) : NexoStyle.CONTROL, 3);
		int y = listTop() - sidebarScroll;
		for (SidebarEntry entry : entries) {
			if (entry.heading() != null && y >= listTop() && y + ITEM_HEIGHT <= listTop() + listHeight()) {
				small(graphics, font, entry.heading().getString().toUpperCase(Locale.ROOT), sx0 + 2, y + 7,
						0xFF6B6B78, true);
			}
			y += ITEM_HEIGHT;
		}
	}

	private void drawHeader(GuiGraphicsExtractor graphics, Font font) {
		int hx = contentX0() + PAD + 4;
		int hy = y0 + 9;
		NexoShapes.outlined(graphics, hx, hy, hx + 22, hy + 22, 0xFF16161C, NexoStyle.CONTROL, 4);
		graphics.item(NexoModuleIcons.forName(module.name()), hx + 3, hy + 3);

		int tx = hx + 30;
		int maxName = contentX1() - PAD - 90 - tx - 40;
		String shownName = ellipsize(font, module.name().getString(), maxName);
		Component name = Component.literal(shownName).withStyle(s -> s.withBold(true));
		graphics.text(font, name, tx, hy + 2, NexoStyle.TEXT_PRIMARY);
		Component tag = Component.translatable(module.tactical() ? "nexomod.qol.card.tactical" : "nexomod.shell.legit");
		int tagX = tx + font.width(name) + 6;
		int tagWidth = Math.round(font.width(tag) * SMALL) + 8;
		NexoShapes.fillRoundedFast(graphics, tagX, hy + 1, tagX + tagWidth, hy + 11, NexoStyle.LINE, 2);
		small(graphics, font, tag.getString(), tagX + 4, hy + 3, NexoStyle.MUTED, true);

		int maxDescription = contentX1() - PAD - 90 - tx;
		small(graphics, font, ellipsize(font, module.description().getString(), Math.round(maxDescription / SMALL)),
				tx, hy + 14, NexoStyle.MUTED, false);
	}

	private void drawPreview(GuiGraphicsExtractor graphics, Minecraft mc, Font font) {
		int px0 = contentX1() + PAD;
		int px1 = x1 - PAD;
		small(graphics, font, Component.translatable("nexomod.shell.preview").getString().toUpperCase(Locale.ROOT),
				px0, y0 + PAD + 2, NexoStyle.MUTED, true);
		int bx0 = px0;
		int by0 = y0 + PAD + 14;
		int bx1 = px1;
		int by1 = module.hud() != null ? y1 - PAD - 18 - 8 : y1 - PAD;
		NexoShapes.fillRoundedGradient(graphics, bx0, by0, bx1, by1, 0xFF1C2635, 0xFF101519, 4);

		NexoModules.Hud hud = module.hud();
		Component hint = null;
		if (hud == null) {
			// Nothing on the HUD to show: the module's icon stands in for it.
			Matrix3x2fStack pose = graphics.pose();
			pose.pushMatrix();
			pose.translate((bx0 + bx1) / 2F - 24, (by0 + by1) / 2F - 30);
			pose.scale(3, 3);
			graphics.item(NexoModuleIcons.forName(module.name()), 0, 0);
			pose.popMatrix();
			hint = Component.translatable("nexomod.shell.noHud");
		} else if (mc.player == null) {
			hint = Component.translatable("nexomod.shell.previewNoWorld");
		} else if (!module.enabled().getAsBoolean()) {
			hint = Component.translatable("nexomod.shell.previewOff");
		} else if (mc.gui.hud.isHidden() || dev.nexoclient.nexomod.hud.NexoHudVisibility.hidden()) {
			hint = Component.translatable("nexomod.shell.previewHidden");
		} else {
			drawHud(graphics, mc, hud, bx0 + 6, by0 + 6, bx1 - 6, by1 - 6);
		}
		if (hint != null) {
			List<net.minecraft.util.FormattedCharSequence> lines = font.split(hint, Math.round((bx1 - bx0 - 16) / SMALL));
			Matrix3x2fStack pose = graphics.pose();
			pose.pushMatrix();
			pose.scale(SMALL, SMALL);
			int ly = Math.round((hud == null ? by1 - 12 - lines.size() * 9 * SMALL : (by0 + by1) / 2F - lines.size() * 4) / SMALL);
			for (net.minecraft.util.FormattedCharSequence line : lines) {
				graphics.text(font, line, Math.round(((bx0 + bx1) / 2F) / SMALL) - font.width(line) / 2, ly, NexoStyle.MUTED);
				ly += 9;
			}
			pose.popMatrix();
		}
	}

	/**
	 * The module's real HUD element, drawn by its own renderer into the preview
	 * box: framed around where it sits on screen, then scaled down to fit. It is
	 * the live element — moving, enabling or restyling it shows here at once.
	 */
	private void drawHud(GuiGraphicsExtractor graphics, Minecraft mc, NexoModules.Hud hud, int bx0, int by0, int bx1, int by1) {
		ScreenRectangle bounds = hud.bounds().apply(screen.width, screen.height);
		if (bounds.width() <= 0 || bounds.height() <= 0) {
			return;
		}
		float scale = Math.min(1.5F, Math.min((bx1 - bx0) / (float) bounds.width(), (by1 - by0) / (float) bounds.height()));
		float cx = (bx0 + bx1) / 2F;
		float cy = (by0 + by1) / 2F;
		graphics.enableScissor(bx0, by0, bx1, by1);
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(cx - (bounds.left() + bounds.width() / 2F) * scale, cy - (bounds.top() + bounds.height() / 2F) * scale);
		pose.scale(scale, scale);
		try {
			hud.renderer().extractRenderState(graphics, mc.getDeltaTracker());
		} finally {
			pose.popMatrix();
			graphics.disableScissor();
		}
	}

	private static void small(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color, boolean bold) {
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.scale(SMALL, SMALL);
		Component line = bold ? Component.literal(text).withStyle(s -> s.withBold(true)) : Component.literal(text);
		graphics.text(font, line, Math.round(x / SMALL), Math.round(y / SMALL), color);
		pose.popMatrix();
	}

	private static String ellipsize(Font font, String text, int maxPx) {
		if (maxPx <= 0) {
			return "";
		}
		if (font.width(text) <= maxPx) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, maxPx - font.width("…"))).stripTrailing() + "…";
	}

	/** Opens {@code target}'s page in the same layout, returning to the same menu. */
	private void open(NexoModules.Module target) {
		Screen next = target.config() != null ? target.config().apply(menu) : new NexoModuleInfoScreen(target, menu);
		continuing = true;
		Minecraft.getInstance().gui.setScreen(next);
	}

	/** The rainbow MOD MENU tab: back to the menu. */
	private final class MenuTab extends AbstractButton {
		MenuTab(Font font) {
			super(x0, y0 - TOP_ROW, NexoQolOverlayScreen.tabWidth(font), NexoQolOverlayScreen.TAB_HEIGHT,
					Component.translatable("nexomod.qol.modMenu"));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			Minecraft.getInstance().gui.setScreen(menu);
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			NexoQolOverlayScreen.drawModMenuTab(graphics, Minecraft.getInstance().font, getX(), getY());
			if (isHoveredOrFocused()) {
				graphics.fill(getX(), getY() + getHeight() + 1, getX() + getWidth(), getY() + getHeight() + 2, NexoStyle.ACCENT);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	/** One module in the sidebar: its item icon, name, and a dot that is lit while it's on. */
	private final class Item extends AbstractButton {
		private final NexoModules.Module target;
		private final boolean selected;
		private final ItemStack icon;

		Item(NexoModules.Module target, boolean selected) {
			super(0, 0, 100, ITEM_HEIGHT, target.name());
			this.target = target;
			this.selected = selected;
			this.icon = NexoModuleIcons.forName(target.name());
		}

		@Override
		public void onPress(InputWithModifiers input) {
			if (!selected) {
				open(target);
			}
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			Font font = Minecraft.getInstance().font;
			int ix0 = getX();
			int iy0 = getY();
			int ix1 = ix0 + getWidth();
			int iy1 = iy0 + getHeight() - 1;
			boolean on = target.enabled().getAsBoolean();
			if (selected) {
				NexoShapes.outlined(graphics, ix0, iy0, ix1, iy1, NexoStyle.mix(NexoStyle.PANEL, NexoStyle.ACCENT, 0.16F),
						NexoStyle.ACCENT, 4);
			} else if (isHoveredOrFocused()) {
				NexoShapes.fillRoundedFast(graphics, ix0, iy0, ix1, iy1, NexoStyle.fade(NexoStyle.ACCENT, 0.08F), 4);
			}
			Matrix3x2fStack pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(ix0 + 3, iy0 + 2.5F);
			pose.scale(0.75F, 0.75F);
			graphics.item(icon, 0, 0);
			pose.popMatrix();
			int dotX = ix1 - 8;
			int nameColor = selected ? NexoStyle.TEXT_PRIMARY : on ? NexoStyle.TEXT_SECONDARY : NexoStyle.MUTED;
			graphics.text(font, ellipsize(font, target.name().getString(), dotX - 6 - (ix0 + 18)), ix0 + 18,
					iy0 + (ITEM_HEIGHT - 8) / 2, nameColor);
			int dy = iy0 + ITEM_HEIGHT / 2 - 2;
			if (on) {
				NexoShapes.glow(graphics, dotX, dy, dotX + 4, dy + 4, NexoStyle.ACCENT, 2, 0.3F);
			}
			NexoShapes.fillRounded(graphics, dotX, dy, dotX + 4, dy + 4, on ? NexoStyle.ACCENT : 0xFF2A2A34, 2);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	/** The header's ENABLED / DISABLED switch for the module itself. */
	private final class EnabledSwitch extends AbstractButton {
		private static final int WIDTH = 86;

		EnabledSwitch() {
			super(contentX1() - PAD - WIDTH, y0 + 11, WIDTH, 18, Component.translatable("nexomod.qol.enabled"));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			module.toggle().run();
			// Rebuilt rather than redrawn: several screens show or hide rows by state.
			open(module);
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			Font font = Minecraft.getInstance().font;
			boolean on = module.enabled().getAsBoolean();
			int sx0 = getX();
			int sy0 = getY();
			int sx1 = sx0 + getWidth();
			int sy1 = sy0 + getHeight();
			if (on) {
				NexoShapes.glow(graphics, sx0, sy0, sx1, sy1, NexoStyle.ACCENT, 4, isHoveredOrFocused() ? 0.26F : 0.18F);
			}
			NexoShapes.outlined(graphics, sx0, sy0, sx1, sy1,
					on ? NexoStyle.mix(NexoStyle.PANEL, NexoStyle.ACCENT, 0.16F) : NexoStyle.FIELD,
					on ? NexoStyle.ACCENT : isHoveredOrFocused() ? NexoStyle.mix(NexoStyle.CONTROL, NexoStyle.ACCENT, 0.6F) : NexoStyle.CONTROL, 4);
			Component label = Component.translatable(on ? "nexomod.qol.card.enabled" : "nexomod.qol.card.disabled");
			small(graphics, font, label.getString(), sx0 + 7, sy0 + 6, on ? NexoStyle.TEXT_PRIMARY : NexoStyle.MUTED, true);
			int tx1 = sx1 - 4;
			int tx0 = tx1 - 22;
			int ty0 = sy0 + 4;
			int ty1 = sy1 - 4;
			NexoShapes.outlined(graphics, tx0, ty0, tx1, ty1,
					on ? NexoStyle.mix(0xFF0A0A0D, NexoStyle.ACCENT, 0.3F) : 0xFF16161C, on ? NexoStyle.ACCENT : 0xFF2A2A34, 5);
			int knob = ty1 - ty0 - 4;
			int kx = on ? tx1 - 2 - knob : tx0 + 2;
			NexoShapes.fillRounded(graphics, kx, ty0 + 2, kx + knob, ty1 - 2, on ? NexoStyle.TEXT_PRIMARY : 0xFF5A5A68, knob / 2);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
					Component.translatable(module.enabled().getAsBoolean() ? "nexomod.qol.card.enabled" : "nexomod.qol.card.disabled"));
		}
	}
}
