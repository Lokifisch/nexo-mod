package dev.nexoclient.nexomod.hud;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.food.FoodData;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.coords.CoordObfuscator;
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

	private static final int TICKS_PER_DAY = 24000;
	private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

	private static final AtomicLong sessionStart = new AtomicLong(System.currentTimeMillis());
	private static final AtomicInteger deaths = new AtomicInteger();
	private static final AtomicInteger blocksBroken = new AtomicInteger();
	private static volatile boolean wasDead;

	/** Horizontal speed, recomputed once per client tick — see {@link #trackSpeed}. */
	private static volatile float speedBlocksPerSecond;
	private static double lastX;
	private static double lastZ;
	private static boolean hasLastPosition;

	private static volatile String clockText;
	private static volatile long clockFormattedAt;

	/**
	 * Smoothed real milliseconds per server tick, from {@link #recordGameTime}.
	 * -1 until the first sample lands. TPS and MSPT are both read off this one
	 * value rather than smoothed separately, so they can never disagree about
	 * how healthy the server currently looks.
	 */
	private static volatile float estimatedMspt = -1F;
	private static long lastGameTime = -1L;
	private static long lastGameTimeAtMillis;

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
			// A tick count from the previous server is meaningless against this
			// one's — treated as "no sample yet" rather than feeding a garbage
			// delta into the first packet of the new session.
			lastGameTime = -1L;
			estimatedMspt = -1F;
		});
		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> blocksBroken.incrementAndGet());
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			LocalPlayer player = client.player;
			if (player == null) {
				hasLastPosition = false;
				return;
			}
			boolean dead = player.isDeadOrDying();
			if (dead && !wasDead) {
				deaths.incrementAndGet();
			}
			wasDead = dead;
			trackSpeed(player);
		});
	}

	/**
	 * Horizontal blocks per second, from the distance covered since the previous
	 * tick. Sampled here rather than in the stat's own supplier because that runs
	 * per frame — at 200fps it would measure the distance covered in 5ms and
	 * report noise. Vertical motion is excluded so falling doesn't read as speed.
	 */
	private static void trackSpeed(LocalPlayer player) {
		double x = player.getX();
		double z = player.getZ();
		if (hasLastPosition) {
			double dx = x - lastX;
			double dz = z - lastZ;
			float sampled = (float) (Math.sqrt(dx * dx + dz * dz) * 20.0);
			// Light smoothing, or the readout flickers on every step-up and turn.
			speedBlocksPerSecond = speedBlocksPerSecond * 0.6F + sampled * 0.4F;
		}
		lastX = x;
		lastZ = z;
		hasLastPosition = true;
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
		// Runs through the same obfuscator F3 does, so this line agrees with the
		// debug screen instead of quietly being the one place the real position
		// still shows. Being hidden by NexoHudVisibility would only protect a
		// deliberate screenshot; a stream overlay or a shoulder-surfer sees
		// whatever is on screen, so the value itself has to be the fake one.
		NexoStatsRegistry.register("coords", Component.translatable("nexomod.stats.coords"),
				NexoStatsHud::coordsText);
		NexoStatsRegistry.register("biome", Component.translatable("nexomod.stats.biome"),
				NexoStatsHud::biomeText);
		NexoStatsRegistry.register("xp", Component.translatable("nexomod.stats.xp"),
				NexoStatsHud::experienceText);
		NexoStatsRegistry.register("speed", Component.translatable("nexomod.stats.speed"),
				() -> String.format("%.1f m/s", speedBlocksPerSecond));
		NexoStatsRegistry.register("clock", Component.translatable("nexomod.stats.clock"),
				NexoStatsHud::wallClockText);
		NexoStatsRegistry.register("day", Component.translatable("nexomod.stats.day"),
				NexoStatsHud::worldTimeText);
		NexoStatsRegistry.register("tps", Component.translatable("nexomod.stats.tps"),
				NexoStatsHud::tpsText);
		NexoStatsRegistry.register("mspt", Component.translatable("nexomod.stats.mspt"),
				NexoStatsHud::msptText);
	}

	/** Real time constant for {@link #recordGameTime}'s smoothing — see that method. */
	private static final long MSPT_SMOOTHING_TAU_MILLIS = 2000L;

	/**
	 * Fed by {@code TpsSampleMixin}, one call per real
	 * {@code ClientboundSetTimePacket} the server sends. Vanilla has no
	 * plugin-free TPS query, so this guesses from the one thing the protocol
	 * already carries: real elapsed time between consecutive tick-counter syncs
	 * gives ms/tick directly, whatever the server's actual send cadence turns
	 * out to be — no assumption about it is needed for the math to hold.
	 *
	 * <p>Smoothed by real elapsed time, not by sample count — a fixed
	 * per-sample blend (e.g. "80% old, 20% new") converges in a fixed number of
	 * *samples*, but samples arrive once per tick-sync, so a server crawling at
	 * 0.5 TPS produces them roughly 40x slower than one running at 20. That
	 * made a crash from 20 TPS down to 0.5 read as ~12 for tens of real seconds
	 * even though every individual sample was already accurate — dozens of
	 * samples just took that long to arrive. Weighting each sample by how much
	 * real time it actually covers fixes that: a rare, wide-gap sample (which
	 * is exactly what a lag spike produces) now pulls the estimate hard toward
	 * itself instead of being treated as "one vote out of many".
	 */
	public static void recordGameTime(long gameTime) {
		long now = System.currentTimeMillis();
		if (lastGameTime >= 0) {
			long tickDelta = gameTime - lastGameTime;
			long millisDelta = now - lastGameTimeAtMillis;
			if (tickDelta > 0 && millisDelta > 0) {
				float sampledMspt = millisDelta / (float) tickDelta;
				float previous = estimatedMspt;
				if (previous < 0) {
					estimatedMspt = sampledMspt;
				} else {
					float alpha = 1F - (float) Math.exp(-millisDelta / (double) MSPT_SMOOTHING_TAU_MILLIS);
					estimatedMspt = previous + (sampledMspt - previous) * alpha;
				}
			}
		}
		lastGameTime = gameTime;
		lastGameTimeAtMillis = now;
	}

	private static String tpsText() {
		float mspt = estimatedMspt;
		// ponytail: display capped at 20 TPS, doesn't reflect a server
		// explicitly configured for a faster tick rate.
		return mspt < 0 ? "--" : String.format("%.1f", Math.min(20F, 1000F / mspt));
	}

	private static String msptText() {
		float mspt = estimatedMspt;
		return mspt < 0 ? "--" : String.format("%.1f", mspt);
	}

	private static final int HEALTH_GREEN = 0xFF4CD964;
	private static final int HEALTH_YELLOW = 0xFFFFCC00;
	private static final int HEALTH_RED = 0xFFFF3B30;

	/** Same three anchors as the TPS scale (20/15/1 TPS), expressed as their equivalent ms/tick. */
	private static final float MSPT_GREEN_AT = 50F;
	private static final float MSPT_YELLOW_AT = 1000F / 15F;
	private static final float MSPT_RED_AT = 1000F;

	/** Shared by the "tps" and "mspt" lines, so the two can never show a different color for the same reality. */
	private static int healthColor() {
		float mspt = estimatedMspt;
		if (mspt < 0) {
			return NexoStyle.TEXT_PRIMARY;
		}
		if (mspt <= MSPT_GREEN_AT) {
			return HEALTH_GREEN;
		}
		if (mspt <= MSPT_YELLOW_AT) {
			return NexoStyle.mix(HEALTH_GREEN, HEALTH_YELLOW, (mspt - MSPT_GREEN_AT) / (MSPT_YELLOW_AT - MSPT_GREEN_AT));
		}
		if (mspt <= MSPT_RED_AT) {
			return NexoStyle.mix(HEALTH_YELLOW, HEALTH_RED, (mspt - MSPT_YELLOW_AT) / (MSPT_RED_AT - MSPT_YELLOW_AT));
		}
		return HEALTH_RED;
	}

	private static String coordsText() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return "--";
		}
		BlockPos pos = player.blockPosition();
		if (CoordObfuscator.active()) {
			// Same per-session offset F3 gets, so the two never disagree — a stat
			// line and a debug screen showing different coordinates would give away
			// that one of them is shifted, and which.
			pos = CoordObfuscator.obscure(pos);
		}
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}

	private static String biomeText() {
		Identifier id = currentBiomeId();
		if (id == null) {
			return "--";
		}
		// Falls back to the raw path when a datapack biome has no translation, which
		// is a readable name either way — better than an empty line.
		String translationKey = "biome." + id.getNamespace() + "." + id.getPath();
		Component translated = Component.translatable(translationKey);
		String text = translated.getString();
		return text.equals(translationKey) ? id.getPath() : text;
	}

	/** The representative color for the current biome — grey stony shores, green plains, the acacia orange for savanna, and so on. */
	private static int biomeColor() {
		Identifier id = currentBiomeId();
		return id == null ? NexoStyle.TEXT_PRIMARY : NexoBiomeColors.forPath(id.getPath());
	}

	private static Identifier currentBiomeId() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) {
			return null;
		}
		return client.level.getBiome(client.player.blockPosition()).unwrapKey()
				.map(ResourceKey::identifier)
				.orElse(null);
	}

	private static String experienceText() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return "--";
		}
		return player.experienceLevel + " (" + Math.round(player.experienceProgress * 100) + "%)";
	}

	/**
	 * Local wall-clock time, formatted at most once a second. {@code value} runs
	 * once per enabled stat per frame, so formatting it inline would mean a
	 * {@code DateTimeFormatter} pass and a string allocation a few hundred times
	 * a second for a number that changes sixty times an hour.
	 */
	private static String wallClockText() {
		long now = System.currentTimeMillis();
		if (now - clockFormattedAt >= 1000L || clockText == null) {
			clockFormattedAt = now;
			clockText = LocalTime.now().format(CLOCK_FORMAT);
		}
		return clockText;
	}

	private static String worldTimeText() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return "--";
		}
		long time = client.level.getDefaultClockTime();
		long day = time / TICKS_PER_DAY;
		long timeOfDay = Math.floorMod(time, TICKS_PER_DAY);
		// Minecraft tick 0 is 06:00, not midnight.
		long hours = (timeOfDay / 1000 + 6) % 24;
		long minutes = timeOfDay % 1000 * 60 / 1000;
		return String.format("%d (%02d:%02d)", day, hours, minutes);
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

	/** Blocks/sec above which movement reads as "Walking" rather than "Standing". */
	private static final float WALK_SPEED_THRESHOLD = 0.5F;

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
		if (speedBlocksPerSecond > WALK_SPEED_THRESHOLD) {
			return Component.translatable("nexomod.stats.movement.walking").getString();
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
			Component value = Component.literal(stat.value().get())
					.withStyle(style -> style.withColor(valueColor(stat.id()) & 0xFFFFFF));
			Component line = stat.label().copy().append(": ").append(value);
			graphics.text(font, line, bounds.left(), y, NexoStyle.TEXT_PRIMARY);
			y += lineHeight;
		}
	}

	/** Most stat values inherit the line's own color; TPS and biome carry a meaningful one of their own. */
	private static int valueColor(String statId) {
		return switch (statId) {
			case "tps", "mspt" -> healthColor();
			case "biome" -> biomeColor();
			default -> NexoStyle.TEXT_PRIMARY;
		};
	}
}
