package dev.fluidgore.blood;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Arterial spurts: a cut artery pumps blood out in pulses, one jet per heartbeat, with the pressure dropping
 * over time. Each spurt follows its wound (a moving entity, a severed neck) and shoots droplets in a direction
 * that wanders a little, so the jets paint streaks across the ground and walls.
 */
public final class Spurts {
	private static final int MAX_SPURTS = 24;

	private static final class Spurt {
		final Supplier<Vec3> wound;
		final int rgb;
		final int life;
		final float strength;
		/** Ticks between heartbeats; a panicking animal's heart races. */
		final int beat;
		Vec3 direction;
		int age;

		Spurt(Supplier<Vec3> wound, Vec3 direction, int rgb, int life, float strength, int beat) {
			this.wound = wound;
			this.direction = direction;
			this.rgb = rgb;
			this.life = life;
			this.strength = strength;
			this.beat = beat;
		}
	}

	private final BloodWorld world;
	private final List<Spurt> spurts = new ArrayList<>();
	private final RandomSource random = RandomSource.create();

	Spurts(BloodWorld world) {
		this.world = world;
	}

	public int size() {
		return spurts.size();
	}

	/**
	 * Starts a spurt.
	 *
	 * @param direction rough jet direction (normalised by the caller or not, it is normalised here)
	 * @param life      duration in ticks
	 * @param strength  initial jet speed factor, about 0.5-1.5
	 */
	public void start(Supplier<Vec3> wound, Vec3 direction, int rgb, int life, float strength) {
		if (spurts.size() >= MAX_SPURTS) {
			spurts.removeFirst();
		}

		Vec3 dir = direction.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 1.0, 0.0) : direction.normalize();
		spurts.add(new Spurt(wound, dir, rgb, life, strength, 6 + random.nextInt(5)));
	}

	void tick() {
		spurts.removeIf(this::step);
	}

	/** Returns true when the spurt is finished. */
	private boolean step(Spurt s) {
		s.age++;
		if (s.age > s.life) {
			return true;
		}

		// Pressure: fades over the spurt's life; each heartbeat is a sharp surge that decays over a few ticks.
		float fade = 1.0F - s.age / (float) s.life;
		int phase = s.age % s.beat;
		float pulse = phase == 0 ? 1.0F : (float) Math.exp(-phase * 0.9);
		float pressure = s.strength * fade * (0.25F + 0.75F * pulse);
		if (pressure < 0.08F || !world.droplets().hasRoom()) {
			return false;
		}

		// The jet wobbles as the body moves.
		s.direction = s.direction.add(random.nextGaussian() * 0.06, random.nextGaussian() * 0.04, random.nextGaussian() * 0.06).normalize();
		Vec3 origin = s.wound.get();
		int drops = phase == 0 ? 4 + random.nextInt(3) : 1 + random.nextInt(2);
		for (int i = 0; i < drops; i++) {
			double speed = (0.22 + random.nextDouble() * 0.12) * pressure;
			world.droplets().spawn(
					origin.x, origin.y, origin.z,
					s.direction.x * speed + random.nextGaussian() * 0.02,
					s.direction.y * speed + random.nextGaussian() * 0.02,
					s.direction.z * speed + random.nextGaussian() * 0.02,
					0.006F * pressure + 0.002F, world.vary(s.rgb, 0.15F), 0.02F + random.nextFloat() * 0.03F * Mth.clamp(pressure, 0.3F, 1.0F));
		}

		return false;
	}

	void clear() {
		spurts.clear();
	}
}
