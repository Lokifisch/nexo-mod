package dev.nexoclient.nexomod.norender;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityTickList;

import dev.nexoclient.nexomod.NexoMod;

/**
 * Entity-type render culling for FPS: a manually-picked set
 * ({@link NexoNoRenderConfig}) merged with a Dynamic, auto-detected set —
 * both feed the same {@link #isHidden} check the render/hitbox mixins call,
 * which is the entire "merge" behavior described by the pig/armor-stand/cow
 * example: toggling Dynamic off only clears what Dynamic added.
 *
 * <h2>Dynamic mode measures its own timing now, not vanilla's profiler</h2>
 *
 * <p>Two earlier versions of this piggybacked on vanilla's own profiling
 * classes and both caused real failures. First, reading
 * {@code Minecraft.fpsPieProfiler} directly: that field is hard-gated by
 * {@code Minecraft.tick()} to only run while the F3 debug screen is
 * physically open — {@code Minecraft.tick()} calls
 * {@code fpsPieProfiler.disable()} every single tick otherwise, fighting our
 * own {@code enable()} call, and querying it produced a crash. Second,
 * wrapping entity ticking in an independent {@code ActiveProfiler} (vanilla's
 * own profiler implementation, just an instance we owned): its internals —
 * string-concatenating path building, a map entry per section, an unclear
 * accumulation lifecycle across repeated {@code startTick()}/{@code endTick()}
 * cycles — turned out to be expensive and heavy enough, applied to a
 * server's full entity list right as a world finishes loading (exactly when
 * FPS is naturally low and the entity count peaks), to run the client out of
 * memory.
 *
 * <p>Neither failure was about the *idea* of per-entity-type tick timing —
 * it was specifically vanilla's profiler machinery being both gate-kept and
 * heavier than it looks. This version doesn't touch it at all: a plain
 * {@code System.nanoTime()} either side of each entity's own {@code tick()}
 * call, summed into two small maps that are cleared at the start of every
 * scan (so their size is always bounded by however many entity types are
 * ticking *this* cycle, never accumulating across cycles or across a
 * session). Simpler, cheaper per entity than vanilla's push/pop, and fully
 * self-contained.
 *
 * <h2>Two more misses before the metric itself was right</h2>
 *
 * <p>A percentage-of-total threshold flagged nearly everything: with only a
 * handful of distinct entity types ticking, each one's share is trivially
 * above even a strict cutoff purely because the total it's a share of is
 * small, regardless of real cost. Switching to a raw nanoseconds total per
 * type still flagged nearly everything, for a related reason — total cost
 * scales with population, so a couple hundred perfectly ordinary pigs at a
 * few microseconds each clears any total-nanos floor just by there being a
 * couple hundred of them. The metric that actually means "this entity type
 * is expensive" is the *average* cost per instance, not a total or a share —
 * see {@link #scan}, and its two-scans-in-a-row requirement, which exists
 * for an unrelated reason: a single {@code nanoTime()} sample can't tell a
 * genuinely expensive entity apart from an ordinary one that happened to be
 * mid-tick when a GC pause or scheduling stall landed on the thread.
 */
public final class NexoNoRender {
	private static final long SCAN_INTERVAL_MILLIS = 2000L;
	/**
	 * Average tick time a single instance of a type must cross for that type
	 * to count as a lag culprit this scan — an average per instance, not a
	 * raw total summed across every instance of the type. Summing raw totals
	 * flagged nearly everything, because total cost trivially scales with
	 * population: 200 perfectly ordinary pigs at a few microseconds each
	 * clears any total-nanos floor just by there being 200 of them, with
	 * nothing actually wrong with any single pig. 100µs is well above what an
	 * ordinary vanilla mob's tick costs and well within reach of something
	 * doing real per-tick work.
	 * ponytail: fixed threshold, not adaptive to the server's actual tick
	 * budget — revisit if a real /tick rate change ever makes this misfire.
	 */
	private static final long PER_INSTANCE_THRESHOLD_NANOS = 100_000L;
	/**
	 * No scanning for this long after joining a world. Chunk/entity loading
	 * right after joining is real, expected lag that has nothing to do with any
	 * one entity type — scanning during it would blame whatever entity happened
	 * to be ticking at the time, not an actual offender.
	 */
	private static final long JOIN_GRACE_MILLIS = 15_000L;

