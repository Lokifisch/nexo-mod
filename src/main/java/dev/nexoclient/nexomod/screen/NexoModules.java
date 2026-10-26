package dev.nexoclient.nexomod.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import dev.nexoclient.nexomod.chat.NexoChatTweaks;
import dev.nexoclient.nexomod.hud.NexoArmorHud;
import dev.nexoclient.nexomod.hud.NexoComboCounter;
import dev.nexoclient.nexomod.hud.NexoCpsCounter;
import dev.nexoclient.nexomod.hud.NexoFadingLogHud;
import dev.nexoclient.nexomod.hud.NexoHudLayout;
import dev.nexoclient.nexomod.hud.NexoInventoryHud;
import dev.nexoclient.nexomod.hud.NexoKeystrokesHud;
import dev.nexoclient.nexomod.hud.NexoMiniPlayerHud;
import dev.nexoclient.nexomod.hud.NexoPotatoMode;
import dev.nexoclient.nexomod.hud.NexoPotionHud;
import dev.nexoclient.nexomod.hud.NexoSecondChatHud;
import dev.nexoclient.nexomod.hud.NexoStatsHud;
import dev.nexoclient.nexomod.norender.NexoNoRender;
import dev.nexoclient.nexomod.norender.NexoNoRenderConfig;

/**
 * Every module the right-shift menu lists, in its order: the menu's card grid
 * and the quick-settings sidebar ({@link NexoModuleShell}) both read this, so
 * the two can never disagree about what exists. Tactical's extra modules come
 * from {@link NexoQolModules} and are appended last.
 */
public final class NexoModules {
	private NexoModules() {
	}

	/**
	 * @param config the module's own settings screen, given the screen to return
	 *               to; null when there is nothing to configure.
	 * @param hud    the HUD element the quick-settings preview draws, or null.
	 */
	public record Module(Component name, Component description, BooleanSupplier enabled, Runnable toggle,
			Function<Screen, Screen> config, boolean tactical, Hud hud) {
		public String key() {
			return keyOf(name);
		}
	}

	/** A HUD element plus where it draws, so the preview can frame it and Reset can re-home it. */
	public record Hud(NexoHudLayout.Element element, HudElement renderer,
			BiFunction<Integer, Integer, ScreenRectangle> bounds) {
	}

