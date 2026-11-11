package dev.fluidgore.blood;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Blood being tipped out of a bucket: it comes out over a second or so, not all in one tick. Most of it lands where
 * it is poured and runs on from there ({@link BloodFluidSim}); some of it is seen falling.
 */
final class Pours {
	/** Ticks a full bucket takes to empty. */
	private static final int TICKS = 30;
	/** Most streams at once. */
	private static final int MOST = 16;
	/** Height above the floor the stream falls from, blocks. */
	private static final double HEIGHT = 0.9;

	private static final class Pour {
		final double x;
		final double y;
		final double z;
		final int rgb;
		final float rate;
		float left;
		int age;

		Pour(double x, double y, double z, float litres, int rgb) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.rgb = rgb;
			this.left = litres;
			this.rate = Math.max(0.5F, litres / TICKS);
		}
	}

	private final BloodWorld world;
	private final List<Pour> pours = new ArrayList<>();

	Pours(BloodWorld world) {
		this.world = world;
	}

	/** Starts {@code litres} of {@code rgb} blood pouring onto the floor at (x, y, z). */
	void start(double x, double y, double z, float litres, int rgb) {
		if (pours.size() >= MOST) {
			pours.removeFirst();
		}

		pours.add(new Pour(x, y, z, litres, rgb));
	}

	void tick(Level level) {
		if (pours.isEmpty()) {
			return;
		}

		int lifetime = world.decalLifetime();
		pours.removeIf(pour -> {
			float amount = Math.min(pour.left, pour.rate);
			pour.left -= amount;
			pour.age++;
			double surface = SurfaceProbe.surfaceBelow(level, pour.x, pour.y + 0.4, pour.y - 2.0, pour.z);
			// A tenth of it is the stream you see falling; it lands with the rest.
			float seen = amount * 0.1F;
			// Where it falls wanders a little, the same for everyone.
			double sway = Math.sin(pour.age * 1.7) * 0.06;
			world.droplets().runoff(pour.x + sway, pour.y + HEIGHT, pour.z - sway, 0.0, 0.0, seen / BloodSupply.LITRES_PER_UNIT, pour.rgb);
			if (Double.isNaN(surface)) {
				// Nothing under it: all of it falls.
				world.droplets().runoff(pour.x, pour.y, pour.z, 0.0, 0.0, (amount - seen) / BloodSupply.LITRES_PER_UNIT, pour.rgb);
			} else {
				world.fluid().pour(level, pour.x, surface, pour.z, amount - seen, pour.rgb, lifetime);
			}

			return pour.left <= 0.0F;
		});
	}

	void removeIn(AABB box) {
		pours.removeIf(pour -> box.contains(pour.x, pour.y, pour.z));
	}

	void clear() {
		pours.clear();
	}
}
