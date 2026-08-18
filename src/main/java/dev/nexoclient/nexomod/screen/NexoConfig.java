package dev.nexoclient.nexomod.screen;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.sounds.SoundSource;

/** Persisted look-and-feel settings for the menu re-skin. */
public final class NexoConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/config");
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-appearance.properties");

	private static NexoConfig instance;

	/** Slider bounds for the bedrock hole finder's size filter, in blocks. */
	public static final int MIN_HOLE_SIZE_FLOOR = 1;
	public static final int MAX_HOLE_SIZE_CEILING = 64;

	/** Slider bounds for the tactical indicator's hearing range, in blocks. */
	public static final int MIN_TACTICAL_RANGE = 8;
	public static final int MAX_TACTICAL_RANGE = 128;

	/**
	 * Sound categories the tactical indicator listens to out of the box, as a
	 * bitmask over {@code SoundSource.ordinal()}.
	 *
	 * <p>Hostile, neutral, players and blocks — the four that mean something is
	 * happening near you. Music, records, weather, ambient, voice and UI are
	 * off: they are either not positional or fire constantly, and an arrow for
	 * every raindrop is an arrow for nothing.
	 *
	 * <p>Stored as an int rather than a set of named booleans so a category
	 * added by a future Minecraft version costs no migration: an unknown ordinal
	 * simply reads as off. Built from the enum constants rather than written out
	 * as literal bits, so a reordering upstream cannot silently turn "hostile
	 * mobs" into "music".
	 */
	public static final int DEFAULT_TACTICAL_CATEGORIES = categoryMask(
			SoundSource.HOSTILE, SoundSource.NEUTRAL, SoundSource.PLAYERS, SoundSource.BLOCKS);

	private static int categoryMask(SoundSource... sources) {
		int mask = 0;
		for (SoundSource source : sources) {
			mask |= 1 << source.ordinal();
		}
		return mask;
	}

	public enum BackgroundStyle {
		STARFIELD,
		MATRIX_RAIN;

		public BackgroundStyle next() {
			BackgroundStyle[] values = values();
			return values[(ordinal() + 1) % values.length];
		}
	}

	/** RGB (no alpha) — alpha is managed per-pixel by the trail fade itself. */
	public enum MatrixColor {
		GREEN(0x00FF66),
		CYAN(0x00E5FF),
		MAGENTA(0xFF3CAC),
		VIOLET(0x9C5CFF),
		WHITE(0xE8FFF0);

		public final int rgb;

		MatrixColor(int rgb) {
			this.rgb = rgb;
		}

		public MatrixColor next() {
			MatrixColor[] values = values();
			return values[(ordinal() + 1) % values.length];
		}
	}

	public enum MatrixDensity {
		SPARSE(16),
		NORMAL(10),
		DENSE(6);

		public final int cellWidth;

		MatrixDensity(int cellWidth) {
			this.cellWidth = cellWidth;
		}

		public MatrixDensity next() {
			MatrixDensity[] values = values();
			return values[(ordinal() + 1) % values.length];
		}
	}

	/**
	 * Position-obscuring preset. NONE/FULL force every obscuring feature off/on;
	 * CUSTOM defers to the individual per-feature flags. The per-feature getters
	 * ({@link #obscureCoordinatesActive()}, {@link #obscureBlockRotationActive()})
	 * resolve the preset, so callers never need to look at it directly.
	 */
	public enum ObscurePreset {
		NONE,
		FULL,
		CUSTOM
	}

	/**
	 * Fixed client-side time of day, as an offset into the 24000-tick day.
	 *
	 * <p>Purely a rendering choice — see
	 * {@code dev.nexoclient.nexomod.tactical.environment.NexoEnvironmentOverride}
	 * for why overriding the client clock cannot change gameplay.
	 */
	public enum TimeOverride {
		OFF(-1),
		SUNRISE(23000),
		DAY(1000),
		NOON(6000),
		SUNSET(12000),
		NIGHT(15000),
		MIDNIGHT(18000);

		/** Ticks into the day, or -1 for "don't touch the clock". */
		public final int dayTime;

		TimeOverride(int dayTime) {
			this.dayTime = dayTime;
		}
	}

	/** Fixed client-side weather appearance. */
	public enum WeatherOverride {
		OFF,
		CLEAR,
		RAIN,
		THUNDER
	}

	/** How far out from the player the bedrock hole finder scans, in chunks. */
	public enum BedrockHoleRadius {
		CHUNKS_4(4),
		CHUNKS_8(8),
		CHUNKS_16(16),
		CHUNKS_32(32);

		public final int chunks;

		BedrockHoleRadius(int chunks) {
			this.chunks = chunks;
		}
	}

	private boolean customMenusEnabled;
	private boolean customFontEnabled;
	private BackgroundStyle backgroundStyle;
	private MatrixColor matrixColor;
	private MatrixDensity matrixDensity;
	private boolean discordRpcEnabled;
	private ObscurePreset obscurePreset;
	private boolean obscureCoordinatesEnabled;
	private boolean obscureBlockRotationEnabled;
	private boolean obscureBedrockFloorEnabled;
	private boolean bedrockHoleFinderEnabled;
	private BedrockHoleRadius bedrockHoleRadius = BedrockHoleRadius.CHUNKS_8;
	private int bedrockHoleMinSize = 2;
	private int bedrockHoleMaxSize = 30;
	private boolean bedrockHoleShowCoordsEnabled = true;
	private boolean bedrockHoleLabelsEnabled = true;
	private boolean bedrockHoleChatEnabled = true;
	private boolean bedrockHoleToastEnabled;
	private boolean bedrockHoleSoundEnabled = true;

	// ------------------------------------------------------------------
	// Chat (src/main — both jars)
	// ------------------------------------------------------------------
	private boolean chatHistoryEnabled;
	private boolean chatFilterEnabled = true;

	/**
	 * Whether this client publishes itself to the badge roster and shows other
	 * Nexo players' badges. On by default: a recognition network in which
	 * everyone has opted out recognises nobody.
	 */
	private boolean badgeSyncEnabled = true;
	/**
	 * The accounts this installation believes are in the roster. Persisted
	 * rather than inferred, so that switching the setting off still removes
	 * them later even if the game happened to be offline at the time.
	 *
	 * <p>Keyed by account and not one flag, because registration is per account
	 * while this file is per instance. As a single boolean, the first account
	 * to register made every later one look already-done: a second account
	 * launched from the same instance — or picked in Nexo's own account
	 * switcher — silently never registered and stayed invisible to everyone.
	 *
	 * <p>The value is <em>when</em> it last registered, in epoch seconds, not
	 * merely that it did. Registration is what refreshes the service's
	 * `last_seen`, and the roster drops an account that has not been seen in
	 * sixty days — so a build that registered once and never again would have
	 * every one of its players silently vanish two months later while they were
	 * still playing daily. It re-registers once a day instead; the service
	 * overwrites a single timestamp, so this costs it no extra storage and
	 * keeps no history.
	 */
	private final Map<UUID, Long> badgeSyncRegisteredAccounts = new ConcurrentHashMap<>();

	// ------------------------------------------------------------------
	// Full-jar features
	// ------------------------------------------------------------------
	//
	// These live here, in src/main, for the same reason the bedrock-hole
	// settings above do: NexoConfig is one properties file and one loader, and
	// splitting it per variant would mean two files, two save paths and a merge
	// question nobody has an answer for. What must not cross the line is *code*
	// — the screens that edit these are in src/full and reach the hub through
	// NexoExtraCategories, so the light jar has the fields and no way to see or
	// use them.
	private boolean tacticalEnabled;
	private int tacticalRange = 48;
	private int tacticalCategoryMask = DEFAULT_TACTICAL_CATEGORIES;
	private boolean tacticalLabelsEnabled = true;
	private boolean armorHudEnabled;
	private int armorHudWarnPercent = 20;
	private boolean armorHudOffhandEnabled = true;
	private TimeOverride timeOverride = TimeOverride.OFF;
	private WeatherOverride weatherOverride = WeatherOverride.OFF;
	private boolean chunkHistoryEnabled;
	private boolean macroTriggersEnabled;

	private NexoConfig(boolean customMenusEnabled, boolean customFontEnabled, BackgroundStyle backgroundStyle, MatrixColor matrixColor, MatrixDensity matrixDensity, boolean discordRpcEnabled, ObscurePreset obscurePreset, boolean obscureCoordinatesEnabled, boolean obscureBlockRotationEnabled, boolean obscureBedrockFloorEnabled) {
		this.customMenusEnabled = customMenusEnabled;
		this.customFontEnabled = customFontEnabled;
		this.backgroundStyle = backgroundStyle;
		this.matrixColor = matrixColor;
		this.matrixDensity = matrixDensity;
		this.discordRpcEnabled = discordRpcEnabled;
		this.obscurePreset = obscurePreset;
		this.obscureCoordinatesEnabled = obscureCoordinatesEnabled;
		this.obscureBlockRotationEnabled = obscureBlockRotationEnabled;
		this.obscureBedrockFloorEnabled = obscureBedrockFloorEnabled;
	}

	public static synchronized NexoConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public boolean customMenusEnabled() {
		return customMenusEnabled;
	}

	public void setCustomMenusEnabled(boolean enabled) {
		this.customMenusEnabled = enabled;
		save();
	}

	public boolean customFontEnabled() {
		return customFontEnabled;
	}

	public void setCustomFontEnabled(boolean enabled) {
		this.customFontEnabled = enabled;
		save();
	}

	public BackgroundStyle backgroundStyle() {
		return backgroundStyle;
	}

	public void setBackgroundStyle(BackgroundStyle style) {
		this.backgroundStyle = style;
		save();
	}

	public MatrixColor matrixColor() {
		return matrixColor;
	}

	public void setMatrixColor(MatrixColor color) {
		this.matrixColor = color;
		save();
	}

	public MatrixDensity matrixDensity() {
		return matrixDensity;
	}

	public void setMatrixDensity(MatrixDensity density) {
		this.matrixDensity = density;
		save();
	}

	public boolean discordRpcEnabled() {
		return discordRpcEnabled;
	}

	public void setDiscordRpcEnabled(boolean enabled) {
		this.discordRpcEnabled = enabled;
		save();
	}

	public ObscurePreset obscurePreset() {
		return obscurePreset;
	}

	public void setObscurePreset(ObscurePreset preset) {
		this.obscurePreset = preset;
		save();
	}

	/** The stored CUSTOM-mode flag; use {@link #obscureCoordinatesActive()} to know whether the feature is on. */
	public boolean obscureCoordinatesEnabled() {
		return obscureCoordinatesEnabled;
	}

	public void setObscureCoordinatesEnabled(boolean enabled) {
		this.obscureCoordinatesEnabled = enabled;
		save();
	}

	/** The stored CUSTOM-mode flag; use {@link #obscureBlockRotationActive()} to know whether the feature is on. */
	public boolean obscureBlockRotationEnabled() {
		return obscureBlockRotationEnabled;
	}

	public void setObscureBlockRotationEnabled(boolean enabled) {
		this.obscureBlockRotationEnabled = enabled;
		save();
	}

	/** Whether F3 coordinates are obscured, after resolving the preset. */
	public boolean obscureCoordinatesActive() {
		return switch (obscurePreset) {
			case NONE -> false;
			case FULL -> true;
			case CUSTOM -> obscureCoordinatesEnabled;
		};
	}

	/** Whether block model randomization is re-seeded from fake coordinates, after resolving the preset. */
	public boolean obscureBlockRotationActive() {
		return switch (obscurePreset) {
			case NONE -> false;
			case FULL -> true;
			case CUSTOM -> obscureBlockRotationEnabled;
		};
	}

	/** The stored CUSTOM-mode flag; use {@link #obscureBedrockFloorActive()} to know whether the feature is on. */
	public boolean obscureBedrockFloorEnabled() {
		return obscureBedrockFloorEnabled;
	}

	public void setObscureBedrockFloorEnabled(boolean enabled) {
		this.obscureBedrockFloorEnabled = enabled;
		save();
	}

	/** Whether deep blocks render as bedrock (anti bedrock-pattern-matching), after resolving the preset. */
	public boolean obscureBedrockFloorActive() {
		return switch (obscurePreset) {
			case NONE -> false;
			case FULL -> true;
			case CUSTOM -> obscureBedrockFloorEnabled;
		};
	}

	public boolean bedrockHoleFinderEnabled() {
		return bedrockHoleFinderEnabled;
	}

	public void setBedrockHoleFinderEnabled(boolean enabled) {
		this.bedrockHoleFinderEnabled = enabled;
		save();
	}

	public BedrockHoleRadius bedrockHoleRadius() {
		return bedrockHoleRadius;
	}

	public void setBedrockHoleRadius(BedrockHoleRadius radius) {
		this.bedrockHoleRadius = radius;
		save();
	}

	/** Smallest pocket, in blocks, still worth reporting. */
	public int bedrockHoleMinSize() {
		return bedrockHoleMinSize;
	}

	/** Setting a minimum above the maximum pushes the maximum up with it, so the range can never be empty. */
	public void setBedrockHoleMinSize(int minSize) {
		this.bedrockHoleMinSize = Math.clamp(minSize, MIN_HOLE_SIZE_FLOOR, MAX_HOLE_SIZE_CEILING);
		this.bedrockHoleMaxSize = Math.max(bedrockHoleMaxSize, bedrockHoleMinSize);
		save();
	}

	/** Largest pocket still counted as a hole; a connected region bigger than this is ordinary rock. */
	public int bedrockHoleMaxSize() {
		return bedrockHoleMaxSize;
	}

	public void setBedrockHoleMaxSize(int maxSize) {
		this.bedrockHoleMaxSize = Math.clamp(maxSize, MIN_HOLE_SIZE_FLOOR, MAX_HOLE_SIZE_CEILING);
		this.bedrockHoleMinSize = Math.min(bedrockHoleMinSize, bedrockHoleMaxSize);
		save();
	}

	/** Whether found holes' coordinates appear in chat and toasts — off is the streaming-safe setting. */
	public boolean bedrockHoleShowCoordsEnabled() {
		return bedrockHoleShowCoordsEnabled;
	}

	public void setBedrockHoleShowCoordsEnabled(boolean enabled) {
		this.bedrockHoleShowCoordsEnabled = enabled;
		save();
	}

	/**
	 * Whether a find notification may name coordinates: the explicit setting, and
	 * never while {@link #obscureCoordinatesActive() F3 coordinates are obscured} —
	 * printing a real position to chat would hand back exactly what that feature
	 * exists to withhold.
	 */
	public boolean bedrockHoleCoordsVisible() {
		return bedrockHoleShowCoordsEnabled && !obscureCoordinatesActive();
	}

	public boolean bedrockHoleLabelsEnabled() {
		return bedrockHoleLabelsEnabled;
	}

	public void setBedrockHoleLabelsEnabled(boolean enabled) {
		this.bedrockHoleLabelsEnabled = enabled;
		save();
	}

	public boolean bedrockHoleChatEnabled() {
		return bedrockHoleChatEnabled;
	}

	public void setBedrockHoleChatEnabled(boolean enabled) {
		this.bedrockHoleChatEnabled = enabled;
		save();
	}

	public boolean bedrockHoleToastEnabled() {
		return bedrockHoleToastEnabled;
	}

	public void setBedrockHoleToastEnabled(boolean enabled) {
		this.bedrockHoleToastEnabled = enabled;
		save();
	}

	public boolean bedrockHoleSoundEnabled() {
		return bedrockHoleSoundEnabled;
	}

	public void setBedrockHoleSoundEnabled(boolean enabled) {
		this.bedrockHoleSoundEnabled = enabled;
		save();
	}

	/** Whether any of the three find notifications (chat, toast, sound) is on. */
	public boolean bedrockHoleNotifyEnabled() {
		return bedrockHoleChatEnabled || bedrockHoleToastEnabled || bedrockHoleSoundEnabled;
	}

	// ------------------------------------------------------------------
	// Chat
	// ------------------------------------------------------------------

	/**
	 * Whether incoming chat is written to the local history database.
	 *
	 * <p>Defaults to <b>off</b>. Everything else in this file changes how the
	 * player's own client looks or behaves; this one writes down what other
	 * people said, and turning that on is a decision to ask for rather than to
	 * make on someone's behalf — even though the file never leaves the machine.
	 */
	public boolean chatHistoryEnabled() {
		return chatHistoryEnabled;
	}

	public void setChatHistoryEnabled(boolean enabled) {
		this.chatHistoryEnabled = enabled;
		save();
	}

	/** Whether the pattern list is applied to incoming chat. Harmless with no patterns, so it defaults on. */
	public boolean chatFilterEnabled() {
		return chatFilterEnabled;
	}

	public void setChatFilterEnabled(boolean enabled) {
		this.chatFilterEnabled = enabled;
		save();
	}

	// ------------------------------------------------------------------
	// Badge sync
	// ------------------------------------------------------------------

	public boolean badgeSyncEnabled() {
		return badgeSyncEnabled;
	}

	public void setBadgeSyncEnabled(boolean enabled) {
		this.badgeSyncEnabled = enabled;
		save();
		// Applies the change now — leaving the account in the roster until the
		// next restart would make "off" mean nothing to everyone else.
		dev.nexoclient.nexomod.badge.NexoBadges.onSettingChanged(enabled);
	}

	public boolean badgeSyncRegistered(UUID account) {
		return badgeSyncRegisteredAccounts.containsKey(account);
	}

	/**
	 * When this account last registered, in epoch seconds, or 0 if never.
	 *
	 * <p>Zero is also what an entry written by 0.6.1 reads as, because that
	 * build recorded only that an account was registered and not when — so
	 * upgrading re-registers everyone once, which is what puts them back on a
	 * clock instead of drifting towards being pruned.
	 */
	public long badgeSyncRegisteredAt(UUID account) {
		return badgeSyncRegisteredAccounts.getOrDefault(account, 0L);
	}

	public void setBadgeSyncRegistered(UUID account, boolean registered) {
		boolean changed = registered
				? !Long.valueOf(nowSeconds()).equals(
						badgeSyncRegisteredAccounts.put(account, nowSeconds()))
				: badgeSyncRegisteredAccounts.remove(account) != null;
		if (changed) {
			save();
		}
	}

	private static long nowSeconds() {
		return System.currentTimeMillis() / 1000L;
	}

	/** The accounts still believed to be in the roster, for the opt-out sweep. */
	public Set<UUID> badgeSyncRegisteredAccounts() {
		return Set.copyOf(badgeSyncRegisteredAccounts.keySet());
	}

	// ------------------------------------------------------------------
	// Tactical sound indicator (full jar only)
	// ------------------------------------------------------------------

	public boolean tacticalEnabled() {
		return tacticalEnabled;
	}

	public void setTacticalEnabled(boolean enabled) {
		this.tacticalEnabled = enabled;
		save();
	}

	/** How far away a sound can be and still produce an indicator, in blocks. */
	public int tacticalRange() {
		return tacticalRange;
	}

	public void setTacticalRange(int range) {
		this.tacticalRange = Math.clamp(range, MIN_TACTICAL_RANGE, MAX_TACTICAL_RANGE);
		save();
	}

	/** Bitmask over {@code SoundSource.ordinal()}; see {@link #DEFAULT_TACTICAL_CATEGORIES}. */
	public int tacticalCategoryMask() {
		return tacticalCategoryMask;
	}

	public boolean tacticalCategoryEnabled(SoundSource source) {
		return (tacticalCategoryMask & (1 << source.ordinal())) != 0;
	}

	public void setTacticalCategoryEnabled(SoundSource source, boolean enabled) {
		int bit = 1 << source.ordinal();
		this.tacticalCategoryMask = enabled ? (tacticalCategoryMask | bit) : (tacticalCategoryMask & ~bit);
		save();
	}

	/** Whether each indicator carries the sound's subtitle text, the way vanilla subtitles do. */
	public boolean tacticalLabelsEnabled() {
		return tacticalLabelsEnabled;
	}

	public void setTacticalLabelsEnabled(boolean enabled) {
		this.tacticalLabelsEnabled = enabled;
		save();
	}

	// ------------------------------------------------------------------
	// Smart armor HUD (full jar only)
	// ------------------------------------------------------------------

	public boolean armorHudEnabled() {
		return armorHudEnabled;
	}

	public void setArmorHudEnabled(boolean enabled) {
		this.armorHudEnabled = enabled;
		save();
	}

	/** Remaining-durability percentage at or below which a piece is drawn as a warning. */
	public int armorHudWarnPercent() {
		return armorHudWarnPercent;
	}

	public void setArmorHudWarnPercent(int percent) {
		this.armorHudWarnPercent = Math.clamp(percent, 0, 100);
		save();
	}

	public boolean armorHudOffhandEnabled() {
		return armorHudOffhandEnabled;
	}

	public void setArmorHudOffhandEnabled(boolean enabled) {
		this.armorHudOffhandEnabled = enabled;
		save();
	}

	// ------------------------------------------------------------------
	// Client-side environment override (full jar only)
	// ------------------------------------------------------------------

	public TimeOverride timeOverride() {
		return timeOverride;
	}

	public void setTimeOverride(TimeOverride override) {
		this.timeOverride = override;
		save();
	}

	public WeatherOverride weatherOverride() {
		return weatherOverride;
	}

	public void setWeatherOverride(WeatherOverride override) {
		this.weatherOverride = override;
		save();
	}

	// ------------------------------------------------------------------
	// Chunk history / state-triggered macros (full jar only)
	// ------------------------------------------------------------------

	public boolean chunkHistoryEnabled() {
		return chunkHistoryEnabled;
	}

	public void setChunkHistoryEnabled(boolean enabled) {
		this.chunkHistoryEnabled = enabled;
		save();
	}

	public boolean macroTriggersEnabled() {
		return macroTriggersEnabled;
	}

	public void setMacroTriggersEnabled(boolean enabled) {
		this.macroTriggersEnabled = enabled;
		save();
	}

	private static NexoConfig load() {
		Properties props = new Properties();
		if (Files.exists(PATH)) {
			try (InputStream in = Files.newInputStream(PATH)) {
				props.load(in);
			} catch (IOException e) {
				LOGGER.warn("Failed to read {}, using defaults", PATH, e);
			}
		}
		boolean customMenusEnabled = Boolean.parseBoolean(props.getProperty("customMenusEnabled", "true"));
		boolean customFontEnabled = Boolean.parseBoolean(props.getProperty("customFontEnabled", "true"));
		BackgroundStyle backgroundStyle = enumOrDefault(BackgroundStyle.class, props.getProperty("backgroundStyle"), BackgroundStyle.STARFIELD);
		MatrixColor matrixColor = enumOrDefault(MatrixColor.class, props.getProperty("matrixColor"), MatrixColor.GREEN);
		MatrixDensity matrixDensity = enumOrDefault(MatrixDensity.class, props.getProperty("matrixDensity"), MatrixDensity.NORMAL);
		boolean discordRpcEnabled = Boolean.parseBoolean(props.getProperty("discordRpcEnabled", "true"));
		boolean obscureCoordinatesEnabled = Boolean.parseBoolean(props.getProperty("obscureCoordinatesEnabled", "false"));
		boolean obscureBlockRotationEnabled = Boolean.parseBoolean(props.getProperty("obscureBlockRotationEnabled", "false"));
		boolean obscureBedrockFloorEnabled = Boolean.parseBoolean(props.getProperty("obscureBedrockFloorEnabled", "false"));
		// Configs from before presets existed only have the coordinates flag; CUSTOM keeps its effect.
		ObscurePreset obscurePreset = enumOrDefault(ObscurePreset.class, props.getProperty("obscurePreset"),
				obscureCoordinatesEnabled ? ObscurePreset.CUSTOM : ObscurePreset.NONE);
		return new NexoConfig(customMenusEnabled, customFontEnabled, backgroundStyle, matrixColor, matrixDensity, discordRpcEnabled, obscurePreset, obscureCoordinatesEnabled, obscureBlockRotationEnabled, obscureBedrockFloorEnabled)
				.withBedrockHoleSettings(props)
				.withChatSettings(props)
				.withBadgeSettings(props)
				.withFullFeatureSettings(props);
	}

	/** @see #withBedrockHoleSettings for why these are applied after construction. */
	private NexoConfig withChatSettings(Properties props) {
		chatHistoryEnabled = Boolean.parseBoolean(props.getProperty("chatHistoryEnabled", "false"));
		chatFilterEnabled = Boolean.parseBoolean(props.getProperty("chatFilterEnabled", "true"));
		return this;
	}

	private NexoConfig withBadgeSettings(Properties props) {
		badgeSyncEnabled = Boolean.parseBoolean(props.getProperty("badgeSyncEnabled", "true"));
		// The pre-0.6.1 `badgeSyncRegistered` boolean is deliberately not read.
		// It said "somebody here is registered" without saying who, so there is
		// nothing to migrate it to. Dropping it re-registers on the next start,
		// which is idempotent on the service side and costs one request.
		for (String part : props.getProperty("badgeSyncRegisteredAccounts", "").split(",")) {
			String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			// `uuid=epoch` since 0.6.2. A bare `uuid` is what 0.6.1 wrote; it
			// reads as "registered, but at an unknown time", which the daily
			// check treats as due and re-registers on the next launch.
			int split = trimmed.indexOf('=');
			String id = split < 0 ? trimmed : trimmed.substring(0, split);
			long at = 0L;
			if (split >= 0) {
				try {
					at = Long.parseLong(trimmed.substring(split + 1).trim());
				} catch (NumberFormatException e) {
					at = 0L;
				}
			}
			try {
				badgeSyncRegisteredAccounts.put(UUID.fromString(id.trim()), at);
			} catch (IllegalArgumentException e) {
				LOGGER.warn("Ignoring malformed badge account id {}", trimmed);
			}
		}
		return this;
	}

	/**
	 * Settings only the full jar's screens can edit. Loaded unconditionally: the
	 * light jar has no way to change them, but it must still round-trip them
	 * rather than delete them, or running the light jar once would silently wipe
	 * the full jar's configuration.
	 */
	private NexoConfig withFullFeatureSettings(Properties props) {
		tacticalEnabled = Boolean.parseBoolean(props.getProperty("tacticalEnabled", "false"));
		tacticalRange = boundedIntOrDefault(props.getProperty("tacticalRange"), 48, MIN_TACTICAL_RANGE, MAX_TACTICAL_RANGE);
		tacticalCategoryMask = boundedIntOrDefault(props.getProperty("tacticalCategoryMask"),
				DEFAULT_TACTICAL_CATEGORIES, 0, Integer.MAX_VALUE);
		tacticalLabelsEnabled = Boolean.parseBoolean(props.getProperty("tacticalLabelsEnabled", "true"));
		armorHudEnabled = Boolean.parseBoolean(props.getProperty("armorHudEnabled", "false"));
		armorHudWarnPercent = boundedIntOrDefault(props.getProperty("armorHudWarnPercent"), 20, 0, 100);
		armorHudOffhandEnabled = Boolean.parseBoolean(props.getProperty("armorHudOffhandEnabled", "true"));
		timeOverride = enumOrDefault(TimeOverride.class, props.getProperty("timeOverride"), TimeOverride.OFF);
		weatherOverride = enumOrDefault(WeatherOverride.class, props.getProperty("weatherOverride"), WeatherOverride.OFF);
		chunkHistoryEnabled = Boolean.parseBoolean(props.getProperty("chunkHistoryEnabled", "false"));
		macroTriggersEnabled = Boolean.parseBoolean(props.getProperty("macroTriggersEnabled", "false"));
		return this;
	}

	/**
	 * Applied after construction rather than through the constructor, which
	 * already carries as many positional booleans as is readable — one more
	 * block of them would be a swap waiting to happen.
	 */
	private NexoConfig withBedrockHoleSettings(Properties props) {
		bedrockHoleFinderEnabled = Boolean.parseBoolean(props.getProperty("bedrockHoleFinderEnabled", "false"));
		bedrockHoleRadius = enumOrDefault(BedrockHoleRadius.class, props.getProperty("bedrockHoleRadius"), BedrockHoleRadius.CHUNKS_8);
		bedrockHoleMinSize = intOrDefault(props.getProperty("bedrockHoleMinSize"), 2);
		bedrockHoleMaxSize = Math.max(bedrockHoleMinSize, intOrDefault(props.getProperty("bedrockHoleMaxSize"), 30));
		bedrockHoleShowCoordsEnabled = Boolean.parseBoolean(props.getProperty("bedrockHoleShowCoordsEnabled", "true"));
		bedrockHoleLabelsEnabled = Boolean.parseBoolean(props.getProperty("bedrockHoleLabelsEnabled", "true"));
		bedrockHoleChatEnabled = Boolean.parseBoolean(props.getProperty("bedrockHoleChatEnabled", "true"));
		bedrockHoleToastEnabled = Boolean.parseBoolean(props.getProperty("bedrockHoleToastEnabled", "false"));
		bedrockHoleSoundEnabled = Boolean.parseBoolean(props.getProperty("bedrockHoleSoundEnabled", "true"));
		return this;
	}

	/**
	 * Like {@link #intOrDefault}, but with the bounds passed in.
	 * {@code intOrDefault} clamps to the bedrock-hole size range specifically,
	 * which is wrong for every setting that isn't one — reusing it for the
	 * tactical range would silently cap it at 64 blocks.
	 */
	private static int boundedIntOrDefault(String value, int fallback, int min, int max) {
		if (value == null) {
			return fallback;
		}
		try {
			return Math.clamp(Integer.parseInt(value.trim()), min, max);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int intOrDefault(String value, int fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Math.clamp(Integer.parseInt(value.trim()), MIN_HOLE_SIZE_FLOOR, MAX_HOLE_SIZE_CEILING);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static <E extends Enum<E>> E enumOrDefault(Class<E> type, String value, E fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	private void save() {
		Properties props = new Properties();
		props.setProperty("customMenusEnabled", Boolean.toString(customMenusEnabled));
		props.setProperty("customFontEnabled", Boolean.toString(customFontEnabled));
		props.setProperty("backgroundStyle", backgroundStyle.name());
		props.setProperty("matrixColor", matrixColor.name());
		props.setProperty("matrixDensity", matrixDensity.name());
		props.setProperty("discordRpcEnabled", Boolean.toString(discordRpcEnabled));
		props.setProperty("obscurePreset", obscurePreset.name());
		props.setProperty("obscureCoordinatesEnabled", Boolean.toString(obscureCoordinatesEnabled));
		props.setProperty("obscureBlockRotationEnabled", Boolean.toString(obscureBlockRotationEnabled));
		props.setProperty("obscureBedrockFloorEnabled", Boolean.toString(obscureBedrockFloorEnabled));
		props.setProperty("bedrockHoleFinderEnabled", Boolean.toString(bedrockHoleFinderEnabled));
		props.setProperty("bedrockHoleRadius", bedrockHoleRadius.name());
		props.setProperty("bedrockHoleMinSize", Integer.toString(bedrockHoleMinSize));
		props.setProperty("bedrockHoleMaxSize", Integer.toString(bedrockHoleMaxSize));
		props.setProperty("bedrockHoleShowCoordsEnabled", Boolean.toString(bedrockHoleShowCoordsEnabled));
		props.setProperty("bedrockHoleLabelsEnabled", Boolean.toString(bedrockHoleLabelsEnabled));
		props.setProperty("bedrockHoleChatEnabled", Boolean.toString(bedrockHoleChatEnabled));
		props.setProperty("bedrockHoleToastEnabled", Boolean.toString(bedrockHoleToastEnabled));
		props.setProperty("bedrockHoleSoundEnabled", Boolean.toString(bedrockHoleSoundEnabled));
		props.setProperty("chatHistoryEnabled", Boolean.toString(chatHistoryEnabled));
		props.setProperty("chatFilterEnabled", Boolean.toString(chatFilterEnabled));
		props.setProperty("badgeSyncEnabled", Boolean.toString(badgeSyncEnabled));
		props.setProperty("badgeSyncRegisteredAccounts", badgeSyncRegisteredAccounts.entrySet()
				.stream()
				.map(entry -> entry.getKey() + "=" + entry.getValue())
				.sorted()
				.collect(Collectors.joining(",")));
		props.setProperty("tacticalEnabled", Boolean.toString(tacticalEnabled));
		props.setProperty("tacticalRange", Integer.toString(tacticalRange));
		props.setProperty("tacticalCategoryMask", Integer.toString(tacticalCategoryMask));
		props.setProperty("tacticalLabelsEnabled", Boolean.toString(tacticalLabelsEnabled));
		props.setProperty("armorHudEnabled", Boolean.toString(armorHudEnabled));
		props.setProperty("armorHudWarnPercent", Integer.toString(armorHudWarnPercent));
		props.setProperty("armorHudOffhandEnabled", Boolean.toString(armorHudOffhandEnabled));
		props.setProperty("timeOverride", timeOverride.name());
		props.setProperty("weatherOverride", weatherOverride.name());
		props.setProperty("chunkHistoryEnabled", Boolean.toString(chunkHistoryEnabled));
		props.setProperty("macroTriggersEnabled", Boolean.toString(macroTriggersEnabled));
		try {
			Files.createDirectories(PATH.getParent());
			try (OutputStream out = Files.newOutputStream(PATH)) {
				props.store(out, "Nexo Client appearance settings");
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to save {}", PATH, e);
		}
	}
}
