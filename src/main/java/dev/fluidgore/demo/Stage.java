package dev.fluidgore.demo;

import java.util.Locale;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where a demo is played: in front of the player, lined up with the blocks. Positions are given as (right, up,
 * forward) in blocks from the centre of the block the player stands on, with "forward" the way the player faced
 * (snapped to the nearest of the four directions) when the demo started.
 */
public final class Stage {
	private final double x;
	private final double y;
	private final double z;
	private final int fx;
	private final int fz;
	private final float yaw;

	Stage(LocalPlayer player) {
		x = Mth.floor(player.getX()) + 0.5;
		y = Mth.floor(player.getY() + 1.0E-3);
		z = Mth.floor(player.getZ()) + 0.5;
		int quarter = Math.floorMod(Math.round(player.getYRot() / 90.0F), 4);
		// Minecraft yaw: 0 faces south (+z), 90 west, 180 north, 270 east.
		fx = quarter == 1 ? -1 : quarter == 3 ? 1 : 0;
		fz = quarter == 0 ? 1 : quarter == 2 ? -1 : 0;
		yaw = quarter * 90.0F;
	}

	/** The world point {@code right}, {@code up} and {@code forward} blocks from the player's spot. */
	public Vec3 at(double right, double up, double forward) {
		// The right hand of someone facing (fx, fz) points along (-fz, fx).
		return new Vec3(x - right * fz + forward * fx, y + up, z + right * fx + forward * fz);
	}

	/** {@link #at} as command coordinates. */
	public String pos(double right, double up, double forward) {
		Vec3 p = at(right, up, forward);
		return String.format(Locale.ROOT, "%.2f %.2f %.2f", p.x, p.y, p.z);
	}

	/** The block at {@link #at}, as command coordinates. */
	public String block(double right, double up, double forward) {
		BlockPos p = BlockPos.containing(at(right, up, forward));
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	/** The direction back towards the player, as a block state value ({@code north}...): a stair's facing that climbs to them. */
	public String back() {
		return fx == 1 ? "west" : fx == -1 ? "east" : fz == 1 ? "north" : "south";
	}

	/** Yaw (degrees) for a mob turned {@code turn} degrees clockwise from the way the player faces; 180 faces the player. */
	public float yaw(float turn) {
		return Mth.wrapDegrees(yaw + turn);
	}

	/** The living mob of {@code type} nearest to a stage spot (within two blocks), or null. */
	public @Nullable LivingEntity find(Level level, EntityType<?> type, double right, double up, double forward) {
		Vec3 spot = at(right, up, forward);
		LivingEntity best = null;
		double bestSq = 4.0;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(spot, spot).inflate(3.0), e -> e.getType() == type && e.isAlive())) {
			double d = e.position().distanceToSqr(spot);
			if (d < bestSq) {
				best = e;
				bestSq = d;
			}
		}

		return best;
	}
}
