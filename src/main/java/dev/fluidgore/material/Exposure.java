package dev.fluidgore.material;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a piece's surroundings do to it, sampled from the blocks it lies in, on and beside: the temperature its
 * surface sees (degrees C, game-scale: about 700 in a fire, 1100 in lava, 250 on a magma block, a third of a fire's
 * heat beside one), whether there is an open flame to light it, and whether it is under water or out in the rain.
 */
public record Exposure(float heat, boolean flame, boolean submerged, boolean rained) {
	public static final float FIRE = 700.0F;
	public static final float LAVA = 1100.0F;
	public static final float MAGMA = 250.0F;
	/** Water a piece is quenched in. */
	public static final float WATER = 12.0F;
	public static final Exposure NONE = new Exposure(Condition.AMBIENT, false, false, false);

	/** Samples the blocks round the point {@code x, y, z} of a piece whose bottom is {@code bottom} below it. */
	public static Exposure at(Level level, double x, double y, double z, double bottom) {
		BlockPos in = BlockPos.containing(x, y, z);
		BlockPos under = BlockPos.containing(x, y - bottom - 0.05, z);
		BlockState inState = level.getBlockState(in);
		boolean submerged = inState.getFluidState().is(FluidTags.WATER);
		float heat = Condition.AMBIENT;
		boolean flame = false;
		for (BlockPos pos : new BlockPos[]{in, under, BlockPos.containing(x, y - bottom + 0.02, z)}) {
			BlockState state = pos == in ? inState : level.getBlockState(pos);
			if (state.getFluidState().is(FluidTags.LAVA)) {
				return new Exposure(LAVA, true, false, false);
			}

			if (state.is(BlockTags.FIRE) || CampfireBlock.isLitCampfire(state)) {
				heat = Math.max(heat, FIRE);
				flame = true;
			} else if (pos == under && state.is(Blocks.MAGMA_BLOCK)) {
				heat = Math.max(heat, MAGMA);
			}
		}

		if (heat < FIRE && !submerged) {
			// Beside a fire, not in it: the radiant heat of the flames.
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockState side = level.getBlockState(in.relative(d));
				if (side.is(BlockTags.FIRE) || CampfireBlock.isLitCampfire(side) || side.getFluidState().is(FluidTags.LAVA)) {
					heat = Math.max(heat, FIRE / 3.0F);
					break;
				}
			}
		}

		if (submerged) {
			heat = WATER;
		}

		return new Exposure(heat, flame, submerged, !submerged && level.isRainingAt(in));
	}
}
