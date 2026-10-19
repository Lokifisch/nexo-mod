package dev.nexoclient.nexomod.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.joml.Matrix3x2fStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.hud.NexoPotatoMode;
import dev.nexoclient.nexomod.hud.NexoQolMenu;
import dev.nexoclient.nexomod.norender.NexoNoRender;
import dev.nexoclient.nexomod.norender.NexoNoRenderConfig;

/**
 * The right-shift QoL menu: a small floating panel of feature toggles over
 * live gameplay, not a full menu screen.
 *
 * <p>Three things set this apart from every other Nexo popup:
 * <ul>
 *   <li>{@link #isPauseScreen()} is false — the world keeps ticking behind
 *   it, unlike a normal pause-adjacent screen.</li>
 *   <li>{@code extractBackground} is deliberately <em>not</em> overridden —
 *   vanilla's own default (blur, then its usual dark tint, the same
 *   treatment the in-game Options screen gets) is exactly the "blurred, not
 *   replaced by anything" look this is going for. Not a
 *   {@link NexoModalScreen}: that dims behind a parent screen it redraws,
 *   which is a different mechanism producing a different effect.</li>
 *   <li>This class is on {@code NeonMenuBackgroundMixin}'s deny-list, so the
 *   neon reskin's animated background does not replace vanilla's blur
 *   here — the whole point is the real (blurred) game showing through.</li>
 * </ul>
 *
 * <p>The panel background is drawn ahead of {@code super.extractRenderState}
 * in the same order {@link NexoModalScreen} uses: {@link NexoPanelRenderer}
 * first, then this screen's own header/footer chrome, then the buttons on
 * top of both. The chrome is drawn here rather than folded into
 * {@code NexoPanelRenderer} on purpose — that renderer is shared with the
 * account switcher and the sign-in popups, which want a plain panel, not a
 * branded one.
 *
 * <p>Nothing in the chrome is a texture: the wordmark, the corner brackets,
 * the scanlines and the sweeping separator are all {@code fill} calls off
 * wall-clock time, so they animate on a screen that never pauses the game
 * and cost no asset to ship.
 */
public class NexoQolOverlayScreen extends Screen {
	private static final int ROW_WIDTH = 220;
	/**
	 * Absolute floor for the adaptive cell width. Only a sanity backstop — at every
	 * real window size the fit calculation lands above it, including the 320×240
	 * minimum, so it never binds and never forces the panel wider than the screen.
	 */
	private static final int MIN_ROW_WIDTH = 100;
	private static final int ROW_HEIGHT = 36;
	private static final int COLUMNS = 2;
	/** Space ScrollableLayout reserves for a scrollbar on each side of its content. */
	private static final int SCROLLBAR_GUTTERS = 28;
	/** Vertical gap the grid leaves under each cell; part of a row's pitch. */
	private static final int CELL_PADDING = 4;
	/** Distance from one grid row's top to the next — a whole number of these is always shown. */
	private static final int ROW_PITCH = ROW_HEIGHT + CELL_PADDING;
	/** Grid rows shown at once — {@code COLUMNS} modules each. Past this the list scrolls. */
	private static final int VISIBLE_ROWS = 6;
	private static final int PANEL_PAD_X = 20;
	private static final int PANEL_PAD_Y = 14;
	/** Room above the list for the wordmark, the active-count badge and the separator. */
	private static final int HEADER_HEIGHT = 36;
	/** Room below the list for the close hint. */
	private static final int FOOTER_HEIGHT = 15;
	private static final int EDIT_BUTTON_HEIGHT = 20;
	/** Same band as the active-count badge, so the search box sits level with it and the wordmark. */
	private static final int SEARCH_HEIGHT = 13;
	private static final int SEARCH_TOP = 11;
	private static final float WORDMARK_SCALE = 1.5F;
	private static final float SMALL_TEXT_SCALE = 0.75F;

