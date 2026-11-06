package dev.fluidgore.rot;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.util.Mth;

/**
 * How far one body has rotted, and what that looks like. Everything here is a function of {@code days}, the body's
 * decay-days (see {@link Decay}: counted up as it lies, each day weighed by the heat where it lay and by the body's
 * size, so 1 is one day of a pig lying in a 20 degree summer; it never goes down), the same on every screen. The stages and their timing follow the pig carcass studies forensic
 * entomology is built on (Payne 1965 and after):
 * <ol>
 * <li><b>Fresh</b> (0 to {@link #BLOAT}): the blood settles and the skin goes waxy pale, rigor mortis stiffens the
 *     limbs within hours and lets go after a day and a half. Blowflies find it within the hour and lay in the wounds.</li>
 * <li><b>Bloat</b> ({@link #BLOAT} to {@link #ACTIVE}): gut bacteria fill the body with gas. It swells tight, the
 *     legs are pushed out stiff, the skin marbles green and purple along the veins, fluid purges from the mouth.</li>
 * <li><b>Active decay</b> ({@link #ACTIVE} to {@link #ADVANCED}): the skin splits and the gas goes out of it at
 *     once. Maggot masses eat through it, the skin slips, the body sinks into a black wet mess in a spreading pool
 *     of fluid that kills the grass under it. The smell and the flies are at their worst.</li>
 * <li><b>Advanced decay</b> ({@link #ADVANCED} to {@link #DRY}): most of the soft tissue is gone; the bones come
 *     through, what is left dries to brown leather, the maggots crawl off to pupate.</li>
 * <li><b>Dry remains</b> (after {@link #DRY}): bones, hair and dried hide, bleaching in the sun for months.</li>
 * </ol>
 * In dry heat (deserts, savanna, the Nether) a body does not get past bloating: it dries out before the maggots can
 * have it, and stays a shrunken leathery mummy ({@link #arid}).
 */
public final class Rot {
	public static final RenderStateDataKey<Rot> KEY = RenderStateDataKey.create(() -> "fluidgore:rot");
	public static final float BLOAT = 1.5F;
	public static final float ACTIVE = 4.5F;
	public static final float ADVANCED = 10.0F;
	public static final float DRY = 20.0F;

	/** This body's own dice for where the rot starts, from its UUID: the same everywhere. */
	public final long seed;
	public float days;
	/** Dry heat: it mummifies instead of liquefying. */
	public boolean arid;

	public Rot(long seed) {
		this.seed = seed;
	}

	/** Rigor mortis, 0..1: sets in over hours, holds, and lets go after about a day and a half. */
	public float rigor() {
		return ramp(days, 0.05F, 0.4F) * (1.0F - ramp(days, 0.9F, 1.6F));
	}

	/**
	 * How swollen the trunk is: up to 1 at the height of the bloat, then it bursts and goes down at once, and sinks
	 * below its living size as the flesh goes (negative).
	 */
	public float swell() {
		return RotClock.swell(days, arid);
	}

	/** Has burst open: the gas is out and the inside is coming out. */
	public boolean burst() {
		return !arid && days >= ACTIVE;
	}

	/** Flies on and over it, 0..1: from the first hour, worst in active decay, gone once it is dry. */
	public float flies() {
		float f = 0.3F * ramp(days, 0.02F, 0.5F) + 0.4F * ramp(days, 0.5F, BLOAT + 1.0F) + 0.3F * ramp(days, BLOAT + 1.0F, ACTIVE);
		f *= 1.0F - 0.7F * ramp(days, ADVANCED, DRY) - 0.3F * ramp(days, DRY, DRY + 5.0F);
		return arid ? f * 0.4F : f;
	}

	/** Maggots working in it, 0..1. */
	public float maggots() {
		if (arid) {
			return 0.0F;
		}

		return ramp(days, ACTIVE - 1.0F, ACTIVE + 1.0F) * (1.0F - ramp(days, ADVANCED, ADVANCED + 5.0F));
	}

	/** Fluid running out of it, 0..1: the purge in the bloat, then the pool of active decay. */
	public float ooze() {
		if (arid) {
			return 0.0F;
		}

		return 0.3F * ramp(days, BLOAT + 1.0F, ACTIVE) + 0.7F * ramp(days, ACTIVE, ACTIVE + 1.0F) * (1.0F - ramp(days, ADVANCED, ADVANCED + 4.0F));
	}

	/** The stench, 0..1: what drifts off it as a haze. */
	public float stench() {
		return Math.max(swell() * 0.6F, flies()) * (1.0F - ramp(days, ADVANCED, DRY));
	}

	/** Size of the dark stain it leaves on the ground, 0..1 of its widest. */
	public float stain() {
		return arid ? 0.0F : ramp(days, BLOAT + 1.0F, ADVANCED);
	}

	/** Colour multiplied into the body's texture (and anything drawn with its state, such as wool). */
	public int tint() {
		return tint(days, arid);
	}

	public static int tint(float days, boolean arid) {
		if (days < BLOAT) {
			// Waxy pallor as the blood drains down out of the skin.
			return lerp(0xFFFFFF, 0xD9D6CC, days / BLOAT);
		}

		if (arid) {
			return days < ADVANCED ? lerp(0xD9D6CC, 0x8E6E4C, (days - BLOAT) / (ADVANCED - BLOAT))
					: lerp(0x8E6E4C, 0x6E5238, (days - ADVANCED) / 30.0F);
		}

		if (days < ACTIVE) {
			// Green from the belly out, the veins marbled.
			return lerp(0xD9D6CC, 0x93A472, (days - BLOAT) / (ACTIVE - BLOAT));
		}

		if (days < ADVANCED) {
			// Black-green and wet.
			return lerp(0x93A472, 0x4B4934, (days - ACTIVE) / (ADVANCED - ACTIVE));
		}

		if (days < DRY) {
			return lerp(0x4B4934, 0x5C4D3A, (days - ADVANCED) / (DRY - ADVANCED));
		}

		// Dried hide, bleaching.
		return lerp(0x5C4D3A, 0x8C8170, (days - DRY) / 40.0F);
	}

	/** 0 before {@code from}, 1 after {@code to}, smooth between. */
	static float ramp(float x, float from, float to) {
		float t = Mth.clamp((x - from) / (to - from), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	static int lerp(int from, int to, float t) {
		t = Mth.clamp(t, 0.0F, 1.0F);
		int r = Math.round(Mth.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF));
		int g = Math.round(Mth.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF));
		int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
		return r << 16 | g << 8 | b;
	}
}
