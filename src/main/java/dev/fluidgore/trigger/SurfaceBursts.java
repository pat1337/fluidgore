package dev.fluidgore.trigger;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.physics.Blast;
import dev.fluidgore.physics.Underwater;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * A blast in the air over water. Water is some eight hundred times as dense as air, so almost none of the wave goes
 * into it: it is thrown back off the surface, and what the water shows is what the wave does to that surface.
 * <ul>
 *   <li><b>The crown.</b> Where the wave strikes, the top of the water is torn off as spray: a ring of white thrown up
 *   and outwards, highest nearest the charge and dying away with the overpressure.</li>
 *   <li><b>The hollow and the jet.</b> Right under a charge close to the surface the water is driven down into a
 *   hollow. It closes again within half a second, and the water rushing in from all sides meets in the middle and
 *   goes straight up in a column.</li>
 *   <li><b>The rain.</b> What went up comes down over the place for a couple of seconds.</li>
 * </ul>
 * Nothing under the water is hurt by it, and nothing here rolls dice that matter: it is spray, the same for a charge
 * of TNT and a bursting gas vessel. A charge that goes off in the water is {@link GasBubbles}' business instead.
 */
public final class SurfaceBursts {
	/** Overpressure, kPa, below which the surface only shivers. */
	private static final float LEAST_KPA = 8.0F;
	/** Overpressure at the surface, kPa, from which the water under the charge is driven into a hollow that throws a jet. */
	private static final float JET_KPA = 120.0F;
	/** Ticks until the hollow has closed and the jet goes up. */
	private static final int JET_AT = 8;
	/** Ticks the spray keeps falling back. */
	private static final int RAIN_TICKS = 50;
	/** Furthest from under the charge the surface is searched, blocks. */
	private static final double REACH = 9.0;
	/** This screen's dice for where the particles go: they change nothing anything does. */
	private static final RandomSource FX = RandomSource.create();
	private static final List<Burst> BURSTS = new ArrayList<>();

	private static final class Burst {
		final ClientLevel level;
		/** The water's surface nearest under the charge. */
		final double x;
		final double y;
		final double z;
		/** Overpressure there, kPa, and how far out the spray was thrown, blocks. */
		final float kPa;
		final double wide;
		int age;

		Burst(ClientLevel level, double x, double y, double z, float kPa, double wide) {
			this.level = level;
			this.x = x;
			this.y = y;
			this.z = z;
			this.kPa = kPa;
			this.wide = wide;
		}
	}

	private SurfaceBursts() {
	}

	/** A charge of {@code tntKg} at {@code psi} went off in the air at {@code centre}: whatever water lies under and round it answers. */
	public static void start(ClientLevel level, Vec3 centre, double tntKg, double psi) {
		float particles = particles();
		double nearest = Double.MAX_VALUE;
		double nx = 0.0;
		double ny = 0.0;
		double nz = 0.0;
		float most = 0.0F;
		double wide = 0.0;
		// Rings round the point under the charge, eight spokes each.
		for (int ring = 0; ring <= 6; ring++) {
			double r = ring * REACH / 6.0;
			int spokes = ring == 0 ? 1 : 8;
			for (int spoke = 0; spoke < spokes; spoke++) {
				double a = spoke * Math.PI / 4.0 + ring * 0.4;
				double x = centre.x + Math.cos(a) * r;
				double z = centre.z + Math.sin(a) * r;
				double surface = surface(level, x, centre.y, z);
				if (Double.isNaN(surface)) {
					continue;
				}

				double distance = Math.sqrt(r * r + Mth.square(centre.y - surface));
				float kPa = Blast.overpressure(Math.max(0.5, distance), tntKg, psi);
				if (kPa < LEAST_KPA) {
					continue;
				}

				crown(level, x, surface, z, r < 0.1 ? 0.0 : Math.cos(a), r < 0.1 ? 0.0 : Math.sin(a), kPa, particles);
				wide = Math.max(wide, r);
				if (distance < nearest) {
					nearest = distance;
					nx = x;
					ny = surface;
					nz = z;
					most = kPa;
				}
			}
		}

		if (most <= 0.0F) {
			return;
		}

		level.playLocalSound(nx, ny, nz, SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 2.0F, 0.7F, false);
		if (most >= JET_KPA) {
			level.playLocalSound(nx, ny, nz, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.BLOCKS, 2.0F, 0.6F, false);
		}

		if (BURSTS.size() >= 8) {
			BURSTS.removeFirst();
		}

		BURSTS.add(new Burst(level, nx, ny, nz, most, Math.max(1.5, wide)));
	}

