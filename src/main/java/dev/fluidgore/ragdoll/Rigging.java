package dev.fluidgore.ragdoll;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.physics.BodySlide;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.Ragdoll;
import dev.fluidgore.wound.Cremation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Hangs an articulated rig ({@link Rig}) on a corpse. The corpse's old one-piece ragdoll ({@link Ragdoll}) stays what
 * every other system reads (where the body is, its heading and lean, whether it lies on something) and pushes
 * (knockback, shoves, spasms): every tick the pushes it was given pass to the rig, the rig moves, and its result is
 * written back.
 */
public final class Rigging {
	/** Ragdoll velocities are blocks per tick; the rig's are blocks per second. */
	private static final double TICKS = 20.0;
	/** How far a kept body's rig may drift from where the server has the body before it is drawn back. */
	private static final double LEASH = 0.35;
	/** How much of a spasm on the old joints becomes spin on a segment (the twitch is drawn on top as well). */
	private static final float KICK = 0.25F;
	/** How hard each knee and hock is set folding as a body goes down, radians per second. */
	private static final float BUCKLE = 5.0F;
	/** The rig grips the ground with friction the old ragdoll never had, so a slide pulls it harder. */
	private static final double SLIDE = 2.5;
	/** Upward pushes below this (blocks per tick) are a spasm's hop, not a blow. */
	private static final double HOP = 0.05;
	/** How far below the rig a kept body may be on the server before the rig is put down there (blocks). */
	private static final double DROP = 0.75;
	/** How far legs folded under a trunk can hold it above what it lies on (blocks). */
	private static final double PROPPED = 0.5;

	/** Time spent stepping rigs and how many steps, for the performance test. */
	public static long nanos;
	public static int steps;
	public static int sleeping;

	private Rigging() {
	}

	/**
	 * A rig for a body, in the pose it is drawn in now; null if it has not been drawn yet or its model has no trunk.
	 * Its segments start with the body's own motion.
	 */
	public static @Nullable Rig build(LivingEntity entity, Ragdoll body) {
		EntityModel<?> model = ModelParts.drawn(entity.getId());
		if (model == null) {
			return null;
		}

		Skeleton skeleton = Skeleton.of(model, GoreConfig.get().ragdollKnees);
		ModelParts.Posed posed = skeleton != null ? ModelParts.pose(entity) : null;
		if (posed == null || posed.model() != model) {
			return null;
		}

		RigPose pose = RigPose.capture(entity, posed, skeleton, entity.position());
		if (pose == null) {
			return null;
		}

		Rig rig = Rig.of(pose, Cremation.kilograms(entity));
		rig.push(body.vx * TICKS, body.vy * TICKS, body.vz * TICKS);
		body.sentVx = body.vx;
		body.sentVy = body.vy;
		body.sentVz = body.vz;
		body.sentRollV = body.rollV;
		java.util.Arrays.fill(body.kicks, 0.0F);
		return rig;
	}

	/** Reused for the boxes of the bodies near the one being stepped. */
	private static final List<Rig.Block> NEAR = new ArrayList<>();

	/** Hands {@code c}'s rig the segments of every other rigged body close enough to touch it: they lie against each other. */
	private static void neighbours(Corpse c) {
		NEAR.clear();
		AABB reach = c.rig.bounds().inflate(0.5);
		for (Corpse other : GoreSystems.suffering().corpses()) {
			if (other != c && other.rig != null && other.rig.bounds().intersects(reach)) {
				NEAR.addAll(other.rig.blocks());
			}
		}

		c.rig.neighbours(NEAR);
	}

