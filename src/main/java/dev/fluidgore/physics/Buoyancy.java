package dev.fluidgore.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * A dead body in water. A fresh carcass is a little denser than water: the lungs empty, and it sinks slowly to the
 * bottom. As it rots, gas from the gut swells it (see {@code rot/Rot.swell}) until it is lighter than the water it
 * displaces, and it comes up and floats, low in the water. When the bloat bursts it goes down again. One rule for the
 * server (which moves a kept body) and the client (its ragdolls).
 */
public final class Buoyancy {
	/** Weight of a fresh body against the water it displaces: just under 1, so it sinks. */
	private static final float FRESH = 0.95F;
	/** What the height of the bloat adds. */
	private static final float BLOATED = 0.45F;
	/** Blocks per tick of a current at full flow, carrying what floats or drifts in it. */
	private static final double CURRENT = 0.014;

	/** Water set moving by something other than its flow (a charge's gas bubble, on the client), blocks per tick. */
	@FunctionalInterface
	public interface Disturbance {
		Vec3 at(BlockGetter level, double x, double y, double z);
	}

	private static volatile Disturbance disturbance = (level, x, y, z) -> Vec3.ZERO;

	private Buoyancy() {
	}

	/** How fast the water at {@code (x, y, z)} is being moved by something other than its flow, blocks per tick. */
	public static Vec3 disturbance(BlockGetter level, double x, double y, double z) {
		return disturbance.at(level, x, y, z);
	}

	/** Sets what else moves the water (the client's gas bubbles). It must answer zero for a level it does not know. */
	public static void disturb(Disturbance d) {
		disturbance = d;
	}

	/** Lift against the body's weight when it is under water entirely, from how swollen it is (below 1: it sinks). */
	public static float lift(float swell) {
		return FRESH + BLOATED * Math.max(0.0F, swell);
	}

	/** The share, 0..1, of the span from {@code bottom} to {@code top} at {@code (x, z)} that is under water. */
	public static float submerged(BlockGetter level, double x, double bottom, double top, double z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(x), Mth.floor(bottom), Mth.floor(z));
		FluidState fluid = level.getFluidState(pos);
		if (!fluid.is(FluidTags.WATER)) {
			// Its lowest point may be in the block under the water (a slab, a wall): try where its middle is.
			pos.setY(Mth.floor((bottom + top) * 0.5));
			fluid = level.getFluidState(pos);
			if (!fluid.is(FluidTags.WATER)) {
				return 0.0F;
			}
		}

		double surface = pos.getY() + fluid.getHeight(level, pos);
		// Up through the water to the surface, no further than the body reaches.
		while (surface >= pos.getY() + 1.0 - 1.0E-3 && surface < top) {
			pos.setY(pos.getY() + 1);
			fluid = level.getFluidState(pos);
			if (!fluid.is(FluidTags.WATER)) {
				break;
			}

			surface = pos.getY() + fluid.getHeight(level, pos);
		}

		double span = Math.max(1.0E-3, top - bottom);
		return (float) Mth.clamp((surface - bottom) / span, 0.0, 1.0);
	}

	/**
	 * The pull of the water at {@code (x, y, z)}, in blocks per tick: its flow, and anything else moving it (a charge's
	 * gas bubble growing and collapsing). Nothing in still water.
	 */
	public static Vec3 current(BlockGetter level, double x, double y, double z) {
		BlockPos pos = BlockPos.containing(x, y, z);
		FluidState fluid = level.getFluidState(pos);
		if (!fluid.is(FluidTags.WATER)) {
			return Vec3.ZERO;
		}

		Vec3 flow = fluid.getFlow(level, pos);
		Vec3 own = flow.lengthSqr() < 1.0E-8 ? Vec3.ZERO : flow.normalize().scale(CURRENT);
		return own.add(disturbance.at(level, x, y, z));
	}
}
