package dev.nexoclient.nexomod.tactical;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.tactical.bedrock.BedrockHoleFinder;
import dev.nexoclient.nexomod.tactical.chunkborder.NexoChunkBorderOverlay;
import dev.nexoclient.nexomod.tactical.chunks.NexoChunkHistory;
import dev.nexoclient.nexomod.tactical.freecam.NexoFreecam;
import dev.nexoclient.nexomod.tactical.ghost.NexoGhostMode;
import dev.nexoclient.nexomod.tactical.light.NexoLightOverlay;
import dev.nexoclient.nexomod.tactical.locate.NexoLocateCommand;
import dev.nexoclient.nexomod.tactical.locate.NexoSeedIndexScreen;
import dev.nexoclient.nexomod.tactical.macro.NexoMacroTriggers;
import dev.nexoclient.nexomod.tactical.screen.NexoBedrockHoleScreen;
import dev.nexoclient.nexomod.tactical.screen.NexoFreecamConfigScreen;
import dev.nexoclient.nexomod.tactical.screen.NexoLightOverlayConfigScreen;
import dev.nexoclient.nexomod.tactical.screen.NexoTacticalFeatureScreen;
import dev.nexoclient.nexomod.tactical.sound.NexoSoundRadar;
import dev.nexoclient.nexomod.tactical.stats.NexoTacticalStats;
import dev.nexoclient.nexomod.tactical.sound.NexoSoundRadarHud;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoExtraCategories;
import dev.nexoclient.nexomod.screen.NexoQolModules;

/**
 * The second {@link ClientModInitializer}, listed only in the full jar's
 * {@code fabric.mod.json}, and the only entry point into everything under
 * {@code src/full}.
 *
 * <h2>Why the split exists</h2>
 *
 * <p>{@code nexomod-legit} is the variant a server admin can whitelist without
 * auditing it: it contains nothing that supplies information or automation
 * vanilla doesn't. Everything on the other side of that line lives in
 * {@code src/full} and is registered here — today that is the bedrock hole
 * finder.
 *
 * <h2>Where the line is</h2>
 *
 * <p><b>What triggers it</b>, not how much work it saves. A keybind macro is in
 * the light jar ({@code dev.nexoclient.nexomod.macro}) because the player
 * pressed a key and the mod typed what they would have typed: convenience, and
 * nothing a fast typist couldn't do. State-triggered automation that acts with
 * no player input belongs here, as does anything that reports information the
 * vanilla client does not have — the hole finder is the latter, since it reads
 * chunks the player cannot see through.
 *
 * <p>Fabric runs every {@code client} entrypoint, in listed order, so
 * {@link NexoMod#onInitializeClient()} has already run by the time this does:
 * {@code NexoNative.bootstrap()} is done, the config is loadable, and native
 * availability can be assumed to be decided.
 *
 * <h2>Rules for adding to this side</h2>
 *
 * <ol>
 * <li>A new full-only feature goes under {@code dev.nexoclient.nexomod.tactical.*}
 *     in {@code src/full} and is registered from here.</li>
 * <li>{@code src/main} may not reference it. The build enforces that by
 *     compiling {@code main} without {@code full} on its classpath.</li>
 * <li>A mixin for it goes in {@code dev.nexoclient.nexomod.tactical.mixin} and is
 *     listed in {@code nexomod-tactical.mixins.json}, never in
 *     {@code nexomod.mixins.json} — a mixin config naming a class the jar
 *     doesn't contain is a hard startup crash, and the light jar contains
 *     neither the config nor the class.</li>
 * <li>Settings UI reaches the hub through {@link NexoExtraCategories}, since
 *     {@code NexoSettingsScreen} is in {@code src/main} and cannot name a
 *     screen from this side.</li>
 * </ol>
 */