	/**
	 * One tick of a rigged corpse: what was done to the old ragdoll goes to the rig, the rig moves, and the ragdoll is
	 * set from it. Returns how hard the trunk hit the ground this tick, on the old ragdoll's scale (0 if it did not).
	 */
	public static float tick(ClientLevel level, Corpse c) {
		Rig rig = c.rig;
		Ragdoll body = c.body;
		float yaw = body.yaw;
		double dvx = body.vx - body.sentVx;
		double dvy = body.vy - body.sentVy;
		double dvz = body.vz - body.sentVz;
		if (rig.grounded && dvy > 0.0 && dvy < HOP) {
			// A twitch on the ground lifts the old ragdoll a little; it does not lift a whole carcass, whose kicking legs
			// would then walk it off across the floor.
			dvy = 0.0;
		}

		if (dvx * dvx + dvy * dvy + dvz * dvz > 1.0E-10) {
			rig.push(dvx * TICKS, dvy * TICKS, dvz * TICKS);
		}

		float roll = body.rollV - body.sentRollV;
		if (Math.abs(roll) > 1.0E-5F) {
			// Positive lean tips the body over onto its own right side: a turn about its backward-pointing axis.
			float yawRad = yaw * Mth.DEG_TO_RAD;
			roll(rig, new Vector3f(Mth.sin(yawRad), 0.0F, -Mth.cos(yawRad)).mul(roll * (float) TICKS));
		}

		rig.beginTick();
		kicks(rig, body.kicks);
		follow(rig, c);
		slide(level, rig, c);
		long start = System.nanoTime();
		if (rig.stepsNext()) {
			neighbours(c);
		}
		rig.lift = c.lift();
		rig.tick(level);
		nanos += System.nanoTime() - start;
		steps++;
		if (rig.asleep()) {
			sleeping++;
		}

		Vec3 centre = rig.centre();
		Vec3 velocity = rig.velocity();
		body.x = centre.x;
		body.z = centre.z;
		body.y = rig.bottom();
		body.vx = body.sentVx = velocity.x / TICKS;
		body.vy = body.sentVy = velocity.y / TICKS;
		body.vz = body.sentVz = velocity.z / TICKS;
		body.yaw = rig.yaw(yaw);
		body.roll = rig.roll(body.yaw);
		body.rollV = body.sentRollV = 0.0F;
		body.onGround = rig.grounded;
		boolean trunk = rig.impactBone == rig.skeleton.chest || rig.impactBone == rig.skeleton.hips;
		return trunk && rig.impact > 2.5F ? Math.min(0.8F, rig.impact * 0.03F) : 0.0F;
	}

	/** Starts the fall of a body dying the vanilla way: over onto {@code side} (+1 its right), as vanilla tips it. */
	public static void topple(Corpse c, float side, float strength) {
		Rig rig = c.rig;
		float yawRad = c.body.yaw * Mth.DEG_TO_RAD;
		Vec3 centre = rig.centre();
		// Over its feet, which stay where they stand, not about its middle, which would kick them out sideways.
		roll(rig, new Vector3f(Mth.sin(yawRad), 0.0F, -Mth.cos(yawRad)).mul(side * strength), new Vec3(centre.x, rig.bottom(), centre.z));
		// A dead leg is no column: it gives way at every knee and hock, each the way it bends.
		for (Skeleton.Joint joint : rig.skeleton.joints) {
			if (joint.hinge()) {
				Vector3f axis = rig.q[joint.parent()].transform(new Vector3f(joint.axisParent()));
				float way = Math.signum(joint.min() + joint.max());
				rig.w[joint.child()].add(new Vector3f(axis).mul(way * BUCKLE));
				rig.w[joint.parent()].add(new Vector3f(axis).mul(-way * BUCKLE * 0.3F));
			}
		}

		rig.wake();
	}


	/** Pushes a falling body further over onto {@code side} (+1 its right) at {@code strength} radians per second (its spin made up to that). */
	public static void lean(Corpse c, float side, float strength) {
		float yawRad = c.body.yaw * Mth.DEG_TO_RAD;
		Rig rig = c.rig;
		Vec3 centre = rig.centre();
		// On the ground it tips over what it lies on, not about its middle, which would skate its underside along.
		Vec3 pivot = rig.grounded ? new Vec3(centre.x, rig.trunkBottom(), centre.z) : centre;
		Vector3f axis = new Vector3f(Mth.sin(yawRad), 0.0F, -Mth.cos(yawRad)).mul(side);
		// Brought up to that rate, never past it: a body that cannot go over is not wound up until it flies.
		float missing = strength - rig.w[rig.skeleton.hips].dot(axis);
		if (missing > 0.0F) {
			roll(rig, axis.mul(missing), pivot);
		}
	}

	private static void roll(Rig rig, Vector3f omega) {
		roll(rig, omega, rig.centre());
	}

	/** Turns the whole body about {@code centre} at {@code omega} (radians per second). */
	private static void roll(Rig rig, Vector3f omega, Vec3 centre) {
		for (int i = 0; i < rig.n; i++) {
			Vector3f arm = new Vector3f((float) (rig.x[i] - centre.x), (float) (rig.y[i] - centre.y), (float) (rig.z[i] - centre.z));
			Vector3f v = new Vector3f(omega).cross(arm);
			rig.vx[i] += v.x;
			rig.vy[i] += v.y;
			rig.vz[i] += v.z;
			rig.w[i].add(omega);
		}

		rig.wake();
	}

	/** Spasms and blows on the old ragdoll's joints: spins on the head and the upper limbs. */
	private static void kicks(Rig rig, float[] kicks) {
		Skeleton s = rig.skeleton;
		if (s.head >= 0) {
			kick(rig, s.head, kicks[Ragdoll.HEAD_X], kicks[Ragdoll.HEAD_Y], kicks[Ragdoll.HEAD_Z]);
		}

		for (int i = 0; i < 4; i++) {
			if (s.limbs[i] >= 0) {
				kick(rig, s.limbs[i], kicks[Ragdoll.LIMB_X + i], 0.0F, kicks[Ragdoll.LIMB_Z + i]);
			}
		}

		java.util.Arrays.fill(kicks, 0.0F);
	}

