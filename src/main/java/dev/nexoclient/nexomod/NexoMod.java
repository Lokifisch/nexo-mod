package dev.nexoclient.nexomod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.auth.HardwareKey;
import dev.nexoclient.nexomod.badge.NexoBadges;
import dev.nexoclient.nexomod.chat.NexoChatFilter;
import dev.nexoclient.nexomod.chat.NexoChatHistory;
import dev.nexoclient.nexomod.chat.NexoChatSearch;
import dev.nexoclient.nexomod.coords.CoordObfuscator;
import dev.nexoclient.nexomod.discord.NexoDiscordRpc;
import dev.nexoclient.nexomod.hud.NexoArmorBar;
import dev.nexoclient.nexomod.hud.NexoArmorBarEffects;
import dev.nexoclient.nexomod.hud.NexoArmorHud;
import dev.nexoclient.nexomod.hud.NexoComboCounter;
import dev.nexoclient.nexomod.hud.NexoCpsCounter;
import dev.nexoclient.nexomod.hud.NexoDamageNumbers;
import dev.nexoclient.nexomod.hud.NexoFadingLogHud;
import dev.nexoclient.nexomod.hud.NexoHudCleaner;
import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.hud.NexoInventoryHud;
import dev.nexoclient.nexomod.hud.NexoKeystrokesHud;
import dev.nexoclient.nexomod.hud.NexoPotionHud;
import dev.nexoclient.nexomod.hud.NexoQolMenu;
import dev.nexoclient.nexomod.hud.NexoStatsHud;
import dev.nexoclient.nexomod.zoom.NexoZoom;
import dev.nexoclient.nexomod.lantunnel.LanTunnel;
import dev.nexoclient.nexomod.macro.NexoMacroDispatcher;
import dev.nexoclient.nexomod.nativecore.NexoNative;
import dev.nexoclient.nexomod.privacy.NexoLogScrubber;
import dev.nexoclient.nexomod.servers.NexoQuickConnect;

/**
 * The client initialiser both build variants share.
 *
 * <p>This class is compiled into {@code nexomod} (full) <em>and</em>
 * {@code nexomod-legit}, so it must not name a single class from
 * {@code src/full} — not in an import, not in a method body, not in a javadoc
 * {@code @link}. Anything the light jar must not contain is registered by
 * {@code dev.nexoclient.nexomod.tactical.NexoTacticalFeatures}, a second
 * {@link ClientModInitializer} listed only in the full jar's
 * {@code fabric.mod.json}.
 *
 * <p>The line the split follows is <em>what triggers a thing</em>, not how
 * convenient it is. Everything registered here is either passive (the badge,
 * the reskin), privacy-preserving (coordinate obscuring), or fired by a key the
 * player pressed — {@link NexoMacroDispatcher} is macros, and a macro is a
 * keybind that types for you, which is why it is in the light jar. Automation
 * that runs off world state with no player input, and anything that reports
 * information vanilla withholds, belongs on the other side.
 */
public class NexoMod implements ClientModInitializer {
	public static final String MOD_ID = "nexomod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Maps to the single glyph in assets/nexomod/textures/font/nexo_badge.png. */
	private static final String BADGE_GLYPH = "";
	private static final Identifier BADGE_FONT_ID = Identifier.fromNamespaceAndPath(MOD_ID, "badge");
	private static final Style BADGE_STYLE = Style.EMPTY.withFont(new FontDescription.Resource(BADGE_FONT_ID));
	/**
	 * The badge font only defines one glyph (the badge itself). Style with an
	 * unset font inherits whatever font preceded it, so without explicitly
	 * resetting back to the vanilla default font before the actual name,
	 * every character in it would render through the badge font too — which
	 * has no letter glyphs, hence tofu boxes.
	 */
	private static final Style DEFAULT_FONT_STYLE = Style.EMPTY.withFont(FontDescription.DEFAULT);

