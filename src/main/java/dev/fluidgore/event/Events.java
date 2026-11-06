package dev.fluidgore.event;

import dev.fluidgore.config.GoreConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where every wound, push and change of material on a body or a piece came from. Each physical event that hurts
 * bodies (a vessel rupturing, a charge going off, a jet, the fire a piece lies in) has a stable id and a {@link Source};
 * what it does reaches a body through separate {@link Channel}s (the pressure wave, the jet, each fragment, the fireball,
 * a piece hitting something afterwards), and each effect is written to a bounded trace naming the event and channel
 * that caused it.
 *
 * <p>Code that makes an effect asks the open scope ({@link #begin}/{@link #end}, like {@code Fate}) what is causing it.
 * Above all a scope says whether the event has any heat to give: a compressed-gas rupture does not, so nothing it does
 * can sear or char; only a {@link Channel#THERMAL} cause, recorded on its own, can.
 *
 * <p>Ids are made from what the event physically is (its source, where, how big), not from dice or the clock, so the
 * same rupture set up again has the same id, and comparing two runs means comparing their traces.
 */
public final class Events {
	/** What made an event. */
	public enum Source {
		/** A chemical charge (TNT, a creeper, a bed): pressure and a fireball. */
		CHARGE(true),
		/** A pressure vessel rupturing: cold gas, pressure, a jet and casing fragments; no fireball. */
		COMPRESSED_GAS(false),
		/** Fire, lava or a hot block a piece lies in or beside. */
		ENVIRONMENT(true),
		/** A player's own hands and tools (a flame they put to something is recorded as {@link #ENVIRONMENT} heat). */
		PLAYER(false),
		/** A blast whose source this game never learned. Treated as a charge, as before sources were told apart. */
		UNKNOWN(true);

		/** True if this source can heat what it reaches. */
		public final boolean thermal;

		Source(boolean thermal) {
			this.thermal = thermal;
		}
	}

	/** How an event reached a body or a piece. */
	public enum Channel {
		/** The blast wave's overpressure and wind. */
		PRESSURE,
		/** A vessel's escaping gas striking a body. */
		JET,
		/** One casing fragment (its index is {@link Entry#index}). */
		FRAGMENT,
		/** A gas vessel's gas cooling as it expands: it chills what it sweeps over (never heats or chars it). */
		EXPANSION,
		/** A player handling a piece: a punch, a blade, a flame put to it, a stamp. */
		HANDLING,
		/** A piece made by the event hitting the world afterwards. */
		COLLISION,
		/** Heat: a charge's fireball, or the fire a piece lies in. */
		THERMAL
	}

	/** What a cause did. */
	public enum Effect {
		WOUND,
		IMPULSE,
		MATERIAL
	}

	/** One effect: when (client tick), which event, through which channel (and which fragment), on what, and how. */
	public record Entry(int tick, long event, Source source, Channel channel, int index, Effect effect, String target, String detail) {
		@Override
		public String toString() {
			return String.format(java.util.Locale.ROOT, "t%d %016x %s/%s%s %s %s: %s", tick, event, source, channel, index >= 0 ? "#" + index : "", effect, target,
					detail);
		}
	}

	/** An open scope: the event and source now acting, and the cloud it threw out (centre, radius in blocks; 0 for none). */
	private record Scope(long event, Source source, @Nullable Vec3 centre, float cloud, dev.fluidgore.physics.Underwater.@Nullable Site water) {
	}

	/** Effects kept, newest last; older ones are dropped. */
	public static final int CAPACITY = 4096;
	private static final ArrayDeque<Entry> TRACE = new ArrayDeque<>();
	private static final ArrayDeque<Scope> SCOPES = new ArrayDeque<>();
	private static int tick;

	private Events() {
	}

	/**
	 * The id of an event of {@code source} at {@code at} of {@code size} (kg of TNT, litres of gas, whatever measures it):
	 * the same for the same event set up the same way.
	 */
	public static long id(Source source, Vec3 at, double size) {
		long h = BlockPos.containing(at).asLong() * 0x9E3779B97F4A7C15L;
		h ^= (long) source.ordinal() * 0xC2B2AE3D27D4EB4FL;
		h ^= Double.doubleToLongBits(Math.round(size * 1000.0) / 1000.0) * 0x165667B19E3779F9L;
		h ^= h >>> 31;
		// Never 0: 0 means "no event".
		return h == 0L ? 1L : h;
	}

	/** A body as a trace names it: its UUID, the same on every screen and across reloads. */
	public static String body(net.minecraft.world.entity.Entity entity) {
		return "body " + entity.getUUID();
	}

	/** A loose piece as a trace names it: its key (see {@code GorePart#key}, {@code Giblets#key}). */
	public static String piece(long key) {
		return String.format(java.util.Locale.ROOT, "piece %016x", key);
	}

	/** Opens a scope: what follows, until {@link #end}, is done by {@code event} of {@code source}. Scopes nest. */
	public static void begin(long event, Source source) {
		SCOPES.push(new Scope(event, source, null, 0.0F, null));
	}

	/**
	 * Opens a scope for an event that threw out a cloud of {@code radius} blocks round {@code centre}: a charge's fireball
	 * (hot), a vessel's expanding gas (cold; see {@link #cloud}).
	 */
	public static void begin(long event, Source source, Vec3 centre, float radius) {
		SCOPES.push(new Scope(event, source, centre, radius, null));
	}

	/**
	 * Opens a scope for an event at {@code centre} under water ({@code water}; null for one in the air). A charge under
	 * water has no fireball and no cold cloud: water takes the heat, and its gas makes a bubble instead (see
	 * {@link #water}), so nothing in it is seared or chilled.
	 */
	public static void begin(long event, Source source, Vec3 centre, float radius, dev.fluidgore.physics.Underwater.@Nullable Site water) {
		SCOPES.push(new Scope(event, source, centre, water != null ? 0.0F : radius, water));
	}

	/** Where the event now acting went off under water, or null if it went off in the air (or nothing is acting). */
	public static dev.fluidgore.physics.Underwater.@Nullable Site water() {
		Scope s = SCOPES.peek();
		return s == null ? null : s.water();
	}

	/**
	 * The open-air overpressure, kPa, that the blast now acting does at {@code at}, {@code metres} from {@code centre}:
	 * in the open by {@code Blast.overpressure}, under water by {@code Underwater.overpressure} for a body of {@code kg}
	 * (NaN for a piece with no lungs).
	 */
	public static float overpressure(net.minecraft.world.level.BlockGetter level, Vec3 at, double metres, double tntKg, double psi, double kg) {
		dev.fluidgore.physics.Underwater.Site water = water();
		return water == null ? dev.fluidgore.physics.Blast.overpressure(metres, tntKg, psi)
				: dev.fluidgore.physics.Underwater.overpressure(level, water, at, tntKg, psi, kg);
	}

	/**
	 * True if the blast now acting went off under water and {@code at} is in the water near enough its gas bubble to be
	 * carried by it: a piece lying there must wake, so the bubble's flow can move it (see {@code GasBubbles}).
	 */
	public static boolean stirred(net.minecraft.world.level.BlockGetter level, Vec3 at, double tntKg) {
		dev.fluidgore.physics.Underwater.Site water = water();
		return water != null && dev.fluidgore.physics.Underwater.submerged(level, at)
				&& at.distanceTo(water.centre()) < 4.0 * dev.fluidgore.physics.Underwater.bubbleRadius(tntKg, water.depth()) + 1.0;
	}

	/** How fast, m/s, the blast now acting throws a piece {@code thick} metres deep at {@code at}, in the open or under water. */
	public static double throwSpeed(net.minecraft.world.level.BlockGetter level, Vec3 at, double metres, double tntKg, double thick) {
		dev.fluidgore.physics.Underwater.Site water = water();
		return water == null ? dev.fluidgore.physics.Blast.throwSpeed(metres, tntKg, thick)
				: dev.fluidgore.physics.Underwater.throwSpeed(level, water, at, tntKg, thick);
	}

	/**
	 * How deep in the acting event's cloud {@code at} is, 0 (outside, or no cloud) to 1 (at its centre). For a charge that
	 * is its fireball: what it sears and heats. For compressed gas it is the gas cooling as it expands: what it chills.
	 */
	public static float cloud(Vec3 at) {
		Scope s = SCOPES.peek();
		if (s == null || s.centre() == null || s.cloud() <= 0.0F) {
			return 0.0F;
		}

		return (float) Math.max(0.0, 1.0 - at.distanceTo(s.centre()) / s.cloud());
	}

	public static void end() {
		SCOPES.poll();
	}

	/** The event now acting, or 0 outside any scope. */
	public static long current() {
		Scope s = SCOPES.peek();
		return s == null ? 0L : s.event();
	}

	/** The source now acting ({@link Source#UNKNOWN} outside any scope). */
	public static Source source() {
		Scope s = SCOPES.peek();
		return s == null ? Source.UNKNOWN : s.source();
	}

	/** True if what is acting now can heat things: false inside a compressed-gas event. */
	public static boolean thermal() {
		return source().thermal;
	}

	/** Records an effect of the event now acting. */
	public static void record(Channel channel, int index, Effect effect, String target, String detail) {
		record(current(), source(), channel, index, effect, target, detail);
	}

	/** Records an effect of {@code event} (0: none known) of {@code source}. */
	public static void record(long event, Source source, Channel channel, int index, Effect effect, String target, String detail) {
		Entry entry = new Entry(tick, event, source, channel, index, effect, target, detail);
		TRACE.addLast(entry);
		while (TRACE.size() > CAPACITY) {
			TRACE.removeFirst();
		}

		if (GoreConfig.get().traceEvents) {
			dev.fluidgore.FluidGore.LOGGER.info("[event] {}", entry);
		}
	}

	/**
	 * What the heat round a piece did in one {@code Thermal} step, as the environment's own event (named by the block it
	 * lies at and how hot it is there): when it first chars, catches and is put out. Called with the step's
	 * {@code events} and the char it had before.
	 */
	public static void heat(String target, Vec3 at, dev.fluidgore.material.Exposure around, int events, float charredBefore, dev.fluidgore.material.Condition c) {
		long event = id(Source.ENVIRONMENT, at, around.heat());
		if (charredBefore <= 0.0F && c.charred > 0.0F) {
			record(event, Source.ENVIRONMENT, Channel.THERMAL, -1, Effect.MATERIAL, target,
					String.format(java.util.Locale.ROOT, "began to char at %.0f C (surroundings %.0f C)", c.temperature, around.heat()));
		}

		if ((events & dev.fluidgore.material.Thermal.IGNITED) != 0) {
			record(event, Source.ENVIRONMENT, Channel.THERMAL, -1, Effect.MATERIAL, target, String.format(java.util.Locale.ROOT, "caught fire at %.0f C", c.temperature));
		}

		if ((events & dev.fluidgore.material.Thermal.EXTINGUISHED) != 0) {
			record(event, Source.ENVIRONMENT, Channel.THERMAL, -1, Effect.MATERIAL, target, "put out by water");
		}
	}

	/** Every effect still kept, oldest first. */
	public static List<Entry> trace() {
		return new ArrayList<>(TRACE);
	}

	/** The effects of {@code event}, oldest first. */
	public static List<Entry> of(long event) {
		List<Entry> out = new ArrayList<>();
		for (Entry e : TRACE) {
			if (e.event() == event) {
				out.add(e);
			}
		}

		return out;
	}

	/** The latest effect through {@code channel} recorded on {@code target}, or null. */
	public static @Nullable Entry last(String target, Channel channel) {
		Entry found = null;
		for (Entry e : TRACE) {
			if (e.channel() == channel && e.target().equals(target)) {
				found = e;
			}
		}

		return found;
	}

	public static void tick() {
		tick++;
	}

	public static void clear() {
		TRACE.clear();
		SCOPES.clear();
	}
}