	/** A kick about a bone's own axes as they are in the model (radians per tick, as the old joints took them). */
	private static void kick(Rig rig, int bone, float x, float y, float z) {
		if (x == 0.0F && y == 0.0F && z == 0.0F) {
			return;
		}


		Quaternionf rest = rig.skeleton.bones[bone].rest.getNormalizedRotation(new Quaternionf()).conjugate();
		Vector3f local = rest.transform(new Vector3f(x, y, z));
		Vector3f omega = rig.q[bone].transform(local).mul((float) TICKS * KICK);
		rig.spin(bone, omega);
	}

	/**
	 * A body the server keeps is where the server has it: if this one has drifted further than {@link #LEASH} from it
	 * across the ground, it is drawn back by the difference. Falling and folding stay the rig's own.
	 */
	private static void follow(Rig rig, Corpse c) {
		if (!c.held || c.unloaded) {
			return;
		}

		LivingEntity entity = c.entity;
		Vec3 centre = rig.centre();
		double dx = entity.getX() - centre.x;
		double dz = entity.getZ() - centre.z;
		double dist = Math.sqrt(dx * dx + dz * dz);
		double dy = entity.getY() - rig.bottom();
		// The server took it down a step or a ledge the rig still lies on: it goes there (the segments then settle). In
		// water each floats or sinks on its own: the server's box is not the shape the rig lies in, so it floats at
		// another height.
		if (dist > 8.0 || dy < -DROP && !rig.inWater) {
			rig.shift(dx, dy, dz);
		} else if (dist > LEASH) {
			double k = (dist - LEASH) / dist * 0.5;
			rig.shift(dx * k, 0.0, dz * k);
		}
	}

	/**
	 * A body only this game has, lying over a drop (stairs, a ledge), slumps over the edge and goes down it, as a kept
	 * body is taken down by the server (see {@link BodySlide}); the segments then fall and fold on their own.
	 */
	private static void slide(ClientLevel level, Rig rig, Corpse c) {
		if (c.held && !c.unloaded || !GoreConfig.get().bodySlide || !rig.grounded && !rig.asleep()) {
			return;
		}

		LivingEntity entity = c.entity;
		Vec3 centre = rig.centre();
		double halfWidth = entity.getBbWidth() * 0.5;
		// Felt for where it really touches (its lowest point), and where the trunk lies, so a leg hanging down the next
		// step does not hide the edge the trunk is over; a trunk propped on folded legs is felt for at their feet.
		Vec3 pull = Vec3.ZERO;
		for (double bottom : new double[] {rig.bottom(), Math.max(rig.bottom(), rig.trunkBottom() - PROPPED)}) {
			AABB box = new AABB(centre.x - halfWidth, bottom, centre.z - halfWidth, centre.x + halfWidth, bottom + entity.getBbHeight(), centre.z + halfWidth);
			Vec3 felt = BodySlide.pull(level, box);
			if (felt.lengthSqr() > pull.lengthSqr()) {
				pull = felt;
			}
		}

		double target = pull.length() * TICKS * SLIDE;
		if (target > 1.0E-4) {
			// Brought up to a slump's pace over the edge, never past it: pulled again every tick, a body on a lip
			// would otherwise be flung off a cliff.
			double ux = pull.x / pull.length();
			double uz = pull.z / pull.length();
			Vec3 v = rig.velocity();
			double missing = target - (v.x * ux + v.z * uz);
			if (missing > 0.0) {
				rig.push(ux * missing, 0.0, uz * missing);
			}
		}
	}

	/** Draws every segment's box and every joint, for the debug view (inside a client tick). */
	public static void debug(Rig rig) {
		double[][] corners = new double[8][3];
		int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int i = 0; i < rig.n; i++) {
			rig.corners(i, corners);
			int color = rig.skeleton.bones[i].part == null ? 0xFFFFD040 : rig.asleep() ? 0xFF4080FF : 0xFF40FF60;
			for (int[] e : edges) {
				Gizmos.line(new Vec3(corners[e[0]][0], corners[e[0]][1], corners[e[0]][2]), new Vec3(corners[e[1]][0], corners[e[1]][1], corners[e[1]][2]),
						color);
			}
		}

		for (int j = 0; j < rig.skeleton.joints.length; j++) {
			Gizmos.point(rig.joint(j), rig.skeleton.joints[j].hinge() ? 0xFFFF4040 : 0xFFFFFFFF, 4.0F);
		}
	}
}
