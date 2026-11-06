package dev.fluidgore.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A charge that goes off under water. Water barely compresses, so it does three things air does not:
 * <ul>
 *   <li><b>The shock wave</b> carries much further and harder (Cole's similitude for TNT:
 *   {@code P = 52.4 (W^(1/3)/R)^1.13} MPa, decaying as {@code e^(-t/theta)}). Flesh is mostly water, so the wave passes
 *   through solid tissue and moves it only millimetres; what it tears is anything holding gas: the lungs, the gut, the
 *   ears. Near the surface its tail is cut off by the wave reflected from the surface, which arrives inverted (the
 *   "surface cutoff"), so shallow water is much safer than deep water at the same range.</li>
 *   <li><b>The gas bubble.</b> The hot products of the charge push the water out into a bubble of radius
 *   {@code R = 3.38 (W/(D+10.1))^(1/3)} m in {@code T = 2.11 W^(1/3)/(D+10.1)^(5/6)} s, then the water crushes it back
 *   down and it rebounds, a few times, rising as it goes. This is what really throws things around in the water: the
 *   water flows out as it grows and back in as it collapses.</li>
 *   <li><b>No fireball.</b> Nothing burns; nothing is scorched or seared. If the bubble reaches the surface it bursts
 *   out as a spray dome and a plume of white water that rains back down.</li>
 * </ul>
 * One set of rules for every screen: nothing here rolls dice. Published fits (Cole 1948; Swisdak 1978; the Goertner /
 * Yelverton lung-injury impulse used for marine mammals); the plume height is a game's choice of scale.
 */
public final class Underwater {
	/** Bubble radius coefficient for TNT, m (m/kg)^(1/3). */
	private static final double BUBBLE_J = 3.38;
	/** Bubble period coefficient for TNT, s m^(5/6) / kg^(1/3). */
	private static final double BUBBLE_K = 2.11;
	/** Hydrostatic head of the atmosphere, metres of water. */
	public static final double ATMOSPHERE_M = 10.1;
	/** Water's acoustic impedance, rho c, Pa s/m (1000 kg/m^3 at 1480 m/s). */
	private static final double RHO_C = 1.48E6;
	/** Sound speed in water, m/s. */
	private static final double SOUND = 1480.0;
	/** Onset of slight lung injury, Pa s per kg^(1/3) of body (Goertner's form of Yelverton's criterion). */
	private static final double LUNG_ONSET = 39.1;
	/** Deepest water searched for a surface or a floor, blocks. */
	private static final int SEARCH = 64;

	private Underwater() {
	}

	/**
	 * Where a charge went off in water: its centre, how deep it was ({@code depth}, m below the surface over it), the
	 * height of that surface, and how far the bottom is below it ({@code floor}, m; {@link #SEARCH} if none was found).
	 */
	public record Site(Vec3 centre, double depth, double surfaceY, double floor) {
	}

	/** The charge's site, or null if {@code centre} is not in water. */
	public static @Nullable Site at(BlockGetter level, Vec3 centre) {
		BlockPos pos = BlockPos.containing(centre);
		if (!water(level, pos)) {
			return null;
		}

		double surface = surfaceAbove(level, pos);
		BlockPos.MutableBlockPos down = pos.mutable();
		int below = 0;
		while (below < SEARCH && water(level, down.move(0, -1, 0))) {
			below++;
		}

		double floor = below >= SEARCH ? SEARCH : centre.y - (pos.getY() - below);
		return new Site(centre, Math.max(0.0, surface - centre.y), surface, Math.max(0.0, floor));
	}

	/** True if {@code at} is in water. */
	public static boolean submerged(BlockGetter level, Vec3 at) {
		return water(level, BlockPos.containing(at));
	}

	/** The height of the water's top over {@code pos} (which is in water). */
	public static double surfaceAbove(BlockGetter level, BlockPos pos) {
		BlockPos.MutableBlockPos up = pos.mutable();
		FluidState fluid = level.getFluidState(up);
		for (int i = 0; i < SEARCH; i++) {
			BlockPos.MutableBlockPos next = up.mutable().move(0, 1, 0);
			if (!water(level, next)) {
				return up.getY() + fluid.getHeight(level, up);
			}

			up.set(next);
			fluid = level.getFluidState(up);
		}

		return up.getY() + 1.0;
	}

	private static boolean water(BlockGetter level, BlockPos pos) {
		return level.getFluidState(pos).is(FluidTags.WATER);
	}

	/** Largest radius, m, of the gas bubble of {@code tntKg} at {@code depth} m (a block of TNT, 2 m down: about 3 m). */
	public static double bubbleRadius(double tntKg, double depth) {
		return BUBBLE_J * Math.cbrt(Math.max(1.0E-6, tntKg) / (Math.max(0.0, depth) + ATMOSPHERE_M));
	}

	/** Seconds from the charge to the bubble's first collapse (a block of TNT, 2 m down: about half a second). */
	public static double bubblePeriod(double tntKg, double depth) {
		return BUBBLE_K * Math.cbrt(Math.max(1.0E-6, tntKg)) / Math.pow(Math.max(0.0, depth) + ATMOSPHERE_M, 5.0 / 6.0);
	}

	/** Peak pressure of the shock wave, kPa, {@code metres} from {@code tntKg} of TNT in open water. */
	public static double peakKPa(double metres, double tntKg) {
		double w = Math.cbrt(Math.max(1.0E-6, tntKg));
		return 52_400.0 * Math.pow(w / Math.max(0.1, metres), 1.13);
	}

	/** Time constant, s, of the shock wave's exponential decay there. */
	public static double decay(double metres, double tntKg) {
		double w = Math.cbrt(Math.max(1.0E-6, tntKg));
		return 92.5E-6 * w * Math.pow(w / Math.max(0.1, metres), -0.22);
	}

