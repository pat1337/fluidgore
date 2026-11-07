package dev.fluidgore.ragdoll;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.net.FallSync;
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
	/** Further than this from the server's body (blocks), a rig is out of step and is put there at once. */
	private static final double SNAP = 3.0;
	/** How fast a rig is drawn after the server's body: blocks per second for each block beyond the leash, and at most. */
	private static final double PULL = 3.0;
	private static final double PULL_MAX = 1.5;
	/** How fast a rig the server took down a step slumps over the edge, blocks per second. */
	private static final double SLUMP = 1.0;
	/** Ticks a rig is drawn without getting closer before it is left where it lies. */
	private static final int STALL_TICKS = 40;
	/** How much of a spasm on the old joints becomes spin on a segment (the twitch is drawn on top as well). */
	private static final float KICK = 0.25F;
	/** How hard each knee and hock is set folding as a body goes down, radians per second. */
	private static final float BUCKLE = 5.0F;
	/** The rig grips the ground with friction the old ragdoll never had, so a slide pulls it harder. */
	private static final double SLIDE = 2.5;
	/** Upward pushes below this (blocks per tick) are a spasm's hop, not a blow. */
	private static final double HOP = 0.05;
	/** How far below the rig a kept body may be on the server before the rig goes down after it (blocks). */
	private static final double DROP = 0.75;
	/** How far legs folded under a trunk can hold it above what it lies on (blocks). */
	private static final double PROPPED = 0.5;
	/** Ticks between reports of where a kept body this game leads has got to, while it moves. */
	private static final int REPORT_TICKS = 4;
	/**
	 * How far the server's body may be from the line through this game's last reports and still be where this game put
	 * it (blocks): the server is a tick or two behind, and every game draws a body between the places it is sent.
	 */
	private static final double REPORTED = 0.3;

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

		Rig rig = Rig.of(pose, Cremation.kilograms(entity), Weights.of(skeleton, entity));
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
		follow(level, rig, c);
		slide(level, rig, c);
		long start = System.nanoTime();
		if (rig.stepsNext()) {
			neighbours(c);
		}
		rig.lift = c.lift();
		rig.tick(level);
		lead(rig, c);
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
	 * A body the server keeps is where the server has it. When the server moves it (a shove, a slump down a step) the rig
	 * goes after it through the world: drawn across the ground, over the edge and down, every segment meeting what is in
	 * the way. Moving it by hand would put it into steps and through fences, and lose which side of a wall it came from.
	 * Only a body out of step by more than {@link #SNAP} (moved while this game was not looking) is put there at once.
	 * Falling and folding stay the rig's own. In water each floats or sinks on its own: the server's box is not the shape
	 * the rig lies in, so it floats at another height.
	 */
	private static void follow(ClientLevel level, Rig rig, Corpse c) {
		rig.pulled = false;
		if (!c.held || c.unloaded) {
			return;
		}

		LivingEntity entity = c.entity;
		if (FallSync.leads(entity.getId()) && rig.onReported(entity.getX(), entity.getZ(), REPORTED)) {
			// The server has the body where this game put it: this ragdoll is the one that moves it (see lead).
			return;
		}

		Vec3 centre = rig.centre();
		double dx = entity.getX() - centre.x;
		double dz = entity.getZ() - centre.z;
		double dist = Math.sqrt(dx * dx + dz * dz);
		double dy = entity.getY() - rig.bottom();
		if (dist > SNAP || dy < -SNAP && !rig.inWater) {
			rig.shift(dx, dy, dz);
			rig.leashStall = 0;
			rig.pulled = true;
			return;
		}

		// Somewhere new to go: try again even if it could not get to the last place.
		if (!(Math.abs(entity.getX() - rig.leashX) + Math.abs(entity.getZ() - rig.leashZ) <= 0.05)) {
			rig.leashX = entity.getX();
			rig.leashZ = entity.getZ();
			rig.leashBest = Double.MAX_VALUE;
			rig.leashStall = 0;
		}

		boolean below = dy < -DROP && !rig.inWater;
		// Somewhere else than this ragdoll: the server moved the body (a shove, a blow), and this one goes after it.
		rig.pulled = dist > LEASH || below;
		// Not while it falls: it lands first. A body in water drifts after it as it floats.
		if (dist <= LEASH && !below || !rig.grounded && !rig.asleep() && !rig.inWater) {
			return;
		}

		// Getting no closer (wedged, or the server's box went where the body does not fit): it is left where it lies.
		if (dist < rig.leashBest - 0.01) {
			rig.leashBest = dist;
			rig.leashStall = 0;
		} else if (++rig.leashStall > STALL_TICKS) {
			return;
		}

		double ux;
		double uz;
		double target;
		Vec3 down = below ? BodySlide.pull(level, box(entity, centre, rig.bottom())) : Vec3.ZERO;
		if (below && down.horizontalDistanceSqr() > 1.0E-8) {
			// The server took it down a step it still lies on: over the edge the way down goes.
			double d = down.horizontalDistance();
			ux = down.x / d;
			uz = down.z / d;
			target = SLUMP;
		} else if (dist > 1.0E-3) {
			ux = dx / dist;
			uz = dz / dist;
			target = Math.min(PULL_MAX, Math.max(dist - LEASH, below ? SLUMP / PULL : 0.0) * PULL);
		} else {
			return;
		}

		// Brought up to that pace, never past it.
		Vec3 v = rig.velocity();
		double missing = target - (v.x * ux + v.z * uz);
		if (missing > 0.0) {
			rig.push(ux * missing, 0.0, uz * missing);
		}
	}

	/**
	 * A kept body whose fall this game leads ({@link FallSync}, see {@code server/FallLeads}): while its ragdoll moves on
	 * its own, where it has got to is sent to the server a few times a second, and once more when it comes to rest, and
	 * the server moves its body there. Not while the ragdoll is drawn after the server's body (the server moved it), nor
	 * in water, where the server floats its body itself.
	 */
	private static void lead(Rig rig, Corpse c) {
		int id = c.entity.getId();
		if (!c.held || c.unloaded || rig.pulled || rig.inWater || !FallSync.leads(id)) {
			return;
		}

		boolean resting = rig.asleep();
		if (!resting) {
			rig.reportedRest = false;
		}

		boolean first = !rig.hasReported();
		if (!first && (resting ? rig.reportedRest : ++rig.reportTicks < REPORT_TICKS)) {
			return;
		}

		Vec3 centre = rig.centre();
		if (FallSync.report(id, centre.x, rig.bottom(), centre.z, resting)) {
			if (first) {
				// Where the server had it until now: it is drawn from there to the first report.
				rig.reported(c.entity.getX(), c.entity.getZ());
			}

			rig.reported(centre.x, centre.z);
			rig.reportTicks = 0;
			rig.reportedRest = resting;
		}
	}

	/** The body's box (as wide and tall as the entity) round {@code centre}, standing at {@code bottom}. */
	private static AABB box(LivingEntity entity, Vec3 centre, double bottom) {
		double halfWidth = entity.getBbWidth() * 0.5;
		return new AABB(centre.x - halfWidth, bottom, centre.z - halfWidth, centre.x + halfWidth, bottom + entity.getBbHeight(), centre.z + halfWidth);
	}

	/**
	 * A body only this game has, lying over a drop (stairs, a ledge), slumps over the edge and goes down it, as a kept
	 * body is taken down by the server (see {@link BodySlide}); the segments then fall and fold on their own. A kept body
	 * whose fall this game leads slumps here too: the server leaves its moving to this game (see {@link #lead}).
	 */
	private static void slide(ClientLevel level, Rig rig, Corpse c) {
		boolean kept = c.held && !c.unloaded;
		if (kept && !FallSync.leads(c.entity.getId()) || !GoreConfig.get().bodySlide || !rig.grounded && !rig.asleep()) {
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
