package dev.fluidgore.material;

import net.minecraft.util.Mth;

/**
 * Heat and water working on one piece over time, the same rule for every piece of a body whoever owns it. It runs
 * whether the piece moves or lies still, and what it does stays done: a piece taken out of the fire cools, but it stays
 * as cooked, as charred and as dry as it got.
 *
 * <ul>
 *   <li>The surface temperature eases towards what its surroundings give it, quicker for thin pieces and tissue heat
 *   gets into easily, and far quicker in water.</li>
 *   <li>While it still holds water, the heat goes into boiling it off and the surface stays near 100 C; it can only
 *   get much hotter once it has dried.</li>
 *   <li>From about 55 C the protein sets (cooked); from about 180 C it chars.</li>
 *   <li>Dry enough (under 12% water left) and hot enough, what can burn in it catches. Fat burns on its own flame until the fat is gone; lean meat,
 *   organs and bone only burn while a fire feeds them. Water puts it out, and rain unless it lies in lava.</li>
 *   <li>Hot fat runs: grease drips from it.</li>
 *   <li>Charring cracks it: char past its material's {@link Material#brittle} and a hard landing breaks it up.</li>
 * </ul>
 *
 * Game-scale numbers, set so a 4-pixel organ in a fire steams for some seconds, cooks, dries and chars within about
 * a minute at the default burning speed. Not a validated tissue model.
 */
public final class Thermal {
	public static final int IGNITED = 1;
	public static final int EXTINGUISHED = 1 << 1;
	/** Boiling off its water. */
	public static final int STEAMING = 1 << 2;
	/** Charring now. */
	public static final int SMOKING = 1 << 3;
	public static final int FLAMING = 1 << 4;
	/** Hot fat running out of it. */
	public static final int DRIPPING = 1 << 5;
	/** A hot piece that went into water just now: a hiss and a puff of steam. */
	public static final int QUENCHED = 1 << 6;
	/** Water (or rain) at work on it: soaking back in, rinsing loose blood out. */
	public static final int WASHED = 1 << 7;
	/** Below freezing: frost on it, steaming off as it warms. */
	public static final int FROSTED = 1 << 8;

	/** Ticks for a 1-pixel piece of lean meat to come most of the way to its surroundings' temperature. */
	private static final float TAU_PER_PIXEL = 30.0F;
	private static final float BOIL = 100.0F;
	private static final float SETS = 55.0F;
	private static final float CHARS = 180.0F;

	private Thermal() {
	}

