package dev.nexoclient.nexomod.screen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The one seam between the two build variants on the UI side.
 *
 * <p>The mod ships as {@code nexomod} (full) and {@code nexomod-light}, built
 * from the same tree: {@code src/main} is in both jars, {@code src/full} only in
 * the full one, and <b>{@code src/main} must never reference a {@code src/full}
 * class</b> — a dangling reference is a {@link NoClassDefFoundError} the moment
 * the light jar opens the settings screen, not a compile error.
 *
 * <p>{@link NexoSettingsScreen} therefore cannot name the Bedrock-Hole category
 * directly. Instead the full initialiser
 * ({@code dev.nexoclient.nexomod.full.NexoFullFeatures}) registers it here at
 * client-init time, and the settings hub renders whatever is present. In the
 * light jar nothing registers, the list stays empty, and the category simply
 * does not exist — which is the point: nothing in the light build hints at a
 * feature it doesn't have.
 *
 * <p>Only full-only categories come through here. Macros are in {@code src/main}
 * and in both jars, so {@code NexoSettingsScreen} still names
 * {@code NexoMacroListScreen} directly; routing it through this registry would
 * buy nothing and cost the compiler's check that the screen exists.
 *
 * <p>Registration happens on the client init thread before any screen can be
 * opened, so the list needs no synchronisation of its own; it is only read
 * afterwards, from the render thread.
 */
public final class NexoExtraCategories {
	private static final List<Category> CATEGORIES = new ArrayList<>();

	private NexoExtraCategories() {
	}

	/**
	 * One extra row in the settings hub's category grid.
	 *
	 * @param label   the button text
	 * @param factory builds the sub-screen; the argument is the screen to
	 *                return to, which is what every {@code NexoOptionScreen}
	 *                takes as its {@code lastScreen}
	 */
	public record Category(Component label, Function<Screen, Screen> factory) {
	}

	/** Called from the full variant's client initialiser. Order of calls is the order shown. */
	public static void register(Component label, Function<Screen, Screen> factory) {
		CATEGORIES.add(new Category(label, factory));
	}

	/** Empty in the light build. */
	public static List<Category> categories() {
		return Collections.unmodifiableList(CATEGORIES);
	}
}
