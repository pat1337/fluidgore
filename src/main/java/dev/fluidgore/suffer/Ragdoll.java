package dev.fluidgore.suffer;

import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * A dead animal as a small physical system, stepped once per client tick and interpolated for rendering.
 *
 * <ul>
 * <li><b>Translation</b>: a point mass with vanilla's gravity, drag and block collisions. Nothing is scripted, so a
 *     body that dies on a ledge falls off it and one that is hit keeps its knockback.</li>
 * <li><b>Heading</b>: a yaw with its own angular velocity, so turning is rate-limited and smooth.</li>
 * <li><b>Toppling</b>: an inverted pendulum. Upright is an unstable equilibrium, a small push makes it fall over
 *     under gravity, and it bounces a little when it hits the ground on its side.</li>
 * <li><b>Joints</b>: head and limbs are damped springs. They are never animated directly; they are kicked by
 *     impacts, by the body rotating and by spasms, and then swing and settle on their own.</li>
 * </ul>
 */
public final class Ragdoll {
	public static final int HEAD_X = 0;
	public static final int HEAD_Y = 1;
	public static final int HEAD_Z = 2;
	/** x rotation of right arm, left arm, right leg, left leg; the matching z rotations follow at {@link #LIMB_Z}. */
	public static final int LIMB_X = 3;
	public static final int LIMB_Z = 7;
	public static final int JOINTS = 11;

	private static final double GRAVITY = 0.08;
	private static final double AIR_DRAG = 0.98;
	private static final double AIR_FRICTION = 0.91;
	private static final double GROUND_FRICTION = 0.546;
	private static final float TOPPLE_TORQUE = 0.05F;
	private static final float JOINT_STIFFNESS = 0.16F;
	private static final float JOINT_DAMPING = 0.14F;
	private static final float JOINT_LIMIT = 1.3F;

	double x, y, z;
	double px, py, pz;
	double vx, vy, vz;
	float yaw, pyaw, yawV;
	/** Sideways lean in radians: 0 upright, +-PI/2 lying on a side. */
	float roll, proll, rollV;
	boolean onGround;
	/** Hit a block sideways during the last step. */
	boolean blocked;

	private final float[] joint = new float[JOINTS];
	private final float[] jointO = new float[JOINTS];
	private final float[] jointV = new float[JOINTS];

	Ragdoll(LivingEntity entity) {
		this.x = this.px = entity.getX();
		this.y = this.py = entity.getY();
		this.z = this.pz = entity.getZ();
		Vec3 motion = entity.getDeltaMovement();
		this.vx = motion.x;
		this.vy = motion.y;
		this.vz = motion.z;
		this.yaw = this.pyaw = entity.yBodyRot;
		this.onGround = entity.onGround();
	}

	double distanceToSqr(Ragdoll other) {
		double dx = x - other.x;
		double dy = y - other.y;
		double dz = z - other.z;
		return dx * dx + dy * dy + dz * dz;
	}

	void beginTick() {
		px = x;
		py = y;
		pz = z;
		pyaw = yaw;
		proll = roll;
		System.arraycopy(joint, 0, jointO, 0, JOINTS);
	}

	/**
	 * Advances translation by one tick. {@code driveSpeed} is a wanted horizontal speed along the heading (a reflex
	 * run); it only has effect while standing on something.
	 *
	 * @param box the body's collision box at its current position
	 */
	void move(ClientLevel level, AABB box, double driveSpeed) {
		if (driveSpeed > 0.0 && onGround) {
			float rad = yaw * Mth.DEG_TO_RAD;
			vx += (-Mth.sin(rad) * driveSpeed - vx) * 0.35;
			vz += (Mth.cos(rad) * driveSpeed - vz) * 0.35;
		}

		vy = (vy - GRAVITY) * AIR_DRAG;
		Vec3 wanted = new Vec3(vx, vy, vz);
		Vec3 moved = Entity.collideBoundingBox(CollisionContext.empty(), wanted, box, level, List.of());
		boolean hitX = Math.abs(moved.x - wanted.x) > 1.0E-7;
		boolean hitZ = Math.abs(moved.z - wanted.z) > 1.0E-7;
		boolean landed = wanted.y < 0.0 && moved.y > wanted.y;
		boolean bumped = wanted.y > 0.0 && moved.y < wanted.y;

		x += moved.x;
		y += moved.y;
		z += moved.z;
		blocked = hitX || hitZ;
		if (hitX) {
			vx = 0.0;
		}

		if (hitZ) {
			vz = 0.0;
		}

		if (landed) {
			float impact = (float) -vy;
			if (impact > 0.15F) {
				jolt(impact * 2.0F);
			}

			vy = 0.0;
		} else if (bumped) {
			vy = 0.0;
		}

		// Resting on the floor still presses down by one tick of gravity, so "landed" is true every tick.
		onGround = landed;
		double friction = onGround ? GROUND_FRICTION : AIR_FRICTION;
		vx *= friction;
		vz *= friction;
	}