	/**
	 * Outer column: the scrolling module list, then the Edit Layout button. Both
	 * layouts are rebuilt in every {@code init()} rather than kept — {@code init()}
	 * re-runs on each resize and a LinearLayout cannot drop what it already holds,
	 * so a reused one would end up with two of every row.
	 */
	private LinearLayout layout;
	/** The scrolling part — a {@link #COLUMNS}-wide grid of modules. */
	private GridLayout moduleGrid;
	private GridLayout.RowHelper moduleRows;
	/** Every module's live on/off state, in row order, for the header's active count. */
	private final List<BooleanSupplier> moduleStates = new ArrayList<>();

	private ScrollableLayout scrollArea;
	/** Kept across the rebuilds typing triggers, so the filter survives them. */
	private String query = "";
	private EditBox searchBox;
	private int rowCount;
	/** Modules passing the search filter; drives the list height, since {@code rowCount} also staggers. */
	private int matchCount;
	/** Set after the first init: the swish-in plays when the menu opens, not on every search or resize. */
	private boolean entrancePlayed;
	private static final long FADE_OUT_MILLIS = 160L;
	/** The rows currently on screen, so the next rebuild can tell which ones the filter dropped. */
	private final List<NexoModuleRow> shownRows = new ArrayList<>();
	/** Filtered-out rows kept only to fade away where they were; not part of the layout or input. */
	private final List<Ghost> ghosts = new ArrayList<>();

	private record Ghost(NexoModuleRow row, long startMillis) {}
	/**
	 * The panel's top and bottom as drawn, easing toward where the layout says they
	 * belong. Search rebuilds the list at its final size at once; animating only the
	 * panel (and clipping the content to it) avoids touching ScrollableLayout, whose
	 * scroll range goes negative if the viewport ever outgrows its content.
	 */
	private float shownTop = Float.NaN;
	private float shownBottom;
	private long lastFrameNanos;

	public NexoQolOverlayScreen() {
		super(Component.translatable("nexomod.qol.title"));
	}

