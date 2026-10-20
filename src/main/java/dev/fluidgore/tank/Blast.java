package dev.fluidgore.tank;

import net.minecraft.util.Mth;

/**
 * The physics of a carbon-overwrapped pressure vessel (COPV), shared by the server (the tank block's real damage) and
 * the client (the gore, {@code trigger/PressureVessel}). Plain numbers only, so the dedicated server can load it.
 * <ul>
 * <li>Stored energy: Brode's isentropic expansion of the gas from vessel pressure down to the air,
 *     {@code E = pV / (gamma - 1) * (1 - (p0 / p)^((gamma - 1) / gamma))}, air (gamma 1.4). 600 L at 10,000 psi holds
 *     about 87 MJ, some 21 kg of TNT.</li>
 * <li>On the ground the blast reflects off it and acts like a charge 1.8 times as big (a hemispherical surface burst).</li>
 * <li>Peak side-on overpressure from the scaled distance {@code Z = r / W^(1/3)} by Mills (1987):
 *     {@code P = 1772/Z^3 - 114/Z^2 + 108/Z} kPa, capped at the vessel's own pressure.</li>
 * <li>A leak through a crack of area A is choked (sonic) flow: thrust {@code 1.26 p A}, 8.7 kN for 1 cm^2 at
 *     10,000 psi, and the pressure falls off exponentially, faster the bigger the crack.</li>
 * </ul>
 */
public final class Blast {
	/** Peak overpressure, kPa, at which each effect begins. */
	public static final float RINGING_KPA = 7.0F;
	public static final float EARDRUM_KPA = 35.0F;
	public static final float LUNG_KPA = 100.0F;
	/** The tank block's real damage kills a body at full health from here (about 8 m from a full 600 L tank). */
	public static final float FATAL_KPA = 150.0F;
	public static final float LETHAL_KPA = 300.0F;
	public static final float TEAR_KPA = 550.0F;
	public static final float SHRED_KPA = 1400.0F;
	/** Doors and windows are blown in from here (about 28 m from a full tank). */
	public static final float DOOR_KPA = 14.0F;
	/** Glass breaks from here (about 55 m from a full tank). */
	public static final float GLASS_KPA = 6.5F;
	/** Energy of a kilogram of TNT, J. */
	private static final double TNT_JOULES = 4.184E6;
	private static final double ATMOSPHERE_PA = 101_325.0;
	public static final double PA_PER_PSI = 6894.76;
	/** Standard gravity, m/s^2. */
	public static final double GRAVITY = 9.81;
	private static final double GAMMA = 1.4;
	/** A burst on the ground: the reflected wave makes it act like this much more charge. */
	private static final double SURFACE_BURST = 1.8;
	/** Air at 10,000 psi and room temperature, kg per litre (250 kg in the Roadster's 600 L). */
	private static final double AIR_KG_PER_LITRE = 0.417;
	/** The carbon overwrap and its liner, kg per litre of volume. */
	private static final double SHELL_KG_PER_LITRE = 0.25;
	/** A choked leak's thrust per unit of gauge pressure and area. */
	private static final double THRUST_COEFFICIENT = 1.26;
	/** How long a 600 L tank's pressure takes to fall to 1/e through a crack of 1 cm^2, seconds. */
	private static final double TAU_SECONDS_CM2 = 5.0;

	private Blast() {
	}

	/** Energy stored in the gas, J (Brode: isentropic expansion to the air). */
	public static double energy(double litres, double psi) {
		double p = psi * PA_PER_PSI + ATMOSPHERE_PA;
		return p * (litres / 1000.0) / (GAMMA - 1.0) * (1.0 - Math.pow(ATMOSPHERE_PA / p, (GAMMA - 1.0) / GAMMA));
	}

	public static double tntKg(double litres, double psi) {
		return energy(litres, psi) / TNT_JOULES;
	}

	/** Peak side-on overpressure, kPa, {@code metres} from a ground burst of {@code tntKg}, in the open. */
	public static float overpressure(double metres, double tntKg, double psi) {
		double z = Math.max(0.05, metres) / Math.cbrt(Math.max(1.0E-6, tntKg) * SURFACE_BURST);
		double kPa = 1772.0 / (z * z * z) - 114.0 / (z * z) + 108.0 / z;
		return (float) Math.min(kPa, psi * PA_PER_PSI / 1000.0);
	}

	/** What a wall between the body and the burst leaves of it ({@code seen} 0..1): a blast wave bends round cover, so never nothing. */
	public static float covered(float kPa, float seen) {
		return kPa * (0.35F + 0.65F * seen);
	}

	/** How far out, in metres, the open-air overpressure is still {@code kPa}. */
	public static double reach(double tntKg, double psi, float kPa) {
		double near = 0.05;
		double far = 4000.0;
		if (overpressure(near, tntKg, psi) < kPa) {
			return 0.0;
		}

		for (int i = 0; i < 60; i++) {
			double mid = 0.5 * (near + far);
			if (overpressure(mid, tntKg, psi) >= kPa) {
				near = mid;
			} else {
				far = mid;
			}
		}

		return near;
	}

	/**
	 * The real damage of {@code kPa} of peak overpressure to a body: nothing below burst eardrums, then a share of its
	 * health that rises with the log of the pressure (lungs bruising, then tearing), and death from {@link #FATAL_KPA}.
	 */
	public static float damage(float kPa, float maxHealth) {
		if (kPa < EARDRUM_KPA) {
			return 0.0F;
		}

		if (kPa >= FATAL_KPA) {
			// Torn lungs, air in the blood: no armour helps against the inside of the chest.
			return 1000.0F;
		}

		float t = (float) (Math.log(kPa / EARDRUM_KPA) / Math.log(FATAL_KPA / EARDRUM_KPA));
		return Math.max(1.0F, maxHealth * (float) Math.pow(t, 1.5));
	}

	/** Mass of a tank of {@code litres} holding gas at {@code psi}, kg. */
	public static double mass(double litres, double psi) {
		return litres * (SHELL_KG_PER_LITRE + AIR_KG_PER_LITRE * Math.max(0.0, psi) / 10_000.0);
	}

	/** Thrust of a leak through {@code areaCm2} at {@code psi}, N. */
	public static double thrust(double psi, double areaCm2) {
		return THRUST_COEFFICIENT * psi * PA_PER_PSI * areaCm2 * 1.0E-4;
	}

	/** The factor the pressure keeps each tick through a crack of {@code areaCm2} in a tank of {@code litres}. */
	public static double bleed(double litres, double areaCm2) {
		double tau = TAU_SECONDS_CM2 / Math.max(1.0E-4, areaCm2) * (litres / 600.0);
		return Math.exp(-1.0 / (20.0 * tau));
	}

	/**
	 * The tank's temperature, C, once it has been leaking down to {@code fraction} of its pressure: the gas left inside
	 * expands and chills, from the jet's first chill at the crack down to -80 C near empty.
	 */
	public static float temperature(float fraction) {
		return Math.max(-80.0F, 5.0F - 85.0F * (float) Math.pow(Mth.clamp(1.0F - fraction, 0.0F, 1.0F), 0.7));
	}

	/**
	 * How loud a leak is {@code metres} away, dB: about 148 dB a metre from a 1 cm^2 crack at 10,000 psi, quieter with
	 * the jet's power, falling 6 dB for each doubling of distance.
	 */
	public static float loudness(double psi, double areaCm2, double metres) {
		double power = Math.max(1.0E-6, psi / 10_000.0 * areaCm2);
		return (float) (148.0 + 10.0 * Math.log10(power) - 20.0 * Math.log10(Math.max(0.5, metres)));
	}
}
