package dev.fluidgore.server;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.physics.BodyMass;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Burning bodies set fire to what they touch ({@code burningSpreadsFire}). Vanilla's burning mob is a light that
 * hurts only itself; a real animal on fire is a torch, and a carcass that died burning burns on for a long while on
 * its own fat. Here:
 * <ul>
 * <li>Anything alive and on fire lights the dry stuff it brushes past: grass, hay, wool, leaves, wood. A pig that runs
 *     burning into a house can set it alight.</li>
 * <li>A kept body that died burning goes on burning where it lies, for as long as it takes to burn to the bone (see
 *     {@link BodyMass}), so players without the mod see it alight too, and it lights what it lies against.</li>
 * </ul>
 * The fire is vanilla fire and spreads as it does: water and rain put a body out, and the game rule
 * {@code fire_spread_radius_around_player} still decides where fire may be lit at all. Checked twice a second, a few
 * blocks per body at most, so it stays cheap.
 */
public final class BurningBodies {
	/** Ticks between looks. */
	private static final int EVERY = 10;
	/** Bodies looked at per level per look, at most. */
	private static final int MAX_BODIES = 48;
	/** Chance a living body on fire lights something it touches, per look: a few seconds of running through dry grass. */
	private static final float RUNNING = 0.08F;
	/** The same for a body burning where it lies, for one of a pig's weight; bigger bodies burn hotter and wider. */
	private static final float LYING = 0.06F;
	private static final float PIG_SIZE = (float) Math.cbrt(110.0);
	private static final RandomSource RANDOM = RandomSource.create();

	private BurningBodies() {
	}

	public static void register() {
		ServerTickEvents.END_LEVEL_TICK.register(BurningBodies::tick);
	}

	/**
	 * A body the server has just begun to keep: if it died burning, it burns on, on its own fat, until it is bone. Called
	 * once, from {@link KeptBodies#keep}.
	 */
	static void keptBurning(LivingEntity body) {
		if (GoreConfig.get().burningSpreadsFire && body.isOnFire() && !body.isInWaterOrRain()) {
			body.setRemainingFireTicks(Math.max(body.getRemainingFireTicks(), Math.round(BodyMass.seconds(body) * 20.0F)));
		}
	}

	private static void tick(ServerLevel level) {
		if (!GoreConfig.get().burningSpreadsFire || level.getGameTime() % EVERY != 0) {
			return;
		}

		int looked = 0;
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof LivingEntity body) || !body.isOnFire() || body.isInWaterOrRain() || body instanceof Player player && player.isSpectator()) {
				continue;
			}

			if (++looked > MAX_BODIES) {
				return;
			}

			float chance = body.isDeadOrDying() ? LYING * (float) Math.cbrt(BodyMass.kilograms(body)) / PIG_SIZE : RUNNING;
			if (RANDOM.nextFloat() < chance) {
				light(level, body.getBoundingBox());
			}
		}
	}

	/** Tries to light one spot in or just around {@code box}: one where fire can burn, next to something that burns. */
	private static void light(ServerLevel level, AABB box) {
		double x = Mth.lerp(RANDOM.nextDouble(), box.minX - 0.3, box.maxX + 0.3);
		double y = Mth.lerp(RANDOM.nextDouble() * RANDOM.nextDouble(), box.minY, box.maxY);
		double z = Mth.lerp(RANDOM.nextDouble(), box.minZ - 0.3, box.maxZ + 0.3);
		BlockPos pos = BlockPos.containing(x, y, z);
		if (!level.canSpreadFireAround(pos) || !level.isLoaded(pos)) {
			return;
		}

		BlockState state = level.getBlockState(pos);
		// Grass, ferns and dead bushes it runs through go up where they stand; otherwise the fire takes in the air beside
		// whatever burns.
		boolean plant = state.canBeReplaced() && state.ignitedByLava() && state.getFluidState().isEmpty();
		if (!plant && !(state.isAir() && burnsBeside(level, pos))) {
			return;
		}

		if (plant || BaseFireBlock.canBePlacedAt(level, pos, Direction.UP)) {
			level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL);
		}
	}

	private static boolean burnsBeside(ServerLevel level, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			if (level.getBlockState(pos.relative(direction)).ignitedByLava()) {
				return true;
			}
		}

		return false;
	}
}