	@Override
	protected void init() {
		moduleStates.clear();
		rowCount = 0;
		matchCount = 0;
		List<NexoModuleRow> previousRows = new ArrayList<>(shownRows);
		shownRows.clear();

		layout = LinearLayout.vertical().spacing(8);
		layout.defaultCellSetting().alignHorizontallyCenter();
		searchBox = new EditBox(minecraft.font, 60, SEARCH_HEIGHT, Component.translatable("nexomod.qol.search"));
		searchBox.setHint(Component.translatable("nexomod.qol.search"));
		searchBox.setValue(query);
		searchBox.setResponder(text -> {
			if (!text.equals(query)) {
				query = text;
				rebuildWidgets();
			}
		});
		moduleGrid = new GridLayout();
		moduleGrid.defaultCellSetting().paddingHorizontal(CELL_PADDING).paddingBottom(CELL_PADDING);
		moduleRows = moduleGrid.createRowHelper(COLUMNS);

		NexoConfig config = NexoConfig.get();
		addRow(Component.translatable("nexomod.qol.keystrokes"),
				Component.translatable("nexomod.qol.keystrokes.description"),
				config::keystrokesHudEnabled,
				() -> config.setKeystrokesHudEnabled(!config.keystrokesHudEnabled()),
				() -> minecraft.gui.setScreen(new NexoKeystrokesConfigScreen(this)));
		addRow(Component.translatable("nexomod.qol.cps"),
				Component.translatable("nexomod.qol.cps.description"),
				config::cpsCounterEnabled,
				() -> config.setCpsCounterEnabled(!config.cpsCounterEnabled()),
				() -> minecraft.gui.setScreen(new NexoCpsConfigScreen(this)));
		addRow(Component.translatable("nexomod.settings.armorHud.enabled"),
				Component.translatable("nexomod.qol.armorHud.description"),
				config::armorHudEnabled,
				() -> config.setArmorHudEnabled(!config.armorHudEnabled()),
				() -> minecraft.gui.setScreen(new NexoArmorHudConfigScreen(this)));
		// Directly under the Armor HUD row on purpose: the two names are one word
		// apart and the only way to tell them apart is seeing them side by side.
		addRow(Component.translatable("nexomod.settings.armorBar.enabled"),
				Component.translatable("nexomod.qol.armorBar.description"),
				config::armorBarEnabled,
				() -> config.setArmorBarEnabled(!config.armorBarEnabled()),
				() -> minecraft.gui.setScreen(new NexoArmorBarConfigScreen(this)));
		// Toggles directly on click rather than opening a config screen — there is
		// nothing to configure, and a quick single click is the point before AFK.
		addRow(Component.translatable("nexomod.qol.potatoMode"),
				Component.translatable("nexomod.qol.potatoMode.description"),
				NexoPotatoMode::active,
				NexoPotatoMode::toggle,
				NexoPotatoMode::toggle);
		addRow(Component.translatable("nexomod.stats.title"),
				Component.translatable("nexomod.stats.description"),
				config::statsHudEnabled,
				() -> config.setStatsHudEnabled(!config.statsHudEnabled()),
				() -> minecraft.gui.setScreen(new NexoStatsConfigScreen(this)));
		addRow(Component.translatable("nexomod.qol.potion"),
				Component.translatable("nexomod.qol.potion.description"),
				config::potionHudEnabled,
				() -> config.setPotionHudEnabled(!config.potionHudEnabled()),
				() -> minecraft.gui.setScreen(new NexoPotionConfigScreen(this)));
		addRow(Component.translatable("nexomod.qol.combo"),
				Component.translatable("nexomod.qol.combo.description"),
				config::comboCounterEnabled,
				() -> config.setComboCounterEnabled(!config.comboCounterEnabled()),
				() -> minecraft.gui.setScreen(new NexoComboConfigScreen(this)));
		addRow(Component.translatable("nexomod.qol.actionbarLog"),
				Component.translatable("nexomod.qol.actionbarLog.description"),
				config::actionbarLogEnabled,
				() -> config.setActionbarLogEnabled(!config.actionbarLogEnabled()),
				() -> minecraft.gui.setScreen(new NexoActionbarLogConfigScreen(this)));
		addRow(Component.translatable("nexomod.qol.pickupLog"),
				Component.translatable("nexomod.qol.pickupLog.description"),
				config::pickupLogEnabled,
				() -> config.setPickupLogEnabled(!config.pickupLogEnabled()),
				() -> minecraft.gui.setScreen(new NexoPickupLogConfigScreen(this)));
		addRow(Component.translatable("nexomod.qol.chat"),
				Component.translatable("nexomod.qol.chat.description"),
				() -> dev.nexoclient.nexomod.chat.NexoChatTweaks.secondEnabled,
				() -> {
					dev.nexoclient.nexomod.chat.NexoChatTweaks.secondEnabled = !dev.nexoclient.nexomod.chat.NexoChatTweaks.secondEnabled;
					dev.nexoclient.nexomod.chat.NexoChatTweaks.save();
				},
				() -> minecraft.gui.setScreen(new NexoChatTweaksScreen(this)));
		addRow(Component.translatable("nexomod.qol.inventoryHud"),
				Component.translatable("nexomod.qol.inventoryHud.description"),
				config::inventoryHudEnabled,
				() -> config.setInventoryHudEnabled(!config.inventoryHudEnabled()),
				() -> minecraft.gui.setScreen(new NexoInventoryHudConfigScreen(this)));
		// Toggles directly on click, like Potato Mode above — nothing to
		// configure, it just shows whatever the desktop's MPRIS bus reports.
		addRow(Component.translatable("nexomod.qol.miniPlayer"),
				Component.translatable("nexomod.qol.miniPlayer.description"),
				config::miniPlayerEnabled,
				() -> config.setMiniPlayerEnabled(!config.miniPlayerEnabled()),
				() -> config.setMiniPlayerEnabled(!config.miniPlayerEnabled()));
		addRow(Component.translatable("nexomod.qol.damageNumbers"),
				Component.translatable("nexomod.qol.damageNumbers.description"),
				config::damageNumbersEnabled,
				() -> config.setDamageNumbersEnabled(!config.damageNumbersEnabled()),
				() -> config.setDamageNumbersEnabled(!config.damageNumbersEnabled()));
		// Four independent hide toggles behind one row; the row reads as on when
		// any of them is hiding something.
		addRow(Component.translatable("nexomod.qol.hudCleaner"),
				Component.translatable("nexomod.qol.hudCleaner.description"),
				config::hudCleanerActive,
				() -> config.setHudCleanerAll(!config.hudCleanerActive()),
				() -> minecraft.gui.setScreen(new NexoHudCleanerConfigScreen(this)));
		addRow(Component.translatable("nexomod.qol.toggleSprint"),
				Component.translatable("nexomod.qol.toggleSprint.description"),
				config::toggleSprintEnabled,
				() -> config.setToggleSprintEnabled(!config.toggleSprintEnabled()),
				() -> config.setToggleSprintEnabled(!config.toggleSprintEnabled()));
		addRow(Component.translatable("nexomod.qol.toggleSneak"),
				Component.translatable("nexomod.qol.toggleSneak.description"),
				config::toggleSneakEnabled,
				() -> config.setToggleSneakEnabled(!config.toggleSneakEnabled()),
				() -> config.setToggleSneakEnabled(!config.toggleSneakEnabled()));
		addRow(Component.translatable("nexomod.qol.screenshotCopy"),
				Component.translatable("nexomod.qol.screenshotCopy.description"),
				config::screenshotCopyEnabled,
				() -> config.setScreenshotCopyEnabled(!config.screenshotCopyEnabled()),
				() -> config.setScreenshotCopyEnabled(!config.screenshotCopyEnabled()));
		addRow(Component.translatable("nexomod.qol.zoom"),
				Component.translatable("nexomod.qol.zoom.description"),
				config::zoomEnabled,
				() -> config.setZoomEnabled(!config.zoomEnabled()),
				() -> minecraft.gui.setScreen(new NexoZoomConfigScreen(this)));
		addRow(Component.translatable("nexomod.qol.crosshair"),
				Component.translatable("nexomod.qol.crosshair.description"),
				config::crosshairEnabled,
				() -> config.setCrosshairEnabled(!config.crosshairEnabled()),
				() -> minecraft.gui.setScreen(new NexoCrosshairConfigScreen(this)));
		// The pill reflects and toggles Dynamic specifically, not "is anything
		// hidden" — manually-hidden entities don't clear when Dynamic is off, so
		// using the combined state here would show the pill permanently on (and
		// impossible to turn off by clicking it) the moment any single entity
		// type was ever hidden by hand.
		addRow(Component.translatable("nexomod.qol.noRender"),
				Component.translatable("nexomod.qol.noRender.description"),
				() -> NexoNoRenderConfig.get().dynamicEnabled(),
				NexoNoRender::toggleDynamic,
				() -> minecraft.gui.setScreen(new NexoNoRenderScreen(this)));
		addModuleRows();

		// Measure the content BEFORE wrapping it. ScrollableLayout's scroll range is
		// contentHeight - viewportHeight, and Mth.clamp does not guard min > max: an
		// unmeasured content layout reports height 0, so the range comes out negative
		// and clamping pins the scroll amount to that negative value. Container.setY
		// then places the content at (y - scrollAmount), i.e. a full viewport BELOW
		// the top of the scissor — every row invisible until some later reposition
		// re-measures and clamps it back to 0. That is exactly the "nothing shows
		// until you resize the window" symptom.
		moduleGrid.arrangeElements();
		// maxHeight is set for real in repositionElements, which runs at the end of
		// this method and again on every resize.
		scrollArea = new ScrollableLayout(minecraft, moduleGrid, listMaxHeight());
		scrollArea.setMinWidth(gridWidth());
		layout.addChild(scrollArea);
		layout.addChild(NexoButton.builder(Component.translatable("nexomod.qol.editLayout"),
						() -> minecraft.gui.setScreen(new NexoHudEditorScreen(this)))
				.size(gridWidth(), EDIT_BUTTON_HEIGHT).build());

		// Registration first, layout last — the order vanilla's own ScrollableLayout
		// users (ExperimentsScreen, DialogScreen) use, and the order that leaves the
		// final arrange as the last thing to touch the geometry.
		layout.visitWidgets(this::addRenderableWidget);
		// Lives in the header, not the layout: placed by repositionElements.
		addRenderableWidget(searchBox);
		repositionElements();
		setInitialFocus(searchBox);
		// Rows the search just dropped keep drawing for a moment, fading out in place.
		java.util.Set<String> stillShown = new java.util.HashSet<>();
		for (NexoModuleRow row : shownRows) {
			stillShown.add(row.getMessage().getString());
		}
		long fadeStart = System.currentTimeMillis();
		for (NexoModuleRow row : previousRows) {
			if (!stillShown.contains(row.getMessage().getString())) {
				ghosts.add(new Ghost(row, fadeStart));
			}
		}
		entrancePlayed = true;
	}

