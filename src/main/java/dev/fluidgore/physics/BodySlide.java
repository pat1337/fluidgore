package dev.fluidgore.physics;

import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A dead body is a slack weight, not a box: lying across a drop (a stair, a ledge, the lip of a pit) with too much of
 * it hanging over, it slumps over the edge, drops onto the next step, and goes on down until it lies on something
 * flat. One rule for the server (which moves a kept body, so everyone sees it where it ends up) and the client (which
 * moves the bodies only it draws).
 * <p>
 * The footprint of the body is sampled on a grid: each point is held by the block under it or hangs over air. If more
 * than {@link #HOLDS} of it hangs, the body is pulled from where it is held towards where it hangs, harder the more
 * hangs. Gravity, momentum and friction do the rest.
 */
public final class BodySlide {
	/** Points per side of the footprint. */
	private static final int GRID = 4;
	/** How far under the body a block still holds it: resting on the ground leaves a hair's gap at most. */
	private static final double PROBE = 0.08;
	/** Share of the footprint that may hang over a drop and the body still stay: a little over an edge, it holds. */
	private static final double HOLDS = 0.2;
	/** Pull per tick of a body hanging entirely over the drop on one side, in blocks per tick. */
	private static final double PULL = 0.11;

	private BodySlide() {
	}

	/**
	 * The pull this tick on a body lying on the ground with collision box {@code box}: horizontal, towards the drop;
	 * zero if it lies steady (on the flat, balanced, or in the air).
	 */
	public static Vec3 pull(CollisionGetter level, AABB box) {
		double inset = Math.min(0.05, box.getXsize() * 0.1);
		double insetZ = Math.min(0.05, box.getZsize() * 0.1);
		int held = 0;
		double heldX = 0.0;
		double heldZ = 0.0;
		for (int i = 0; i < GRID; i++) {
			double x = box.minX + inset + (box.getXsize() - 2.0 * inset) * i / (GRID - 1);
			for (int j = 0; j < GRID; j++) {
				double z = box.minZ + insetZ + (box.getZsize() - 2.0 * insetZ) * j / (GRID - 1);
				AABB probe = new AABB(x - 0.01, box.minY - PROBE, z - 0.01, x + 0.01, box.minY - 1.0E-3, z + 0.01);
				if (!level.noBlockCollision(null, probe)) {
					held++;
					heldX += x;
					heldZ += z;
				}
			}
		}

		int all = GRID * GRID;
		double hangs = 1.0 - held / (double) all;
		if (held == 0 || hangs <= HOLDS) {
			return Vec3.ZERO;
		}

		Vec3 centre = box.getCenter();
		double dx = centre.x - heldX / held;
		double dz = centre.z - heldZ / held;
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 1.0E-4) {
			// Balanced on a point: it stays.
			return Vec3.ZERO;
		}

		double strength = PULL * (hangs - HOLDS) / (1.0 - HOLDS);
		return new Vec3(dx / length * strength, 0.0, dz / length * strength);
	}
}
