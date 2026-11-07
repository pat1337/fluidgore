package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/** Owns all static splat decals (floor splats, wall splatter, drip streaks, footprints). */
public final class DecalManager {
	/** Pieces are aligned to a half-block grid, which matches the top-surface steps of stairs and slabs. */
	private static final double GRID = 0.5;
	/** Floor decals may follow a step of at most this height (stairs, slabs, carpets). */
	private static final double MAX_STEP = 0.55;

	private final BloodWorld world;
	/** Kept in creation order, so index 0 is always the oldest decal. */
	private final List<Decal> decals = new ArrayList<>();

	DecalManager(BloodWorld world) {
		this.world = world;
	}

	public List<Decal> decals() {
		return decals;
	}

	public int size() {
		return decals.size();
	}

	/**
	 * Places a square decal on the floor around (x, z), resting on the surface at {@code surfaceY}. Returns null
	 * if none of its pieces lie on a valid surface.
	 */
	public @Nullable Decal addFloor(Level level, double x, double surfaceY, double z, float half, int tile, int orient,
			int rgb, boolean dries, int lifetime) {
		float[] buffer = new float[Decal.PIECE_STRIDE * 16];
		int count = 0;
		double x0 = x - half;
		double x1 = x + half;
		double z0 = z - half;
		double z1 = z + half;

		for (double gx = Math.floor(x0 / GRID) * GRID; gx < x1 && count < 16; gx += GRID) {
			for (double gz = Math.floor(z0 / GRID) * GRID; gz < z1 && count < 16; gz += GRID) {
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
		return add(new Decal(world.nextSerial(), Direction.UP, x, z, half, tile, orient, rgb, dries, pieces,
				anchor, surfaceY, lifetime, light(level, anchor.above())));
	}

	/**
	 * Places a decal on the side face of {@code block}. (a, b) are the tangent coordinates of the centre: for
	 * east/west faces a = z, for north/south faces a = x, and b = y. The decal is clipped to the block face.
	 */
	public @Nullable Decal addWall(Level level, BlockPos block, Direction face, double a, double b, float half, int tile,
			int orient, int rgb, int lifetime) {
		if (face.getAxis() == Direction.Axis.Y) {
			return null;
		}

		boolean xNormal = face.getAxis() == Direction.Axis.X;
		double plane = xNormal ? block.getX() + (face == Direction.EAST ? 1.0 : 0.0) : block.getZ() + (face == Direction.SOUTH ? 1.0 : 0.0);
		double blockA = xNormal ? block.getZ() : block.getX();
		double a0 = Math.max(a - half, blockA);
		double a1 = Math.min(a + half, blockA + 1.0);
		double b0 = Math.max(b - half, block.getY());
		double b1 = Math.min(b + half, block.getY() + 1.0);
		if (a1 - a0 < 0.02 || b1 - b0 < 0.02) {
			return null;
		}

		// The face must be a real full-ish surface: the block must still collide just behind the decal centre
		// and the space just in front of it must be open.
		double probeA = (a0 + a1) * 0.5;
		double probeB = (b0 + b1) * 0.5;
		double behind = plane - face.getStepX() * 0.05 - face.getStepZ() * 0.05;
		double front = plane + face.getStepX() * 0.05 + face.getStepZ() * 0.05;
		double bx = xNormal ? behind : probeA;
		double bz = xNormal ? probeA : behind;
		double fx = xNormal ? front : probeA;
		double fz = xNormal ? probeA : front;
		if (SurfaceProbe.isOpen(level, bx, probeB, bz) || !SurfaceProbe.isOpen(level, fx, probeB, fz)) {
			return null;
		}

		float[] pieces = {(float) (a0 - a), (float) (b0 - b), (float) (a1 - a), (float) (b1 - b), 0.0F};
		return add(new Decal(world.nextSerial(), face, a, b, half, tile, orient, rgb, true, pieces,
				block, plane, lifetime, light(level, block.relative(face))));
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

			// Spread the expensive checks: every decal is re-validated once per second, at a different tick each.
			if (((d.serial + tickCount) % 20) == 0 || BrokenBlocks.near(d.anchor)) {
				BlockPos open = d.face == Direction.UP ? d.anchor.above() : d.anchor.relative(d.face);
				if (!level.isLoaded(open) || !supportIntact(level, d)) {
					continue;
				}

				if (level.getFluidState(open).is(FluidTags.WATER)) {
					// Blood does not persist in water: it disperses within a second.
					d.age = Math.max(d.age, d.lifetime - 20);
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
	}

	private boolean supportIntact(Level level, Decal d) {
		if (d.face == Direction.UP) {
			return trim(level, d);
		}

		return !level.getBlockState(d.anchor).getCollisionShape(level, d.anchor, CollisionContext.empty()).isEmpty();
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

		d.pieces = java.util.Arrays.copyOf(kept, count * Decal.PIECE_STRIDE);
		return true;
	}

	/** Removes the oldest decal; returns its serial or -1 if there is none. */
	long removeOldest() {
		if (decals.isEmpty()) {
			return -1;
		}

		return decals.removeFirst().serial;
	}

	long oldestSerial() {
		return decals.isEmpty() ? Long.MAX_VALUE : decals.getFirst().serial;
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
	}

	static int light(Level level, BlockPos pos) {
		return LightCoordsUtil.getLightCoords(level, pos);
	}
}