	/**
	 * @param onToggle run when the row's pill is clicked — flips the module in
	 *                 place. {@code onPress} (anywhere else on the row) opens the
	 *                 module's own screen instead.
	 */
	private void addRow(Component name, Component description,
			BooleanSupplier state, Runnable onToggle, Runnable onPress) {
		moduleStates.add(state);
		String needle = query.strip().toLowerCase(java.util.Locale.ROOT);
		if (!needle.isEmpty() && !name.getString().toLowerCase(java.util.Locale.ROOT).contains(needle)
				&& !description.getString().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
			return;
		}
		matchCount++;
		NexoModuleRow row = new NexoModuleRow(0, 0, rowWidth(), ROW_HEIGHT, name, description, state, onPress)
				.withToggle(onToggle)
				.stagger(rowCount++);
		if (entrancePlayed) {
			row.noReveal();
		}
		shownRows.add(row);
		moduleRows.addChild(row);
	}

	/**
	 * Full width of the grid: {@link #COLUMNS} cells plus their horizontal padding,
	 * narrowed if the window cannot fit that. Two 220px columns plus the panel
	 * padding and the scrollbar gutters come to ~524px, which is wider than the
	 * whole screen at GUI scale 4 — without this the panel would hang off both
	 * sides, the horizontal version of the bug that hid the rows.
	 */
	private int gridWidth() {
		int usable = width - PANEL_PAD_X * 2 - SCROLLBAR_GUTTERS;
		int cell = Math.clamp(usable / COLUMNS - CELL_PADDING * 2, MIN_ROW_WIDTH, ROW_WIDTH);
		return COLUMNS * (cell + CELL_PADDING * 2);
	}

