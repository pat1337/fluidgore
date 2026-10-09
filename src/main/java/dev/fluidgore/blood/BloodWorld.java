package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/**
 * Client-side container for all blood in the current level: decals, fluid cells, flying droplets and footprint
 * tracking. Everything here is cosmetic and local; nothing is sent to or read from the server.
 */
public final class BloodWorld {
	/** Smallest amount of blood that starts a new puddle on its own. */
	private static final float MIN_POOL_VOLUME = 0.02F;

	private final DecalManager decals = new DecalManager(this);
	private final BloodFluidSim fluid = new BloodFluidSim(this);
	private final DropletSystem droplets = new DropletSystem(this);
	private final Footprints footprints = new Footprints(this);
	private final Spurts spurts = new Spurts(this);
	private final RandomSource random = RandomSource.create();
	private long serial;

	public DecalManager decals() {
		return decals;
	}

	public BloodFluidSim fluid() {
		return fluid;
	}

	public Spurts spurts() {
		return spurts;
	}

	public DropletSystem droplets() {
		return droplets;
	}

	long nextSerial() {
		return serial++;
	}

	/** Decal/puddle lifetime in ticks for the current settings. */
	public int decalLifetime() {
		GoreConfig config = GoreConfig.get();
		return Math.max(200, Math.round(config.persistenceSeconds * 20 * config.goreLevel.duration));
	}

	/** A droplet (or anything else carrying blood) reached a floor at (x, y, z). */
	public void landOnFloor(Level level, double x, double y, double z, float volume, int rgb, int lifetime) {
		// Small drops only leave a splat; they join the fluid simulation when they land in an existing puddle
		// or carry enough blood to start one. This keeps a spray from turning into dozens of tiny puddles.
		boolean pooled = volume >= MIN_POOL_VOLUME
				|| fluid.cellAt(net.minecraft.util.Mth.floor(x), SurfaceProbe.airBlockY(y), net.minecraft.util.Mth.floor(z)) != null;
		if (pooled) {
			fluid.addVolume(level, x, y, z, volume, rgb, lifetime);
		}

		if (!pooled || random.nextFloat() < 0.55F) {
			// Heavy-tailed size: mostly modest splats, now and then a big one.
			float half = (0.07F + (float) Math.min(0.45, Math.sqrt(volume) * 1.1)) * lognormal(0.35F);
			half = Math.min(0.75F, Math.max(0.05F, half));
			int tile = volume < 0.006F ? BloodTextures.smallSplat(random) : BloodTextures.splat(random);
			decals.addFloor(level, x, y, z, half, tile, random.nextInt(8), vary(rgb, 0.18F), true, lifetime);

			// Secondary spatter: little splashes thrown out around the impact.
			if (volume > 0.004F && random.nextFloat() < 0.3F) {
				int extra = 1 + random.nextInt(3);
				for (int i = 0; i < extra; i++) {
					double angle = random.nextDouble() * Math.PI * 2.0;
					double dist = half + 0.1 + random.nextDouble() * 0.5;
					double sx = x + Math.cos(angle) * dist;
					double sz = z + Math.sin(angle) * dist;
					double surface = SurfaceProbe.surfaceBelow(level, sx, y + 0.5, y - 0.6, sz);
					if (!Double.isNaN(surface)) {
						decals.addFloor(level, sx, surface, sz, 0.04F + random.nextFloat() * 0.08F, BloodTextures.smallSplat(random),
								random.nextInt(8), vary(rgb, 0.2F), true, lifetime);
					}
				}
			}
		}
	}

	/** Log-normal random factor with median 1: most values near 1, occasional large ones. */
	public float lognormal(float sigma) {
		return (float) Math.exp(random.nextGaussian() * sigma);
	}

	/** Random brightness variation of a colour, so blood is not one flat tint. */
	public int vary(int rgb, float amount) {
		float f = 1.0F + (random.nextFloat() * 2.0F - 1.0F) * amount;
		int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
		int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
		int b = Math.min(255, Math.round((rgb & 0xFF) * f));
		return (r << 16) | (g << 8) | b;
	}

	public void tick(Level level, int tickCount) {
		spurts.tick();
		droplets.tick(level);
		fluid.tick(level, tickCount);
		decals.tick(level, tickCount);
		if (GoreConfig.get().footprints) {
			footprints.tick(level);
		} else {
			footprints.clear();
		}
	}

	/** Keeps decals + puddle cells under {@link GoreConfig#maxBloodDecals}, removing the oldest first. */
	void enforceCap() {
		int max = GoreConfig.get().maxBloodDecals;
		while (decals.size() + fluid.size() > max) {
			if (decals.oldestSerial() <= fluid.oldestSerial()) {
				decals.removeOldest();
			} else {
				fluid.removeOldest();
			}
		}
	}

	public void onChunkUnload(int chunkX, int chunkZ) {
		decals.removeChunk(chunkX, chunkZ);
		fluid.removeChunk(chunkX, chunkZ);
	}

	public void clear() {
		decals.clear();
		fluid.clear();
		droplets.clear();
		spurts.clear();
		footprints.clear();
	}
}
