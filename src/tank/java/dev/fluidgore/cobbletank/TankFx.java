package dev.fluidgore.cobbletank;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What a leak looks like (client side, but only {@link Level#addParticle}, so it lives with the common code): the jet
 * as a white plume of fog where the cold gas meets the air, longer and thicker with the jet's power, and frost blowing
 * off the crack once the casing is below freezing.
 */
public final class TankFx {
	/** Told of every leak this client sees, each tick (the client sets it: the leak's sound and the hearing it costs). */
	public interface Leaks {
		void leak(Object key, Vec3 origin, Vec3 direction, TankState tank);
	}

	public static Leaks leaks = (key, origin, direction, tank) -> {
	};

	private TankFx() {
	}

	/** One tick of the leak of {@code key} (a block entity or a flying tank) seen on this client. */
	public static void jet(Object key, Level level, Vec3 origin, Vec3 dir, TankState tank, RandomSource random) {
		leaks.leak(key, origin, dir, tank);
		double power = dev.fluidgore.physics.GasJet.power(tank.psiNow(), tank.area);
		if (power < 1.0E-4) {
			return;
		}

		double scale = Math.sqrt(power);
		double reach = dev.fluidgore.physics.GasJet.reach(level, origin, dir, tank.psiNow(), tank.area);
		if (reach < 0.05) return;
		int puffs = 1 + (int) Math.round(6.0 * scale);
		for (int i = 0; i < puffs; i++) {
			double speed = Math.min(reach / 12.0, (0.25 + random.nextDouble() * 0.9) * (0.4 + 1.6 * scale));
			double spread = 0.08 + 0.1 * random.nextDouble();
			Vec3 v = dir.scale(speed).add(random.nextGaussian() * spread, random.nextGaussian() * spread, random.nextGaussian() * spread);
			level.addParticle(i % 3 == 0 ? ParticleTypes.POOF : ParticleTypes.CLOUD, origin.x, origin.y, origin.z, v.x, v.y, v.z);
		}

		if (tank.temperature() < 0.0F && random.nextInt(3) == 0) {
			Vec3 v = dir.scale(0.05 + 0.2 * scale);
			level.addParticle(ParticleTypes.SNOWFLAKE, origin.x, origin.y, origin.z, v.x + random.nextGaussian() * 0.03, v.y, v.z + random.nextGaussian() * 0.03);
		}
	}
}
