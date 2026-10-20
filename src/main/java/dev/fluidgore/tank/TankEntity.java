package dev.fluidgore.tank;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * A Cobble Tank torn loose by its own leak: a missile, moved as a rigid body in real units (metres, kilograms, seconds;
 * a tick is 1/20 s).
 * <ul>
 * <li>The crack's thrust (8.7 kN for 1 cm^2 at 10,000 psi) acts against the jet on a tank of some 400 kg, plus real
 *     gravity (9.81 m/s^2) and air drag ({@code 1/2 rho Cd A v^2}).</li>
 * <li>No crack points straight through the centre: the jet leaves a few degrees off the casing's normal, so the thrust
 *     has a lever arm and a torque, and the tank (a 1 m cube, moment of inertia m/6) tumbles faster and faster.</li>
 * <li>On the ground it skids against kinetic friction on its weight, less whatever of the thrust lifts it, more
 *     whatever presses it down; the scraping slows its spin.</li>
 * </ul>
 * It hits bodies by its kinetic energy, goes through weak blocks (glass, leaves, dirt, sand) and bounces off the rest,
 * each hard landing cutting deeper into its casing. Its jet does what a standing tank's does. Vented and still, it
 * stands as a block again, top where it came to rest; it can still burst on the way, hit or cracked further.
 */
public class TankEntity extends Entity {
	private static final EntityDataAccessor<Quaternionfc> ROTATION = SynchedEntityData.defineId(TankEntity.class, EntityDataSerializers.QUATERNION);
	/** Where the crack is, in the tank's own frame (a unit vector from its centre). */
	private static final EntityDataAccessor<Vector3fc> CRACK = SynchedEntityData.defineId(TankEntity.class, EntityDataSerializers.VECTOR3);
	/** Which way the jet leaves the crack, in the tank's own frame: a little off the casing's normal. */
	private static final EntityDataAccessor<Vector3fc> NOZZLE = SynchedEntityData.defineId(TankEntity.class, EntityDataSerializers.VECTOR3);
	/** Which way its top pointed when it stood as a block (so it looks the same once it flies). */
	private static final EntityDataAccessor<Direction> TOP = SynchedEntityData.defineId(TankEntity.class, EntityDataSerializers.DIRECTION);
	private static final EntityDataAccessor<Float> FRACTION = SynchedEntityData.defineId(TankEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> AREA = SynchedEntityData.defineId(TankEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> SCARS = SynchedEntityData.defineId(TankEntity.class, EntityDataSerializers.INT);
	private static final double DT = 0.05;
	/** Kinetic friction, casing skidding on the ground. */
	private static final double SKID = 0.45;
	/** {@code 1/2 rho Cd A}: air 1.2 kg/m^3, a cube's drag coefficient 1.05, a square metre facing the wind. */
	private static final double DRAG = 0.5 * 1.2 * 1.05 * 1.0;
	/** Fastest it may go, m/s: faster would step through blocks between ticks. */
	private static final double MAX_SPEED = 36.0;
	/** Fastest it may spin, rad/s. */
	private static final float MAX_SPIN = 40.0F;
	/** Of the speed into a surface, how much comes back out of a bounce. */
	private static final double RESTITUTION = 0.3;
	/** A block no harder than this is smashed through rather than bounced off. */
	private static final float WEAK = 0.6F;
	/** Joules of impact per point of damage to a body. */
	private static final double JOULES_PER_DAMAGE = 2000.0;
	/** Impact speed, m/s, above which a landing cuts deeper into the casing. */
	private static final double HARD_LANDING = 14.0;
	/** The crack's jet leaves this far off the casing's normal, degrees (a range). */
	private static final float TILT_MIN = 8.0F;
	private static final float TILT_MAX = 20.0F;

	private TankState tank = new TankState();
	/** Angular velocity, rad/s, in the world frame. */
	private final Vector3f spin = new Vector3f();
	private int resting;
	private final Int2LongOpenHashMap struck = new Int2LongOpenHashMap();

	public TankEntity(EntityType<? extends TankEntity> type, Level level) {
		super(type, level);
		this.blocksBuilding = true;
	}

	/** The tank at {@code pos} tears loose, its jet leaving along {@code jet} (world), its top towards {@code top}. */
	static void launch(ServerLevel level, BlockPos pos, TankState tank, Vec3 jet, Direction top) {
		TankEntity entity = new TankEntity(Tanks.ENTITY, level);
		entity.tank = tank;
		entity.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		Vector3f crack = new Vector3f((float) jet.x, (float) jet.y, (float) jet.z).normalize();
		// Tip the nozzle off the normal about some axis across it: that offset is what sets it tumbling.
		RandomSource random = level.getRandom();
		Vector3f across = new Vector3f(random.nextFloat() - 0.5F, random.nextFloat() - 0.5F, random.nextFloat() - 0.5F).cross(crack);
		if (across.lengthSquared() < 1.0E-6F) {
			across.set(crack.y, -crack.x, 0.0F);
		}

		float tilt = (float) Math.toRadians(TILT_MIN + random.nextFloat() * (TILT_MAX - TILT_MIN));
		Vector3f nozzle = new Quaternionf().rotateAxis(tilt, across.normalize()).transform(new Vector3f(crack));
		entity.entityData.set(CRACK, crack);
		entity.entityData.set(NOZZLE, nozzle.normalize());
		entity.entityData.set(TOP, top);
		entity.sync();
		level.addFreshEntity(entity);
		level.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1.5F, 0.5F);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(ROTATION, new Quaternionf());
		builder.define(CRACK, new Vector3f(0.0F, 1.0F, 0.0F));
		builder.define(NOZZLE, new Vector3f(0.0F, 1.0F, 0.0F));
		builder.define(TOP, Direction.UP);
		builder.define(FRACTION, 1.0F);
		builder.define(AREA, 0.0F);
		builder.define(SCARS, 0);
	}

	public Quaternionfc rotation() {
		return entityData.get(ROTATION);
	}

	public Direction top() {
		return entityData.get(TOP);
	}

	public int scars() {
		return entityData.get(SCARS);
	}

	private Vec3 world(EntityDataAccessor<Vector3fc> local) {
		Vector3f d = rotation().transform(new Vector3f(entityData.get(local)));
		return new Vec3(d.x, d.y, d.z).normalize();
	}

	/** Which way the jet leaves now, in the world. */
	public Vec3 jet() {
		return world(NOZZLE);
	}

	/** Where on the casing the crack is now, from the centre, in the world (a point on the cube's surface). */
	public Vec3 crack() {
		Vector3fc c = entityData.get(CRACK);
		float most = Math.max(Math.abs(c.x()), Math.max(Math.abs(c.y()), Math.abs(c.z())));
		Vector3f p = rotation().transform(new Vector3f(c).mul(0.5F / Math.max(most, 1.0E-3F)));
		return new Vec3(p.x, p.y, p.z);
	}

	public Vec3 centre() {
		return position().add(0.0, getBbHeight() / 2.0, 0.0);
	}

	/** The tank's state as the client knows it (pressure and crack synced every tick). */
	public TankState state() {
		if (level().isClientSide()) {
			tank.fraction = entityData.get(FRACTION);
			tank.area = entityData.get(AREA);
		}

		return tank;
	}

	private void sync() {
		entityData.set(FRACTION, tank.fraction);
		entityData.set(AREA, tank.area);
		entityData.set(SCARS, TankBlock.scars(tank.damage));
	}

	@Override
	protected double getDefaultGravity() {
		return Blast.GRAVITY * DT * DT;
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel level)) {
			if (state().leaking()) {
				TankFx.jet(this, level(), centre().add(crack()), jet(), state(), random);
			}

			return;
		}

