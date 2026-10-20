package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.trigger.Fate;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client-side container for all blood in the current level: decals, fluid cells, flying droplets and footprint
 * tracking. Everything here is cosmetic and local; nothing is sent to or read from the server.
 */
public final class BloodWorld {
	/** Smallest amount of blood that starts a new puddle on its own. */
	private static final float MIN_POOL_VOLUME = 0.02F;
	/** Sine of the impact angle below which a drop stretches (about 50 degrees from the floor). */
	private static final double SLANT = 0.77;

	private final DecalManager decals = new DecalManager(this);
	private final BloodFluidSim fluid = new BloodFluidSim(this);
	private final DropletSystem droplets = new DropletSystem(this);
	private final Footprints footprints = new Footprints(this);
	private final Spurts spurts = new Spurts(this);
	private final RandomSource random = Fate.stream("world");
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
		return Math.max(200, Math.round(config.persistenceSeconds * 20 * config.goreLevel.bloodDuration));
	}

	/** A droplet (or anything else carrying blood) reached a floor at (x, y, z). */
	public void landOnFloor(Level level, double x, double y, double z, float volume, int rgb, int lifetime) {
		landOnFloor(level, x, y, z, 0.0, -1.0, 0.0, volume, rgb, lifetime);
	}

	/**
	 * A drop landing at velocity (vx, vy, vz). Steep, it splats round; at a slant it stretches into a teardrop as long
	 * as its width over the sine of the angle it came in at, its tail pointing the way it flew. That is how a spatter
	 * analyst reads where blood came from.
	 */
	public void landOnFloor(Level level, double x, double y, double z, double vx, double vy, double vz, float volume, int rgb, int lifetime) {
		double across = Math.sqrt(vx * vx + vz * vz);
		double sine = Math.abs(vy) / Math.max(1.0E-6, Math.sqrt(across * across + vy * vy));
		if (sine < SLANT && across > 1.0E-4 && volume < MIN_POOL_VOLUME && GoreConfig.get().directionalSpatter) {
			float width = (0.05F + (float) Math.min(0.3, Math.sqrt(volume) * 0.9)) * lognormal(0.3F);
			float half = Math.min(0.6F, width / (float) Math.max(0.33, sine) * 0.5F);
			double dx = vx / across;
			double dz = vz / across;
			// The streak tile's blob sits near one end with the tails running on: the blob goes where the drop hit.
			double ahead = half * 0.76;
			int orient = Math.abs(dx) > Math.abs(dz) ? 4 | (dx < 0.0 ? 2 : 0) : dz < 0.0 ? 2 : 0;
			decals.addFloor(level, x + dx * ahead, y, z + dz * ahead, half, BloodTextures.STREAK, orient | random.nextInt(2), vary(rgb, 0.18F), true,
					lifetime);
			return;
		}

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

	/**
	 * One stroke of a cleaning tool: what it touched, how much blood it took up (litres) and its colour, and how many
	 * smears it left.
	 */
	public record Scrub(int touched, float lifted, int rgb, int smeared) {
	}

	/**
	 * A cleaning tool wiped within {@code radius} of {@code at} on the surface that was hit (3.1). It takes up the share
	 * {@code lift} of what it touches (a clean sponge nearly all, a soaked one next to nothing); the rest it drags along
	 * {@code stroke} (null for a dab, which leaves it where it is) and lays down again as smears. Decals it takes up fade
	 * out at once; puddles lose that share of their volume.
	 */
	public Scrub scrub(Level level, Vec3 at, Direction face, float radius, float lift, @Nullable Vec3 stroke) {
		int touched = 0;
		int smeared = 0;
		float lifted = 0.0F;
		float[] colour = new float[4];
		List<Decal> dragged = new ArrayList<>();
		for (Decal d : decals.decals()) {
			if (d.face != face || d.age >= d.lifetime - 4 || decalDistanceSqr(d, at) > sq(radius + d.half * 0.5F)) {
				continue;
			}

			touched++;
			float amount = d.half * d.half * 0.25F * d.alpha();
			if (random.nextFloat() < lift) {
				// Jump to the end of its life: the normal fade-out does the wiping.
				d.age = d.lifetime - 4;
				lifted += amount;
				weigh(colour, d.rgb, amount);
			} else if (stroke != null && face == Direction.UP) {
				d.age = d.lifetime - 4;
				dragged.add(d);
			}
		}

		// Laid down after the loop: the new smears join the very list it walked.
		for (Decal d : dragged) {
			if (smear(level, d.centerA + stroke.x * (0.25 + d.half * 0.6), d.plane, d.centerB + stroke.z * (0.25 + d.half * 0.6), stroke,
					d.half * 0.9F, d.rgb)) {
				smeared++;
			}
		}

		if (face == Direction.UP) {
			for (BloodCell cell : fluid.cells()) {
				double dx = cell.bx + 0.5 - at.x;
				double dz = cell.bz + 0.5 - at.z;
				if (Math.abs(cell.surfaceY - at.y) >= 0.6 || dx * dx + dz * dz > sq(radius + 0.5F) || cell.volume <= 0.0F) {
					continue;
				}

				touched++;
				float taken = Math.min(cell.volume, (cell.volume * 0.75F + 0.05F) * lift);
				lifted += taken;
				weigh(colour, cell.rgb, taken);
				int rgb = cell.rgb;
				fluid.drain(cell, taken);
				if (stroke != null && lift < 0.9F && smear(level, at.x + stroke.x * 0.4, cell.surfaceY, at.z + stroke.z * 0.4, stroke,
						0.2F + 0.25F * (1.0F - lift), rgb)) {
					// What it could not take up, it pushes on through the pool.
					smeared++;
				}
			}
		}

		int rgb = colour[3] > 0.0F ? Math.round(colour[0] / colour[3]) << 16 | Math.round(colour[1] / colour[3]) << 8 | Math.round(colour[2] / colour[3]) : 0;
		return new Scrub(touched, lifted, rgb, smeared);
	}

	/** Lays a smear on the floor near {@code y} at (x, z), drawn out along {@code stroke}. False if there is no floor there. */
	public boolean smear(Level level, double x, double y, double z, Vec3 stroke, float half, int rgb) {
		double surface = SurfaceProbe.surfaceBelow(level, x, y + 0.5, y - 0.6, z);
		if (Double.isNaN(surface)) {
			return false;
		}

		int orient = Math.abs(stroke.x) > Math.abs(stroke.z) ? 4 | (stroke.x < 0.0 ? 2 : 0) : stroke.z < 0.0 ? 2 : 0;
		decals.addFloor(level, x, surface, z, Math.clamp(half, 0.1F, 0.6F), BloodTextures.STREAK, orient | random.nextInt(2), vary(rgb, 0.12F), true,
				decalLifetime());
		return true;
	}

	private static void weigh(float[] colour, int rgb, float amount) {
		colour[0] += ((rgb >> 16) & 0xFF) * amount;
		colour[1] += ((rgb >> 8) & 0xFF) * amount;
		colour[2] += (rgb & 0xFF) * amount;
		colour[3] += amount;
	}

	/** True if any decal or puddle lies within {@code radius} of {@code at}. */
	public boolean hasBloodNear(Vec3 at, float radius) {
		for (Decal d : decals.decals()) {
			if (d.age < d.lifetime - 4 && decalDistanceSqr(d, at) <= sq(radius)) {
				return true;
			}
		}

		for (BloodCell cell : fluid.cells()) {
			double dx = cell.bx + 0.5 - at.x;
			double dz = cell.bz + 0.5 - at.z;
			if (cell.volume > 0.01F && Math.abs(cell.surfaceY - at.y) < 1.0 && dx * dx + dz * dz <= sq(radius)) {
				return true;
			}
		}

		return false;
	}

	private static double decalDistanceSqr(Decal d, Vec3 at) {
		double x;
		double y;
		double z;
		switch (d.face.getAxis()) {
			case Y -> {
				x = d.centerA;
				y = d.plane;
				z = d.centerB;
			}
			case X -> {
				x = d.plane;
				y = d.centerB;
				z = d.centerA;
			}
			default -> {
				x = d.centerA;
				y = d.centerB;
				z = d.plane;
			}
		}

		return at.distanceToSqr(x, y, z);
	}

	private static double sq(float v) {
		return v * v;
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
