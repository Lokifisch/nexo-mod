package dev.nexoclient.nexomod.hud;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.food.FoodData;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoStyle;

/**
 * A stack of small stat lines — FPS, ping, and the rest of
 * {@link NexoStatsRegistry} — each independently toggleable rather than one
 * module per stat. Ten near-identical "compute a value, draw a text line"
 * modules would mean ten HUD elements, ten config screens and ten
 * {@link NexoHudLayout} entries for what a single scrolling checklist covers
 * just as well; see {@code NexoStatsConfigScreen} for that checklist.
 *
 * <p>Session-scoped counters (deaths, blocks broken) reset on
 * {@link ClientPlayConnectionEvents#JOIN} — they describe "this session",
 * not a lifetime total, and are deliberately never persisted to disk for the
 * same reason {@link NexoHudVisibility}'s flags aren't: a stale count from
 * last time you played would be a wrong number, not a missing one.
 */
public final class NexoStatsHud implements HudElement {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "stats_hud");
	private static final int LINE_HEIGHT = 10;
	private static final int NOMINAL_WIDTH = 110;
	private static final int EDGE_MARGIN = 4;

	private static final AtomicLong sessionStart = new AtomicLong(System.currentTimeMillis());
	private static final AtomicInteger deaths = new AtomicInteger();
	private static final AtomicInteger blocksBroken = new AtomicInteger();
	private static volatile boolean wasDead;

	private NexoStatsHud() {
	}

	public static void register() {
		registerBuiltinStats();
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, ID, new NexoStatsHud());

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			sessionStart.set(System.currentTimeMillis());
			deaths.set(0);
			blocksBroken.set(0);
			wasDead = false;
		});
		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> blocksBroken.incrementAndGet());
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			LocalPlayer player = client.player;
			if (player == null) {
				return;
			}
			boolean dead = player.isDeadOrDying();
			if (dead && !wasDead) {
				deaths.incrementAndGet();
			}
			wasDead = dead;
		});
	}

	private static void registerBuiltinStats() {
		NexoStatsRegistry.register("fps", Component.translatable("nexomod.stats.fps"),
				() -> String.valueOf(Minecraft.getInstance().getFps()));
		NexoStatsRegistry.register("ping", Component.translatable("nexomod.stats.ping"),
				NexoStatsHud::pingText);
		NexoStatsRegistry.register("hunger", Component.translatable("nexomod.stats.hunger"),
				NexoStatsHud::hungerText);
		NexoStatsRegistry.register("facing", Component.translatable("nexomod.stats.facing"),
				NexoStatsHud::facingText);
		NexoStatsRegistry.register("session", Component.translatable("nexomod.stats.session"),
				() -> durationText(System.currentTimeMillis() - sessionStart.get()));
		NexoStatsRegistry.register("deaths", Component.translatable("nexomod.stats.deaths"),
				() -> String.valueOf(deaths.get()));
		NexoStatsRegistry.register("blocks", Component.translatable("nexomod.stats.blocks"),
				() -> String.valueOf(blocksBroken.get()));
		NexoStatsRegistry.register("movement", Component.translatable("nexomod.stats.movement"),
				NexoStatsHud::movementText);
	}

	private static String pingText() {
		Minecraft client = Minecraft.getInstance();
		ClientPacketListener connection = client.getConnection();
		if (connection == null || client.player == null) {
			return "--";
		}
		PlayerInfo info = connection.getPlayerInfo(client.player.getGameProfile().id());
		return info == null ? "--" : info.getLatency() + "ms";
	}

	private static String hungerText() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return "--";
		}
		FoodData food = player.getFoodData();
		return food.getFoodLevel() + "/20 (+" + food.getSaturationLevel() + ")";
	}

	private static final String[] COMPASS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

	private static String facingText() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return "--";
		}
		float yaw = Mth.wrapDegrees(player.getYRot());
		int index = Math.floorMod(Math.round(yaw / 45f), 8);
		return COMPASS[index];
	}

	private static String movementText() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return "--";
		}
		if (player.isSprinting()) {
			return Component.translatable("nexomod.stats.movement.sprinting").getString();
		}
		if (player.isCrouching()) {
			return Component.translatable("nexomod.stats.movement.sneaking").getString();
		}
		return Component.translatable("nexomod.stats.movement.standing").getString();
	}

	private static String durationText(long millis) {
		long totalSeconds = Math.max(0, millis / 1000);
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;
		return hours > 0
				? String.format("%d:%02d:%02d", hours, minutes, seconds)
				: String.format("%d:%02d", minutes, seconds);
	}

	/** Where this element draws right now — shared by rendering and the layout editor. */
	public static ScreenRectangle resolveBounds(int guiWidth, int guiHeight) {
		int lines = Math.max(1, enabledCount());
		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.STATS);
		float scale = override != null ? override.scale : 1f;
		int width = Math.round(NOMINAL_WIDTH * scale);
		int height = Math.round(lines * LINE_HEIGHT * scale);
		int x = override != null ? override.x : EDGE_MARGIN;
		int y = override != null ? override.y : EDGE_MARGIN;
		return NexoHudBounds.clamp(x, y, width, height, guiWidth, guiHeight);
	}

	private static int enabledCount() {
		NexoStatsConfig config = NexoStatsConfig.get();
		int count = 0;
		for (NexoStatsRegistry.Stat stat : NexoStatsRegistry.stats()) {
			if (config.isEnabled(stat.id())) {
				count++;
			}
		}
		return count;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (NexoHudVisibility.hidden()) {
			return;
		}
		if (!NexoConfig.get().statsHudEnabled()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options.hideGui) {
			return;
		}
		NexoStatsConfig config = NexoStatsConfig.get();
		List<NexoStatsRegistry.Stat> enabledStats = NexoStatsRegistry.stats().stream()
				.filter(stat -> config.isEnabled(stat.id()))
				.toList();
		if (enabledStats.isEmpty()) {
			return;
		}

		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.STATS);
		float scale = override != null ? override.scale : 1f;
		ScreenRectangle bounds = resolveBounds(graphics.guiWidth(), graphics.guiHeight());
		Font font = client.font;
		int lineHeight = Math.round(LINE_HEIGHT * scale);

		int y = bounds.top();
		for (NexoStatsRegistry.Stat stat : enabledStats) {
			Component line = stat.label().copy().append(": " + stat.value().get());
			graphics.text(font, line, bounds.left(), y, NexoStyle.TEXT_PRIMARY);
			y += lineHeight;
		}
	}
}
