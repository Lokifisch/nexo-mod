package dev.nexoclient.nexomod.screen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;

import dev.nexoclient.nexomod.NexoMod;

/**
 * The Nexo launcher's look, read from its {@code appearance.txt}, so the mod's
 * menus follow whatever theme and background the launcher shows. Only when the
 * game was started by the Nexo launcher: its instances live at
 * {@code <launcher data>/instances/<id>}, with {@code appearance.txt} two levels
 * up. Any other launcher has no such file and the mod's own settings apply.
 *
 * <p>The format is the launcher's ({@code Client/.../screens/settings.rs}
 * {@code serialize}): {@code theme=} a built-in theme's name or
 * {@code custom:<n>}, {@code background=} an effect's label,
 * {@code particle_amount=} / {@code particle_speed=} as percent, and one
 * {@code custom=<effect>|#rrggbb x7|<name>} line per custom theme. Anything that
 * doesn't parse falls back the way the launcher's own parser does.
 *
 * <p>Re-read when the file changes (checked at most once a second, from the
 * menu background), so a change in the launcher shows up without a restart.
 */
public final class NexoLauncherAppearance {
	private NexoLauncherAppearance() {}

	/** What the launcher shows; null fields mean "not given". */
	public record Look(NexoStyle.Theme theme, int[] customColors, NexoBackdrop.Effect effect, int amount, int speed) {
		/** The background to draw: the launcher's, else the theme's own, as the launcher falls back. */
		public NexoBackdrop.Effect backgroundEffect(NexoBackdrop.Effect customEffect) {
			return effect != null ? effect : customColors != null && customEffect != null ? customEffect : theme.effect;
		}
	}

	/** The selected custom theme's own effect, its first field; null for a built-in theme. */
	private static NexoBackdrop.Effect customEffect;

	private static final long CHECK_EVERY_MS = 1000;
	private static Path file;
	private static boolean located;

	private static long checkedAt;
	private static long modifiedAt = Long.MIN_VALUE;
	private static Look look;
	/** What was last applied to {@link NexoStyle}, so it is applied once per change. */
	private static Object applied;

	private static Path file() {
		if (!located) {
			located = true;
			file = locate();
		}
		return file;
	}

	private static Path locate() {
		Path instance = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		Path instances = instance.getParent();
		if (instances == null || !"instances".equals(String.valueOf(instances.getFileName())) || instances.getParent() == null) {
			return null;
		}
		return instances.getParent().resolve("appearance.txt");
	}

	/** The launcher's look if syncing is on and the file exists, else null. Cheap to call every frame. */
	public static Look current() {
		if (file() == null || !NexoConfig.get().launcherSyncEnabled()) {
			return null;
		}
		long now = System.currentTimeMillis();
		if (now - checkedAt >= CHECK_EVERY_MS) {
			checkedAt = now;
			reload();
		}
		return look;
	}

	private static void reload() {
		try {
			// One stat for type, time and size: this runs on the render thread.
			BasicFileAttributes attributes;
			try {
				attributes = Files.readAttributes(file, BasicFileAttributes.class);
			} catch (NoSuchFileException e) {
				attributes = null;
			}
			// The launcher's own reader refuses big files; a settings file is a few hundred bytes.
			if (attributes == null || !attributes.isRegularFile() || attributes.size() > 64 * 1024) {
				look = null;
				modifiedAt = Long.MIN_VALUE;
				return;
			}
			long modified = attributes.lastModifiedTime().toMillis();
			if (modified == modifiedAt) {
				return;
			}
			look = parse(Files.readString(file, StandardCharsets.UTF_8));
			// Only now: a failed read is retried next second rather than skipped until the next save.
			modifiedAt = modified;
		} catch (IOException | RuntimeException e) {
			NexoMod.LOGGER.warn("[nexomod] Could not read the launcher's appearance file", e);
			look = null;
		}
	}

