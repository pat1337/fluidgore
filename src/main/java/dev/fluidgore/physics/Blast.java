package dev.fluidgore.physics;

/**
 * The physics of a carbon-overwrapped pressure vessel (COPV), shared by the server (the tank block's real damage) and
 * the client (the gore, {@code trigger/PressureVessel}). Plain numbers only, so the dedicated server can load it.
 * <ul>
 * <li>Expansion energy: ideal isentropic expansion of the gas from vessel pressure down to the air,
 *     {@code E = pV / (gamma - 1) * (1 - (p0 / p)^((gamma - 1) / gamma))}, air (gamma 1.4). 600 L at 10,000 psi holds
 *     about 87 MJ. Dividing by TNT's energy is an energy comparison; TankState.waveKg applies the chosen wave coupling.</li>
 * <li>On the ground the blast reflects off it and acts like a charge 1.8 times as big (a hemispherical surface burst).</li>
 * <li>Peak side-on overpressure from the scaled distance {@code Z = r / W^(1/3)} by Mills (1987):
 *     {@code P = 1772/Z^3 - 114/Z^2 + 108/Z} kPa, capped at the vessel's own pressure.</li>
 * <li>A leak through a crack of area A is choked (sonic) flow: thrust {@code 1.26 p A}, 8.7 kN for 1 cm^2 at
 *     10,000 psi. TankState integrates finite orifice mass loss, gas cooling and shell heat exchange.</li>
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
	/** Air at 10,000 psi and room temperature, kg per litre (250 kg in the default 600 L tank). */
	private static final double AIR_KG_PER_LITRE = 0.417;
	/** The carbon overwrap and its liner, kg per litre of volume. */
	private static final double SHELL_KG_PER_LITRE = 0.25;
	/** A choked leak's thrust per unit of gauge pressure and area. */
	private static final double THRUST_COEFFICIENT = 1.26;

	private Blast() {
	}

	/** Ideal isentropic expansion energy, J; not a detonation yield or a real-gas EOS calculation. */
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

	/**
	 * Real TNT, kg, that a block of vanilla TNT (an explosion of power 4) matches: vanilla's blast kills a cow at full
	 * health out to about 6 blocks, and by the overpressure curve above a surface burst of 8 kg does it there (150 kPa,
	 * {@link #FATAL_KPA}). Its bands then fall where a real charge's do: bodies blown apart within 2.6 m, torn open to 3.6,
	 * blast lung out to 6, burst eardrums to 11.
	 */
	public static final double TNT_BLOCK_KG = 8.0;
	/** Soft tissue's density, kg/m^3, for how hard a blast throws a piece of it. */
	private static final double FLESH_DENSITY = 1050.0;

	/**
	 * The real charge, kg of TNT, of a vanilla explosion of {@code power} (its radius: TNT 4, a creeper 3, a charged
	 * creeper 6, a bed 5). Lethal range goes with the cube root of the charge and vanilla's with the power, so the charge
	 * goes with the cube of it: a creeper is 3.4 kg, a charged one 27.
	 */
	public static double charge(float power) {
		double p = Math.max(0.1, power) / 4.0;
		return TNT_BLOCK_KG * p * p * p;
	}

	/**
	 * Specific impulse of the blast's positive phase, Pa s, {@code metres} from a ground burst of {@code tntKg}: the push
	 * that throws things, as opposed to the peak pressure that tears them. A fit to the Kingery-Bulmash hemispherical
	 * charts, {@code i / W^(1/3) = 230 / Z^1.1} for scaled distance Z in m/kg^(1/3).
	 */
	public static double impulse(double metres, double tntKg) {
		double w = Math.cbrt(Math.max(1.0E-6, tntKg) * SURFACE_BURST);
		double z = Math.max(0.15, metres) / w;
		return 230.0 * w / Math.pow(z, 1.1);
	}

	/**
	 * How fast, m/s, the blast throws a piece of flesh {@code thick} metres deep along its path, {@code metres} from the
	 * charge: the impulse over the piece's mass per unit area (it takes the wave head on, reflected, half again as hard).
	 * A thin rib flies faster than a lung; within a metre of the charge pieces leave at tens of metres a second.
	 */
	public static double throwSpeed(double metres, double tntKg, double thick) {
		return 1.5 * impulse(metres, tntKg) / (FLESH_DENSITY * Math.max(0.02, thick));
	}

	/**
	 * Radius, m, of a charge's fireball: the common empirical fit for high explosives, diameter {@code 3.86 W^0.32} m for
	 * {@code W} kg of TNT (a block of TNT, 8 kg: about 3.7 m). It lasts a fraction of a second, so it sears and heats the
	 * surface of what is in it; it does not cook it through. Published fit; how much it sears is a game's choice.
	 */
	public static float fireballRadius(double tntKg) {
		return (float) (1.93 * Math.pow(Math.max(0.0, tntKg), 0.32));
	}

	/**
	 * Radius, m, of the cold cloud of a gas vessel's burst: the gas cools sharply as it expands from the vessel's pressure
	 * and frosts what it sweeps over close in. A game's choice of size ({@code 1.5 W^(1/3)} for its TNT equivalent),
	 * not a measured one.
	 */
	public static float gasCloudRadius(double tntKg) {
		return (float) (1.5 * Math.cbrt(Math.max(0.0, tntKg)));
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

	/** Bounded ideal-air orifice flow, kg/s. The subcritical branch tends to zero at ambient pressure. */
	public static double massFlow(double psi, double celsius, double areaCm2) {
		double p = Math.max(0.0, psi) * PA_PER_PSI + ATMOSPHERE_PA;
		double ratio = ATMOSPHERE_PA / p;
		double t = Math.max(100.0, celsius + 273.15);
		double critical = Math.pow(2.0 / (GAMMA + 1.0), GAMMA / (GAMMA - 1.0));
		double flow = ratio <= critical
				? Math.sqrt(GAMMA / 287.0) * Math.pow(2.0 / (GAMMA + 1.0), (GAMMA + 1.0) / (2.0 * (GAMMA - 1.0)))
				: Math.sqrt(Math.max(0.0, 2.0 * GAMMA / (287.0 * (GAMMA - 1.0)) * (Math.pow(ratio, 2.0 / GAMMA) - Math.pow(ratio, (GAMMA + 1.0) / GAMMA))));
		return 0.7 * Math.max(0.0, areaCm2) * 1.0E-4 * p / Math.sqrt(t) * flow;
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