	/** Width each module row is built at — the grid's cell width, minus its padding. */
	private int rowWidth() {
		return gridWidth() / COLUMNS - CELL_PADDING * 2;
	}

	/** Grid rows the modules occupy — two per row, so a trailing odd module still needs a whole row. */
	private int gridRowCount() {
		return Math.max(1, (matchCount + COLUMNS - 1) / COLUMNS);
	}

	/** Extra module rows contributed by other parts of the mod — see {@link NexoQolModules}. */
	private void addModuleRows() {
		for (NexoQolModules.Entry entry : NexoQolModules.entries()) {
			addRow(entry.name(), entry.description(), entry.enabled(), entry.toggle(),
					entry.openConfig() == null ? entry.toggle()
							: () -> minecraft.gui.setScreen(entry.openConfig().apply(this)));
		}
	}

	/**
	 * How tall the scrolling list may get: {@link #VISIBLE_ROWS} rows, except
	 * that it never exceeds what the window can actually hold once the header,
	 * footer, panel padding and the Edit Layout button are subtracted. Without
	 * that clamp a short window would push the panel off both edges, since a
	 * fixed row count is a fixed pixel height.
	 *
	 * <p>Also capped at the real content height, so a build with fewer modules
	 * than {@code VISIBLE_ROWS} gets a panel that fits its list rather than one
	 * with dead space under the last row. That cap is load-bearing beyond looks:
	 * a viewport taller than its content would give ScrollableLayout a negative
	 * scroll range, which it does not guard against — see the note in init().
	 */
	private int listMaxHeight() {
		int wanted = VISIBLE_ROWS * ROW_PITCH;
		int content = gridRowCount() * ROW_PITCH;
		int available = height - HEADER_HEIGHT - FOOTER_HEIGHT - PANEL_PAD_Y * 2 - EDIT_BUTTON_HEIGHT - 24;
		// Snap down to a whole row: a viewport ending mid-row leaves a sliced module
		// peeking out of the bottom edge, which reads as a rendering fault.
		int rows = Math.min(Math.min(wanted, content), available) / ROW_PITCH;
		return Math.max(1, rows) * ROW_PITCH;
	}

