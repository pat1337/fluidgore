package dev.fluidgore.blood;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;

/**
 * Blocks that lost their collision since the last client tick (mined, blown up, burnt). Anything resting on or
 * stuck to one of them checks its support at once instead of waiting for its periodic check, so gore never hangs in
 * the air where the floor was a moment ago. Filled by {@code ClientLevelMixin}, emptied by {@code GoreSystems}.
 */
public final class BrokenBlocks {
	/** More changes than this in one tick (a big blast): every resting thing is rechecked. */
	private static final int MAX = 4096;
	private static final LongArrayList CHANGED = new LongArrayList();
	private static boolean overflow;
	private static int minX, minY, minZ, maxX, maxY, maxZ;

	private BrokenBlocks() {
	}

	public static void add(BlockPos pos) {
		if (CHANGED.isEmpty() && !overflow) {
			minX = maxX = pos.getX();
			minY = maxY = pos.getY();
			minZ = maxZ = pos.getZ();
		} else {
			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX());
			maxY = Math.max(maxY, pos.getY());
			maxZ = Math.max(maxZ, pos.getZ());
		}

		if (CHANGED.size() < MAX) {
			CHANGED.add(pos.asLong());
		} else {
			overflow = true;
		}
	}

	public static boolean any() {
		return overflow || !CHANGED.isEmpty();
	}

	/** True if a block within 1.5 blocks of the point lost its collision this tick. */
	public static boolean near(double x, double y, double z) {
		if (!any() || x < minX - 1.5 || x > maxX + 2.5 || y < minY - 1.5 || y > maxY + 2.5 || z < minZ - 1.5 || z > maxZ + 2.5) {
			return false;
		}

		if (overflow) {
			return true;
		}

		for (int i = 0; i < CHANGED.size(); i++) {
			long p = CHANGED.getLong(i);
			if (Math.abs(BlockPos.getX(p) + 0.5 - x) <= 2.0 && Math.abs(BlockPos.getY(p) + 0.5 - y) <= 2.0 && Math.abs(BlockPos.getZ(p) + 0.5 - z) <= 2.0) {
				return true;
			}
		}

		return false;
	}

	public static boolean near(BlockPos pos) {
		return near(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	public static void clear() {
		CHANGED.clear();
		overflow = false;
	}
}
