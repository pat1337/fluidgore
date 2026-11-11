package dev.fluidgore.trigger;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodTextures;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.SurfaceProbe;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.event.Events;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * What tells a charge from a gas vessel at a glance: the cloud each throws out (see {@link Events#cloud}).
 * <ul>
 *   <li>A charge (TNT, a creeper) makes a fireball, {@code Blast.fireballRadius}: a flash of flame, the ground under it
 *   scorched black, bodies in it singed, pieces in it seared and left smoking, the fattest of them alight.</li>
 *   <li>A gas vessel (the Cobble Tank) makes no fire at all. Its gas cools hard as it expands: a burst of snow and white
 *   frost on the ground round it ({@code Blast.gasCloudRadius}) that melts away, pieces in it frosted pale until they
 *   warm, and nothing seared.</li>
 * </ul>
 * A charge under water makes neither: its gas makes a bubble instead (see {@link GasBubbles}).
 * Either way the wave throws the pieces already lying about (see {@code Giblets#blast}, {@code GorePartManager#blast}).
 * Runs inside the blast's event scope, so everything it does is traced to it.
 */
public final class BlastCloud {
	/** Soot on the ground. */
	private static final int SCORCH = 0x1A1512;
	/** Frost on the ground. */
	private static final int FROST = 0xE6F0FA;
	/** Ticks frost lies on the ground before it has melted. */
	private static final int FROST_TICKS = 600;
	/** This screen's own dice: where the flames and the frost fall changes nothing anything is. */
	private static final RandomSource FX = RandomSource.create();

	private BlastCloud() {
	}

	/** The cloud of the event now acting, {@code radius} blocks round {@code centre}, of {@code tntKg} at {@code psi}. */
	public static void burst(ClientLevel level, BloodWorld blood, Dismemberment dismemberment, Vec3 centre, float radius, double tntKg, double psi) {
		GoreConfig config = GoreConfig.get();
		dev.fluidgore.physics.Underwater.Site water = Events.water();
		if (water != null) {
			// Under water: no flame, no smoke, no scorch, nothing singed. The water takes the heat; the gas makes a bubble
			// that pulses, rises and bursts out of the surface, and its flow is what moves the pieces (see GasBubbles).
			GasBubbles.start(level, water, tntKg);
			dismemberment.giblets().blast(level, blood, centre, tntKg, psi);
			dismemberment.parts().blast(level, centre, tntKg, psi);
			return;
		}

		boolean hot = Events.thermal();
		float particles = config.goreLevel.particles;
		if (hot) {
			fireball(level, blood, centre, radius, particles);
		} else {
			frost(level, blood, centre, radius, particles);
		}

		spill(level, blood, centre, hot ? radius : 0.0F, tntKg, psi, particles);
		SurfaceBursts.start(level, centre, tntKg, psi);

		dismemberment.giblets().blast(level, blood, centre, tntKg, psi);
		dismemberment.parts().blast(level, centre, tntKg, psi);
		if (!hot) {
			return;
		}

		// Bodies in the fireball: their hair and skin singed (see Burns), more the deeper in.
		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity body) || !EntityFilter.affects(body)) {
				continue;
			}

			float depth = Events.cloud(body.getBoundingBox().getCenter());
			if (depth > 0.1F) {
				float singe = 0.6F * depth;
				GoreSystems.dismemberment().burns().restore(body.getId(), Math.max(GoreSystems.dismemberment().burns().charOf(body.getId()), singe));
				Events.record(Events.Channel.THERMAL, -1, Events.Effect.WOUND, Events.body(body),
						String.format(java.util.Locale.ROOT, "singed by the fireball, %.0f%% in, char %.2f", depth * 100.0F, singe));
			}
		}
	}

/** Overpressure, kPa, out to which a blast moves standing blood at all. */
private static final float SPILL_KPA = 12.0F;
/** The dice for where thrown blood goes: it lands and lies, so every screen must throw it alike. */
private static final RandomSource THROWN = dev.fluidgore.trigger.Fate.stream("thrown");

/**
 * Standing blood in the way of the wave. The wind behind a blast front is far faster than any liquid film can hold
 * against: close in it tears the blood off the floor as a red mist that hangs and drifts, further out it throws it
 * as drops, low and outwards, that land in streaks pointing back at the charge. What a fireball reaches of what is
 * left is cooked onto the floor.
 */
private static void spill(ClientLevel level, BloodWorld blood, Vec3 c, float fireball, double tntKg, double psi, float particles) {
	double reach = Math.min(24.0, dev.fluidgore.physics.Blast.reach(tntKg, psi, SPILL_KPA));
	dev.fluidgore.blood.BloodSupply.as(-1, () -> blood.fluid().blast(c.x, c.y, c.z, reach, d -> dev.fluidgore.physics.Blast.overpressure(d, tntKg, psi), fireball,
			thrown -> {
				// The harder the wind, the more of it goes as mist rather than drops.
				float mist = net.minecraft.util.Mth.clamp(thrown.kPa() / 250.0F, 0.15F, 0.8F);
				double dx = thrown.x() - c.x;
				double dz = thrown.z() - c.z;
				double flat = Math.sqrt(dx * dx + dz * dz);
				if (flat < 0.3) {
					double a = THROWN.nextDouble() * Math.PI * 2.0;
					dx = Math.cos(a);
					dz = Math.sin(a);
				} else {
					dx /= flat;
					dz /= flat;
				}

				int puffs = Math.min(10, Math.round(thrown.litres() * mist * 6.0F * particles) + 1);
				for (int i = 0; i < puffs; i++) {
					int color = blood.vary(thrown.rgb(), 0.25F);
					int dark = (color >> 16 & 0xFF) * 2 / 5 << 16 | (color >> 8 & 0xFF) * 2 / 5 << 8 | (color & 0xFF) * 2 / 5;
					level.addParticle(new net.minecraft.core.particles.DustColorTransitionOptions(color, dark, 0.7F + FX.nextFloat() * 1.1F),
							thrown.x() + FX.nextGaussian() * 0.4, thrown.y() + 0.15 + FX.nextDouble() * 0.5, thrown.z() + FX.nextGaussian() * 0.4,
							dx * 0.06 + FX.nextGaussian() * 0.03, 0.02 + FX.nextDouble() * 0.05, dz * 0.06 + FX.nextGaussian() * 0.03);
				}

				float units = thrown.litres() * (1.0F - mist * 0.5F) / dev.fluidgore.blood.BloodSupply.LITRES_PER_UNIT;
				int drops = net.minecraft.util.Mth.clamp(Math.round(thrown.litres() * 5.0F), 1, 8);
				double speed = net.minecraft.util.Mth.clamp(0.25 + thrown.kPa() / 200.0, 0.3, 1.4);
				for (int i = 0; i < drops; i++) {
					double turn = THROWN.nextGaussian() * 0.35;
					double vx = dx * Math.cos(turn) - dz * Math.sin(turn);
					double vz = dz * Math.cos(turn) + dx * Math.sin(turn);
					double v = speed * (0.45 + THROWN.nextDouble() * 0.55);
					blood.droplets().spawn(thrown.x() + THROWN.nextGaussian() * 0.25, thrown.y() + 0.08, thrown.z() + THROWN.nextGaussian() * 0.25, vx * v,
							0.12 + THROWN.nextDouble() * 0.3 * v, vz * v, units / drops, blood.vary(thrown.rgb(), 0.15F), 0.03F + THROWN.nextFloat() * 0.04F);
				}
			}));
}

/** The flash of a charge: flame thrown out to the fireball's edge, embers, a column of smoke, black scorch under it. */
	private static void fireball(ClientLevel level, BloodWorld blood, Vec3 c, float radius, float particles) {
		int flames = Math.round(90 * radius * particles);
		for (int i = 0; i < flames; i++) {
			Vec3 d = new Vec3(FX.nextGaussian(), Math.abs(FX.nextGaussian()) * 0.6, FX.nextGaussian()).normalize();
			double speed = radius * (0.05 + FX.nextDouble() * 0.12);
			level.addParticle(i % 5 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, c.x, c.y + 0.3, c.z, d.x * speed, d.y * speed, d.z * speed);
		}

		for (int i = 0; i < Math.round(30 * particles); i++) {
			level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, c.x + FX.nextGaussian() * radius * 0.3, c.y + FX.nextDouble(),
					c.z + FX.nextGaussian() * radius * 0.3, 0.0, 0.05 + FX.nextDouble() * 0.05, 0.0);
		}

		ground(level, blood, c, radius, SCORCH, blood.decalLifetime());
	}

	/** The cold of a gas vessel: snow thrown out with the fog, frost on the ground round it that melts away. */
	private static void frost(ClientLevel level, BloodWorld blood, Vec3 c, float radius, float particles) {
		int flakes = Math.round(120 * radius * particles);
		for (int i = 0; i < flakes; i++) {
			Vec3 d = new Vec3(FX.nextGaussian(), Math.abs(FX.nextGaussian()) * 0.3, FX.nextGaussian()).normalize();
			double speed = radius * (0.04 + FX.nextDouble() * 0.1);
			level.addParticle(i % 4 == 0 ? ParticleTypes.WHITE_SMOKE : ParticleTypes.CLOUD, c.x, c.y, c.z, d.x * speed, d.y * speed, d.z * speed);
		}

		// Condensation fog does not imply that the ground freezes solid.
	}

	/** Patches of {@code rgb} on the ground within {@code radius}: thick near the centre, scattered at the edge. */
	private static void ground(ClientLevel level, BloodWorld blood, Vec3 c, float radius, int rgb, int lifetime) {
		int patches = Math.round(6 + radius * radius * 1.5F);
		for (int i = 0; i < patches; i++) {
			double r = radius * Math.sqrt(FX.nextDouble());
			double a = FX.nextDouble() * Math.PI * 2.0;
			double x = c.x + Math.cos(a) * r;
			double z = c.z + Math.sin(a) * r;
			double surface = SurfaceProbe.surfaceBelow(level, x, c.y + 1.5, c.y - 3.0, z);
			if (Double.isNaN(surface)) {
				continue;
			}

			float half = (float) (0.35 + 0.5 * (1.0 - r / radius)) * (0.7F + 0.6F * FX.nextFloat());
			blood.decals().addFloor(level, x, surface, z, half, BloodTextures.splat(FX), FX.nextInt(8), rgb, false, lifetime);
		}
	}
}
