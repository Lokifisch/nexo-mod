package dev.nexoclient.nexomod.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.contents.TranslatableContents;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoArmorHudConfigScreen;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoHudEditorScreen;
import dev.nexoclient.nexomod.screen.NexoQolModules;
import dev.nexoclient.nexomod.screen.NexoQolOverlayScreen;
import dev.nexoclient.nexomod.screen.NexoSettingsScreen;

/**
 * A screenshot tour of the restyled screens, for checking them against the
 * design mockup without anyone driving the game: once a world is loaded it
 * opens each screen in turn, saves a screenshot of it to
 * {@code screenshots/tour-<name>.png}, and quits when done.
 *
 * <p>Dev-only twice over: it does nothing unless the game runs in Loom's
 * development environment ({@code gradle runClient}) <em>and</em> the
 * {@code NEXOMOD_DEV_TOUR} environment variable is set — an environment
 * variable because Gradle's run task passes those through without any build
 * script change. A release jar carries the class but never registers it.
 */
public final class NexoDevTour {
	/** Ticks to let a freshly loaded world settle (chunks, the intro fade) before the first shot. */
	private static final int WORLD_SETTLE_TICKS = 100;
	/** Ticks a screen is shown before its screenshot, so entrance animations finish. */
	private static final int SCREEN_SETTLE_TICKS = 30;

	private record Step(String name, Supplier<Screen> screen) {}

	private static final List<Step> STEPS = new ArrayList<>();
	private static int stepIndex = -1;
	private static int wait;
	private static boolean worldRequested;
	private static int waitingTicks;
	/** The dev run's world, in {@code run/saves/}. */
	private static final String WORLD = "New World";

	private static boolean stay;

	private NexoDevTour() {}

	public static void registerIfRequested() {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment() || System.getenv("NEXOMOD_DEV_TOUR") == null) {
			return;
		}
		STEPS.add(new Step("qol", NexoQolOverlayScreen::new));
		// Opened from the menu, as a player would: the wide quick-settings layout.
		STEPS.add(new Step("armor-hud-settings", () -> new NexoArmorHudConfigScreen(new NexoQolOverlayScreen())));
		STEPS.add(new Step("nexo-settings", () -> new NexoSettingsScreen(null)));
		STEPS.add(new Step("hud-editor", () -> new NexoHudEditorScreen(null)));
		STEPS.add(new Step("aim-assist", () -> tacticalConfig("nexomod.qol.aimAssist")));
		STEPS.add(new Step("title", TitleScreen::new));
		// NEXOMOD_DEV_TOUR=stay:<step> opens just that screen and leaves it up, to
		// try it by hand instead of screenshotting it.
		String mode = System.getenv("NEXOMOD_DEV_TOUR");
		if (mode.startsWith("stay:")) {
			stay = true;
			STEPS.removeIf(step -> !step.name().equals(mode.substring(5)));
		}
		NexoMod.LOGGER.info("dev tour: {} screens queued", STEPS.size());
		ClientTickEvents.END_CLIENT_TICK.register(NexoDevTour::tick);
	}

	private static void tick(Minecraft mc) {
		if (stepIndex == STEPS.size()) {
			return;
		}
		if (stepIndex < 0) {
			// Waiting for the world: opened from the title screen once it is up,
			// and the tour starts once the player has stood there a moment.
			if (mc.player == null) {
				Screen shown = mc.gui.screen();
				if (++waitingTicks % 100 == 0) {
					NexoMod.LOGGER.info("dev tour: waiting for the world, showing {}",
							shown == null ? "nothing" : shown.getClass().getName());
				}
				// The dev world predates this Minecraft version, so opening it asks
				// whether to back it up first. A throwaway dev world: skip the backup.
				// Then, once upgraded, whether to join it now.
				if (shown != null) {
					pressByKey(shown, "selectWorld.backupJoinSkipButton");
					if (shown instanceof net.minecraft.client.gui.screens.ConfirmScreen) {
						pressByKey(shown, "gui.yes");
					}
				}
				// From the title screen as soon as it is up — or after 15s whatever is
				// showing, since a first-run or notice screen may sit in front of it.
				if (!worldRequested && (shown instanceof TitleScreen || waitingTicks > 300)) {
					worldRequested = true;
					mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.gui.setScreen(new TitleScreen()));
				}
				return;
			}
			if (wait == 0) {
				// Something for the Armor HUD preview to show. Dev world only, which
				// has cheats on; the dev config is a throwaway too.
				NexoConfig.get().setArmorHudEnabled(true);
				for (String piece : List.of("head with diamond_helmet", "chest with iron_chestplate",
						"legs with netherite_leggings", "feet with golden_boots")) {
					mc.player.connection.sendCommand("item replace entity @s armor." + piece);
				}
			}
			if (++wait < WORLD_SETTLE_TICKS) {
				return;
			}
			advance(mc);
			return;
		}
		if (stay || ++wait < SCREEN_SETTLE_TICKS) {
			return;
		}
		String file = "tour-" + STEPS.get(stepIndex).name() + ".png";
		Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1,
				message -> NexoMod.LOGGER.info("dev tour: {}", message.getString()));
		advance(mc);
	}

	private static void advance(Minecraft mc) {
		wait = 0;
		stepIndex++;
		if (stepIndex == STEPS.size()) {
			NexoMod.LOGGER.info("dev tour: done");
			mc.stop();
			return;
		}
		Step step = STEPS.get(stepIndex);
		Screen screen = step.screen().get();
		NexoMod.LOGGER.info("dev tour: {} -> {}", step.name(), screen == null ? "(none, skipped)" : screen.getClass().getSimpleName());
		if (screen == null) {
			advance(mc);
			return;
		}
		mc.gui.setScreen(screen);
	}

	/** Presses the button on {@code screen} whose label is the translation {@code key}, if there is one. */
	private static void pressByKey(Screen screen, String key) {
		for (var child : screen.children()) {
			if (child instanceof net.minecraft.client.gui.components.Button button
					&& button.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key)) {
				button.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
				return;
			}
		}
	}

	/** A Tactical module's config screen, found by its name key; null in the Legit build. */
	private static Screen tacticalConfig(String key) {
		for (NexoQolModules.Entry entry : NexoQolModules.entries()) {
			if (entry.name().getContents() instanceof TranslatableContents t && t.getKey().equals(key)
					&& entry.openConfig() != null) {
				return entry.openConfig().apply(null);
			}
		}
		return null;
	}
}
