package dev.nexoclient.nexomod.screen;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/** Persisted look-and-feel settings for the menu re-skin. */
public final class NexoConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/config");
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("nexomod-appearance.properties");

	private static NexoConfig instance;

	/** Slider bounds for the bedrock hole finder's size filter, in blocks. */
	public static final int MIN_HOLE_SIZE_FLOOR = 1;
	public static final int MAX_HOLE_SIZE_CEILING = 64;

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
				.withBedrockHoleSettings(props);
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
