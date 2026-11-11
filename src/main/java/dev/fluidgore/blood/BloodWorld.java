package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.trigger.Fate;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client-side container for all blood in the current level: decals, fluid cells, flying droplets and footprint
 * tracking. Everything here is cosmetic and local; nothing is sent to or read from the server.
 */
public final class BloodWorld {
	/** Sine of the impact angle below which a drop stretches (about 50 degrees from the floor). */
	private static final double SLANT = 0.77;
	/** The most blood, pool units, that goes into one run down a wall: enough for three blocks of it. */
	private static final float MOST_RUN = 0.0075F;

	private final DecalManager decals = new DecalManager(this);
	private final BloodFluidSim fluid = new BloodFluidSim(this);
	private final DropletSystem droplets = new DropletSystem(this);
	private final Footprints footprints = new Footprints(this);
	private final Spurts spurts = new Spurts(this);
	private final WaterClouds clouds = new WaterClouds();
	private final Pours pours = new Pours(this);
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

	/** Blood in water. */
	public WaterClouds clouds() {
		return clouds;
	}

	/** Blood being tipped out of buckets. */
	Pours pours() {
		return pours;
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

	/** Stains drawn out so far (see {@link #drawnOut}), for the game test. */
	private long drawn;

	public long drawn() {
		return drawn;
	}

	/**
	 * A stain drawn out across the floor along ({@code dx}, {@code dz}), {@code length} long and {@code radius} wide at the
	 * start: round blots overlapping in a line, the first the biggest, each further one smaller, ending in a drop. A drop
	 * that came in at a slant, or blood dragged along the ground. (The wall-run tile, used for this before, read as a pin
	 * lying on the floor.)
	 */
	public void drawnOut(Level level, double x, double surfaceY, double z, double dx, double dz, float radius, float length, int rgb, int lifetime) {
		drawn++;
		int blots = Mth.clamp(Math.round(length / Math.max(0.02F, radius)), 1, 4);
		for (int i = 0; i < blots; i++) {
			float f = blots == 1 ? 0.0F : i / (float) (blots - 1);
			float r = radius * (1.0F - 0.55F * f) * (0.9F + random.nextFloat() * 0.2F);
			double along = (length - radius) * f;
			int tile = i == blots - 1 && blots > 1 ? BloodTextures.DROPLET : BloodTextures.SMALL_SPLAT;
			decals.addFloor(level, x + dx * along, surfaceY, z + dz * along, Math.max(0.04F, r), tile, random.nextInt(8), rgb, true,
					lifetime);
		}
	}

	/**
	 * The same on the side of a block: a stain drawn out across the face of {@code block} from (a, b) along
	 * ({@code da}, {@code db}), where a runs along the wall and b is height.
	 */
	private void drawnOutWall(Level level, BlockPos block, Direction face, double a, double b, double da, double db, float radius, float length,
			int rgb, int lifetime) {
		drawn++;
		int blots = Mth.clamp(Math.round(length / Math.max(0.02F, radius)), 1, 4);
		for (int i = 0; i < blots; i++) {
			float f = blots == 1 ? 0.0F : i / (float) (blots - 1);
			float r = radius * (1.0F - 0.55F * f) * (0.9F + random.nextFloat() * 0.2F);
			double along = (length - radius) * f;
			int tile = i == blots - 1 && blots > 1 ? BloodTextures.DROPLET : BloodTextures.SMALL_SPLAT;
			decals.addWall(level, block, face, a + da * along, b + db * along, Math.max(0.04F, r), tile, random.nextInt(8), rgb, lifetime);
		}
	}

	/**
	 * A drop hitting the side of a block at {@code at}, flying at (vx, vy, vz). It leaves a stain where it hit: round if
	 * it came straight at the wall, drawn out the way it was going if it came across it. From a drop with more blood than
	 * a stain holds some runs on down the wall, further the more there was. Returns the blood, in pool units, that stays
	 * on the wall: the rest falls down it at once.
	 */
	public float landOnWall(Level level, BlockPos block, Direction face, Vec3 at, double vx, double vy, double vz, float volume, int rgb, int lifetime) {
		boolean xNormal = face.getAxis() == Direction.Axis.X;
		double a = xNormal ? at.z : at.x;
		double va = xNormal ? vz : vx;
		double into = xNormal ? vx : vz;
		double across = Math.sqrt(va * va + vy * vy);
		double sine = Math.abs(into) / Math.max(1.0E-6, Math.sqrt(across * across + into * into));
		float width = Mth.clamp((0.04F + (float) Math.min(0.2, Math.sqrt(volume) * 0.7)) * lognormal(0.35F), 0.04F, 0.36F);
		int shade = vary(rgb, 0.18F);
		double plane = xNormal ? block.getX() + (face == Direction.EAST ? 1.0 : 0.0) : block.getZ() + (face == Direction.SOUTH ? 1.0 : 0.0);
		if (decals.covered(face, plane, a, at.y)) {
			// On a stain already: it adds nothing to see there, but it may still run.
		} else if (sine < SLANT && across > 1.0E-4 && GoreConfig.get().directionalSpatter) {
			float half = Math.min(0.5F, width / (float) Math.max(0.33, sine) * 0.5F);
			drawnOutWall(level, block, face, a, at.y, va / across, vy / across, width * 0.5F, half * 2.0F, shade, lifetime);
		} else {
			decals.addWall(level, block, face, a, at.y, width, volume < 0.006F ? BloodTextures.SMALL_SPLAT : BloodTextures.splat(random),
					random.nextInt(8), shade, lifetime);
		}

		// What the stain itself holds: a film over it. A drop no bigger than that stays where it hit.
		float held = Math.min(volume, width * width * 0.006F);
		float over = volume - held;
		// Of the rest, most falls down the wall at once. Some of it creeps: more often, and further, the more there was.
		if (over > 0.0005F && random.nextFloat() < Mth.clamp(over * 60.0F, 0.12F, 0.7F)) {
			float runs = Math.min(over * 0.35F, MOST_RUN) * (0.4F + random.nextFloat() * 0.6F);
			if (decals.run(level, block, face, a, at.y - width * 0.25, runs, rgb, lifetime) != null) {
				held += runs;
			}
		}

		return held;
	}

	/**
	 * A drop landing at velocity (vx, vy, vz). Steep, it splats round; at a slant it stretches into a teardrop as long
	 * as its width over the sine of the angle it came in at, its tail pointing the way it flew. That is how a spatter
	 * analyst reads where blood came from.
	 */
	public void landOnFloor(Level level, double x, double y, double z, double vx, double vy, double vz, float volume, int rgb, int lifetime) {
		net.minecraft.world.phys.AABB top = SurfaceProbe.topFace(level, x - 0.01, x + 0.01, y, z - 0.01, z + 0.01);
		if (top != null && SurfaceProbe.narrow(top)) {
			onRail(level, x, y, z, top, volume, rgb, lifetime);
			return;
		}

		// All of it joins the blood on the ground (see BloodFluidSim). Where that is enough to be seen it is drawn as part
		// of the pool; a drop too small for that is drawn as a mark of its own, and its blood is the stain under the mark.
		fluid.addVolume(level, x, y, z, volume, rgb, lifetime);
		double across = Math.sqrt(vx * vx + vz * vz);
		double sine = Math.abs(vy) / Math.max(1.0E-6, Math.sqrt(across * across + vy * vy));
		if (fluid.shows(x, y, z) || decals.covered(Direction.UP, y, x, z)) {
			// Nothing of its own to draw.
		} else if (sine < SLANT && across > 1.0E-4 && GoreConfig.get().directionalSpatter) {
			float width = (0.05F + (float) Math.min(0.3, Math.sqrt(volume) * 0.9)) * lognormal(0.3F);
			float half = Math.min(0.6F, width / (float) Math.max(0.33, sine) * 0.5F);
			double dx = vx / across;
			double dz = vz / across;
			// The drop where it hit, drawn out the way it flew.
			drawnOut(level, x, y, z, dx, dz, width * 0.5F, half * 2.0F, vary(rgb, 0.18F), lifetime);
		} else {
			// A drop leaves a splat. Heavy-tailed size: mostly modest, now and then a big one.
			float half = (0.05F + (float) Math.min(0.3, Math.sqrt(volume) * 0.8)) * lognormal(0.35F);
			half = Math.min(0.5F, Math.max(0.04F, half));
			int tile = volume < 0.006F ? BloodTextures.SMALL_SPLAT : BloodTextures.splat(random);
			decals.addFloor(level, x, y, z, half, tile, random.nextInt(8), vary(rgb, 0.18F), true, lifetime);
		}

		// Secondary spatter: little splashes thrown out round where a good deal of blood came down.
		if (volume > 0.004F && random.nextFloat() < 0.3F) {
			float reach = 0.1F + (float) Math.min(0.5, Math.sqrt(volume) * 0.9);
			int extra = 1 + random.nextInt(3);
			for (int i = 0; i < extra; i++) {
				double angle = random.nextDouble() * Math.PI * 2.0;
				double dist = reach + 0.1 + random.nextDouble() * 0.5;
				double sx = x + Math.cos(angle) * dist;
				double sz = z + Math.sin(angle) * dist;
				double surface = SurfaceProbe.surfaceBelow(level, sx, y + 0.5, y - 0.6, sz);
				if (!Double.isNaN(surface) && !fluid.shows(sx, surface, sz) && !decals.covered(Direction.UP, surface, sx, sz)) {
					decals.addFloor(level, sx, surface, sz, 0.04F + random.nextFloat() * 0.08F, BloodTextures.SMALL_SPLAT, random.nextInt(8),
							vary(rgb, 0.2F), true, lifetime);
				}
			}
		}
	}

	/** Share of the blood landing on a rail that stays on it; the rest runs off. */
	private static final float RAIL_HOLDS = 0.25F;

	/**
	 * Blood landing on something too narrow to hold a pool (a fence rail, a wall's arm, a pane): a little stays on top as
	 * a mark along it, and the rest runs off the nearer edge and drips to whatever is below.
	 */
	private void onRail(Level level, double x, double y, double z, net.minecraft.world.phys.AABB top, float volume, int rgb, int lifetime) {
		float half = Math.min(0.3F, Math.max(0.05F, (0.06F + (float) Math.sqrt(volume * RAIL_HOLDS)) * lognormal(0.3F)));
		decals.addFloor(level, x, y, z, half, BloodTextures.SMALL_SPLAT, random.nextInt(8), vary(rgb, 0.18F), true, lifetime);
		float rest = volume * (1.0F - RAIL_HOLDS);
		if (rest < 0.002F) {
			return;
		}

		// Off across its narrow way, on the side the drop landed nearer.
		boolean acrossX = top.getXsize() < top.getZsize();
		double edgeX = x;
		double edgeZ = z;
		double vx = 0.0;
		double vz = 0.0;
		if (acrossX) {
			boolean high = x > (top.minX + top.maxX) * 0.5;
			edgeX = (high ? top.maxX : top.minX) + (high ? 0.02 : -0.02);
			vx = high ? 0.01 : -0.01;
		} else {
			boolean high = z > (top.minZ + top.maxZ) * 0.5;
			edgeZ = (high ? top.maxZ : top.minZ) + (high ? 0.02 : -0.02);
			vz = high ? 0.01 : -0.01;
		}

		droplets.runoff(edgeX, y - 0.02, edgeZ, vx, vz, rest, rgb);
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
		pours.tick(level);
		droplets.tick(level);
		fluid.tick(level, tickCount);
		decals.tick(level, tickCount);
		clouds.tick(level);
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
			BloodCell cell = fluid.at(at.x, at.y, at.z);
			// Most of the liquid within reach, and a good part of what has dried on; a porous floor keeps what it drank.
			float taken = fluid.lift(at.x, at.y, at.z, radius, 0.8F * lift);
			float scoured = fluid.scrubStain(at.x, at.y, at.z, radius, (cell != null && cell.surface.porous() ? 0.25F : 0.7F) * lift);
			float units = (taken + scoured) / BloodSupply.LITRES_PER_UNIT;
			if (units > 0.0F) {
				int rgb = cell != null ? cell.rgb : 0x8A0303;
				touched++;
				lifted += units;
				weigh(colour, rgb, units);
				if (stroke != null && lift < 0.9F && taken > 0.0F && smear(level, at.x + stroke.x * 0.4, at.y, at.z + stroke.z * 0.4, stroke,
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

		double along = Math.sqrt(stroke.x * stroke.x + stroke.z * stroke.z);
		double ux = along > 1.0E-6 ? stroke.x / along : 1.0;
		double uz = along > 1.0E-6 ? stroke.z / along : 0.0;
		float size = Math.clamp(half, 0.1F, 0.6F);
		// Centred on (x, z): the smear runs back along the stroke and on past it.
		drawnOut(level, x - ux * size, surface, z - uz * size, ux, uz, size * 0.6F, size * 2.0F, vary(rgb, 0.12F), decalLifetime());
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
			if (!cell.empty() && Math.abs(cell.surfaceY - at.y) < 1.0 && dx * dx + dz * dz <= sq(radius + 0.5F)) {
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

	/**
	 * Keeps marks and the blocks of floor that hold blood under {@link GoreConfig#maxBloodDecals} together. The oldest mark
	 * goes first, and blood on the ground only when there are no marks left to go: a mark is one drop, and what lies on a
	 * block of floor may be a pool that spatter falling somewhere else should not take away.
	 */
	void enforceCap() {
		int max = GoreConfig.get().maxBloodDecals;
		while (decals.size() + fluid.size() > max) {
			if (decals.size() > 0) {
				decals.removeOldest();
			} else {
				fluid.removeOldest();
			}
		}
	}

	public void onChunkUnload(int chunkX, int chunkZ) {
		decals.removeChunk(chunkX, chunkZ);
		fluid.removeChunk(chunkX, chunkZ);
		clouds.removeChunk(chunkX, chunkZ);
	}

	/** Takes away all blood inside {@code box}: marks, pools, drops in flight, clouds in water and the jets feeding them. */
	public void removeIn(net.minecraft.world.phys.AABB box) {
		decals.removeIn(box);
		fluid.removeIn(box);
		droplets.removeIn(box);
		clouds.removeIn(box);
		spurts.removeIn(box);
		pours.removeIn(box);
	}

	public void clear() {
		decals.clear();
		fluid.clear();
		droplets.clear();
		spurts.clear();
		footprints.clear();
		clouds.clear();
		pours.clear();
	}
}
