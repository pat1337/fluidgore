package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Turns damage into blood: a spray of droplets, a mist of vanilla dust particles, and a pool on a kill. */
public final class BloodEmitter {
	private static final RandomSource RANDOM = RandomSource.create();

	private BloodEmitter() {
	}

	/**
	 * @param damage health lost (2 = one heart)
	 * @param lethal true for the killing blow: much larger burst and a pool under the body
	 * @param away   unit direction pointing away from the attacker, or null if unknown
	 */
	public static void bleed(BloodWorld world, ClientLevel level, LivingEntity entity, float damage, boolean lethal, @Nullable Vec3 away) {
		GoreLevel preset = GoreConfig.get().goreLevel;
		if (!preset.enabled()) {
			return;
		}

		damage = Mth.clamp(damage, 0.5F, 40.0F);
		int rgb = dev.fluidgore.trigger.EntityFilter.bloodColor(entity);
		float bodySize = Mth.clamp(entity.getBbWidth() * entity.getBbHeight() / (0.6F * 1.8F), 0.3F, 3.0F);
		float volume = Math.min(3.0F, (0.03F + damage * 0.03F) * preset.puddles * bodySize * (lethal ? 3.0F : 1.0F));
		int count = Math.round((3.0F + damage * 2.5F) * preset.particles * (lethal ? 2.2F : 1.0F));
		Vec3 origin = new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() * 0.6, entity.getZ());

		spray(world, level, origin, away, count, volume * 0.7F, rgb, entity.getBbWidth() * 0.4, 0.12, 0.32);
		mist(level, origin, rgb, Math.min(60, Math.round(count * 1.2F)), entity.getBbWidth() * 0.5);

		if (lethal) {
			double surface = SurfaceProbe.surfaceBelow(level, entity.getX(), entity.getY() + 0.3, entity.getY() - 1.5, entity.getZ());
			if (!Double.isNaN(surface)) {
				world.landOnFloor(level, entity.getX(), surface, entity.getZ(), volume * 0.3F, rgb, world.decalLifetime());
			}
		}
	}

	/** A burst from an arbitrary point (a severed neck or limb). */
	public static void burst(BloodWorld world, ClientLevel level, Vec3 origin, int rgb, float volume, int count) {
		GoreLevel preset = GoreConfig.get().goreLevel;
		int scaled = Math.round(count * preset.particles);
		spray(world, level, origin, null, scaled, volume * preset.puddles, rgb, 0.1, 0.08, 0.3);
		mist(level, origin, rgb, Math.min(40, scaled), 0.15);
	}

	/** A slow trickle, used for stumps and moving severed parts. */
	public static void drip(BloodWorld world, Vec3 origin, int rgb, float volume) {
		world.droplets().spawn(origin.x + (RANDOM.nextDouble() - 0.5) * 0.1, origin.y, origin.z + (RANDOM.nextDouble() - 0.5) * 0.1,
				(RANDOM.nextDouble() - 0.5) * 0.04, -0.02, (RANDOM.nextDouble() - 0.5) * 0.04, volume, rgb, 0.03F);
	}

	private static void spray(BloodWorld world, ClientLevel level, Vec3 origin, @Nullable Vec3 away, int count, float volume,
			int rgb, double spread, double minSpeed, double maxSpeed) {
		if (count <= 0) {
			return;
		}

		float each = volume / count;
		for (int i = 0; i < count && world.droplets().hasRoom(); i++) {
			// Random direction on the upper hemisphere, biased away from the attacker.
			double yaw = RANDOM.nextDouble() * Math.PI * 2.0;
			double pitch = RANDOM.nextDouble() * 0.9 + 0.1;
			double dx = Math.cos(yaw) * Math.cos(pitch);
			double dz = Math.sin(yaw) * Math.cos(pitch);
			double dy = Math.sin(pitch);
			if (away != null) {
				dx += away.x * 1.4;
				dz += away.z * 1.4;
			}

			double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
			double speed = minSpeed + RANDOM.nextDouble() * (maxSpeed - minSpeed);
			world.droplets().spawn(
					origin.x + (RANDOM.nextDouble() - 0.5) * spread,
					origin.y + (RANDOM.nextDouble() - 0.5) * spread,
					origin.z + (RANDOM.nextDouble() - 0.5) * spread,
					dx / length * speed, dy / length * speed * 0.8 + 0.05, dz / length * speed,
					each, rgb, 0.025F + RANDOM.nextFloat() * 0.035F);
		}
	}

	private static void mist(ClientLevel level, Vec3 origin, int rgb, int count, double spread) {
		DustParticleOptions dust = new DustParticleOptions(rgb, 0.9F);
		for (int i = 0; i < count; i++) {
			level.addParticle(dust,
					origin.x + (RANDOM.nextDouble() - 0.5) * spread * 2.0,
					origin.y + (RANDOM.nextDouble() - 0.5) * spread * 2.0,
					origin.z + (RANDOM.nextDouble() - 0.5) * spread * 2.0,
					0.0, 0.0, 0.0);
		}
	}
}