	/** Impulse of the shock wave, Pa s, there in open water (no surface near). */
	public static double impulse(double metres, double tntKg) {
		double w = Math.cbrt(Math.max(1.0E-6, tntKg));
		return 5_750.0 * w * Math.pow(w / Math.max(0.1, metres), 0.89);
	}

	/**
	 * The impulse that reaches {@code at} once the surface has cut the wave off: the reflection off the surface arrives
	 * inverted after the extra path it travels, and from then on cancels the wave. Out of the water, none reaches it.
	 */
	public static double cutImpulse(BlockGetter level, Site site, Vec3 at, double tntKg) {
		double direct = Math.max(0.1, at.distanceTo(site.centre()));
		double targetDepth = Math.max(0.0, site.surfaceY() - at.y);
		double dx = at.x - site.centre().x;
		double dz = at.z - site.centre().z;
		double reflected = Math.sqrt(dx * dx + dz * dz + Mth.square(site.depth() + targetDepth));
		double delay = Math.max(0.0, reflected - direct) / SOUND;
		double share = 1.0 - Math.exp(-delay / decay(direct, tntKg));
		return impulse(direct, tntKg) * share;
	}

	/** The impulse, Pa s, at which a body of {@code kg} at {@code depth} m first has its lungs bruised. */
	public static double lungOnset(double kg, double depth) {
		return LUNG_ONSET * Math.cbrt(Math.max(0.5, kg)) * Math.sqrt(1.0 + Math.max(0.0, depth) / ATMOSPHERE_M);
	}

	/**
	 * Share 0..1 of the charge that vents into the air: all of it from a charge at the surface, none from one deeper
	 * than its bubble reaches. What a body above the water feels is a charge of this much in the open.
	 */
	public static double vent(Site site, double tntKg) {
		return Mth.clamp(1.0 - site.depth() / bubbleRadius(tntKg, site.depth()), 0.0, 1.0);
	}

	/**
	 * The overpressure, kPa, that a charge of {@code tntKg} under water does to a body (or a piece of one) at
	 * {@code at}, given as the open-air overpressure that would do the same, so the bands of {@link Blast} (eardrums,
	 * lungs, torn, shredded) read it unchanged.
	 * <ul>
	 *   <li>Inside the gas bubble it is torn apart as by a charge in the open close by: shredded within about 0.6 of the
	 *   bubble's radius, torn open out to its edge.</li>
	 *   <li>In the water beyond, it is the lungs and ears that suffer, by the impulse after the surface cutoff against
	 *   what bruises lungs of its weight ({@code kg}; NaN for a piece with no lungs, which then takes nothing): at the
	 *   onset it reads as {@link Blast#LUNG_KPA}, so twice the onset reads twice that. Never as much as tears a body
	 *   apart: the wave goes through solid flesh.</li>
	 *   <li>Out of the water, only what vented reaches it (see {@link #vent}).</li>
	 * </ul>
	 */
	public static float overpressure(BlockGetter level, Site site, Vec3 at, double tntKg, double psi, double kg) {
		double metres = at.distanceTo(site.centre());
		if (!submerged(level, at)) {
			double vented = tntKg * vent(site, tntKg);
			return vented <= 1.0E-4 ? 0.0F : Blast.overpressure(metres, vented, psi);
		}

		double bubble = bubbleRadius(tntKg, site.depth());
		if (metres <= bubble) {
			return (float) (Blast.SHRED_KPA * Math.pow(0.6 * bubble / Math.max(0.05, metres), 1.5));
		}

		if (Double.isNaN(kg)) {
			return 0.0F;
		}

		double depth = Math.max(0.0, site.surfaceY() - at.y);
		double ratio = cutImpulse(level, site, at, tntKg) / lungOnset(kg, depth);
		return (float) Math.min(Blast.TEAR_KPA * 0.95, Blast.LUNG_KPA * ratio);
	}

	/**
	 * How fast, m/s, the shock wave itself throws a piece under water: hardly at all. The water at the front moves at
	 * {@code P / (rho c)} for a fraction of a millisecond, a few millimetres; what moves pieces is the bubble's flow
	 * (see the client's {@code GasBubbles}). Out of the water, the vented share throws it as in the open.
	 */
	public static double throwSpeed(BlockGetter level, Site site, Vec3 at, double tntKg, double thick) {
		double metres = at.distanceTo(site.centre());
		if (!submerged(level, at)) {
			double vented = tntKg * vent(site, tntKg);
			return vented <= 1.0E-4 ? 0.0 : Blast.throwSpeed(metres, vented, thick);
		}

		// The front's own push, kept to a nudge: its impulse over the piece's mass per area is tiny next to the flow.
		return Math.min(0.5, impulse(metres, tntKg) / RHO_C / Math.max(0.02, thick) * 0.05);
	}

	/** Speed, m/s, the surface over a charge is thrown up at when the shock wave reaches it: twice the water's speed in the wave. */
	public static double domeSpeed(Site site, double tntKg) {
		return 2.0 * peakKPa(Math.max(0.1, site.depth()), tntKg) * 1000.0 / RHO_C;
	}

	/**
	 * Height, m, of the plume of water a bubble throws up when it bursts out of the surface: a game's choice of scale
	 * (about six bubble radii from a charge just under the surface, still a third of that from one a full radius down,
	 * where the bubble only just breaks through), in line with the tens of metres filmed for charges of a few kilograms.
	 */
	public static double plumeHeight(Site site, double tntKg) {
		double bubble = bubbleRadius(tntKg, site.depth());
		return 6.0 * bubble * Mth.clamp(1.0 - site.depth() / (1.5 * bubble), 0.15, 1.0);
	}
}
