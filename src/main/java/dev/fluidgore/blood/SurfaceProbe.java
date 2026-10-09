package dev.fluidgore.blood;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Finds exact surface heights using block collision shapes, so blood sits on top of slabs, stairs, carpets,
 * snow layers and so on instead of floating at the block grid.
 */
public final class SurfaceProbe {
	private SurfaceProbe() {
	}

	/**
	 * Casts straight down from {@code fromY} to {@code toY} at (x, z) and returns the Y of the first upward-facing
	 * collision surface, or {@link Double#NaN} if there is none or the start point is inside a solid shape.
	 */
	public static double surfaceBelow(Level level, double x, double fromY, double toY, double z) {
		if (!isOpen(level, x, fromY, z)) {
			return Double.NaN;
		}

		BlockHitResult hit = level.clip(new ClipContext(new Vec3(x, fromY, z), new Vec3(x, toY, z),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
		if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP) {
			return Double.NaN;
		}

		return hit.getLocation().y;
	}

	/** True if the point is not inside any block collision box. */
	public static boolean isOpen(Level level, double x, double y, double z) {
		BlockPos pos = BlockPos.containing(x, y, z);
		BlockState state = level.getBlockState(pos);
		VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
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

	/** The block space a fluid film resting on a surface at {@code surfaceY} occupies. */
	public static int airBlockY(double surfaceY) {
		return (int) Math.floor(surfaceY + 1.0E-4);
	}
}
