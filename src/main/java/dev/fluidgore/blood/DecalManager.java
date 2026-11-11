package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** Owns all static marks (floor splats, wall splatter, runs down walls, footprints, scorch and ash). */
public final class DecalManager {
	/** Pieces are aligned to a half-block grid, which matches the top-surface steps of stairs and slabs. */
	private static final double GRID = 0.5;
	/** The most pieces a floor mark is split into: enough for one four blocks across. */
	private static final int MOST_PIECES = 81;
	/** Floor decals may follow a step of at most this height (stairs, slabs, carpets). */
	private static final double MAX_STEP = 0.55;
	/** Half the width of a run down a wall, blocks. */
	public static final float RUN_HALF = 0.016F;
	/**
	 * Blood a run leaves on a block of wall it has run down, pool units: a film three centimetres wide and a third of a
	 * millimetre thick.
	 */
	private static final float RUN_LAYS = 0.0025F;
	/** How fast a run with plenty behind it creeps, blocks a tick, and how fast one that is nearly spent. */
	private static final float RUN_FAST = 0.02F;
	private static final float RUN_SLOW = 0.004F;
	/** Blood behind a run, pool units, from which it goes at its fastest. */
	private static final float RUN_FAT = 0.004F;

	/** How many of the newest marks {@link #covered} looks through. */
	private static final int RECENT = 256;
	/** Marks whose middles are nearer than this, blocks, are on the same spot: half a pixel of a block's texture. */
	private static final double SPOT = 1.0 / 32.0;

	/** A run that has reached the foot of its block with blood left, to be carried on below once the tick is done. */
	private record Onward(Decal from, float carried) {
	}

	private final BloodWorld world;
	/** Kept in creation order, so index 0 is always the oldest decal. */
	private final List<Decal> decals = new ArrayList<>();
	private final List<Onward> onward = new ArrayList<>();
	/** Runs started so far (see {@link #run}), for the game test. */
	private long runs;

	DecalManager(BloodWorld world) {
		this.world = world;
	}

	public List<Decal> decals() {
		return decals;
	}

	public int size() {
		return decals.size();
	}

	public long runs() {
		return runs;
	}

	/**
	 * Places a square decal on the floor around (x, z), resting on the surface at {@code surfaceY}. Returns null
	 * if none of its pieces lie on a valid surface.
	 */
	public @Nullable Decal addFloor(Level level, double x, double surfaceY, double z, float half, int tile, int orient,
			int rgb, boolean dries, int lifetime) {
		double x0 = x - half;
		double x1 = x + half;
		double z0 = z - half;
		double z1 = z + half;
		// A piece for every square of the half-block grid the mark touches, up to the most a mark may have.
		int most = Math.min(MOST_PIECES, (int) ((Math.floor(x1 / GRID) - Math.floor(x0 / GRID) + 1.0) * (Math.floor(z1 / GRID) - Math.floor(z0 / GRID) + 1.0)));
		float[] buffer = new float[Decal.PIECE_STRIDE * most];
		int count = 0;
		for (double gx = Math.floor(x0 / GRID) * GRID; gx < x1 && count < most; gx += GRID) {
			for (double gz = Math.floor(z0 / GRID) * GRID; gz < z1 && count < most; gz += GRID) {
				double a0 = Math.max(x0, gx);
				double a1 = Math.min(x1, gx + GRID);
				double b0 = Math.max(z0, gz);
				double b1 = Math.min(z1, gz + GRID);
				if (a1 - a0 < 0.01 || b1 - b0 < 0.01) {
					continue;
				}

				double h = SurfaceProbe.surfaceBelow(level, (a0 + a1) * 0.5, surfaceY + MAX_STEP, surfaceY - MAX_STEP, (b0 + b1) * 0.5);
				if (Double.isNaN(h)) {
					continue;
				}

				// Only as far as the face it lies on: a fence rail takes a strip of a splat, not a sheet beside it.
				AABB top = SurfaceProbe.topFace(level, a0, a1, h, b0, b1);
				if (top != null) {
					a0 = Math.max(a0, top.minX);
					a1 = Math.min(a1, top.maxX);
					b0 = Math.max(b0, top.minZ);
					b1 = Math.min(b1, top.maxZ);
					if (a1 - a0 < 0.01 || b1 - b0 < 0.01) {
						continue;
					}
				}

				int o = count * Decal.PIECE_STRIDE;
				buffer[o] = (float) (a0 - x);
				buffer[o + 1] = (float) (b0 - z);
				buffer[o + 2] = (float) (a1 - x);
				buffer[o + 3] = (float) (b1 - z);
				buffer[o + 4] = (float) (h - surfaceY);
				count++;
			}
		}

		if (count == 0) {
			return null;
		}

		float[] pieces = new float[count * Decal.PIECE_STRIDE];
		System.arraycopy(buffer, 0, pieces, 0, pieces.length);
		BlockPos anchor = BlockPos.containing(x, surfaceY - 0.01, z);
		Decal decal = new Decal(world.nextSerial(), Direction.UP, x, z, half, tile, orient, rgb, dries, pieces,
				anchor, surfaceY, lifetime, light(level, anchor.above()));
		decal.porous = Surfaces.of(level.getBlockState(anchor)).porous();
		return add(decal);
	}