	/**
	 * {@code ticks} of {@code exposure} on a piece of {@code material} in {@code condition}, {@code thickness} model
	 * pixels through at its thinnest, at {@code pace} (1 = the default burning speed). Returns what happened, as the
	 * flags above.
	 */
	public static int step(Material material, Condition c, Exposure exposure, float thickness, int ticks, float pace) {
		float dt = ticks * pace;
		float thick = Math.max(1.0F, thickness);
		float scale = Mth.sqrt(4.0F / thick);
		int events = 0;
		float before = c.temperature;
		boolean wasBurning = c.burning;

		if (c.burning && (exposure.submerged() || exposure.rained() && exposure.heat() < Exposure.LAVA)) {
			c.burning = false;
		}

		float around = exposure.heat();
		if (c.burning) {
			// Its own flame: hot for fat, barely a flame for lean meat.
			around = Math.max(around, 300.0F + 500.0F * material.fuel());
		}

		float tau = TAU_PER_PIXEL * thick / material.diffusivity() / (exposure.submerged() ? 8.0F : 1.0F);
		c.temperature += (around - c.temperature) * (1.0F - (float) Math.exp(-dt / tau));
		if (exposure.submerged() && before > BOIL) {
			events |= QUENCHED;
		}

		// Wet tissue holds near the boil: the heat goes into the water.
		if (c.moisture > 0.02F && c.temperature > BOIL) {
			float plateau = BOIL + 260.0F * (1.0F - c.moisture) * (1.0F - c.moisture);
			if (c.temperature > plateau) {
				c.temperature = plateau;
			}
		}

		if (c.temperature >= BOIL - 5.0F && around > BOIL && c.moisture > 0.0F) {
			float wetter = material.water() / 0.75F;
			float dry = dt * 0.0025F * ((around - BOIL) / 600.0F) * scale / Math.max(0.1F, wetter);
			float left = Math.max(0.0F, c.moisture - dry);
			// The loose blood dries with it.
			c.fluid *= c.moisture > 0.0F ? left / c.moisture : 0.0F;
			c.moisture = left;
			events |= STEAMING;
		} else if (exposure.submerged() || exposure.rained()) {
			// Soaks back in at the surface, less the more it is cooked or charred; the loose blood washes away.
			float cap = soaks(c);
			float wet = dt * 0.002F * (exposure.submerged() ? 1.0F : 0.25F);
			if (c.moisture < cap) {
				c.moisture = Math.min(cap, c.moisture + wet);
			}

			c.fluid = Math.max(0.0F, c.fluid - wet * 2.0F);
			events |= WASHED;
		}

		if (c.temperature > SETS && c.cooked < 1.0F) {
			c.cooked = Math.min(1.0F, c.cooked + dt * 0.0015F * Math.min(3.0F, (c.temperature - SETS) / 45.0F) * scale);
			// Set blood does not run.
			c.fluid *= 1.0F - Math.min(1.0F, dt * 0.002F);
		}

		if (c.temperature > CHARS && c.charred < 1.0F) {
			float rate = material.bone() ? 0.0004F : 0.0008F;
			c.charred = Math.min(1.0F, c.charred + dt * rate * Math.min(3.0F, (c.temperature - CHARS) / 300.0F) * scale);
			events |= SMOKING;
		}

		if (!c.burning && c.fuel > 0.05F && material.fuel() > 0.0F && !exposure.submerged() && material.water() * c.moisture < 0.12F
				&& (c.temperature > material.ignites() || exposure.flame() && c.temperature > material.ignites() * 0.6F)
				&& !(exposure.rained() && exposure.heat() < Exposure.LAVA)) {
			c.burning = true;
		}

		if (c.burning) {
			// Fat feeds its own flame; anything leaner only burns while a fire feeds it.
			boolean fed = material.fuel() >= 0.3F || exposure.flame() || exposure.heat() >= Exposure.FIRE;
			c.fuel = Math.max(0.0F, c.fuel - dt * 0.0017F);
			if (c.fuel <= 0.02F || !fed) {
				c.burning = false;
			} else {
				events |= FLAMING;
			}
		}

		if (material.fuel() >= 0.5F && c.temperature > 60.0F && c.fuel > 0.1F && !exposure.submerged()) {
			// The fat renders out.
			c.fuel = Math.max(0.0F, c.fuel - dt * 0.0003F);
			events |= DRIPPING;
		}

		if (c.temperature < 0.0F) {
			events |= FROSTED;
		}

		float cracked = material.bone() ? 1.0F - 0.8F * Mth.clamp((c.charred - 0.75F) / 0.25F, 0.0F, 1.0F) : 1.0F - 0.6F * c.charred;
		c.integrity = Math.min(c.integrity, cracked);
		if (c.burning && !wasBurning) {
			events |= IGNITED;
		} else if (wasBurning && !c.burning && (exposure.submerged() || exposure.rained())) {
			// Put out by water, not gone out for want of anything to burn.
			events |= EXTINGUISHED;
		}

		return events;
	}

	/** How much of its speed a piece keeps bouncing: a dried, cooked piece is firmer and bounces more than a raw one. */
	public static float restitution(Material material, Condition c) {
		return Math.min(0.6F, material.restitution() + 0.25F * (1.0F - c.moisture) * c.cooked + 0.1F * c.charred);
	}

	/** True if landing at {@code speed} (blocks per tick) breaks it up: charred brittle, it crumbles. */
	public static boolean crumbles(Material material, Condition c, double speed) {
		// About a block's fall: v^2 = 2 g h at 0.06 blocks per tick per tick.
		return c.charred >= material.brittle() && speed > 0.3;
	}

	/** The loose fluid it leaves where it lands, at most {@code most}, taken out of what it has left. */
	public static float release(Condition c, float most) {
		float amount = Math.min(c.fluid, most);
		c.fluid -= amount;
		return amount;
	}

	/** How much water it can take back in: less the more it is cooked or charred. */
	private static float soaks(Condition c) {
		return 1.0F - 0.6F * c.cooked - 0.3F * c.charred;
	}

	/** True if a step would change anything: hot, burning, near heat, or in water or rain with work for it. */
	public static boolean active(Condition c, Exposure exposure) {
		// Water always works on what is in it, cold or not; rain only while there is blood to rinse or water to soak back in.
		return c.burning || c.temperature > 35.0F || c.temperature < 10.0F || exposure.heat() > 35.0F || exposure.submerged()
				|| exposure.rained() && (c.fluid > 0.0F || c.moisture < soaks(c));
	}
}