	public static List<Module> all() {
		NexoConfig config = NexoConfig.get();
		List<Module> list = new ArrayList<>();
		list.add(main("nexomod.qol.keystrokes", "nexomod.qol.keystrokes.description", config::keystrokesHudEnabled,
				() -> config.setKeystrokesHudEnabled(!config.keystrokesHudEnabled()), NexoKeystrokesConfigScreen::new,
				new Hud(NexoHudLayout.Element.KEYSTROKES, NexoKeystrokesHud.INSTANCE, NexoKeystrokesHud::resolveBounds)));
		list.add(main("nexomod.qol.cps", "nexomod.qol.cps.description", config::cpsCounterEnabled,
				() -> config.setCpsCounterEnabled(!config.cpsCounterEnabled()), null,
				new Hud(NexoHudLayout.Element.CPS, NexoCpsCounter.INSTANCE, NexoCpsCounter::resolveBounds)));
		list.add(main("nexomod.settings.armorHud.enabled", "nexomod.qol.armorHud.description", config::armorHudEnabled,
				() -> config.setArmorHudEnabled(!config.armorHudEnabled()), NexoArmorHudConfigScreen::new,
				new Hud(NexoHudLayout.Element.ARMOR, NexoArmorHud.INSTANCE, NexoArmorHud::resolveBounds)));
		// Directly under the Armor HUD on purpose: the two names are one word
		// apart and the only way to tell them apart is seeing them side by side.
		list.add(main("nexomod.settings.armorBar.enabled", "nexomod.qol.armorBar.description", config::armorBarEnabled,
				() -> config.setArmorBarEnabled(!config.armorBarEnabled()), NexoArmorBarConfigScreen::new, null));
		// Nothing to configure, and a quick single click is the point before AFK.
		list.add(main("nexomod.qol.potatoMode", "nexomod.qol.potatoMode.description", NexoPotatoMode::active,
				NexoPotatoMode::toggle, null, null));
		list.add(main("nexomod.stats.title", "nexomod.stats.description", config::statsHudEnabled,
				() -> config.setStatsHudEnabled(!config.statsHudEnabled()), NexoStatsConfigScreen::new,
				new Hud(NexoHudLayout.Element.STATS, NexoStatsHud.INSTANCE, NexoStatsHud::resolveBounds)));
		list.add(main("nexomod.qol.potion", "nexomod.qol.potion.description", config::potionHudEnabled,
				() -> config.setPotionHudEnabled(!config.potionHudEnabled()), null,
				new Hud(NexoHudLayout.Element.POTION, NexoPotionHud.INSTANCE, NexoPotionHud::resolveBounds)));
		list.add(main("nexomod.qol.combo", "nexomod.qol.combo.description", config::comboCounterEnabled,
				() -> config.setComboCounterEnabled(!config.comboCounterEnabled()), null,
				new Hud(NexoHudLayout.Element.COMBO, NexoComboCounter.INSTANCE, NexoComboCounter::resolveBounds)));
		list.add(main("nexomod.qol.targetHud", "nexomod.qol.targetHud.description", config::targetHudEnabled,
				() -> config.setTargetHudEnabled(!config.targetHudEnabled()), null, null));
		list.add(main("nexomod.qol.reach", "nexomod.qol.reach.description", config::reachDisplayEnabled,
				() -> config.setReachDisplayEnabled(!config.reachDisplayEnabled()), null, null));
		list.add(main("nexomod.qol.totemPops", "nexomod.qol.totemPops.description", config::totemPopCounterEnabled,
				() -> config.setTotemPopCounterEnabled(!config.totemPopCounterEnabled()), null, null));
		list.add(main("nexomod.qol.hitOverlay", "nexomod.qol.hitOverlay.description", config::hitOverlayEnabled,
				() -> config.setHitOverlayEnabled(!config.hitOverlayEnabled()), null, null));
		list.add(main("nexomod.qol.durability", "nexomod.qol.durability.description", config::durabilityWarningEnabled,
				() -> config.setDurabilityWarningEnabled(!config.durabilityWarningEnabled()), null, null));
		list.add(main("nexomod.qol.autoReconnect", "nexomod.qol.autoReconnect.description", config::autoReconnectEnabled,
				() -> config.setAutoReconnectEnabled(!config.autoReconnectEnabled()), null, null));
		list.add(main("nexomod.qol.stackCount", "nexomod.qol.stackCount.description", config::stackCountEnabled,
				() -> config.setStackCountEnabled(!config.stackCountEnabled()), null, null));
		list.add(main("nexomod.qol.containerSearch", "nexomod.qol.containerSearch.description", config::containerSearchEnabled,
				() -> config.setContainerSearchEnabled(!config.containerSearchEnabled()), null, null));
		list.add(main("nexomod.qol.actionbarLog", "nexomod.qol.actionbarLog.description", config::actionbarLogEnabled,
				() -> config.setActionbarLogEnabled(!config.actionbarLogEnabled()), null,
				new Hud(NexoHudLayout.Element.ACTIONBAR_LOG, NexoFadingLogHud.ACTIONBAR, NexoFadingLogHud.ACTIONBAR::resolveBounds)));
		list.add(main("nexomod.qol.pickupLog", "nexomod.qol.pickupLog.description", config::pickupLogEnabled,
				() -> config.setPickupLogEnabled(!config.pickupLogEnabled()), null,
				new Hud(NexoHudLayout.Element.PICKUP_LOG, NexoFadingLogHud.PICKUPS, NexoFadingLogHud.PICKUPS::resolveBounds)));
		list.add(main("nexomod.qol.chat", "nexomod.qol.chat.description", () -> NexoChatTweaks.secondEnabled,
				() -> {
					NexoChatTweaks.secondEnabled = !NexoChatTweaks.secondEnabled;
					NexoChatTweaks.save();
				}, NexoChatTweaksScreen::new,
				new Hud(NexoHudLayout.Element.SECOND_CHAT, NexoSecondChatHud.INSTANCE, NexoSecondChatHud::resolveBounds)));
		list.add(main("nexomod.qol.inventoryHud", "nexomod.qol.inventoryHud.description", config::inventoryHudEnabled,
				() -> config.setInventoryHudEnabled(!config.inventoryHudEnabled()), NexoInventoryHudConfigScreen::new,
				new Hud(NexoHudLayout.Element.INVENTORY, NexoInventoryHud.INSTANCE, NexoInventoryHud::resolveBounds)));
		// Nothing to configure — it just shows whatever the desktop's MPRIS bus reports.
		list.add(main("nexomod.qol.miniPlayer", "nexomod.qol.miniPlayer.description", config::miniPlayerEnabled,
				() -> config.setMiniPlayerEnabled(!config.miniPlayerEnabled()), null,
				new Hud(NexoHudLayout.Element.MINI_PLAYER, NexoMiniPlayerHud.INSTANCE, NexoMiniPlayerHud::resolveBounds)));
		list.add(main("nexomod.qol.damageNumbers", "nexomod.qol.damageNumbers.description", config::damageNumbersEnabled,
				() -> config.setDamageNumbersEnabled(!config.damageNumbersEnabled()), null, null));
		// Four independent hide toggles behind one entry; it reads as on when any
		// of them is hiding something.
		list.add(main("nexomod.qol.hudCleaner", "nexomod.qol.hudCleaner.description", config::hudCleanerActive,
				() -> config.setHudCleanerAll(!config.hudCleanerActive()), NexoHudCleanerConfigScreen::new, null));
		list.add(main("nexomod.qol.toggleSprint", "nexomod.qol.toggleSprint.description", config::toggleSprintEnabled,
				() -> config.setToggleSprintEnabled(!config.toggleSprintEnabled()), null, null));
		list.add(main("nexomod.qol.toggleSneak", "nexomod.qol.toggleSneak.description", config::toggleSneakEnabled,
				() -> config.setToggleSneakEnabled(!config.toggleSneakEnabled()), null, null));
		list.add(main("nexomod.qol.screenshotCopy", "nexomod.qol.screenshotCopy.description", config::screenshotCopyEnabled,
				() -> config.setScreenshotCopyEnabled(!config.screenshotCopyEnabled()), null, null));
		list.add(main("nexomod.qol.zoom", "nexomod.qol.zoom.description", config::zoomEnabled,
				() -> config.setZoomEnabled(!config.zoomEnabled()), NexoZoomConfigScreen::new, null));
		list.add(main("nexomod.qol.crosshair", "nexomod.qol.crosshair.description", config::crosshairEnabled,
				() -> config.setCrosshairEnabled(!config.crosshairEnabled()), NexoCrosshairConfigScreen::new, null));
		// Reflects and toggles Dynamic specifically, not "is anything hidden" —
		// manually-hidden entities don't clear when Dynamic is off, so the combined
		// state would read permanently on (and be impossible to turn off by clicking)
		// the moment any single entity type was ever hidden by hand.
		list.add(main("nexomod.qol.noRender", "nexomod.qol.noRender.description",
				() -> NexoNoRenderConfig.get().dynamicEnabled(), NexoNoRender::toggleDynamic, NexoNoRenderScreen::new, null));
		for (NexoQolModules.Entry entry : NexoQolModules.entries()) {
			// Only Tactical registers extra modules.
			list.add(new Module(entry.name(), entry.description(), entry.enabled(), entry.toggle(),
					entry.openConfig(), true, null));
		}
		return list;
	}

	/** The module whose name is {@code title}'s translation key — how a settings screen finds its own entry. */
	public static Module find(Component title) {
		String key = keyOf(title);
		if (key == null) {
			return null;
		}
		for (Module module : all()) {
			if (key.equals(module.key())) {
				return module;
			}
		}
		return null;
	}

	static String keyOf(Component component) {
		return component.getContents() instanceof TranslatableContents t ? t.getKey() : null;
	}

	private static Module main(String name, String description, BooleanSupplier enabled, Runnable toggle,
			Function<Screen, Screen> config, Hud hud) {
		return new Module(Component.translatable(name), Component.translatable(description), enabled, toggle,
				config, false, hud);
	}
}