	/**
	 * True if one of the newest marks of blood on the plane of {@code face} at {@code plane} already lies on the spot (a,
	 * b), given as a mark's own centre is. A second drop on the same spot adds its blood to what is there; it does not
	 * leave a second mark over the first.
	 */
	boolean covered(Direction face, double plane, double a, double b) {
		int oldest = Math.max(0, decals.size() - RECENT);
		for (int i = decals.size() - 1; i >= oldest; i--) {
			Decal d = decals.get(i);
			if (d.face != face || !d.dries || d.tile == BloodTextures.RUN || Math.abs(d.plane - plane) > 0.05) {
				continue;
			}

			double near = Math.max(SPOT, d.half * 0.4);
			if (Math.abs(d.centerA - a) < near && Math.abs(d.centerB - b) < near) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Places a decal on the side face of {@code block}. (a, b) are the tangent coordinates of the centre: for
	 * east/west faces a = z, for north/south faces a = x, and b = y. It lies on that face and on as much of the faces
	 * beside, above and below it as are part of the same wall, so a mark near a block's edge runs on across the joint
	 * instead of being cut off at it.
	 */
	public @Nullable Decal addWall(Level level, BlockPos block, Direction face, double a, double b, float half, int tile,
			int orient, int rgb, int lifetime) {
		if (face.getAxis() == Direction.Axis.Y) {
			return null;
		}

		boolean xNormal = face.getAxis() == Direction.Axis.X;
		double plane = xNormal ? block.getX() + (face == Direction.EAST ? 1.0 : 0.0) : block.getZ() + (face == Direction.SOUTH ? 1.0 : 0.0);
		float[] buffer = new float[Decal.PIECE_STRIDE * 9];
		int count = 0;
		for (int ga = Mth.floor(a - half); ga <= Mth.floor(a + half) && count < 9; ga++) {
			for (int gb = Mth.floor(b - half); gb <= Mth.floor(b + half) && count < 9; gb++) {
				double a0 = Math.max(a - half, ga);
				double a1 = Math.min(a + half, ga + 1.0);
				double b0 = Math.max(b - half, gb);
				double b1 = Math.min(b + half, gb + 1.0);
				// The face must be a real one there: the wall just behind the middle of the piece, open air just in front.
				if (a1 - a0 < 0.02 || b1 - b0 < 0.02 || !onFace(level, face, plane, (a0 + a1) * 0.5, (b0 + b1) * 0.5)) {
					continue;
				}

				int o = count * Decal.PIECE_STRIDE;
				buffer[o] = (float) (a0 - a);
				buffer[o + 1] = (float) (b0 - b);
				buffer[o + 2] = (float) (a1 - a);
				buffer[o + 3] = (float) (b1 - b);
				count++;
			}
		}

		if (count == 0) {
			return null;
		}

		float[] pieces = new float[count * Decal.PIECE_STRIDE];
		System.arraycopy(buffer, 0, pieces, 0, pieces.length);
		Decal decal = new Decal(world.nextSerial(), face, a, b, half, tile, orient, rgb, true, pieces,
				block, plane, lifetime, light(level, block.relative(face)));
		decal.porous = Surfaces.of(level.getBlockState(block)).porous();
		return add(decal);
	}

	/**
	 * True if the point (a, b) on the plane of a wall facing {@code face} is on a face of it: a block's shape just behind
	 * it and open air just in front.
	 */
	private static boolean onFace(Level level, Direction face, double plane, double a, double b) {
		boolean xNormal = face.getAxis() == Direction.Axis.X;
		double out = (face.getStepX() + face.getStepZ()) * 0.05;
		double behind = plane - out;
		double front = plane + out;
		return !SurfaceProbe.isOpen(level, xNormal ? behind : a, b, xNormal ? a : behind) && SurfaceProbe.isOpen(level, xNormal ? front : a, b, xNormal ? a : front);
	}

	/**
	 * Starts a run of blood down the face of {@code block} from (a, top): more blood on a wall than a stain holds does not
	 * stay where it hit. It creeps down a few centimetres a tick, fast while there is plenty behind it and slower as it
	 * leaves that behind it on the wall, and stops where it runs out, in a bead. Where the wall ends first, over a floor
	 * what is left pools at the foot of it, and over nothing it drips off the edge. {@code units} is the blood behind it
	 * in pool units.
	 */
	public @Nullable Decal run(Level level, BlockPos block, Direction face, double a, double top, float units, int rgb, int lifetime) {
		if (face.getAxis() == Direction.Axis.Y || !(units > 0.0F)) {
			return null;
		}

		if (top < block.getY() + 0.02) {
			// Too near the foot of this block to run on it: it starts at the top of the one below.
			block = block.below();
		}

		boolean xNormal = face.getAxis() == Direction.Axis.X;
		double plane = xNormal ? block.getX() + (face == Direction.EAST ? 1.0 : 0.0) : block.getZ() + (face == Direction.SOUTH ? 1.0 : 0.0);
		double low = xNormal ? block.getZ() : block.getX();
		a = Mth.clamp(a, low + RUN_HALF, low + 1.0 - RUN_HALF);
		top = Math.min(top, block.getY() + 1.0);
		if (!onFace(level, face, plane, a, top - 0.01)) {
			return null;
		}

		float[] pieces = {-RUN_HALF, 0.0F, RUN_HALF, 0.0F, 0.0F};
		Decal decal = new Decal(world.nextSerial(), face, a, top, RUN_HALF, BloodTextures.RUN, 0, rgb, true, pieces, block, plane, lifetime,
				light(level, block.relative(face)));
		decal.carried = units;
		runs++;
		return add(decal);
	}

	/** One tick of a run that is still creeping. */
	private void creep(Level level, Decal d) {
		float length = -d.pieces[1];
		float speed = RUN_SLOW + (RUN_FAST - RUN_SLOW) * Math.min(1.0F, d.carried / RUN_FAT);
		// No further than the blood behind it reaches.
		speed = Math.min(speed, d.carried / RUN_LAYS);
		double bottom = d.centerB - length - speed;
		double foot = d.anchor.getY();
		if (bottom <= foot) {
			// The foot of this block's face: what is left goes on from there.
			float reached = (float) (d.centerB - foot);
			float rest = d.carried - (reached - length) * RUN_LAYS;
			d.pieces[1] = -reached;
			d.carried = 0.0F;
			if (rest > 0.0F) {
				onward.add(new Onward(d, rest));
			}

			return;
		}

		if (!onFace(level, d.face, d.plane, d.centerA, bottom)) {
			// The face ends part of the way down (the side of a slab, a stair): the rest drips off its lower edge.
			drip(level, d, d.centerB - length, d.carried);
			d.carried = 0.0F;
			return;
		}

		d.pieces[1] = -(length + speed);
		d.carried -= speed * RUN_LAYS;
		if (d.carried < 1.0E-5F) {
			d.carried = 0.0F;
		}
	}

	/** A run that reached the foot of its block: on down the block below, into a pool at the foot of the wall, or off its edge. */
	private void carryOn(Level level, Onward on) {
		Decal d = on.from;
		double foot = d.anchor.getY();
		if (onFace(level, d.face, d.plane, d.centerA, foot - 0.01)) {
			run(level, d.anchor.below(), d.face, d.centerA, foot, on.carried, d.rgb, Math.max(40, d.lifetime - d.age));
			return;
		}

		drip(level, d, foot, on.carried);
	}

	/** The blood a run still carried where its wall ended at height {@code y}: onto the floor there, or falling from the edge. */
	private void drip(Level level, Decal d, double y, float units) {
		boolean xNormal = d.face.getAxis() == Direction.Axis.X;
		double out = (d.face.getStepX() + d.face.getStepZ()) * 0.06;
		double x = xNormal ? d.plane + out : d.centerA;
		double z = xNormal ? d.centerA : d.plane + out;
		double surface = SurfaceProbe.surfaceBelow(level, x, y + 0.3, y - 0.15, z);
		if (Double.isNaN(surface)) {
			world.droplets().runoff(x, y - 0.02, z, d.face.getStepX() * 0.01, d.face.getStepZ() * 0.01, units, d.rgb);
		} else {
			world.fluid().addVolume(level, x, surface, z, units, d.rgb, d.lifetime);
		}
	}

	private Decal add(Decal decal) {
		decals.add(decal);
		world.enforceCap();
		return decal;
	}

	void tick(Level level, int tickCount) {
		GoreConfig config = GoreConfig.get();
		int write = 0;
		for (int i = 0; i < decals.size(); i++) {
			Decal d = decals.get(i);
			d.age++;
			if (d.carried > 0.0F) {
				creep(level, d);
			}

			// Spread the expensive checks: every decal is re-validated once per second, at a different tick each.
			if (((d.serial + tickCount) % 20) == 0 || BrokenBlocks.near(d.anchor)) {
				BlockPos open = d.face == Direction.UP ? d.anchor.above() : d.anchor.relative(d.face);
				if (!level.isLoaded(open) || !supportIntact(level, d)) {
					continue;
				}

				if (level.getFluidState(open).is(FluidTags.WATER)) {
					// Blood does not persist in water: it disperses within a second.
					d.age = Math.max(d.age, d.lifetime - 20);
				} else if (d.face == Direction.UP && world.fluid().shows(d.centerA, d.plane, d.centerB)) {
					// A pool has run over it: the mark is part of the pool now.
					d.age = Math.max(d.age, d.lifetime - 4);
				}

				d.light = light(level, open);
			}

			if (config.rainWashesBlood && ((d.serial + tickCount) % 10) == 0 && level.isRainingAt(d.face == Direction.UP ? d.anchor.above() : d.anchor.relative(d.face))) {
				d.age += 30;
			}

			if (!d.expired()) {
				decals.set(write++, d);
			}
		}

		decals.subList(write, decals.size()).clear();
		if (!onward.isEmpty()) {
			// After the sweep: a run carried on is a new mark, and adding one may push the oldest out.
			for (Onward on : onward) {
				carryOn(level, on);
			}

			onward.clear();
		}
	}

	private boolean supportIntact(Level level, Decal d) {
		return d.face == Direction.UP ? trim(level, d) : trimWall(level, d);
	}

	/** Blood a floor mark of a whole block holds, in pool units, for what runs off where the ground went from under it. */
	private static final float RUNOFF_PER_BLOCK = 0.02F;

	/**
	 * Checks every piece of a floor mark, not just one: blocks blown or dug out from under part of it leave that part
	 * over nothing (a blast's blood lands a moment before its crater is cut). Those pieces go, and while the blood is
	 * still wet it runs off the new edge and falls. Returns false if nothing of it is left.
	 */
	private boolean trim(Level level, Decal d) {
		int n = d.pieces.length / Decal.PIECE_STRIDE;
		float[] kept = null;
		int count = 0;
		for (int i = 0; i < n; i++) {
			int o = i * Decal.PIECE_STRIDE;
			double x = d.centerA + (d.pieces[o] + d.pieces[o + 2]) * 0.5;
			double y = d.plane + d.pieces[o + 4];
			double z = d.centerB + (d.pieces[o + 1] + d.pieces[o + 3]) * 0.5;
			if (SurfaceProbe.stillSupported(level, x, y, z)) {
				if (kept != null) {
					System.arraycopy(d.pieces, o, kept, count * Decal.PIECE_STRIDE, Decal.PIECE_STRIDE);
				}

				count++;
				continue;
			}

			if (kept == null) {
				kept = new float[d.pieces.length];
				System.arraycopy(d.pieces, 0, kept, 0, i * Decal.PIECE_STRIDE);
			}

			float wet = 1.0F - d.dryness();
			float area = (d.pieces[o + 2] - d.pieces[o]) * (d.pieces[o + 3] - d.pieces[o + 1]);
			if (wet > 0.3F && area > 0.0F) {
				world.droplets().spawn(x, y, z, 0.0, 0.0, 0.0, RUNOFF_PER_BLOCK * area * wet, d.rgb, 0.05F);
			}
		}

		if (kept == null) {
			return true;
		}

		if (count == 0) {
			return false;
		}

		d.reshape(java.util.Arrays.copyOf(kept, count * Decal.PIECE_STRIDE));
		return true;
	}

	/**
	 * Checks every piece of a wall mark: the pieces on blocks that are gone go with them. A run is checked at the end it
	 * has reached. Returns false if nothing of it is left.
	 */
	private boolean trimWall(Level level, Decal d) {
		if (d.tile == BloodTextures.RUN) {
			return onFace(level, d.face, d.plane, d.centerA, d.centerB + Math.min(-0.01F, d.pieces[1] * 0.5F));
		}

		int n = d.pieces.length / Decal.PIECE_STRIDE;
		float[] kept = null;
		int count = 0;
		for (int i = 0; i < n; i++) {
			int o = i * Decal.PIECE_STRIDE;
			if (onFace(level, d.face, d.plane, d.centerA + (d.pieces[o] + d.pieces[o + 2]) * 0.5, d.centerB + (d.pieces[o + 1] + d.pieces[o + 3]) * 0.5)) {
				if (kept != null) {
					System.arraycopy(d.pieces, o, kept, count * Decal.PIECE_STRIDE, Decal.PIECE_STRIDE);
				}

				count++;
			} else if (kept == null) {
				kept = new float[d.pieces.length];
				System.arraycopy(d.pieces, 0, kept, 0, i * Decal.PIECE_STRIDE);
			}
		}

		if (kept == null) {
			return true;
		}

		if (count == 0) {
			return false;
		}

		d.reshape(java.util.Arrays.copyOf(kept, count * Decal.PIECE_STRIDE));
		return true;
	}

	/** Removes the oldest mark, if there is one. */
	void removeOldest() {
		if (!decals.isEmpty()) {
			decals.removeFirst();
		}
	}

	void removeChunk(int chunkX, int chunkZ) {
		decals.removeIf(d -> (d.anchor.getX() >> 4) == chunkX && (d.anchor.getZ() >> 4) == chunkZ);
	}

	/** Takes away every mark inside {@code box}, on the floor or on a wall. */
	void removeIn(net.minecraft.world.phys.AABB box) {
		decals.removeIf(d -> box.contains(d.anchor.getX() + 0.5, d.anchor.getY() + 0.5, d.anchor.getZ() + 0.5));
	}

	void clear() {
		decals.clear();
		onward.clear();
	}

	static int light(Level level, BlockPos pos) {
		return LightCoordsUtil.getLightCoords(level, pos);
	}
}
