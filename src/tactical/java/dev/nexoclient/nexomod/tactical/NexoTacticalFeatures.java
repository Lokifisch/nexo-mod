package dev.nexoclient.nexomod.tactical;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.tactical.bedrock.BedrockHoleFinder;
import dev.nexoclient.nexomod.tactical.chunks.NexoChunkHistory;
import dev.nexoclient.nexomod.tactical.ghost.NexoGhostMode;
import dev.nexoclient.nexomod.tactical.hud.NexoArmorHud;
import dev.nexoclient.nexomod.tactical.macro.NexoMacroTriggers;
import dev.nexoclient.nexomod.tactical.screen.NexoBedrockHoleScreen;
import dev.nexoclient.nexomod.tactical.screen.NexoTacticalFeatureScreen;
import dev.nexoclient.nexomod.tactical.sound.NexoSoundRadar;
import dev.nexoclient.nexomod.tactical.sound.NexoSoundRadarHud;
import dev.nexoclient.nexomod.screen.NexoExtraCategories;

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
		NexoSoundRadarHud.register();
		NexoArmorHud.register();
		NexoChunkHistory.register();
		NexoGhostMode.register();
		NexoMacroTriggers.register();

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
	}
}
