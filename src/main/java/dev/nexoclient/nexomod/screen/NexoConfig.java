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

	private boolean customMenusEnabled;
	private boolean customFontEnabled;
	private BackgroundStyle backgroundStyle;
	private MatrixColor matrixColor;
	private MatrixDensity matrixDensity;

	private NexoConfig(boolean customMenusEnabled, boolean customFontEnabled, BackgroundStyle backgroundStyle, MatrixColor matrixColor, MatrixDensity matrixDensity) {
		this.customMenusEnabled = customMenusEnabled;
		this.customFontEnabled = customFontEnabled;
		this.backgroundStyle = backgroundStyle;
		this.matrixColor = matrixColor;
		this.matrixDensity = matrixDensity;
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
		return new NexoConfig(customMenusEnabled, customFontEnabled, backgroundStyle, matrixColor, matrixDensity);
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
