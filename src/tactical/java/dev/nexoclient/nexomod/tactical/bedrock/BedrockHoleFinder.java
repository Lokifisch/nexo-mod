package dev.nexoclient.nexomod.tactical.bedrock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import dev.nexoclient.nexomod.tactical.bedrock.BedrockHole.Band;
import dev.nexoclient.nexomod.tactical.bedrock.BedrockHole.Kind;
import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoStyle;

/**
 * Locates gaps in the world's bedrock boundary layers — Overworld floor, Nether
 * floor, Nether roof — and highlights them through the terrain.
 *
 * Detection reads <em>loaded chunks</em>, not the world seed: the client already
 * has the real block data for everything in render distance, so this works on
 * any server without knowing its seed, and it finds player-broken openings
 * (which is most of them — see {@link BedrockHole.Kind}) that no seed-based
 * prediction could know about. The trade-off is that nothing is known about
 * chunks the client hasn't received.
 *
 * Work is spread out rather than done in one pass: at most
 * {@link #CHUNKS_PER_TICK} chunks are scanned per client tick, walking outwards
 * from the player, and results are cached per chunk until that chunk reloads.
 * The highlight list is rebuilt once per tick and merely replayed each frame, so
 * the render path allocates nothing and is bounded at
 * {@link #MAX_HIGHLIGHTED_BLOCKS} boxes however many holes are cached.
 *
 * Everything here runs on the client thread — both the tick and gizmo events do
 * — so no field needs synchronisation.
 *
 * Note the interaction with Position Obscuring's <em>Hide Bedrock Pattern</em>:
 * that disguise hides the seed-derived bedrock mix precisely because a
 * screenshot of it can be matched back to real coordinates. These highlights
 * draw the shape of that same mix on top of the disguise, so a screenshot taken
 * with both on leaks what the disguise was hiding. Detection itself is
 * unaffected by the disguise, which only rewrites what the chunk mesher sees.
 */
public final class BedrockHoleFinder {
	private static final int CHUNKS_PER_TICK = 4;
	private static final int MAX_HIGHLIGHTED_BLOCKS = 512;
	private static final int MAX_LABELS = 16;
	private static final int HIGHLIGHT_INTERVAL_TICKS = 4;
	/** Find notifications only fire for holes this close, so travelling doesn't produce a wall of coordinates. */
	private static final int ALERT_RADIUS = 128;
	private static final int MAX_ALERTS_PER_TICK = 3;
	/** Own toast token so find toasts replace each other and never collide with a vanilla toast. */
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(5000L);
	private static final int PRUNE_INTERVAL_TICKS = 60;
	/** How often pending records reach disk; a crash can only cost one interval's worth of them. */
	private static final int FLUSH_INTERVAL_TICKS = 200;

	private static final float STROKE_WIDTH = 2.0F;
	/**
	 * Sides, outline and label all cycle the hue. Void holes and sealed pockets
	 * ride the same sweep half a period apart, so the two kinds are always in
	 * opposite hues and stay distinguishable without giving up the animation.
	 */
	private static final long RAINBOW_PERIOD_MILLIS = 15000L;
	/** Vanilla's debug-text scale reads as fine print in the world; labels want to be legible from across a cave. */
	private static final float LABEL_SCALE = TextGizmo.Style.DEFAULT_SCALE * 2.5F;
	private static final int FILL_ALPHA = 0x33000000;

	private static final Long2ObjectMap<List<BedrockHole>> holesByChunk = new Long2ObjectOpenHashMap<>();
	/**
	 * Anchors of the pockets currently held above. A pocket on a chunk border is
	 * found by both chunks' scans, so without this it would be stored — and
	 * announced — twice, and which of the two got there first would depend on chunk
	 * load order rather than on the world.
	 */
	private static final LongSet knownAnchors = new LongOpenHashSet();

	private static List<BedrockHoleOutline.Face> faces = List.of();
	private static List<BedrockHoleOutline.Edge> edges = List.of();
	private static List<Label> labels = List.of();
	/** Set when a scan changes what should be drawn, so the outline doesn't wait for the next interval. */
	private static boolean outlinesDirty;

	/** Chunk offsets to visit, ordered nearest-first, rebuilt only when the radius setting changes. */
	private static int[] offsetX = new int[0];
	private static int[] offsetZ = new int[0];
	private static int offsetRadius = -1;

	private static ClientLevel lastLevel;
	private static int ticks;

	private static KeyMapping toggleKey;

	/** No colour of its own: labels take their hole's animated hue at draw time. */
	private record Label(Vec3 pos, String text, Kind kind) {
	}

	private BedrockHoleFinder() {
	}