	@Override
	protected void repositionElements() {
		if (scrollArea == null) {
			return;
		}
		scrollArea.setMaxHeight(listMaxHeight());
		layout.arrangeElements();
		// Centred on the list alone would push the panel low, since the header is
		// taller than the footer; offsetting by half the difference centres the
		// panel as a whole instead.
		FrameLayout.centerInRectangle(layout, 0, (HEADER_HEIGHT - FOOTER_HEIGHT) / 2, width, height);
		placeSearchBox();
	}

	/** Between the title block and the active-count badge, level with the badge. */
	private void placeSearchBox() {
		Font font = minecraft.font;
		int x0 = layout.getX() - PANEL_PAD_X;
		int x1 = layout.getX() + layout.getWidth() + PANEL_PAD_X;
		int y0 = layout.getY() - PANEL_PAD_Y - HEADER_HEIGHT;
		int title = Math.max(Math.round(font.width("NEXO") * WORDMARK_SCALE) + 4,
				Math.round(font.width(Component.translatable("nexomod.qol.subtitle")) * SMALL_TEXT_SCALE));
		Component badge = Component.translatable("nexomod.qol.activeCount", moduleStates.size(), moduleStates.size());
		int left = x0 + 17 + title + 12;
		int right = x1 - 16 - Math.round(font.width(badge) * SMALL_TEXT_SCALE) - 14 - 10;
		searchBox.setPosition(left, y0 + SEARCH_TOP);
		searchBox.setWidth(Math.max(40, right - left));
		searchBox.setHeight(SEARCH_HEIGHT);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x0 = layout.getX() - PANEL_PAD_X;
		int y0 = layout.getY() - PANEL_PAD_Y - HEADER_HEIGHT;
		int x1 = layout.getX() + layout.getWidth() + PANEL_PAD_X;
		int y1 = layout.getY() + layout.getHeight() + PANEL_PAD_Y + FOOTER_HEIGHT;

		long nanos = System.nanoTime();
		float dt = lastFrameNanos == 0L ? 0F : Math.min(0.1F, (nanos - lastFrameNanos) / 1_000_000_000F);
		lastFrameNanos = nanos;
		if (Float.isNaN(shownTop)) {
			shownTop = y0;
			shownBottom = y1;
		}
		// Frame-rate independent ease-out; snaps once within half a pixel so it settles.
		float ease = 1F - (float) Math.exp(-dt * 16F);
		shownTop = approachPx(shownTop, y0, ease);
		shownBottom = approachPx(shownBottom, y1, ease);
		int py0 = Math.round(shownTop);
		int py1 = Math.round(shownBottom);

		NexoPanelRenderer.draw(graphics, x0, py0, x1, py1);
		long now = System.currentTimeMillis();
		drawScanlines(graphics, x0, py0, x1, py1);
		drawCornerBrackets(graphics, x0, py0, x1, py1, now);

		// Content sits at its final position and is revealed or cut off by the moving panel.
		graphics.enableScissor(x0, py0, x1, py1);
		drawHeader(graphics, x0, y0, x1, now);
		drawFooter(graphics, x0, x1, y1);
		drawGhosts(graphics, partialTick, now);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.disableScissor();
	}

