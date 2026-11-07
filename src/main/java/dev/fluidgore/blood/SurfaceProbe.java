package dev.fluidgore.blood;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Finds exact surface heights, so blood sits on top of slabs, stairs, carpets, snow layers and so on instead of
 * floating at the block grid. Gore rests on what a block draws, not on its collision box alone: a fence's or a wall's
 * collision box stands half a block above its rails so that mobs cannot jump it, and blood landing on that would hang
 * in the air (see {@link #shape}).
 */
public final class SurfaceProbe {
	private SurfaceProbe() {
	}

	/**
	 * The shape blood, pieces and bodies rest on: the block's collision shape cut down to its outline (what is drawn
	 * and picked). Nearly every block has one shape for both, and gets it back as it is.
	 */
	public static VoxelShape shape(BlockGetter level, BlockPos pos, BlockState state) {
		VoxelShape collision = state.getCollisionShape(level, pos, CollisionContext.empty());
		if (collision.isEmpty()) {
			return collision;
		}

		VoxelShape outline = state.getShape(level, pos, CollisionContext.empty());
		return collision == outline ? collision : Shapes.join(collision, outline, BooleanOp.AND);
	}

	/** A ray against {@link #shape}, for blood in flight. */
	private static final class Visible extends ClipContext {
		Visible(Vec3 from, Vec3 to) {
			super(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty());
		}

		@Override
		public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
			return shape(level, pos, state);
		}
	}

	/** The first surface between two points, as {@link #shape} has it. */
	public static BlockHitResult clip(Level level, Vec3 from, Vec3 to) {
		return level.clip(new Visible(from, to));
	}

	/**
	 * Casts straight down from {@code fromY} to {@code toY} at (x, z) and returns the Y of the first upward-facing
	 * surface, or {@link Double#NaN} if there is none or the start point is inside a solid shape.
	 */
	public static double surfaceBelow(Level level, double x, double fromY, double toY, double z) {
		if (!isOpen(level, x, fromY, z)) {
			return Double.NaN;
		}

		BlockHitResult hit = clip(level, new Vec3(x, fromY, z), new Vec3(x, toY, z));
		if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP) {
			return Double.NaN;
		}

		return hit.getLocation().y;
	}

	/** True if the point is not inside any block's {@link #shape}. */
	public static boolean isOpen(Level level, double x, double y, double z) {
		BlockPos pos = BlockPos.containing(x, y, z);
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return true;
		}

		VoxelShape shape = shape(level, pos, state);
		if (shape.isEmpty()) {
			return true;
		}

		double lx = x - pos.getX();
		double ly = y - pos.getY();
		double lz = z - pos.getZ();
		for (AABB box : shape.toAabbs()) {
			if (lx > box.minX && lx < box.maxX && ly > box.minY && ly < box.maxY && lz > box.minZ && lz < box.maxZ) {
				return false;
			}
		}

		return true;
	}

	/** True if there is still a supporting top face just below {@code surfaceY} at (x, z). */
	public static boolean stillSupported(Level level, double x, double surfaceY, double z) {
		double found = surfaceBelow(level, x, surfaceY + 0.05, surfaceY - 0.05, z);
		return !Double.isNaN(found);
	}

	/**
	 * The extent, in world x and z, of the top face at height {@code surfaceY} that the rectangle (x0..x1, z0..z1)
	 * lies on: of the boxes of the block's shape that have their top there, the one under most of the rectangle. Blood is
	 * cut to it, so a splat on a fence rail is a strip along the rail, not a sheet in the air beside it, and at a corner
	 * post it keeps to one arm rather than spanning the gap between two. Null if no top face is there.
	 */
	public static @Nullable AABB topFace(Level level, double x0, double x1, double surfaceY, double z0, double z1) {
		BlockPos pos = BlockPos.containing((x0 + x1) * 0.5, surfaceY - 1.0E-4, (z0 + z1) * 0.5);
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return null;
		}

		double ly = surfaceY - pos.getY();
		double a0 = x0 - pos.getX();
		double a1 = x1 - pos.getX();
		double b0 = z0 - pos.getZ();
		double b1 = z1 - pos.getZ();
		AABB best = null;
		double most = 0.0;
		for (AABB box : shape(level, pos, state).toAabbs()) {
			if (Math.abs(box.maxY - ly) > 1.0E-3) {
				continue;
			}

			double under = Math.max(0.0, Math.min(a1, box.maxX) - Math.max(a0, box.minX)) * Math.max(0.0, Math.min(b1, box.maxZ) - Math.max(b0, box.minZ));
			if (under > most) {
				most = under;
				best = box;
			}
		}

		if (best == null) {
			return null;
		}

		return new AABB(pos.getX() + best.minX, surfaceY, pos.getZ() + best.minZ, pos.getX() + best.maxX, surfaceY, pos.getZ() + best.maxZ);
	}

	/** A top face narrower than this either way (a fence rail, a wall's arm, a pane) holds no pool: blood runs off it. */
	private static final double NARROW = 0.4;

	/** True if {@code face} (from {@link #topFace}) is too narrow to hold a pool. */
	public static boolean narrow(AABB face) {
		return Math.min(face.getXsize(), face.getZsize()) < NARROW;
	}

	/** The block space a fluid film resting on a surface at {@code surfaceY} occupies. */
	public static int airBlockY(double surfaceY) {
		return (int) Math.floor(surfaceY + 1.0E-4);
	}
}