	public static void register() {
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.bedrockHoles",
				InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(BedrockHoleFinder::tick);
		// Both directions invalidate: an unloaded chunk's holes are stale, and a
		// chunk re-sent by the server may not match what was scanned before.
		// A newly loaded chunk also frees its neighbours' scans to be retried: a
		// fill that ran into this chunk while it was missing was rejected for want
		// of blocks that now exist.
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> invalidateAround(chunk.getPos()));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> invalidateAround(chunk.getPos()));
		LevelRenderEvents.BEFORE_GIZMOS.register(context -> render());
		// Leaving a world and closing the game are the two moments records would
		// otherwise be lost between flush intervals.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BedrockHoleLog.flush());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> BedrockHoleLog.flush());
	}

	/** Call after any change to the hole-finder settings: cached results were produced under the old ones. */
	public static void onSettingsChanged() {
		clear();
	}

	private static void tick(Minecraft client) {
		NexoConfig config = NexoConfig.get();
		while (toggleKey.consumeClick()) {
			config.setBedrockHoleFinderEnabled(!config.bedrockHoleFinderEnabled());
			clear();
			client.gui.getChat().addClientSystemMessage(Component.translatable(config.bedrockHoleFinderEnabled()
					? "nexomod.bedrockHoles.enabled"
					: "nexomod.bedrockHoles.disabled"));
		}

		if (client.level == null || client.player == null || !config.bedrockHoleFinderEnabled()) {
			if (lastLevel != null || !holesByChunk.isEmpty() || !faces.isEmpty()) {
				clear();
			}
			return;
		}

		if (client.level != lastLevel) {
			// New dimension or world: heights and bedrock layers both change, and
			// so does which records apply.
			clear();
			lastLevel = client.level;
		}
		BedrockHoleLog.openWorld(BedrockHoleLog.worldKey(client));

		int radius = config.bedrockHoleRadius().chunks;
		buildOffsets(radius);
		ChunkPos center = client.player.chunkPosition();
		scanSome(client, config, center);
		// Merged geometry costs more than a box per block, so it is rebuilt a few
		// times a second rather than every tick — except right after a scan found
		// something, which should show up immediately.
		if (outlinesDirty || ticks % HIGHLIGHT_INTERVAL_TICKS == 0) {
			rebuildHighlights(client, config, center);
			outlinesDirty = false;
		}

		if (++ticks % PRUNE_INTERVAL_TICKS == 0) {
			prune(center, radius + 2);
		}
		if (ticks % FLUSH_INTERVAL_TICKS == 0) {
			BedrockHoleLog.flush();
		}
	}

	private static void scanSome(Minecraft client, NexoConfig config, ChunkPos center) {
		int minSize = config.bedrockHoleMinSize();
		int maxSize = config.bedrockHoleMaxSize();
		int notifyBudget = config.bedrockHoleNotifyEnabled() ? MAX_ALERTS_PER_TICK : 0;
		boolean chimed = false;
		int scanned = 0;
		for (int i = 0; i < offsetX.length && scanned < CHUNKS_PER_TICK; i++) {
			int chunkX = center.x() + offsetX[i];
			int chunkZ = center.z() + offsetZ[i];
			long key = ChunkPos.pack(chunkX, chunkZ);
			if (holesByChunk.containsKey(key)) {
				continue;
			}
			// Waiting for the ring of neighbours is what makes a scan's result depend
			// on the world instead of on chunk load order: a fill that runs off the
			// edge of what the client has received is rejected, so scanning a chunk
			// the moment it arrives finds a different set of pockets every join —
			// and each join then announces the ones the last one had missed.
			if (!neighboursLoaded(client, chunkX, chunkZ)) {
				continue;
			}
			LevelChunk chunk = client.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (chunk == null) {
				continue;
			}

			List<BedrockHole> found = BedrockHoleScanner.scan(client.level, chunk, minSize, maxSize);
			List<BedrockHole> holes = new ArrayList<>(found.size());
			for (BedrockHole hole : found) {
				if (knownAnchors.add(BlockPos.asLong(hole.anchor().getX(), hole.anchor().getY(), hole.anchor().getZ()))) {
					holes.add(hole);
				}
			}
			holesByChunk.put(key, holes.isEmpty() ? List.of() : holes);
			scanned++;
			outlinesDirty |= !holes.isEmpty();
			for (BedrockHole hole : holes) {
				// Recorded the moment it is scanned, not when it is announced.
				// Recording only what got announced meant a rejoin — which loads
				// chunks in a different order, from a slightly different spot —
				// announced every hole that had been out of notification range the
				// time before. The cost is that a hole first scanned from far away
				// is never announced at all; its outline still shows it.
				if (!BedrockHoleLog.markFound(hole.anchor()) || notifyBudget <= 0) {
					continue;
				}
				if (announce(client, config, hole, chimed)) {
					notifyBudget--;
					chimed = true;
				}
			}
		}
	}

	/** Whether this chunk and all eight around it have arrived, so a fill can't run out of blocks. */
	private static boolean neighboursLoaded(Minecraft client, int chunkX, int chunkZ) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (!client.level.getChunkSource().hasChunk(chunkX + dx, chunkZ + dz)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Announces one never-before-seen pocket over whichever of the three channels
	 * are enabled. Both kinds qualify: the connectivity test already rules out the
	 * ordinary dents that would have made this noise rather than news.
	 *
	 * @param chimed whether something already chimed this tick — several holes
	 *               found in the same moment should sound like one find, not a chord
	 * @return whether it notified; a hole further than {@link #ALERT_RADIUS} away is
	 *         left to the outline rather than announced
	 */
	private static boolean announce(Minecraft client, NexoConfig config, BedrockHole hole, boolean chimed) {
		BlockPos anchor = hole.anchor();
		double distance = Math.sqrt(anchor.distToCenterSqr(client.player.getEyePosition()));
		if (distance > ALERT_RADIUS) {
			return false;
		}

		Component name = Component.translatable(nameKey(hole));
		int blocks = Mth.floor(distance);
		int size = hole.blockCount();
		// With coordinates hidden, a find still reports its size and distance —
		// enough to walk to it, nothing a viewer could write down.
		boolean coords = config.bedrockHoleCoordsVisible();
		if (config.bedrockHoleChatEnabled()) {
			client.gui.getChat().addClientSystemMessage(coords
					? Component.translatable("nexomod.bedrockHoles.alert", name, size, anchor.getX(), anchor.getY(), anchor.getZ(), blocks)
					: Component.translatable("nexomod.bedrockHoles.alertNoCoords", name, size, blocks));
		}
		if (config.bedrockHoleToastEnabled()) {
			// addOrUpdate, not add: a burst of finds replaces one toast rather
			// than stacking a column of them down the screen.
			SystemToast.addOrUpdate(client.getToastManager(), TOAST_ID, name, coords
					? Component.translatable("nexomod.bedrockHoles.toast", size, anchor.getX(), anchor.getY(), anchor.getZ(), blocks)
					: Component.translatable("nexomod.bedrockHoles.toastNoCoords", size, blocks));
		}
		if (config.bedrockHoleSoundEnabled() && !chimed) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F));
		}
		return true;
	}

	/**
	 * Rebuilds what the next frames draw. Walks chunks nearest-first and stops at
	 * the column cap, so the boxes that survive the cap are the closest ones.
	 */
	private static void rebuildHighlights(Minecraft client, NexoConfig config, ChunkPos center) {
		Vec3 eye = client.player.getEyePosition();
		boolean showLabels = config.bedrockHoleLabelsEnabled();
		List<BedrockHoleOutline.Face> newFaces = new ArrayList<>();
		List<BedrockHoleOutline.Edge> newEdges = new ArrayList<>();
		List<Label> newLabels = new ArrayList<>();
		int drawnBlocks = 0;

		for (int i = 0; i < offsetX.length && drawnBlocks < MAX_HIGHLIGHTED_BLOCKS; i++) {
			List<BedrockHole> holes = holesByChunk.get(ChunkPos.pack(center.x() + offsetX[i], center.z() + offsetZ[i]));
			if (holes == null || holes.isEmpty()) {
				continue;
			}
			for (BedrockHole hole : holes) {
				if (drawnBlocks >= MAX_HIGHLIGHTED_BLOCKS) {
					break;
				}
				// Whole pockets, never a partial one: half an outline would read as a
				// different shape rather than a clipped one.
				BedrockHoleOutline.build(hole, newFaces, newEdges);
				drawnBlocks += hole.blockCount();
				if (showLabels && newLabels.size() < MAX_LABELS) {
					Vec3 pos = hole.labelPos();
					newLabels.add(new Label(pos, labelText(hole, pos.distanceTo(eye)), hole.kind()));
				}
			}
		}

		faces = newFaces;
		edges = newEdges;
		labels = newLabels;
	}

	private static String labelText(BedrockHole hole, double distance) {
		Component name = Component.translatable(nameKey(hole));
		int blocks = Mth.floor(distance);
		return hole.blockCount() > 1
				? Component.translatable("nexomod.bedrockHoles.labelWide", name, hole.blockCount(), blocks).getString()
				: Component.translatable("nexomod.bedrockHoles.label", name, blocks).getString();
	}

	private static String nameKey(BedrockHole hole) {
		if (hole.band() == Band.ROOF) {
			return hole.kind() == Kind.VOID ? "nexomod.bedrockHoles.roofHole" : "nexomod.bedrockHoles.roofPocket";
		}
		return hole.kind() == Kind.VOID ? "nexomod.bedrockHoles.floorHole" : "nexomod.bedrockHoles.floorPocket";
	}

	private static void render() {
		// Top of the render path, before anything is sampled: the outlines are
		// Nexo's own drawing, so the screenshot toggle and ghost mode both take
		// them away. NexoHudVisibility.hidden() folds the two together.
		if (dev.nexoclient.nexomod.hud.NexoHudVisibility.hidden()) {
			return;
		}
		if (faces.isEmpty() && labels.isEmpty()) {
			return;
		}
		// Sampled once per frame, so everything on screen sweeps the hue together
		// instead of shimmering independently.
		long now = System.currentTimeMillis();
		int voidHue = NexoStyle.rainbow(now, RAINBOW_PERIOD_MILLIS);
		int sealedHue = NexoStyle.rainbow(now + RAINBOW_PERIOD_MILLIS / 2, RAINBOW_PERIOD_MILLIS);

		// Always-on-top throughout: a hole is by definition surrounded by bedrock,
		// so depth-tested geometry would only be visible once you're already in it.
		for (BedrockHoleOutline.Face face : faces) {
			int color = face.kind() == Kind.VOID ? voidHue : sealedHue;
			Gizmos.rect(face.a(), face.b(), face.c(), face.d(), GizmoStyle.fill(color & 0x00FFFFFF | FILL_ALPHA)).setAlwaysOnTop();
		}
		for (BedrockHoleOutline.Edge edge : edges) {
			Gizmos.line(edge.from(), edge.to(), edge.kind() == Kind.VOID ? voidHue : sealedHue, STROKE_WIDTH).setAlwaysOnTop();
		}
		for (Label label : labels) {
			int color = label.kind() == Kind.VOID ? voidHue : sealedHue;
			Gizmos.billboardText(label.text(), label.pos(), TextGizmo.Style.forColorAndCentered(color).withScale(LABEL_SCALE)).setAlwaysOnTop();
		}
	}

	/** Forgets a chunk's scan and its neighbours', since pockets cross chunk borders. */
	private static void invalidateAround(ChunkPos pos) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				forget(ChunkPos.pack(pos.x() + dx, pos.z() + dz));
			}
		}
	}

	/** Drops one chunk's scan, releasing its pockets' anchors so a re-scan can find them again. */
	private static void forget(long chunkKey) {
		List<BedrockHole> holes = holesByChunk.remove(chunkKey);
		if (holes == null) {
			return;
		}
		for (BedrockHole hole : holes) {
			BlockPos anchor = hole.anchor();
			knownAnchors.remove(BlockPos.asLong(anchor.getX(), anchor.getY(), anchor.getZ()));
		}
	}

	/** Drops cached chunks the player has moved away from, for the case where the scan radius exceeds render distance. */
	private static void prune(ChunkPos center, int radius) {
		LongIterator iterator = holesByChunk.keySet().iterator();
		while (iterator.hasNext()) {
			long key = iterator.nextLong();
			if (Math.abs(ChunkPos.getX(key) - center.x()) > radius || Math.abs(ChunkPos.getZ(key) - center.z()) > radius) {
				for (BedrockHole hole : holesByChunk.get(key)) {
					BlockPos anchor = hole.anchor();
					knownAnchors.remove(BlockPos.asLong(anchor.getX(), anchor.getY(), anchor.getZ()));
				}
				iterator.remove();
			}
		}
	}

	/**
	 * Precomputes the visit order once per radius: every chunk offset inside the
	 * radius, sorted by squared distance. Sorting a {@code long[]} of
	 * {@code distance²|offset} keeps it allocation-light and avoids re-deriving
	 * ring geometry on every tick.
	 */
	private static void buildOffsets(int radius) {
		if (radius == offsetRadius) {
			return;
		}
		long[] keys = new long[(radius * 2 + 1) * (radius * 2 + 1)];
		int count = 0;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int distanceSqr = dx * dx + dz * dz;
				if (distanceSqr > radius * radius) {
					continue;
				}
				keys[count++] = (long) distanceSqr << 32 | (dx + radius) << 16 | (dz + radius);
			}
		}
		Arrays.sort(keys, 0, count);

		offsetX = new int[count];
		offsetZ = new int[count];
		for (int i = 0; i < count; i++) {
			int packed = (int) keys[i];
			offsetX[i] = (packed >>> 16) - radius;
			offsetZ[i] = (packed & 0xFFFF) - radius;
		}
		offsetRadius = radius;
	}

	/** Forgets scan results and geometry. Deliberately does not touch {@link BedrockHoleLog}: a settings change is no reason to re-announce old finds. */
	private static void clear() {
		holesByChunk.clear();
		knownAnchors.clear();
		faces = List.of();
		edges = List.of();
		labels = List.of();
		outlinesDirty = false;
		lastLevel = null;
	}
}
