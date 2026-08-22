package dev.nexoclient.nexomod.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The same seam {@link NexoExtraCategories} provides for the settings hub,
 * for the right-shift QoL menu: {@code src/main} cannot name a
 * {@code src/tactical} config screen directly, so a Tactical-only module
 * (the chunk-boundary overlay, so far) registers its row here instead of
 * {@link NexoQolOverlayScreen} naming it.
 *
 * <p>Most modules don't need this — anything living in {@code src/main}
 * (Keystrokes, CPS, Armor HUD, Potato Mode, Stats, Potion, Combo, the fading
 * logs) is added directly in {@code NexoQolOverlayScreen.init()}, the same
 * way the settings hub still names {@code NexoMacroListScreen} directly.
 * This registry exists only for the cross-jar case.
 */
public final class NexoQolModules {
	private static final List<Entry> ENTRIES = new ArrayList<>();

	private NexoQolModules() {
	}

	/**
	 * @param toggle flips the module without opening anything — what a click on
	 *               the row's on/off pill runs. See {@link NexoModuleRow#withToggle}.
	 */
	public record Entry(Component name, Component description, BooleanSupplier enabled,
			Runnable toggle, Function<Screen, Screen> openConfig) {
	}

	/** Called from the full variant's client initialiser. Order of calls is the order shown. */
	public static void register(Component name, Component description, BooleanSupplier enabled,
			Runnable toggle, Function<Screen, Screen> openConfig) {
		ENTRIES.add(new Entry(name, description, enabled, toggle, openConfig));
	}

	/** Empty in the light build. */
	public static List<Entry> entries() {
		return List.copyOf(ENTRIES);
	}
}
