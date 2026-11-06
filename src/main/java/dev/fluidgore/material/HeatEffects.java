package dev.fluidgore.material;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/**
 * What a {@link Thermal} step shows, for a piece of any size: frost coming off a frozen one, steam off a wet piece in
 * the heat, smoke as it chars, a flame while it burns, a burst of flame as it catches, and a hiss and a puff of steam
 * when water puts it out. This screen's own dice: none of it changes what the piece is.
 */
public final class HeatEffects {
	private static final RandomSource FX = RandomSource.create();

	private HeatEffects() {
	}

	/**
	 * Shows {@code events} on a piece of {@code material} centred at {@code x, y, z}, its top at {@code top}, {@code size}
	 * blocks across. {@code hisses} is how many sounds it may still make this tick; returns how many are left.
	 */
	public static int show(Level level, int events, Material material, double x, double y, double z, double top, double size, int hisses) {
		// A bigger piece puts out more of everything.
		int n = Math.max(1, (int) Math.round(size * 6.0));
		if ((events & Thermal.STEAMING) != 0) {
			for (int k = 0; k < n; k++) {
				if (FX.nextInt(3) == 0) {
					level.addParticle(ParticleTypes.WHITE_SMOKE, x + FX.nextGaussian() * size * 0.4, top, z + FX.nextGaussian() * size * 0.4, 0.0, 0.02, 0.0);
				}
			}

			if (hisses > 0 && FX.nextInt(30) == 0) {
				hisses--;
				level.playLocalSound(x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.08F, 1.6F + FX.nextFloat() * 0.4F, false);
			}
		}

		if ((events & Thermal.SMOKING) != 0) {
			for (int k = 0; k < n; k++) {
				if (FX.nextInt(2) == 0) {
					level.addParticle(ParticleTypes.SMOKE, x + FX.nextGaussian() * size * 0.5, top, z + FX.nextGaussian() * size * 0.5, 0.0, 0.03, 0.0);
				}
			}
		}

		if ((events & Thermal.FLAMING) != 0) {
			for (int k = 0; k < n; k++) {
				if (FX.nextInt(2) == 0) {
					level.addParticle(material.fuel() >= 0.5F ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME, x + FX.nextGaussian() * size * 0.4, top,
							z + FX.nextGaussian() * size * 0.4, 0.0, 0.01, 0.0);
				}
			}

			if (hisses > 0 && FX.nextInt(40) == 0) {
				hisses--;
				level.playLocalSound(x, y, z, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.NEUTRAL, 0.25F, 0.9F + FX.nextFloat() * 0.3F, false);
			}
		}

		if ((events & Thermal.FROSTED) != 0) {
			for (int k = 0; k < n; k++) {
				if (FX.nextInt(3) == 0) {
					// Frost subliming off it into the warmer air.
					level.addParticle(ParticleTypes.SNOWFLAKE, x + FX.nextGaussian() * size * 0.4, top, z + FX.nextGaussian() * size * 0.4, 0.0, 0.01, 0.0);
				}
			}
		}

		if ((events & Thermal.IGNITED) != 0) {
			for (int k = 0; k < 4 * n; k++) {
				level.addParticle(ParticleTypes.FLAME, x, top, z, FX.nextGaussian() * 0.02, 0.04, FX.nextGaussian() * 0.02);
			}
		}

		if ((events & (Thermal.QUENCHED | Thermal.EXTINGUISHED)) != 0) {
			for (int k = 0; k < 5 * n; k++) {
				level.addParticle(ParticleTypes.CLOUD, x + FX.nextGaussian() * size * 0.3, top, z + FX.nextGaussian() * size * 0.3, FX.nextGaussian() * 0.02, 0.05,
						FX.nextGaussian() * 0.02);
			}

			if (hisses > 0) {
				hisses--;
				level.playLocalSound(x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.3F, 1.2F + FX.nextFloat() * 0.4F, false);
			}
		}

		return hisses;
	}
}
