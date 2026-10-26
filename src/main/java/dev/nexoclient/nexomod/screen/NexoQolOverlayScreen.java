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
 * <p>The layout follows the "In-game mod menu" mockup (design/STYLE.md): a
 * rainbow "MOD MENU" tab, search and the active count in the header, then a
 * grid of {@link NexoModuleCard}s. Nothing in the chrome is a texture — it is
 * all {@code fill} calls, so it costs no asset to ship.
 */
public class NexoQolOverlayScreen extends Screen {
	/** Widest a module card gets; past this a wider window just shows more columns. */
	private static final int ROW_WIDTH = 150;
	/**
	 * Absolute floor for the adaptive cell width. Only a sanity backstop — at every
	 * real window size the fit calculation lands above it, including the 320×240
	 * minimum, so it never binds and never forces the panel wider than the screen.
	 */
	private static final int MIN_ROW_WIDTH = 100;
	private static final int ROW_HEIGHT = 76;
	/** Most cards across. Fewer when the window is too narrow for this many at {@link #MIN_ROW_WIDTH}. */
	private static final int MAX_COLUMNS = 4;
	/** Space ScrollableLayout reserves for a scrollbar on each side of its content. */
	private static final int SCROLLBAR_GUTTERS = 28;
	/** Vertical gap the grid leaves under each cell; part of a row's pitch. */
	private static final int CELL_PADDING = 4;
	/** Distance from one grid row's top to the next — a whole number of these is always shown. */
	private static final int ROW_PITCH = ROW_HEIGHT + CELL_PADDING;
	/** Grid rows shown at once. Past this the list scrolls. */
	private static final int VISIBLE_ROWS = 4;
	private static final int PANEL_PAD_X = 20;
	private static final int PANEL_PAD_Y = 14;
	/** Room above the list for the search box, the active-count badge and the separator. */
	private static final int HEADER_HEIGHT = 30;
	/** Room below the list for the close hint. */
	private static final int FOOTER_HEIGHT = 15;
	private static final int EDIT_BUTTON_HEIGHT = 20;
	/** Same band as the active-count badge, so the search box sits level with it. */
	private static final int SEARCH_HEIGHT = 13;
	private static final int SEARCH_TOP = 8;
	/** The row above the panel: the MOD MENU tab, the icon tabs and the close button. */
	private static final int TAB_HEIGHT = 20;
	private static final int TAB_PAD = 10;
	private static final int TAB_GAP = 4;
	/** Height of that row plus its gap to the panel. */
	private static final int TOP_ROW = TAB_HEIGHT + 6;
	private static final float SMALL_TEXT_SCALE = 0.75F;

	/**
	 * Outer column: the scrolling module list, then the Edit Layout button. Both
	 * layouts are rebuilt in every {@code init()} rather than kept — {@code init()}
	 * re-runs on each resize and a LinearLayout cannot drop what it already holds,
	 * so a reused one would end up with two of every row.
	 */
	private LinearLayout layout;
	/** The scrolling part — a {@link #columns()}-wide grid of module cards. */
	private GridLayout moduleGrid;
	private GridLayout.RowHelper moduleRows;
	/** Every module's live on/off state, in row order, for the header's active count. */
	private final List<BooleanSupplier> moduleStates = new ArrayList<>();

	private ScrollableLayout scrollArea;
	/** The icon tabs right of MOD MENU, in order, and the close button at the far right. */
	private final List<NexoIconButton> tabs = new ArrayList<>();
	private NexoIconButton closeButton;
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
	private final List<NexoModuleCard> shownRows = new ArrayList<>();
	/** Filtered-out rows kept only to fade away where they were; not part of the layout or input. */
	private final List<Ghost> ghosts = new ArrayList<>();

	private record Ghost(NexoModuleCard row, long startMillis) {}
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
		List<NexoModuleCard> previousRows = new ArrayList<>(shownRows);
		shownRows.clear();

		layout = LinearLayout.vertical().spacing(8);
		layout.defaultCellSetting().alignHorizontallyCenter();
		searchBox = new EditBox(minecraft.font, 60, SEARCH_HEIGHT, Component.translatable("nexomod.qol.search"));
		searchBox.setHint(Component.translatable("nexomod.qol.search"));
		// The flat field behind it is drawn by drawHeader; vanilla's own border
		// is the black-and-white box the rest of the menu doesn't use.
		searchBox.setBordered(false);
		searchBox.setValue(query);
		searchBox.setResponder(text -> {
			if (!text.equals(query)) {
				query = text;
				rebuildWidgets();
			}
		});
		moduleGrid = new GridLayout();
		moduleGrid.defaultCellSetting().paddingHorizontal(CELL_PADDING).paddingBottom(CELL_PADDING);
		moduleRows = moduleGrid.createRowHelper(columns());

