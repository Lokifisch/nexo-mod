package dev.nexoclient.nexomod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import dev.nexoclient.nexomod.lantunnel.LanTunnel;

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
		CommandRegistrationCallback.EVENT.register((dispatcher, ignoredRegistryAccess, ignoredEnvironment) -> LanTunnel.registerCommands(dispatcher));
	}

	/**
	 * Prepends the Nexo badge glyph to a name. Only meant to be applied to the
	 * local player's own name right now — there's no network protocol yet for
	 * telling which other players have Nexo Mod installed, so showing this on
	 * anyone else's name would be a lie.
	 */
	public static MutableComponent withBadge(Component name) {
		return Component.literal(BADGE_GLYPH)
				.withStyle(BADGE_STYLE)
				.append(Component.literal(" ").withStyle(DEFAULT_FONT_STYLE))
				.append(Component.literal("").withStyle(DEFAULT_FONT_STYLE).append(name));
	}
}