		if (tank.bleed(random)) {
			burst(level, null);
			return;
		}

		fly();
		Vec3 step = getDeltaMovement();
		strike(level, step);
		move(MoverType.SELF, step);
		if (isRemoved()) {
			return;
		}

		collide(level, step);
		if (isRemoved()) {
			return;
		}

		if (tank.leaking()) {
			Jet.blow(level, centre().add(crack()), jet(), tank.psiNow(), tank.area, this, tickCount);
		}

		Jet.chill(level, getBoundingBox(), tank.temperature(), this);
		sync();
		rest(level);
	}

	/** One tick of the rigid body: thrust, its torque, gravity, drag, and friction on the ground. */
	private void fly() {
		double mass = tank.mass();
		Vec3 force = jet().scale(-tank.thrust());
		// Torque of the thrust about the centre: the crack's offset crossed with the force.
		Vec3 torque = crack().cross(force);
		float inertia = (float) (mass / 6.0);
		spin.add((float) (torque.x / inertia * DT), (float) (torque.y / inertia * DT), (float) (torque.z / inertia * DT));

		Vec3 v = getDeltaMovement().scale(1.0 / DT).add(force.scale(DT / mass)).add(0.0, -Blast.GRAVITY * DT, 0.0);
		double speed = v.length();
		if (speed > 1.0E-6) {
			v = v.scale(Math.max(0.0, speed - DRAG * speed * speed / mass * DT) / speed);
		}

		if (onGround()) {
			// Kinetic friction on what presses it to the ground: its weight, less thrust lifting it, plus thrust pressing it down.
			double normal = Math.max(0.0, mass * Blast.GRAVITY - force.y);
			double slow = SKID * normal / mass * DT;
			double sideways = Math.sqrt(v.x * v.x + v.z * v.z);
			double keep = sideways > slow ? (sideways - slow) / sideways : 0.0;
			v = new Vec3(v.x * keep, v.y, v.z * keep);
			spin.mul(0.85F);
		} else {
			spin.mul(0.995F);
		}

		if (spin.length() > MAX_SPIN) {
			spin.normalize(MAX_SPIN);
		}

		if (v.length() > MAX_SPEED) {
			v = v.normalize().scale(MAX_SPEED);
		}

		float angle = spin.length() * (float) DT;
		if (angle > 1.0E-6F) {
			Quaternionf q = new Quaternionf().rotateAxis(angle, new Vector3f(spin).normalize()).mul(rotation());
			entityData.set(ROTATION, q.normalize());
		}

		setDeltaMovement(v.scale(DT));
	}

	/** Bodies in its way: hit by its kinetic energy, thrown along with it, and it slows. */
	private void strike(ServerLevel level, Vec3 step) {
		if (step.length() < 0.15) {
			return;
		}

		AABB swept = getBoundingBox().expandTowards(step).inflate(0.1);
		for (Entity entity : level.getEntities(this, swept, e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator())) {
			if (level.getGameTime() - struck.get(entity.getId()) < 10) {
				continue;
			}

			struck.put(entity.getId(), level.getGameTime());
			double ms = step.subtract(entity.getDeltaMovement()).length() / DT;
			double joules = 0.5 * tank.mass() * ms * ms;
			float damage = (float) (joules / JOULES_PER_DAMAGE);
			if (damage >= 1.0F) {
				entity.hurtServer(level, Jet.source(level, Tanks.IMPACT, this), damage);
				level.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.BLOCKS, 1.0F, 0.6F);
			}

			entity.push(step.x * 0.8, 0.25 + Math.abs(step.y) * 0.5, step.z * 0.8);
			setDeltaMovement(getDeltaMovement().scale(0.75));
		}
	}

	/** After moving: smash through weak blocks it ran into, bounce off the rest, and feel the landing. */
	private void collide(ServerLevel level, Vec3 step) {
		if (horizontalCollision) {
			double speed = Math.sqrt(step.x * step.x + step.z * step.z) / DT;
			boolean broke = false;
			if (speed > 7.0) {
				AABB ahead = getBoundingBox().move(step.x, 0.0, step.z).deflate(0.02);
				for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(ahead.minX, ahead.minY, ahead.minZ),
						BlockPos.containing(ahead.maxX, ahead.maxY, ahead.maxZ))) {
					BlockState state = level.getBlockState(pos);
					float hardness = state.getDestroySpeed(level, pos);
					if (!state.isAir() && hardness >= 0.0F && hardness <= WEAK && !state.getCollisionShape(level, pos).isEmpty()) {
						level.destroyBlock(pos, true, this);
						broke = true;
					}
				}
			}

			Vec3 now = getDeltaMovement();
			if (broke) {
				setDeltaMovement(step.x * 0.7, now.y, step.z * 0.7);
			} else {
				setDeltaMovement(-step.x * RESTITUTION, now.y, -step.z * RESTITUTION);
				// The corner that hit takes the blow: the spin kicks round.
				spin.mul(-0.5F).add((random.nextFloat() - 0.5F) * 6.0F, (random.nextFloat() - 0.5F) * 6.0F, (random.nextFloat() - 0.5F) * 6.0F);
				land(level, speed);
			}
		}

		if (verticalCollision && Math.abs(step.y) / DT > 4.0) {
			Vec3 now = getDeltaMovement();
			setDeltaMovement(now.x, -step.y * RESTITUTION, now.z);
			land(level, Math.abs(step.y) / DT);
		}
	}

	/** A landing at {@code speed} m/s: a clang, and above {@link #HARD_LANDING} the casing cuts deeper (it may burst). */
	private void land(ServerLevel level, double speed) {
		if (isRemoved()) {
			return;
		}

		level.playSound(null, getX(), getY(), getZ(), SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, (float) Math.min(1.5, speed / 14.0), 0.6F + random.nextFloat() * 0.3F);
		if (speed > HARD_LANDING && tank.blow((float) ((speed - HARD_LANDING) * 0.012), random) == TankState.Blow.BURST) {
			burst(level, null);
		}
	}

	/** Vented and still: it stands as a block again where it lies, top the way it ended up (or, if it cannot, is left as scrap). */
	private void rest(ServerLevel level) {
		if (tank.leaking() || getDeltaMovement().lengthSqr() > 0.01 * 0.01 || !onGround()) {
			resting = 0;
			return;
		}

		if (++resting < 20) {
			return;
		}

		BlockPos pos = blockPosition();
		Vector3f up = rotation().transform(new Vector3f(top().getStepX(), top().getStepY(), top().getStepZ()));
		Direction facing = Direction.getApproximateNearest(up.x, up.y, up.z);
		if (level.getBlockState(pos).canBeReplaced()) {
			level.setBlockAndUpdate(pos, Tanks.BLOCK.defaultBlockState().setValue(TankBlock.FACING, facing).setValue(TankBlock.SCARS, TankBlock.scars(tank.damage)));
			if (level.getBlockEntity(pos) instanceof TankBlockEntity standing) {
				standing.set(tank.copy(), jet());
			}
		} else {
			level.addFreshEntity(new ItemEntity(level, getX(), getY() + 0.5, getZ(), new ItemStack(Items.COBBLESTONE, 2)));
		}

		discard();
	}

	private void burst(ServerLevel level, @Nullable Entity cause) {
		Vec3 centre = centre();
		TankState copy = tank.copy();
		discard();
		TankBurst.burst(level, centre, copy, cause);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		if (isRemoved() || isInvulnerableToBase(source)) {
			return false;
		}

		if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			// Caught in a blast: it goes a moment later.
			TankState copy = tank.copy();
			Vec3 centre = centre();
			discard();
			TankBurst.later(level, centre, copy, source.getEntity(), 2 + random.nextInt(6));
			return true;
		}

		if (source.getDirectEntity() instanceof Player player) {
			ItemStack stack = player.getMainHandItem();
			float force = TankState.force(stack);
			TankState.Blow blow = tank.blow(force, random);
			Vec3 along = centre().subtract(player.getEyePosition()).normalize();
			Scrape.play(level, centre().subtract(along.scale(0.5)), along, blow, force);
			if (blow == TankState.Blow.BURST) {
				burst(level, player);
			}

			return true;
		}

		return false;
	}

	@Override
	public boolean isPickable() {
		return !isRemoved();
	}

	@Override
	public boolean isAttackable() {
		return true;
	}

	@Override
	public boolean canBeCollidedWith(@Nullable Entity other) {
		return true;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		tank.save(output);
		Quaternionfc q = rotation();
		output.putFloat("Qx", q.x());
		output.putFloat("Qy", q.y());
		output.putFloat("Qz", q.z());
		output.putFloat("Qw", q.w());
		vector(output, "Crack", entityData.get(CRACK));
		vector(output, "Nozzle", entityData.get(NOZZLE));
		output.putInt("Top", top().get3DDataValue());
		output.putFloat("SpinX", spin.x);
		output.putFloat("SpinY", spin.y);
		output.putFloat("SpinZ", spin.z);
	}

	private static void vector(ValueOutput output, String name, Vector3fc v) {
		output.putFloat(name + "X", v.x());
		output.putFloat(name + "Y", v.y());
		output.putFloat(name + "Z", v.z());
	}

	private static Vector3f vector(ValueInput input, String name) {
		Vector3f v = new Vector3f(input.getFloatOr(name + "X", 0.0F), input.getFloatOr(name + "Y", 1.0F), input.getFloatOr(name + "Z", 0.0F));
		return v.isFinite() && v.lengthSquared() > 1.0E-4F ? v.normalize() : new Vector3f(0.0F, 1.0F, 0.0F);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		tank.load(input);
		Quaternionf q = new Quaternionf(input.getFloatOr("Qx", 0.0F), input.getFloatOr("Qy", 0.0F), input.getFloatOr("Qz", 0.0F), input.getFloatOr("Qw", 1.0F));
		entityData.set(ROTATION, q.isFinite() && q.lengthSquared() > 1.0E-4F ? q.normalize() : new Quaternionf());
		entityData.set(CRACK, vector(input, "Crack"));
		entityData.set(NOZZLE, vector(input, "Nozzle"));
		entityData.set(TOP, Direction.from3DDataValue(input.getIntOr("Top", Direction.UP.get3DDataValue())));
		spin.set(input.getFloatOr("SpinX", 0.0F), input.getFloatOr("SpinY", 0.0F), input.getFloatOr("SpinZ", 0.0F));
		if (!spin.isFinite() || spin.length() > MAX_SPIN) {
			spin.zero();
		}

		sync();
	}
}
