package dev.fluidgore.cobbletank;

import dev.fluidgore.physics.GasJet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The gas jet out of a leaking tank, and what it does to a body in it. The jet of a 1 cm^2 crack at 10,000 psi leaves at
 * the speed of sound and widens as it goes; its force falls off with the square of the distance. For a smaller crack or
 * a lower pressure every distance below shrinks with the square root of the jet's power, so a pinhole only hurts what
 * touches it:
 * <ul>
 * <li>within 0.3 m: an injection injury, air driven through the skin and swelling under it, and now and then a fatal
 *     air embolism;</li>
 * <li>0.3 to 1 m: a sandblaster of grit and bits of casing, cutting;</li>
 * <li>1 to 3 m: knocked down or thrown, the most common real injury;</li>
 * <li>3 to 5 m: a gale.</li>
 * </ul>
 * The jet itself is no colder than a cold wind when it hits (the gas warms back up as it stops); the tank and the crack
 * are what freeze, so touching them gives frostbite and frost grows on the block in front of the crack.
 */
public final class Jet {
	public static final ResourceKey<DamageType> JET = key("jet");
	public static final ResourceKey<DamageType> EMBOLISM = key("embolism");
	/** Where each injury begins, metres along a full-power jet. */
	private static final double INJECTION = 0.3;
	private static final double SANDBLAST = 1.0;
	private static final double GALE = 3.0;
	/** Speed added each tick 1 m along a full-power jet, blocks per tick. */
	private static final double PUSH = 0.35;

	private Jet() {
	}

	private static ResourceKey<DamageType> key(String path) {
		return ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(CobbleTank.MOD_ID, path));
	}

	static DamageSource source(ServerLevel level, ResourceKey<DamageType> type, @Nullable Entity direct) {
		return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type), direct);
	}

	/**
	 * One tick of a jet leaving {@code origin} along {@code dir} (unit), from a crack of {@code areaCm2} at {@code psi}:
	 * every body in it is pushed, and hurt by where it stands. {@code tank} is the flying tank, if it is one (never
	 * caught in its own jet).
	 */
	public static void blow(ServerLevel level, Vec3 origin, Vec3 dir, double psi, double areaCm2, @Nullable Entity tank, int age) {
		double power = GasJet.power(psi, areaCm2);
		if (power < 1.0E-4) {
			return;
		}

		double scale = Math.sqrt(power);
		double reach = GasJet.reach(level, origin, dir, psi, areaCm2);
		Vec3 end = origin.add(dir.scale(reach));
		AABB box = new AABB(origin, end).inflate(0.2 + 0.15 * reach);
		for (Entity entity : level.getEntities(tank, box, e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator())) {
			LivingEntity body = (LivingEntity) entity;
			double along = GasJet.firstContact(body.getBoundingBox(), origin, dir, reach);
			if (along < 0.0) {
				continue;
			}

			// Distance as if the jet were a full-power one, so every zone below scales with the jet.
			double d = along / scale;
			double resist = 1.0 - Mth.clamp(body.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.0, 1.0);
			// The jet's whole momentum lands on a body close to the crack; further out the jet is wider than the body.
			double shove = Math.min(0.9, PUSH * power / Math.max(along * along, INJECTION * INJECTION)) * resist;
			if (!(body instanceof Player player && (player.getAbilities().flying || player.isCreative()))) {
				body.push(dir.x * shove, dir.y * shove + (d < GALE ? shove * 0.15 : 0.0), dir.z * shove);
			}

			if (age % 10 != 0 || d > SANDBLAST) {
				continue;
			}

			if (d <= INJECTION) {
				// A close-contact injury proxy. No invented random instant-death embolism roll.
				body.hurtServer(level, source(level, JET, tank), (float) (1.0 + 3.0 * scale));
				body.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 200, 1));
			} else {
				// Grit and bits of the casing in the jet: a sandblaster.
				body.hurtServer(level, source(level, JET, tank), (float) (0.5 + 1.5 * scale * (SANDBLAST - d + INJECTION)));
			}
		}
	}

	/** Bodies touching a casing at {@code celsius} freeze (vanilla's freezing: frost on the screen, then damage). */
	public static void chill(ServerLevel level, AABB casing, float celsius, @Nullable Entity tank) {
		if (celsius > -10.0F) {
			return;
		}

		// Vanilla thaws 2 a tick out of powder snow, so more than that has to go in for the cold to build.
		int per = celsius < -40.0F ? 6 : 4;
		for (Entity entity : level.getEntities(tank, casing.inflate(0.08), e -> e instanceof LivingEntity && e.canFreeze())) {
			entity.setTicksFrozen(Math.min(entity.getTicksRequiredToFreeze() + 60, entity.getTicksFrozen() + per));
		}
	}

	/**
	 * Frost where the crack of a standing tank points, while the casing is below freezing: still water turns to ice that
	 * melts again, a ground surface gathers a skin of snow.
	 */
	public static void frost(ServerLevel level, BlockPos tank, Direction crack, float celsius) {
		if (celsius > -2.0F) {
			return;
		}

		BlockPos at = tank.relative(crack);
		BlockState state = level.getBlockState(at);
		if (state.getFluidState().is(Fluids.WATER) && state.getFluidState().isSource() && state.is(Blocks.WATER)) {
			level.setBlockAndUpdate(at, Blocks.FROSTED_ICE.defaultBlockState());
			level.scheduleTick(at, Blocks.FROSTED_ICE, Mth.nextInt(level.getRandom(), 60, 120));
			return;
		}

		BlockPos snow = state.isAir() ? at : null;
		if (snow == null && crack.getAxis().isHorizontal() && level.getBlockState(at.above()).isAir()) {
			// The jet is blocked: frost on the face it hits, laid on top of it.
			snow = at.above();
		}

		if (snow != null) {
			BlockState here = level.getBlockState(snow);
			BlockState layer = Blocks.SNOW.defaultBlockState();
			if (here.isAir() && layer.canSurvive(level, snow)) {
				level.setBlockAndUpdate(snow, layer);
			} else if (here.is(Blocks.SNOW) && here.getValue(SnowLayerBlock.LAYERS) < 3 && celsius < -40.0F) {
				level.setBlockAndUpdate(snow, here.setValue(SnowLayerBlock.LAYERS, here.getValue(SnowLayerBlock.LAYERS) + 1));
			}
		}
	}
}