	/** The height of the water's top at (x, z) within reach of a charge at height {@code y}: from a block above it to five below. NaN if none. */
	private static double surface(ClientLevel level, double x, double y, double z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dy = 1; dy >= -5; dy--) {
			pos.set(Mth.floor(x), Mth.floor(y) + dy, Mth.floor(z));
			if (!level.isLoaded(pos)) {
				return Double.NaN;
			}

			if (level.getFluidState(pos).is(FluidTags.WATER)) {
				if (level.getFluidState(pos.above()).is(FluidTags.WATER)) {
					// The charge is over deeper water than this: the top of it is higher up.
					return Underwater.surfaceAbove(level, pos);
				}

				return pos.getY() + level.getFluidState(pos).getHeight(level, pos);
			}

			if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
				// Ground first: no water under here.
				return Double.NaN;
			}
		}

		return Double.NaN;
	}

	/** Spray torn off the surface at one place: up, and out along (ox, oz), as hard as the overpressure there. */
	private static void crown(ClientLevel level, double x, double y, double z, double ox, double oz, float kPa, float particles) {
		int n = Math.round(Mth.clamp(kPa / 8.0F, 2.0F, 14.0F) * particles);
		double up = Mth.clamp(kPa / 500.0, 0.04, 0.45);
		double out = Mth.clamp(kPa / 900.0, 0.02, 0.2);
		for (int i = 0; i < n; i++) {
			double px = x + (FX.nextDouble() - 0.5) * 1.4;
			double pz = z + (FX.nextDouble() - 0.5) * 1.4;
			level.addParticle(ParticleTypes.CLOUD, px, y + 0.1, pz, ox * out + FX.nextGaussian() * 0.02, up * (0.5 + 0.5 * FX.nextDouble()),
					oz * out + FX.nextGaussian() * 0.02);
			level.addParticle(ParticleTypes.SPLASH, px, y + 0.05, pz, ox * 0.2, 0.0, oz * 0.2);
		}
	}

	public static void tick(ClientLevel level) {
		if (BURSTS.isEmpty()) {
			return;
		}

		float particles = particles();
		BURSTS.removeIf(b -> {
			if (b.level != level) {
				return true;
			}

			b.age++;
			if (b.kPa >= JET_KPA && b.age >= JET_AT && b.age < JET_AT + 6) {
				// The hollow has closed: the water meeting in the middle goes straight up.
				double height = Mth.clamp(b.kPa / 60.0, 2.0, 14.0);
				int n = Math.round(12 * particles);
				for (int i = 0; i < n; i++) {
					double a = FX.nextDouble() * Math.PI * 2.0;
					double r = 0.5 * Math.sqrt(FX.nextDouble());
					// The cloud particle drags at 4% a tick: given this, it rises about as high as the jet.
					b.level.addParticle(ParticleTypes.CLOUD, b.x + Math.cos(a) * r, b.y + 0.2, b.z + Math.sin(a) * r, Math.cos(a) * 0.01,
							height * 0.04 * (0.7 + 0.3 * FX.nextDouble()), Math.sin(a) * 0.01);
				}
			}

			if (b.age > 4 && b.age < RAIN_TICKS) {
				// What went up comes down.
				int n = Math.round(Mth.clamp(b.kPa / 20.0F, 2.0F, 10.0F) * particles);
				for (int i = 0; i < n; i++) {
					double a = FX.nextDouble() * Math.PI * 2.0;
					double r = b.wide * Math.sqrt(FX.nextDouble());
					double h = 0.5 + FX.nextDouble() * Mth.clamp(b.kPa / 60.0, 1.0, 10.0);
					b.level.addParticle(ParticleTypes.FALLING_WATER, b.x + Math.cos(a) * r, b.y + h, b.z + Math.sin(a) * r, 0.0, 0.0, 0.0);
					if (i % 3 == 0) {
						b.level.addParticle(ParticleTypes.SPLASH, b.x + Math.cos(a) * r, b.y + 0.05, b.z + Math.sin(a) * r, 0.0, 0.0, 0.0);
					}
				}
			}

			return b.age >= RAIN_TICKS;
		});
	}

	private static float particles() {
		return Math.max(0.3F, GoreConfig.get().goreLevel.particles);
	}

	/** Bursts over water now playing out, for the game test. */
	public static int active() {
		return BURSTS.size();
	}

	public static void clear() {
		BURSTS.clear();
	}
}