	/** The effect to draw for the current launcher look. */
	public static NexoBackdrop.Effect effect(Look current) {
		return current.backgroundEffect(customEffect);
	}

	static Look parse(String contents) {
		List<int[]> customColors = new ArrayList<>();
		List<NexoBackdrop.Effect> customEffects = new ArrayList<>();
		String theme = null, background = null, amount = null, speed = null;
		// Values exactly as written, like the launcher's parser: a line one accepts and
		// the other refuses would shift the custom:N numbering between them.
		for (String line : contents.split("\n")) {
			int equals = line.indexOf('=');
			if (equals < 0) {
				continue;
			}
			String key = line.substring(0, equals), value = line.substring(equals + 1);
			switch (key) {
				case "theme" -> theme = theme == null ? value : theme;
				case "background" -> background = background == null ? value : background;
				case "particle_amount" -> amount = amount == null ? value : amount;
				case "particle_speed" -> speed = speed == null ? value : speed;
				case "custom" -> {
					int[] colors = parseCustom(value);
					if (colors != null) {
						customColors.add(colors);
						customEffects.add(NexoBackdrop.Effect.labelled(value.substring(0, value.indexOf('|'))));
					}
				}
				default -> {}
			}
		}
		NexoStyle.Theme builtIn = NexoStyle.Theme.NEXO;
		int[] custom = null;
		NexoBackdrop.Effect ownEffect = null;
		if (theme != null && theme.startsWith("custom:")) {
			try {
				int at = Integer.parseInt(theme.substring(7));
				if (at >= 0 && at < customColors.size()) {
					custom = customColors.get(at);
					ownEffect = customEffects.get(at);
				}
			} catch (NumberFormatException ignored) {
				// The launcher shows its first theme then, and so does this.
			}
		} else if (theme != null) {
			for (NexoStyle.Theme candidate : NexoStyle.Theme.values()) {
				if (candidate.label.equals(theme)) {
					builtIn = candidate;
				}
			}
		}
		NexoBackdrop.Effect effect = background == null ? null : NexoBackdrop.Effect.labelled(background);
		customEffect = ownEffect;
		return new Look(builtIn, custom, effect, percent(amount), percent(speed));
	}

	/** {@code effect|#hex x7|name}, or null if any colour doesn't parse. */
	private static int[] parseCustom(String value) {
		String[] parts = value.split("\\|", 9);
		if (parts.length < 9) {
			return null;
		}
		int[] colors = new int[7];
		for (int i = 0; i < 7; i++) {
			String hex = parts[i + 1];
			hex = hex.startsWith("#") ? hex.substring(1) : hex;
			if (!hex.matches("[0-9a-fA-F]{6}")) {
				return null;
			}
			colors[i] = 0xFF000000 | Integer.parseInt(hex, 16);
		}
		return colors;
	}

	private static int percent(String value) {
		try {
			// The launcher parses a u32: anything that isn't plain digits is the default.
			return value == null || !value.matches("\\d{1,9}") ? 100 : Math.clamp(Integer.parseInt(value), 25, 200);
		} catch (NumberFormatException e) {
			return 100;
		}
	}

	/**
	 * Puts the right colours into {@link NexoStyle}: the launcher's when syncing,
	 * else the mod's own theme. Called every menu frame; does work only on a change.
	 */
	public static void applyColors() {
		Look current = current();
		Object wanted = current == null ? NexoConfig.get().theme()
				: current.customColors() != null ? java.util.Arrays.toString(current.customColors()) : current.theme();
		if (wanted.equals(applied)) {
			return;
		}
		applied = wanted;
		if (current != null && current.customColors() != null) {
			NexoStyle.applyCustom(current.customColors());
		} else {
			NexoStyle.apply(current != null ? current.theme() : NexoConfig.get().theme());
		}
	}

	/** Forget what was applied, so the next {@link #applyColors} applies again. */
	public static void invalidate() {
		applied = null;
		modifiedAt = Long.MIN_VALUE;
		checkedAt = 0;
	}
}