	private void drawGhosts(GuiGraphicsExtractor graphics, float partialTick, long now) {
		ghosts.removeIf(ghost -> now - ghost.startMillis() >= FADE_OUT_MILLIS);
		for (Ghost ghost : ghosts) {
			float remaining = 1F - (now - ghost.startMillis()) / (float) FADE_OUT_MILLIS;
			ghost.row().setFade(remaining * remaining);
			// Mouse far off-screen: a fading row never reads as hovered.
			ghost.row().extractRenderState(graphics, -10000, -10000, partialTick);
		}
	}

	private static float approachPx(float current, float target, float ease) {
		float next = current + (target - current) * ease;
		return Math.abs(target - next) < 0.5F ? target : next;
	}

	/** A faint CRT ruling over the panel fill, to keep a large flat area from reading as dead space. */
	private static void drawScanlines(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
		for (int y = y0 + 3; y < y1 - 2; y += 4) {
			graphics.fill(x0 + 2, y, x1 - 2, y + 1, 0x0AFFFFFF);
		}
	}

	private static void drawCornerBrackets(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, long now) {
		int color = NexoStyle.cycle(now, 8000L);
		int arm = 12;
		int inset = 3;
		int left = x0 + inset;
		int top = y0 + inset;
		int right = x1 - inset;
		int bottom = y1 - inset;

		graphics.fill(left, top, left + arm, top + 2, color);
		graphics.fill(left, top, left + 2, top + arm, color);
		graphics.fill(right - arm, top, right, top + 2, color);
		graphics.fill(right - 2, top, right, top + arm, color);
		graphics.fill(left, bottom - 2, left + arm, bottom, color);
		graphics.fill(left, bottom - arm, left + 2, bottom, color);
		graphics.fill(right - arm, bottom - 2, right, bottom, color);
		graphics.fill(right - 2, bottom - arm, right, bottom, color);
	}

	private void drawHeader(GuiGraphicsExtractor graphics, int x0, int y0, int x1, long now) {
		Font font = minecraft.font;
		Matrix3x2fStack pose = graphics.pose();

		// "NEXO", one letter at a time so the brand cycle runs *through* the word
		// rather than recolouring it as a block. Each letter gets a dark offset
		// copy first, which reads as a glow against the blurred world behind.
		String wordmark = "NEXO";
		float cursor = x0 + 16;
		float baseline = y0 + 8;
		pose.pushMatrix();
		pose.scale(WORDMARK_SCALE, WORDMARK_SCALE);
		for (int i = 0; i < wordmark.length(); i++) {
			String letter = String.valueOf(wordmark.charAt(i));
			int color = NexoStyle.cycle(now + i * 260L, 4000L);
			int sx = Math.round(cursor / WORDMARK_SCALE);
			int sy = Math.round(baseline / WORDMARK_SCALE);
			graphics.text(font, letter, sx + 1, sy + 1, NexoStyle.fade(color, 0.35F));
			graphics.text(font, letter, sx, sy, color);
			cursor += (font.width(letter) + 1) * WORDMARK_SCALE;
		}
		pose.popMatrix();

		pose.pushMatrix();
		pose.scale(SMALL_TEXT_SCALE, SMALL_TEXT_SCALE);
		graphics.text(font, Component.translatable("nexomod.qol.subtitle"),
				Math.round((x0 + 17) / SMALL_TEXT_SCALE), Math.round((y0 + 24) / SMALL_TEXT_SCALE),
				NexoStyle.TEXT_SECONDARY);
		pose.popMatrix();

		drawActiveBadge(graphics, x1, y0);
		drawSeparator(graphics, x0, y0, x1, now);
	}

