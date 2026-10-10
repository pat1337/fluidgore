package dev.fluidgore.blood;

import dev.fluidgore.trigger.Fate;
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
	private final RandomSource random = Fate.stream("spurts");

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

	/** Blood running off a body: slow drops that form on the wound and below it, then fall with gravity. */
	private static final class Ooze {
		final Supplier<Vec3> top;
		final Supplier<Vec3> bottom;
		final double width;
		final int rgb;
		final int life;
		final float strength;
		int age;

		Ooze(Supplier<Vec3> top, Supplier<Vec3> bottom, double width, int rgb, int life, float strength) {
			this.top = top;
			this.bottom = bottom;
			this.width = width;
			this.rgb = rgb;
			this.life = life;
			this.strength = strength;
		}
	}

	private static final int MAX_OOZE = 24;
	private final List<Ooze> oozes = new ArrayList<>();

	/**
	 * Starts blood running down a body, from the wound at {@code top} to the feet at {@code bottom}. Drops
	 * form anywhere along that line and fall off the limbs, so a wounded animal leaves a trail.
	 */
	public void ooze(Supplier<Vec3> top, Supplier<Vec3> bottom, double width, int rgb, int life, float strength) {
		if (oozes.size() >= MAX_OOZE) {
			oozes.removeFirst();
		}

		oozes.add(new Ooze(top, bottom, width, rgb, life, strength));
	}

	void tick() {
		spurts.removeIf(this::step);
		oozes.removeIf(this::stepOoze);
	}

	private boolean stepOoze(Ooze o) {
		if (++o.age > o.life) {
			return true;
		}

		float fade = 1.0F - o.age / (float) o.life;
		if (random.nextFloat() < 0.55F * o.strength * fade && world.droplets().hasRoom()) {
			Vec3 top = o.top.get();
			Vec3 bottom = o.bottom.get();
			double t = random.nextDouble();
			float size = 0.02F + random.nextFloat() * 0.025F;
			world.droplets().spawn(
					top.x + (bottom.x - top.x) * t + random.nextGaussian() * o.width,
					top.y + (bottom.y - top.y) * t,
					top.z + (bottom.z - top.z) * t + random.nextGaussian() * o.width,
					random.nextGaussian() * 0.006, -0.01, random.nextGaussian() * 0.006,
					0.004F + size * 0.2F, world.vary(o.rgb, 0.15F), size);
		}

		return false;
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
		oozes.clear();
	}
}