public class NexoTacticalFeatures implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		NexoMod.LOGGER.info("[nexomod] Full feature set enabled.");
		BedrockHoleFinder.register();
		NexoTntTimer.register();
		NexoSoundRadarHud.register();
		NexoChunkHistory.register();
		NexoGhostMode.register();
		NexoMacroTriggers.register();
		NexoChunkBorderOverlay.register();
		// Light level and nearby hostiles, the two stats NexoStatsRegistry has
		// always documented as coming from this side.
		NexoTacticalStats.register();
		NexoLightOverlay.register();
		NexoFreecam.register();
		dev.nexoclient.nexomod.tactical.aim.NexoAimAssist.register();
		dev.nexoclient.nexomod.tactical.combat.NexoTriggerBot.register();
		dev.nexoclient.nexomod.tactical.knockback.NexoKnockbackAngle.register();
		dev.nexoclient.nexomod.tactical.combat.NexoCombatAssist.register();
		dev.nexoclient.nexomod.tactical.combat.NexoCombatHud.register();
		dev.nexoclient.nexomod.tactical.combat.NexoCombatLog.register();
		dev.nexoclient.nexomod.tactical.combat.NexoCrystalAura.register();
		dev.nexoclient.nexomod.tactical.combat.NexoAnchorAura.register();
		dev.nexoclient.nexomod.tactical.combat.NexoShieldBreaker.register();
		dev.nexoclient.nexomod.tactical.ore.NexoOreHighlight.register();
		dev.nexoclient.nexomod.tactical.stealth.NexoServerView.register();
		dev.nexoclient.nexomod.tactical.trajectory.NexoTrajectory.register();
		NexoLocateCommand.register();

		// The full-only modules the per-server profiles can force. Main
		// registers its own in NexoServerProfiles.registerBuiltins().
		registerProfileModules(NexoConfig.get());

		// A bearing to something in a world you have left is nonsense, and a
		// stale ping would otherwise survive into the next server.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> NexoSoundRadar.clear());
		// The chunk store's own close. NexoMod registers its CLIENT_STOPPING
		// handler from the first entrypoint and calls NexoNative.shutdown()
		// there, so by the time this runs the pool has already dropped every
		// handle — including this one. Closing it again is a clean "handle is
		// not live" error by contract, never a crash, and the call is kept
		// because the ordering between two entrypoints' listeners is not
		// something this side should be relying on.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> NexoChunkHistory.close());

		// Lands in the settings hub between Position Obscuring and Macros,
		// which is where the button sat when NexoSettingsScreen named it
		// directly.
		NexoExtraCategories.register(Component.translatable("nexomod.settings.bedrockHoles"), NexoBedrockHoleScreen::new);
		NexoExtraCategories.register(Component.translatable("nexomod.settings.tactical"), NexoTacticalFeatureScreen::new);
		NexoExtraCategories.register(Component.translatable("nexomod.settings.seedIndex"), NexoSeedIndexScreen::new);

		// The one QoL-menu row src/main cannot add directly — see NexoQolModules.
		NexoConfig config = NexoConfig.get();
		NexoQolModules.register(Component.translatable("nexomod.qol.chunkBorder"),
				Component.translatable("nexomod.qol.chunkBorder.description"),
				config::chunkBorderOverlayEnabled,
				() -> config.setChunkBorderOverlayEnabled(!config.chunkBorderOverlayEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.tntTimer"),
				Component.translatable("nexomod.qol.tntTimer.description"),
				config::tntTimerEnabled,
				() -> config.setTntTimerEnabled(!config.tntTimerEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.lightOverlay"),
				Component.translatable("nexomod.qol.lightOverlay.description"),
				config::lightOverlayEnabled,
				() -> config.setLightOverlayEnabled(!config.lightOverlayEnabled()),
				parent -> new NexoLightOverlayConfigScreen(parent));
		NexoQolModules.register(Component.translatable("nexomod.qol.freecam"),
				Component.translatable("nexomod.qol.freecam.description"),
				config::freecamEnabled,
				() -> config.setFreecamEnabled(!config.freecamEnabled()),
				parent -> new NexoFreecamConfigScreen(parent));
		NexoQolModules.register(Component.translatable("nexomod.qol.aimAssist"),
				Component.translatable("nexomod.qol.aimAssist.description"),
				config::aimAssistEnabled,
				() -> config.setAimAssistEnabled(!config.aimAssistEnabled()),
				parent -> new dev.nexoclient.nexomod.tactical.screen.NexoAimAssistConfigScreen(parent));
		NexoQolModules.register(Component.translatable("nexomod.qol.rangeIndicator"),
				Component.translatable("nexomod.qol.rangeIndicator.description"),
				config::rangeIndicatorEnabled,
				() -> config.setRangeIndicatorEnabled(!config.rangeIndicatorEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.triggerBot"),
				Component.translatable("nexomod.qol.triggerBot.description"),
				config::triggerBotEnabled,
				() -> config.setTriggerBotEnabled(!config.triggerBotEnabled()),
				parent -> new dev.nexoclient.nexomod.tactical.screen.NexoTriggerBotConfigScreen(parent));
		NexoQolModules.register(Component.translatable("nexomod.qol.fullbright"),
				Component.translatable("nexomod.qol.fullbright.description"),
				config::fullbrightEnabled,
				() -> config.setFullbrightEnabled(!config.fullbrightEnabled()),
				parent -> new dev.nexoclient.nexomod.tactical.screen.NexoFullbrightConfigScreen(parent));
		NexoQolModules.register(Component.translatable("nexomod.qol.knockback"),
				Component.translatable("nexomod.qol.knockback.description"),
				config::knockbackDisplacementEnabled,
				() -> config.setKnockbackDisplacementEnabled(!config.knockbackDisplacementEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.hitboxes"),
				Component.translatable("nexomod.qol.hitboxes.description"),
				config::hitboxDisplayEnabled,
				() -> config.setHitboxDisplayEnabled(!config.hitboxDisplayEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.critIndicator"),
				Component.translatable("nexomod.qol.critIndicator.description"),
				config::critIndicatorEnabled,
				() -> config.setCritIndicatorEnabled(!config.critIndicatorEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.wTap"),
				Component.translatable("nexomod.qol.wTap.description"),
				config::wTapAssistEnabled,
				() -> config.setWTapAssistEnabled(!config.wTapAssistEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.threat"),
				Component.translatable("nexomod.qol.threat.description"),
				config::threatWarningEnabled,
				() -> config.setThreatWarningEnabled(!config.threatWarningEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.autoTotem"),
				Component.translatable("nexomod.qol.autoTotem.description"),
				config::autoTotemEnabled,
				() -> config.setAutoTotemEnabled(!config.autoTotemEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.crystalAura"),
				Component.translatable("nexomod.qol.crystalAura.description"),
				config::crystalAuraEnabled,
				() -> config.setCrystalAuraEnabled(!config.crystalAuraEnabled()),
				parent -> new dev.nexoclient.nexomod.tactical.screen.NexoCrystalAuraConfigScreen(parent));
		NexoQolModules.register(Component.translatable("nexomod.qol.anchorAura"),
				Component.translatable("nexomod.qol.anchorAura.description"),
				config::anchorAuraEnabled,
				() -> config.setAnchorAuraEnabled(!config.anchorAuraEnabled()),
				parent -> new dev.nexoclient.nexomod.tactical.screen.NexoAnchorAuraConfigScreen(parent));
		NexoQolModules.register(Component.translatable("nexomod.qol.shieldBreaker"),
				Component.translatable("nexomod.qol.shieldBreaker.description"),
				config::shieldBreakerEnabled,
				() -> config.setShieldBreakerEnabled(!config.shieldBreakerEnabled()),
				parent -> new dev.nexoclient.nexomod.tactical.screen.NexoShieldBreakerConfigScreen(parent));
		NexoQolModules.register(Component.translatable("nexomod.qol.oreHighlight"),
				Component.translatable("nexomod.qol.oreHighlight.description"),
				config::oreHighlightEnabled,
				() -> config.setOreHighlightEnabled(!config.oreHighlightEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.fallWarning"),
				Component.translatable("nexomod.qol.fallWarning.description"),
				config::fallWarningEnabled,
				() -> config.setFallWarningEnabled(!config.fallWarningEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.playerRadar"),
				Component.translatable("nexomod.qol.playerRadar.description"),
				config::playerRadarEnabled,
				() -> config.setPlayerRadarEnabled(!config.playerRadarEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.combatLog"),
				Component.translatable("nexomod.qol.combatLog.description"),
				config::combatLogEnabled,
				() -> config.setCombatLogEnabled(!config.combatLogEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.serverView"),
				Component.translatable("nexomod.qol.serverView.description"),
				config::serverViewEnabled,
				() -> config.setServerViewEnabled(!config.serverViewEnabled()),
				null);
		NexoQolModules.register(Component.translatable("nexomod.qol.trajectory"),
				Component.translatable("nexomod.qol.trajectory.description"),
				config::trajectoryEnabled,
				() -> config.setTrajectoryEnabled(!config.trajectoryEnabled()),
				parent -> new dev.nexoclient.nexomod.tactical.screen.NexoTrajectoryConfigScreen(parent));
	}

	private static void registerProfileModules(NexoConfig config) {
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("aimAssist",
				Component.translatable("nexomod.qol.aimAssist"),
				config::aimAssistEnabled, config::setAimAssistEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("triggerBot",
				Component.translatable("nexomod.qol.triggerBot"),
				config::triggerBotEnabled, config::setTriggerBotEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("autoTotem",
				Component.translatable("nexomod.qol.autoTotem"),
				config::autoTotemEnabled, config::setAutoTotemEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("crystalAura",
				Component.translatable("nexomod.qol.crystalAura"),
				config::crystalAuraEnabled, config::setCrystalAuraEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("anchorAura",
				Component.translatable("nexomod.qol.anchorAura"),
				config::anchorAuraEnabled, config::setAnchorAuraEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("shieldBreaker",
				Component.translatable("nexomod.qol.shieldBreaker"),
				config::shieldBreakerEnabled, config::setShieldBreakerEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("knockback",
				Component.translatable("nexomod.qol.knockback"),
				config::knockbackDisplacementEnabled, config::setKnockbackDisplacementEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("wTap",
				Component.translatable("nexomod.qol.wTap"),
				config::wTapAssistEnabled, config::setWTapAssistEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("hitboxes",
				Component.translatable("nexomod.qol.hitboxes"),
				config::hitboxDisplayEnabled, config::setHitboxDisplayEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("critIndicator",
				Component.translatable("nexomod.qol.critIndicator"),
				config::critIndicatorEnabled, config::setCritIndicatorEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("rangeIndicator",
				Component.translatable("nexomod.qol.rangeIndicator"),
				config::rangeIndicatorEnabled, config::setRangeIndicatorEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("threatWarning",
				Component.translatable("nexomod.qol.threat"),
				config::threatWarningEnabled, config::setThreatWarningEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("freecam",
				Component.translatable("nexomod.qol.freecam"),
				config::freecamEnabled, config::setFreecamEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("fullbright",
				Component.translatable("nexomod.qol.fullbright"),
				config::fullbrightEnabled, config::setFullbrightEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("oreHighlight",
				Component.translatable("nexomod.qol.oreHighlight"),
				config::oreHighlightEnabled, config::setOreHighlightEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("lightOverlay",
				Component.translatable("nexomod.qol.lightOverlay"),
				config::lightOverlayEnabled, config::setLightOverlayEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("playerRadar",
				Component.translatable("nexomod.qol.playerRadar"),
				config::playerRadarEnabled, config::setPlayerRadarEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("combatLog",
				Component.translatable("nexomod.qol.combatLog"),
				config::combatLogEnabled, config::setCombatLogEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("fallWarning",
				Component.translatable("nexomod.qol.fallWarning"),
				config::fallWarningEnabled, config::setFallWarningEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("serverView",
				Component.translatable("nexomod.qol.serverView"),
				config::serverViewEnabled, config::setServerViewEnabled);
		dev.nexoclient.nexomod.profiles.NexoServerProfiles.registerModule("trajectory",
				Component.translatable("nexomod.qol.trajectory"),
				config::trajectoryEnabled, config::setTrajectoryEnabled);
	}
}