	@Override
	public void onInitializeClient() {
		LOGGER.info("[nexomod] Initialised.");
		// First, so anything registered below can ask NexoNative.isAvailable()
		// and pick its code path once instead of re-checking per call. Never
		// throws: a platform with no native library logs one WARN and carries
		// on with those features off.
		NexoNative.bootstrap();
		// Immediately after, and before anything else this mod does: from here
		// on every line any mod or the game itself writes goes through the
		// scrubber first. A no-op when the native core is absent.
		NexoLogScrubber.install();
		HardwareKey.warmUp();
		CommandRegistrationCallback.EVENT.register((dispatcher, ignoredRegistryAccess, ignoredEnvironment) -> LanTunnel.registerCommands(dispatcher));
		NexoMacroDispatcher.register();
		NexoDiscordRpc.register();
		CoordObfuscator.register();
		NexoHudVisibility.register();
		NexoChatSearch.register();
		NexoQuickConnect.register();
		NexoBadges.register();
		// Temporary one-shot confirmation lines while tracking down a report that
		// armor HUD renders nothing despite being enabled and armor equipped —
		// these narrow down whether one of these four calls is silently throwing
		// and aborting the rest of this method. Remove once that's resolved.
		NexoQolMenu.register();
		LOGGER.info("[nexomod] Registered QoL menu.");
		NexoKeystrokesHud.register();
		LOGGER.info("[nexomod] Registered keystrokes HUD.");
		NexoCpsCounter.register();
		LOGGER.info("[nexomod] Registered CPS counter.");
		NexoArmorHud.register();
		LOGGER.info("[nexomod] Registered armor HUD.");
		NexoArmorBarEffects.register();
		NexoArmorBar.register();
		NexoStatsHud.register();
		NexoPotionHud.register();
		NexoComboCounter.register();
		NexoFadingLogHud.ACTIONBAR.register();
		NexoFadingLogHud.PICKUPS.register();
		NexoInventoryHud.register();
		NexoDamageNumbers.register();
		NexoZoom.register();
		// Last of the HUD registrations on purpose: this one wraps vanilla
		// elements rather than adding its own, and wrapping is cheapest to reason
		// about once every Nexo element that might replace one is already in.
		NexoHudCleaner.register();
		ClientLifecycleEvents.CLIENT_STOPPING.register(NexoMod::onClientStopping);
	}

	/**
	 * Releases everything held on the native side, in dependency order: the
	 * handles first, then the library.
	 *
	 * <p>{@code NexoNative.shutdown()} drops every handle on its own, so the two
	 * calls above it are not strictly required — but {@code chatDbClose} is also
	 * what flushes the database, and a close that happens implicitly during a
	 * pool teardown is not the same promise. All three are no-ops when the
	 * library never loaded.
	 */
	private static void onClientStopping(net.minecraft.client.Minecraft client) {
		NexoBadges.shutdown();
		NexoChatHistory.close();
		NexoChatFilter.closeIfOpen();
		// Before the library goes: a wrapped appender left pointing at a dead
		// scrubber handle would answer every remaining shutdown line with a
		// withheld placeholder instead of the line.
		NexoLogScrubber.uninstall();
		NexoNative.shutdown();
	}

	/**
	 * Prepends the Nexo badge glyph to a name.
	 *
	 * <p>Apply it only to names {@link dev.nexoclient.nexomod.badge.NexoBadges#hasBadge}
	 * vouches for. It used to be restricted to the local player, because
	 * nothing could tell which other players had the mod — Nexo Mod is
	 * client-only and a vanilla server discards custom payloads it does not
	 * recognise, so the fact cannot travel between two clients inside the game.
	 * That check now lives in {@code NexoBadges}, which answers from a roster
	 * fetched out of band; putting this glyph on a name without asking it would
	 * still be a lie.
	 */
	public static MutableComponent withBadge(Component name) {
		return Component.literal(BADGE_GLYPH)
				.withStyle(BADGE_STYLE)
				.append(Component.literal(" ").withStyle(DEFAULT_FONT_STYLE))
				.append(Component.literal("").withStyle(DEFAULT_FONT_STYLE).append(name));
	}
}