	/** "N / M active" as a pill at the header's right edge — the one number worth reading at a glance. */
	private void drawActiveBadge(GuiGraphicsExtractor graphics, int x1, int y0) {
		int active = 0;
		for (BooleanSupplier state : moduleStates) {
			if (state.getAsBoolean()) {
				active++;
			}
		}
		Component label = Component.translatable("nexomod.qol.activeCount", active, moduleStates.size());

		Font font = minecraft.font;
		int textWidth = Math.round(font.width(label) * SMALL_TEXT_SCALE);
		int padding = 7;
		int bx1 = x1 - 16;
		int bx0 = bx1 - textWidth - padding * 2;
		int by0 = y0 + 11;
		int by1 = by0 + 13;

		int accent = active > 0 ? NexoStyle.TEXT_ACTIVE_ACCENT : NexoStyle.BORDER_DIM;
		graphics.fill(bx0 - 2, by0 - 2, bx1 + 2, by1 + 2, NexoStyle.fade(accent, 0.12F));
		NexoShapes.fillRounded(graphics, bx0, by0, bx1, by1,
				NexoStyle.mix(NexoStyle.PANEL_BG_RAISED, accent, 0.22F), 6);

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.scale(SMALL_TEXT_SCALE, SMALL_TEXT_SCALE);
		graphics.text(font, label, Math.round((bx0 + padding) / SMALL_TEXT_SCALE),
				Math.round((by0 + 4) / SMALL_TEXT_SCALE), accent);
		pose.popMatrix();
	}

	/**
	 * The rule under the header, with a highlight travelling along it. Built from
	 * short slices with a triangular alpha ramp because {@code fillGradient} only
	 * interpolates vertically — there is no horizontal-gradient primitive.
	 */
	private static void drawSeparator(GuiGraphicsExtractor graphics, int x0, int y0, int x1, long now) {
		int left = x0 + 14;
		int right = x1 - 14;
		int y = y0 + HEADER_HEIGHT - 3;
		graphics.fill(left, y, right, y + 1, 0x28FFFFFF);

		int span = right - left;
		if (span <= 0) {
			return;
		}
		int color = NexoStyle.cycle(now, 8000L);
		int slices = 16;
		int sliceWidth = 3;
		int head = left + Math.round((now % 2800L) / 2800F * (span + slices * sliceWidth)) - slices * sliceWidth;
		for (int i = 0; i < slices; i++) {
			float ramp = 1F - Math.abs(i - (slices - 1) / 2F) / ((slices - 1) / 2F);
			int sx0 = head + i * sliceWidth;
			if (sx0 < left || sx0 + sliceWidth > right) {
				continue;
			}
			graphics.fill(sx0, y, sx0 + sliceWidth, y + 1, NexoStyle.fade(color, ramp));
		}
	}

	private void drawFooter(GuiGraphicsExtractor graphics, int x0, int x1, int y1) {
		Component hint = Component.translatable("nexomod.qol.closeHint", NexoQolMenu.toggleKeyName());
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.scale(SMALL_TEXT_SCALE, SMALL_TEXT_SCALE);
		graphics.centeredText(minecraft.font, hint,
				Math.round((x0 + x1) / 2F / SMALL_TEXT_SCALE),
				Math.round((y1 - 13) / SMALL_TEXT_SCALE),
				NexoStyle.TEXT_DISABLED);
		pose.popMatrix();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// A letter or digit bound as the menu key must stay typeable in the search box.
		boolean typing = searchBox != null && searchBox.isFocused() && isTextKey(event.key());
		if (!typing && NexoQolMenu.isToggleKey(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	private static boolean isTextKey(int key) {
		return key == 32 || key == 39 || (key >= 44 && key <= 57) || key == 59 || key == 61
				|| (key >= 65 && key <= 93) || key == 96;
	}
}