	/** Throws the body along a direction (unit vector) at the given horizontal speed, with a hop so it leaves the ground. */
	void launch(Vec3 direction, double speed) {
		vx += direction.x * speed;
		vz += direction.z * speed;
		vy += 0.1 + speed * 0.25 + Math.max(0.0, direction.y) * speed;
		onGround = false;
	}

	/** Gives the body a push towards a side; {@code side} is +1 or -1 (the lean direction), so it will topple. */
	void topple(float side, float strength) {
		rollV += side * (0.03F + strength);
	}

	/**
	 * Advances the lean. Returns the speed of the impact if the body hit the ground this tick, otherwise 0, so the
	 * caller can add a thud.
	 */
	float tip() {
		rollV = (rollV + TOPPLE_TORQUE * Mth.sin(roll)) * 0.97F;
		roll += rollV;
		float limit = Mth.HALF_PI;
		if (Math.abs(roll) < limit) {
			return 0.0F;
		}

		float side = Math.signum(roll);
		float speed = rollV * side;
		roll = side * limit;
		if (speed > 0.04F) {
			rollV = -rollV * 0.28F;
			jolt(speed * 3.0F);
			return speed;
		}

		rollV = 0.0F;
		return 0.0F;
	}

	/** A sudden convulsion: every joint is flung, and a body on the ground may hop. */
	void spasm(RandomSource random, float strength) {
		for (int i = 0; i < JOINTS; i++) {
			jointV[i] += (random.nextFloat() - 0.5F) * strength * (i >= LIMB_X && i < LIMB_Z ? 1.6F : 0.9F);
		}

		if (onGround) {
			vy += strength * 0.05;
		}
	}

	/**
	 * A blow on one joint: -1 for the head, 0-3 for the limbs in {@code ModelParts.LIMBS} order. The struck part
	 * swings away and the rest of the body twitches a little with it.
	 */
	void strikeJoint(int slot, float strength, RandomSource random) {
		if (slot < 0) {
			jointV[HEAD_X] += (random.nextFloat() - 0.3F) * strength;
			jointV[HEAD_Y] += (random.nextFloat() - 0.5F) * strength;
		} else {
			jointV[LIMB_X + slot] += (random.nextBoolean() ? 1.0F : -1.0F) * strength * 1.4F;
			jointV[LIMB_Z + slot] += (random.nextFloat() - 0.5F) * strength * 0.6F;
		}

		spasm(random, strength * 0.25F);
	}

	/** Passes an impact or sudden movement of the whole body on to the joints, which then swing on their own. */
	private void jolt(float amount) {
		for (int i = LIMB_X; i < LIMB_X + 4; i++) {
			jointV[i] += amount * (i % 2 == 0 ? 0.35F : -0.35F);
		}

		jointV[HEAD_X] += amount * 0.4F;
	}

	/** Steps the spring joints, driven by how the body is turning. */
	void stepJoints() {
		float lag = -(roll - proll) * 1.2F;
		jointV[HEAD_Z] += lag;
		jointV[HEAD_Y] += -(yaw - pyaw) * Mth.DEG_TO_RAD * 0.8F;
		for (int i = 0; i < 4; i++) {
			jointV[LIMB_Z + i] += lag * (i % 2 == 0 ? 1.0F : -1.0F);
		}

		for (int i = 0; i < JOINTS; i++) {
			jointV[i] -= JOINT_STIFFNESS * joint[i] + JOINT_DAMPING * jointV[i];
			joint[i] = Mth.clamp(joint[i] + jointV[i], -JOINT_LIMIT, JOINT_LIMIT);
		}
	}

	public double x(float t) {
		return Mth.lerp(t, px, x);
	}

	public double y(float t) {
		return Mth.lerp(t, py, y);
	}

	public double z(float t) {
		return Mth.lerp(t, pz, z);
	}

	public float yaw(float t) {
		return Mth.rotLerp(t, pyaw, yaw);
	}

	public float roll(float t) {
		return Mth.lerp(t, proll, roll);
	}

	/** Interpolated joint offsets in radians, indexed by the constants above. */
	public float[] joints(float t) {
		float[] out = new float[JOINTS];
		for (int i = 0; i < JOINTS; i++) {
			out[i] = Mth.lerp(t, jointO[i], joint[i]);
		}

		return out;
	}
}