		NexoConfig config = NexoConfig.get();
		addRow(Component.translatable("nexomod.qol.keystrokes"),
				Component.translatable("nexomod.qol.keystrokes.description"),
				config::keystrokesHudEnabled,
				() -> config.setKeystrokesHudEnabled(!config.keystrokesHudEnabled()),
				() -> minecraft.gui.setScreen(new NexoKeystrokesConfigScreen(this)));
		addRow(Component.translatable("nexomod.qol.cps"),
				Component.translatable("nexomod.qol.cps.description"),
				config::cpsCounterEnabled,
				() -> config.setCpsCounterEnabled(!config.cpsCounterEnabled()));
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
				NexoPotatoMode::toggle);
		addRow(Component.translatable("nexomod.stats.title"),
				Component.translatable("nexomod.stats.description"),
				config::statsHudEnabled,
				() -> config.setStatsHudEnabled(!config.statsHudEnabled()),
				() -> minecraft.gui.setScreen(new NexoStatsConfigScreen(this)));
		addRow(Component.translatable("nexomod.qol.potion"),
				Component.translatable("nexomod.qol.potion.description"),
				config::potionHudEnabled,
				() -> config.setPotionHudEnabled(!config.potionHudEnabled()));
		addRow(Component.translatable("nexomod.qol.combo"),
				Component.translatable("nexomod.qol.combo.description"),
				config::comboCounterEnabled,
				() -> config.setComboCounterEnabled(!config.comboCounterEnabled()));
		addRow(Component.translatable("nexomod.qol.targetHud"),
				Component.translatable("nexomod.qol.targetHud.description"),
				config::targetHudEnabled,
				() -> config.setTargetHudEnabled(!config.targetHudEnabled()));
		addRow(Component.translatable("nexomod.qol.reach"),
				Component.translatable("nexomod.qol.reach.description"),
				config::reachDisplayEnabled,
				() -> config.setReachDisplayEnabled(!config.reachDisplayEnabled()));
		addRow(Component.translatable("nexomod.qol.totemPops"),
				Component.translatable("nexomod.qol.totemPops.description"),
				config::totemPopCounterEnabled,
				() -> config.setTotemPopCounterEnabled(!config.totemPopCounterEnabled()));
		addRow(Component.translatable("nexomod.qol.hitOverlay"),
				Component.translatable("nexomod.qol.hitOverlay.description"),
				config::hitOverlayEnabled,
				() -> config.setHitOverlayEnabled(!config.hitOverlayEnabled()));
		addRow(Component.translatable("nexomod.qol.durability"),
				Component.translatable("nexomod.qol.durability.description"),
				config::durabilityWarningEnabled,
				() -> config.setDurabilityWarningEnabled(!config.durabilityWarningEnabled()));
		addRow(Component.translatable("nexomod.qol.autoReconnect"),
				Component.translatable("nexomod.qol.autoReconnect.description"),
				config::autoReconnectEnabled,
				() -> config.setAutoReconnectEnabled(!config.autoReconnectEnabled()));
		addRow(Component.translatable("nexomod.qol.stackCount"),
				Component.translatable("nexomod.qol.stackCount.description"),
				config::stackCountEnabled,
				() -> config.setStackCountEnabled(!config.stackCountEnabled()));
		addRow(Component.translatable("nexomod.qol.containerSearch"),
				Component.translatable("nexomod.qol.containerSearch.description"),
				config::containerSearchEnabled,
				() -> config.setContainerSearchEnabled(!config.containerSearchEnabled()));
		addRow(Component.translatable("nexomod.qol.actionbarLog"),
				Component.translatable("nexomod.qol.actionbarLog.description"),
				config::actionbarLogEnabled,
				() -> config.setActionbarLogEnabled(!config.actionbarLogEnabled()));
		addRow(Component.translatable("nexomod.qol.pickupLog"),
				Component.translatable("nexomod.qol.pickupLog.description"),
				config::pickupLogEnabled,
				() -> config.setPickupLogEnabled(!config.pickupLogEnabled()));
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
				() -> config.setMiniPlayerEnabled(!config.miniPlayerEnabled()));
		addRow(Component.translatable("nexomod.qol.damageNumbers"),
				Component.translatable("nexomod.qol.damageNumbers.description"),
				config::damageNumbersEnabled,
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
				() -> config.setToggleSprintEnabled(!config.toggleSprintEnabled()));
		addRow(Component.translatable("nexomod.qol.toggleSneak"),
				Component.translatable("nexomod.qol.toggleSneak.description"),
				config::toggleSneakEnabled,
				() -> config.setToggleSneakEnabled(!config.toggleSneakEnabled()));
		addRow(Component.translatable("nexomod.qol.screenshotCopy"),
				Component.translatable("nexomod.qol.screenshotCopy.description"),
				config::screenshotCopyEnabled,
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
		// The card reflects and toggles Dynamic specifically, not "is anything
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
		addTopRow();
		repositionElements();
		setInitialFocus(searchBox);
		// Rows the search just dropped keep drawing for a moment, fading out in place.
		java.util.Set<String> stillShown = new java.util.HashSet<>();
		for (NexoModuleCard row : shownRows) {
			stillShown.add(row.getMessage().getString());
		}
		long fadeStart = System.currentTimeMillis();
		for (NexoModuleCard row : previousRows) {
			if (!stillShown.contains(row.getMessage().getString())) {
				ghosts.add(new Ghost(row, fadeStart));
			}
		}
		entrancePlayed = true;
	}

	/**
	 * The icon tabs above the panel — shortcuts to the screens around the
	 * menu — and the close button. Placed by {@link #placeTopRow()}.
	 */
	private void addTopRow() {
		tabs.clear();
		addTab("nexomod.qol.tab.settings", net.minecraft.world.item.Items.COMPARATOR, () -> new NexoSettingsScreen(this));
		addTab("nexomod.qol.tab.hudEditor", net.minecraft.world.item.Items.ITEM_FRAME, () -> new NexoHudEditorScreen(this));
		addTab("nexomod.qol.tab.chat", net.minecraft.world.item.Items.WRITABLE_BOOK, () -> new NexoChatTweaksScreen(this));
		addTab("nexomod.qol.tab.macros", net.minecraft.world.item.Items.REPEATER, () -> new NexoMacroListScreen(this));
		addTab("nexomod.qol.tab.appearance", net.minecraft.world.item.Items.PAINTING, () -> new NexoAppearanceScreen(this));
		// Only outside a world. Switching account while connected swaps the session's
		// chat-signing keys mid-connection: the client then sends the *other*
		// account's key to a server it joined as the first, which gets it kicked
		// and hands that server proof the two accounts are one person.
		if (minecraft.level == null && minecraft.getConnection() == null) {
			addTab("nexomod.qol.tab.accounts", net.minecraft.world.item.Items.PLAYER_HEAD, () -> new AccountSwitcherScreen(this));
		}
		closeButton = addRenderableWidget(NexoIconButton.close(TAB_HEIGHT, Component.translatable("gui.done"), this::onClose));
	}

	private void addTab(String key, net.minecraft.world.item.Item icon, java.util.function.Supplier<Screen> screen) {
		tabs.add(addRenderableWidget(NexoIconButton.icon(TAB_HEIGHT, Component.translatable(key),
				new net.minecraft.world.item.ItemStack(icon), () -> minecraft.gui.setScreen(screen.get()))));
	}

	/** MOD MENU at the panel's left edge, the icon tabs after it, close flush with the panel's right edge. */
	private void placeTopRow() {
		int x0 = layout.getX() - PANEL_PAD_X;
		int x1 = layout.getX() + layout.getWidth() + PANEL_PAD_X;
		int y = layout.getY() - PANEL_PAD_Y - HEADER_HEIGHT - TOP_ROW;
		int x = x0 + tabWidth(minecraft.font) + TAB_GAP;
		// Tabs that would run into the close button on a narrow window are hidden.
		int limit = x1 - TAB_HEIGHT - TAB_GAP;
		for (NexoIconButton tab : tabs) {
			tab.setPosition(x, y);
			tab.visible = x + TAB_HEIGHT <= limit;
			x += TAB_HEIGHT + TAB_GAP;
		}
		closeButton.setPosition(x1 - TAB_HEIGHT, y);
	}

	/** A module with nothing to configure: its card has no settings button, and any click toggles it. */
	private void addRow(Component name, Component description, BooleanSupplier state, Runnable onToggle) {
		addRow(name, description, state, onToggle, null, null);
	}

	private void addRow(Component name, Component description,
			BooleanSupplier state, Runnable onToggle, Runnable onPress) {
		addRow(name, description, state, onToggle, onPress, null);
	}

	/**
	 * @param onToggle run when the card's ENABLED/DISABLED bar is clicked — flips
	 *                 the module in place.
	 * @param onPress  run for a click anywhere else: opens the module's own
	 *                 screen. Null when there is none.
	 * @param tag      small edition label in the card's corner, or null.
	 */
	private void addRow(Component name, Component description,
			BooleanSupplier state, Runnable onToggle, Runnable onPress, Component tag) {
		moduleStates.add(state);
		String needle = query.strip().toLowerCase(java.util.Locale.ROOT);
		if (!needle.isEmpty() && !name.getString().toLowerCase(java.util.Locale.ROOT).contains(needle)
				&& !description.getString().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
			return;
		}
		matchCount++;
		NexoModuleCard row = new NexoModuleCard(rowWidth(), ROW_HEIGHT, name, description, state, onToggle, onPress, tag)
				.stagger(rowCount++);
		if (entrancePlayed) {
			row.noReveal();
		}
		shownRows.add(row);
		moduleRows.addChild(row);
	}

	/** Width the window leaves for the grid once the panel padding and scrollbar gutters are taken. */
	private int usableWidth() {
		return width - PANEL_PAD_X * 2 - SCROLLBAR_GUTTERS;
	}

	/**
	 * Cards across: as many as fit at {@link #MIN_ROW_WIDTH}, up to
	 * {@link #MAX_COLUMNS}, never fewer than two. A fixed four would hang off
	 * both sides of the screen at GUI scale 4.
	 */
	private int columns() {
		return Math.clamp(usableWidth() / (MIN_ROW_WIDTH + CELL_PADDING * 2), 2, MAX_COLUMNS);
	}

	/** Full width of the grid: {@link #columns()} cells plus their horizontal padding. */
	private int gridWidth() {
		int cell = Math.clamp(usableWidth() / columns() - CELL_PADDING * 2, MIN_ROW_WIDTH, ROW_WIDTH);
		return columns() * (cell + CELL_PADDING * 2);
	}

	/** Width each module card is built at — the grid's cell width, minus its padding. */
	private int rowWidth() {
		return gridWidth() / columns() - CELL_PADDING * 2;
	}

	/** Grid rows the cards occupy, so a trailing partial row still counts as a whole one. */
	private int gridRowCount() {
		return Math.max(1, (matchCount + columns() - 1) / columns());
	}

	/** Extra module rows contributed by other parts of the mod — see {@link NexoQolModules}. */
	private void addModuleRows() {
		for (NexoQolModules.Entry entry : NexoQolModules.entries()) {
			// Only Tactical registers extra modules, so they wear its tag.
			addRow(entry.name(), entry.description(), entry.enabled(), entry.toggle(),
					entry.openConfig() == null ? null
							: () -> minecraft.gui.setScreen(entry.openConfig().apply(this)),
					Component.translatable("nexomod.qol.card.tactical"));
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
		int available = height - TOP_ROW - HEADER_HEIGHT - FOOTER_HEIGHT - PANEL_PAD_Y * 2 - EDIT_BUTTON_HEIGHT - 24;
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
		FrameLayout.centerInRectangle(layout, 0, (TOP_ROW + HEADER_HEIGHT - FOOTER_HEIGHT) / 2, width, height);
		placeSearchBox();
		placeTopRow();
	}

	/** From the panel's left edge to the active-count badge, level with the badge. */
	private void placeSearchBox() {
		Font font = minecraft.font;
		int x0 = layout.getX() - PANEL_PAD_X;
		int x1 = layout.getX() + layout.getWidth() + PANEL_PAD_X;
		int y0 = layout.getY() - PANEL_PAD_Y - HEADER_HEIGHT;
		Component badge = Component.translatable("nexomod.qol.activeCount", moduleStates.size(), moduleStates.size());
		int left = x0 + 14;
		int right = x1 - 16 - Math.round(font.width(badge) * SMALL_TEXT_SCALE) - 14 - 10;
		// Inset inside the field drawHeader paints: an unbordered EditBox draws
		// its text at its own top-left, so the padding has to come from here.
		searchBox.setPosition(left + 6, y0 + SEARCH_TOP + 3);
		searchBox.setWidth(Math.max(40, right - left) - 12);
		searchBox.setHeight(SEARCH_HEIGHT - 3);
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

		// Cards scrolled out of view skip drawing entirely — see NexoModuleCard#clip.
		for (NexoModuleCard card : shownRows) {
			card.clip(scrollArea.getY(), scrollArea.getY() + scrollArea.getHeight());
		}
		// Content sits at its final position and is revealed or cut off by the moving
		// panel. The scissor reaches up over the tab row so its buttons draw too.
		graphics.enableScissor(x0 - 4, Math.min(py0, y0 - TOP_ROW) - 4, x1 + 4, py1);
		drawModMenuTab(graphics, x0, y0 - TOP_ROW);
		drawHeader(graphics, x0, y0, x1);
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

	/**
	 * The "MOD MENU" tab at the head of the top row: the logo's rainbow, dark
	 * bold label, accent glow — the menu's one filled control, like the mockup's.
	 */
	private void drawModMenuTab(GuiGraphicsExtractor graphics, int tx0, int ty0) {
		Font font = minecraft.font;
		int tx1 = tx0 + tabWidth(font);
		int ty1 = ty0 + TAB_HEIGHT;
		NexoShapes.glow(graphics, tx0, ty0, tx1, ty1, NexoStyle.ACCENT, 3, 0.22F);
		// fillLogoRamp has square corners; trimming its top and bottom rows by a
		// pixel gives the tab a small chamfer instead of a hard box.
		NexoShapes.fillLogoRamp(graphics, tx0 + 1, tx1 - 1, tx1 - 1, ty0, ty0 + 1, 0xFF);
		NexoShapes.fillLogoRamp(graphics, tx0, tx1, tx1, ty0 + 1, ty1 - 1, 0xFF);
		NexoShapes.fillLogoRamp(graphics, tx0 + 1, tx1 - 1, tx1 - 1, ty1 - 1, ty1, 0xFF);
		graphics.text(font, modMenuLabel(), tx0 + TAB_PAD, ty0 + (TAB_HEIGHT - 8) / 2 + 1, 0xFF0A0A0D, false);
	}

	private void drawHeader(GuiGraphicsExtractor graphics, int x0, int y0, int x1) {
		// The search field: searchBox sits inset inside this box, see placeSearchBox.
		int sx0 = searchBox.getX() - 6;
		int sy0 = searchBox.getY() - 3;
		NexoShapes.outlined(graphics, sx0, sy0, sx0 + searchBox.getWidth() + 12, sy0 + SEARCH_HEIGHT + 2,
				NexoStyle.FIELD, searchBox.isFocused() ? NexoStyle.mix(NexoStyle.CONTROL, NexoStyle.ACCENT, 0.7F) : NexoStyle.CONTROL, 3);
		drawActiveBadge(graphics, x1, y0);
		drawSeparator(graphics, x0, y0, x1);
	}

	private static Component modMenuLabel() {
		return Component.translatable("nexomod.qol.modMenu").withStyle(style -> style.withBold(true));
	}

	private static int tabWidth(Font font) {
		return font.width(modMenuLabel()) + TAB_PAD * 2;
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
		int by0 = y0 + 8;
		int by1 = by0 + 13;

		int accent = active > 0 ? NexoStyle.ACCENT : NexoStyle.MUTED;
		NexoShapes.outlined(graphics, bx0, by0, bx1, by1,
				NexoStyle.mix(NexoStyle.RAISED, accent, 0.14F), NexoStyle.mix(NexoStyle.RAISED, accent, 0.7F), 6);
		int textColor = active > 0 ? NexoStyle.TEXT_PRIMARY : NexoStyle.MUTED;

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.scale(SMALL_TEXT_SCALE, SMALL_TEXT_SCALE);
		graphics.text(font, label, Math.round((bx0 + padding) / SMALL_TEXT_SCALE),
				Math.round((by0 + 4) / SMALL_TEXT_SCALE), textColor);
		pose.popMatrix();
	}

	/** The plain 1px rule under the header. */
	private static void drawSeparator(GuiGraphicsExtractor graphics, int x0, int y0, int x1) {
		graphics.fill(x0 + 14, y0 + HEADER_HEIGHT - 3, x1 - 14, y0 + HEADER_HEIGHT - 2, NexoStyle.LINE);
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