	/** Auto-detected offenders. Never persisted — see the class doc. */
	private static final Set<EntityType<?>> dynamicHidden = ConcurrentHashMap.newKeySet();
	/** Scratch maps, cleared at the start of every scan — never left to grow across cycles. */
	private static final Map<EntityType<?>, Long> nanosByType = new ConcurrentHashMap<>();
	private static final Map<EntityType<?>, Integer> countByType = new ConcurrentHashMap<>();
	/**
	 * Types that crossed the threshold on the *previous* scan. A type only
	 * moves into {@link #dynamicHidden} once it crosses on two scans in a row
	 * — a single stray GC pause or OS scheduling stall lands on whichever
	 * entity happens to be mid-tick at that instant, which one sample can't
	 * tell apart from a genuinely expensive entity type; a real offender is
	 * expensive every time it's measured, a stall victim isn't.
	 */
	private static Set<EntityType<?>> previousOffenders = Set.of();

	private static long lastScanMillis;
	private static volatile long worldJoinedAtMillis = Long.MAX_VALUE;

	private NexoNoRender() {
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> worldJoinedAtMillis = System.currentTimeMillis());
	}

	/**
	 * Called from {@code NoRenderProfilerMixin} in place of a plain
	 * {@code EntityTickList.forEach(action)}, whenever it decides this tick is
	 * worth timing. Falls back to a bare {@code forEach} — zero overhead — on
	 * every other tick, and whenever Dynamic is off entirely.
	 */
	public static void tickEntitiesProfiled(EntityTickList entities, Consumer<Entity> action) {
		if (!shouldProfileThisTick()) {
			entities.forEach(action);
			return;
		}
		lastScanMillis = System.currentTimeMillis();
		nanosByType.clear();
		countByType.clear();
		try {
			entities.forEach(entity -> timeOneEntity(entity, action));
		} finally {
			scan();
		}
	}

	private static void timeOneEntity(Entity entity, Consumer<Entity> action) {
		long start = System.nanoTime();
		action.accept(entity);
		long elapsed = System.nanoTime() - start;
		EntityType<?> type = entity.getType();
		nanosByType.merge(type, elapsed, Long::sum);
		countByType.merge(type, 1, Integer::sum);
	}

	private static boolean shouldProfileThisTick() {
		if (!NexoNoRenderConfig.get().dynamicEnabled()) {
			return false;
		}
		long now = System.currentTimeMillis();
		if (now - worldJoinedAtMillis < JOIN_GRACE_MILLIS) {
			return false;
		}
		if (now - lastScanMillis < SCAN_INTERVAL_MILLIS) {
			return false;
		}
		return Minecraft.getInstance().getFps() < NexoNoRenderConfig.get().dynamicFpsThreshold();
	}

	private static void scan() {
		Set<EntityType<?>> currentOffenders = new HashSet<>();
		for (Map.Entry<EntityType<?>, Long> entry : nanosByType.entrySet()) {
			EntityType<?> type = entry.getKey();
			long average = entry.getValue() / countByType.get(type);
			if (average >= PER_INSTANCE_THRESHOLD_NANOS) {
				currentOffenders.add(type);
			}
		}
		for (EntityType<?> type : currentOffenders) {
			if (previousOffenders.contains(type)) {
				dynamicHidden.add(type);
			}
		}
		previousOffenders = currentOffenders;
	}

	/** Called from the render thread once per candidate entity per frame — must stay cheap. */
	public static boolean isHidden(EntityType<?> type) {
		if (dynamicHidden.contains(type)) {
			return true;
		}
		return NexoNoRenderConfig.get().isManuallyHidden(EntityType.getKey(type).getPath());
	}

	/** The QoL row's pill click — flips Dynamic specifically, the "quick" half of this feature. */
	public static void toggleDynamic() {
		setDynamicEnabled(!NexoNoRenderConfig.get().dynamicEnabled());
	}

	public static void setDynamicEnabled(boolean enabled) {
		NexoNoRenderConfig.get().setDynamicEnabled(enabled);
		if (!enabled) {
			// Cleared rather than left to decay: an anti-lag tool that keeps hiding
			// yesterday's offenders after being turned off is just a stale list.
			dynamicHidden.clear();
			previousOffenders = Set.of();
		}
	}

	/** For the config screen, to mark rows Dynamic is currently also hiding. */
	public static Set<EntityType<?>> dynamicallyHidden() {
		return Set.copyOf(dynamicHidden);
	}
}
