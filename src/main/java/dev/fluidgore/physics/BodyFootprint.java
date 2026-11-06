package dev.fluidgore.physics;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;

/**
 * What a body lying on the ground covers of it, seen from above: a capsule along its length, head to tail. Bodies that
 * fall together are eased out of each other's until they lie side by side, against each other and not through each
 * other. One shape for the server (which spreads the bodies it keeps, so everyone sees them where they end up) and the
 * client (which spreads the bodies only it draws, and smears the ones that touch).
 *
 * @param x    middle, world x
 * @param z    middle, world z
 * @param ux   along its length (unit), x
 * @param uz   along its length (unit), z
 * @param half half the length of the capsule's core
 * @param r    its radius
 */
public record BodyFootprint(double x, double z, double ux, double uz, double half, double r) {
	/** A four-legged animal is about this many times as long as it is wide, head to tail. */
	private static final double FOUR_LEGGED_LENGTH = 1.6;
	/** The radius as a share of the body's breadth across the ground. */
	private static final double ROUND = 0.5;

	/**
	 * The footprint of a body whose middle is at {@code x, z}, facing {@code yaw} degrees, {@code width} and
	 * {@code height} as it stands, leaning {@code roll} radians (0 standing, +-PI/2 on its side: then it is as broad
	 * across the ground as it is tall).
	 */
	public static BodyFootprint of(double x, double z, float yaw, float width, float height, float roll, boolean fourLegged) {
		double across = Mth.lerp(Math.abs(Mth.sin(roll)), width, height);
		double r = ROUND * across;
		double length = width * (fourLegged ? FOUR_LEGGED_LENGTH : 1.0);
		float yawRad = yaw * Mth.DEG_TO_RAD;
		// Forward is (-sin yaw, cos yaw).
		return new BodyFootprint(x, z, -Mth.sin(yawRad), Mth.cos(yawRad), Math.max(0.0, length * 0.5 - r), r);
	}

	/** A body longer than it is wide: a four-legged animal (the farm animals, horses, dogs), not a bird or a person. */
	public static boolean fourLegged(LivingEntity entity) {
		return entity instanceof Animal && !(entity instanceof Chicken);
	}

	/**
	 * How far {@code this} lies into {@code other} (0 or less: apart), and the way out of it for this one: {x, z, depth}.
	 * From the nearest points of the two cores, as two capsules are tested.
	 */
	public double[] into(BodyFootprint other) {
		// Nearest points of segments p + s*u (|s| <= half) and q + t*v (|t| <= other.half).
		double wx = x - other.x;
		double wz = z - other.z;
		double b = ux * other.ux + uz * other.uz;
		double d = ux * wx + uz * wz;
		double e = other.ux * wx + other.uz * wz;
		double denominator = 1.0 - b * b;
		double s = denominator > 1.0E-6 ? Mth.clamp((b * e - d) / denominator, -half, half) : 0.0;
		double t = Mth.clamp(e + b * s, -other.half, other.half);
		s = Mth.clamp(b * t - d, -half, half);
		double dx = wx + ux * s - other.ux * t;
		double dz = wz + uz * s - other.uz * t;
		double dist = Math.sqrt(dx * dx + dz * dz);
		if (dist < 1.0E-4) {
			// Crossing: apart the way their middles lie from each other, or across this one's length if those meet too
			// (the caller then decides which goes which way).
			double apart = Math.sqrt(wx * wx + wz * wz);
			return apart > 1.0E-6 ? new double[] {wx / apart, wz / apart, r + other.r} : new double[] {-uz, ux, r + other.r};
		}

		return new double[] {dx / dist, dz / dist, r + other.r - dist};
	}
}
