package dev.fluidgore.ragdoll;

import dev.fluidgore.physics.Buoyancy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A dead body as a chain of rigid segments ({@link Skeleton}), simulated with extended position-based dynamics (Müller
 * et al. 2020): every tick is cut into {@link #SUBSTEPS} small steps, each moving the segments under gravity, then
 * pulling them back together at their joints, inside their joints' limits and out of the blocks. Position-based
 * because it is unconditionally stable at Minecraft's 20 ticks, needs no tuning of springs, and handles long chains (a
 * leg on a hip on a spine) with one pass per substep.
 *
 * <p>Dead means no muscle: nothing holds a joint but its limits and a little friction in it, so a body folds the way
 * its joints let it, under its own weight (masses from the kilogram table, shared out by volume). A segment never moves
 * more than half its own thickness in a substep, so nothing tunnels through a wall. A body that has stopped moving
 * sleeps and costs nothing until something moves it.
 *
 * <p>World units: blocks, seconds. Positions are segment centres of mass; a frame origin is {@code x + q * frameOffset}.
 */
public final class Rig {
	public static final int SUBSTEPS = 8;
	private static final float TICK = 0.05F;
	private static final float H = TICK / SUBSTEPS;
	/** Vanilla's gravity for mobs, 0.08 blocks per tick per tick. */
	private static final float GRAVITY = 32.0F;
	private static final float STATIC_FRICTION = 0.75F;
	private static final float FRICTION = 0.55F;
	/** How fast relative turning in a joint dies away: the friction of dead tissue, per second. */
	private static final float JOINT_FRICTION = 15.0F;
	private static final float ANGULAR_DAMPING = 0.6F;
	/** Spin kept per contact per substep: rolling resistance of a soft body on the ground. */
	private static final float ROLL_KEEP = 0.97F;
	private static final float SLEEP_SPEED = 0.2F;
	private static final float SLEEP_SPIN = 0.6F;
	private static final int SLEEP_TICKS = 15;
	/** Below this (blocks and radians per second, net over a tick) a body on the ground is settling. */
	private static final float CALM_SPEED = 1.2F;
	private static final float CALM_SPIN = 2.5F;
	/** Ticks of settling before it sleeps regardless. */
	private static final int CALM_TICKS = 40;
	private static final float MARGIN = 0.002F;
	/** How fast water takes up a segment's motion through it, per second, all of it under. */
	private static final float WATER_DRAG = 6.0F;
	/** Net lift or weight, as a share of its own weight, that a body asleep in water stays asleep with. */
	private static final float AFLOAT = 0.03F;
	/** Share of a stiff leg's bend taken out per pass at full stiffness: enough to hold an upper leg up against its weight. */
	private static final float STIFF_DRIVE = 0.2F;
	/**
	 * Ticks a stiffening leg is driven straight before it sets where it got to. Held there, it no longer pushes against
	 * the floor or a body beside it that stopped it short, so the body can come to rest and sleep.
	 */
	private static final int STRAIGHTEN_TICKS = 20;

	public final Skeleton skeleton;
	public final int n;
	public final float scale;
	public final float kilograms;

	final double[] x;
	final double[] y;
	final double[] z;
	private final double[] lx;
	private final double[] ly;
	private final double[] lz;
	private final double[] tx;
	private final double[] ty;
	private final double[] tz;
	final double[] vx;
	final double[] vy;
	final double[] vz;
	final Quaternionf[] q;
	private final Quaternionf[] lq;
	private final Quaternionf[] tq;
	final Vector3f[] w;
	final float[] invMass;
	private final Vector3f[] invInertia;
	/** Frame origin relative to the centre of mass, in the frame. */
	final Vector3f[] frameOffset;
	final Vector3f[] half;
	private final float[] thick;
	private final float[][] samples;
	private final Vector3f[] anchorParent;
	private final Vector3f[] anchorChild;
	/** How each joint's child sits on its parent as the model stands: the straight leg a stiff one goes back to. */
	private final Quaternionf[] restTurn;
	/** The joint is in a leg (the hip or shoulder, a knee or hock): what {@link #stiff} holds. */
	private final boolean[] legJoint;
	/** The turn each leg joint set at, after {@link #STRAIGHTEN_TICKS} of straightening: held from then on. */
	private final Quaternionf[] heldTurn;
	/** Ticks the legs have been stiff. */
	private int stiffTicks;
	/**
	 * How stiff the legs are, 0 (limp, as the rest of a dead body) to 1: a dead animal on its side lies with its legs
	 * locked straight out from the trunk, as a stunned or freshly bled carcass does, not folded under it. Set by its owner.
	 */
	private float stiff;

	private final List<List<AABB>> near = new ArrayList<>();
	/** Other bodies' segments within reach of each segment of this one, this tick (see {@link #neighbours}). */
	private final List<List<Block>> others = new ArrayList<>();
	private boolean hasOthers;
	/** This body's own segments as other bodies see them (see {@link #blocks}), rebuilt after it moves. */
	private final List<Block> blocks = new ArrayList<>();
	private boolean blocksStale = true;
	private static final float[] SCRATCH_DEPTH = new float[1];
	/** Contacts of the current substep: body, arm (x, y, z), normal (x, y, z), depth. */
	private float[] contacts = new float[8 * 64];
	private int contactCount;
	private int still;
	private int calm;
	private boolean asleep;
	private int sleepTicks;
	/** Fastest a segment hit the ground this tick, blocks per second; and which. */
	public float impact;
	public int impactBone = -1;
	/** Something touched the ground this tick. */
	public boolean grounded;
	/** Fastest segment and fastest spin at the end of the last tick, blocks and radians per second. */
	public float lastSpeed;
	public float lastSpin;
	/** Lift against its weight when it is entirely under water ({@link Buoyancy#lift}), set by its owner each tick. */
	public float lift = Buoyancy.lift(0.0F);
	/** Some of it was in water this tick. */
	public boolean inWater;
	/** The share of each segment under water this tick. */
	private final float[] wet;
	/** The current it lies in, blocks per second. */
	private double currentX;
	private double currentZ;

	private final Vector3f r = new Vector3f();
	private final Vector3f t = new Vector3f();
	private final Vector3f u = new Vector3f();
	private final Quaternionf dq = new Quaternionf();

	private Rig(Skeleton skeleton, float scale, float kilograms) {
		this.skeleton = skeleton;
		this.n = skeleton.bones.length;
		this.scale = scale;
		this.kilograms = kilograms;
		x = new double[n];
		y = new double[n];
		z = new double[n];
		lx = new double[n];
		ly = new double[n];
		lz = new double[n];
		tx = new double[n];
		ty = new double[n];
		tz = new double[n];
		vx = new double[n];
		vy = new double[n];
		vz = new double[n];
		q = new Quaternionf[n];
		lq = new Quaternionf[n];
		tq = new Quaternionf[n];
		w = new Vector3f[n];
		invMass = new float[n];
		invInertia = new Vector3f[n];
		frameOffset = new Vector3f[n];
		half = new Vector3f[n];
		thick = new float[n];
		wet = new float[n];
		samples = new float[n][];
		float volume = 0.0F;
		for (Skeleton.Bone bone : skeleton.bones) {
			volume += bone.volume;
		}

		for (int i = 0; i < n; i++) {
			Skeleton.Bone bone = skeleton.bones[i];
			q[i] = new Quaternionf();
			lq[i] = new Quaternionf();
			tq[i] = new Quaternionf();
			w[i] = new Vector3f();
			Vector3f h = new Vector3f(bone.halfX(), bone.halfY(), bone.halfZ()).mul(scale).max(new Vector3f(0.02F));
			half[i] = h;
			thick[i] = 2.0F * Math.min(h.x, Math.min(h.y, h.z));
			frameOffset[i] = bone.centre().mul(scale).negate();
			float mass = Math.max(0.05F, kilograms * bone.volume / Math.max(1.0F, volume));
			invMass[i] = 1.0F / mass;
			float a = 2.0F * h.x;
			float b = 2.0F * h.y;
			float c = 2.0F * h.z;
			invInertia[i] = new Vector3f(12.0F / (mass * (b * b + c * c)), 12.0F / (mass * (a * a + c * c)), 12.0F / (mass * (a * a + b * b)));
			samples[i] = samples(h);
			near.add(new ArrayList<>());
			others.add(new ArrayList<>());
		}

		int joints = skeleton.joints.length;
		anchorParent = new Vector3f[joints];
		anchorChild = new Vector3f[joints];
		restTurn = new Quaternionf[joints];
		heldTurn = new Quaternionf[joints];
		legJoint = new boolean[joints];
		for (int j = 0; j < joints; j++) {
			Skeleton.Joint joint = skeleton.joints[j];
			anchorParent[j] = new Vector3f(joint.anchorParent()).mul(scale).add(frameOffset[joint.parent()]);
			anchorChild[j] = new Vector3f(joint.anchorChild()).mul(scale).add(frameOffset[joint.child()]);
			Quaternionf restA = skeleton.bones[joint.parent()].rest.getNormalizedRotation(new Quaternionf());
			Quaternionf restB = skeleton.bones[joint.child()].rest.getNormalizedRotation(new Quaternionf());
			restTurn[j] = restA.conjugate().mul(restB);
			heldTurn[j] = new Quaternionf(restTurn[j]);
			legJoint[j] = leg(skeleton, joint);
		}
	}

	/** A rig with its segments where {@code pose} has them, at rest (the caller sets their velocity). */
	public static Rig of(RigPose pose, float kilograms) {
		Rig rig = new Rig(pose.skeleton, pose.scale, kilograms);
		Vector3f c = new Vector3f();
		for (int i = 0; i < rig.n; i++) {
			Quaternionf turn = pose.turn(i);
			rig.q[i].set(turn);
			// The centre of mass is the frame origin less the turned offset.
			turn.transform(c.set(rig.frameOffset[i]).negate());
			rig.x[i] = pose.ox + pose.pos[3 * i] + c.x;
			rig.y[i] = pose.oy + pose.pos[3 * i + 1] + c.y;
			rig.z[i] = pose.oz + pose.pos[3 * i + 2] + c.z;
		}

		rig.beginTick();
		return rig;
	}

	/** Corners, edge middles and face middles of a box: where it can touch something. */
	private static float[] samples(Vector3f h) {
		List<float[]> points = new ArrayList<>();
		for (int i = -1; i <= 1; i++) {
			for (int j = -1; j <= 1; j++) {
				for (int k = -1; k <= 1; k++) {
					if (i != 0 || j != 0 || k != 0) {
						points.add(new float[] {i * h.x, j * h.y, k * h.z});
					}
				}
			}
		}

		float[] out = new float[points.size() * 3];
		for (int p = 0; p < points.size(); p++) {
			System.arraycopy(points.get(p), 0, out, 3 * p, 3);
		}

		return out;
	}

	public boolean asleep() {
		return asleep;
	}

	/** It will move or look at what is under it on its next tick: awake, or asleep and due its once-a-second check. */
	public boolean stepsNext() {
		return !asleep || (sleepTicks + 1) % 20 == 0;
	}

	public void wake() {
		asleep = false;
		still = 0;
		calm = 0;
	}


	/** Keeps where everything was at the start of this tick, for drawing between ticks. */
	public void beginTick() {
		for (int i = 0; i < n; i++) {
			tx[i] = x[i];
			ty[i] = y[i];
			tz[i] = z[i];
			tq[i].set(q[i]);
		}
	}

	/** One tick: {@link #SUBSTEPS} steps of motion, joints and contacts. */
	public void tick(BlockGetter level) {
		impact = 0.0F;
		impactBone = -1;
		grounded = false;
		if (asleep) {
			// Every second, check whether anything has moved under it.
			if (++sleepTicks % 20 != 0) {
				return;
			}

			gatherBlocks(level);
			soak(level);
			if (inWater) {
				// Floating, or on the bottom: it stays until the water around it would move it (it swelled, it burst, the
				// current took it, the water went).
				float net = buoyancy();
				if (net > AFLOAT || net < -AFLOAT && !supported() || currentX != 0.0 || currentZ != 0.0) {
					wake();
				} else {
					return;
				}
			} else if (!supported()) {
				wake();
			} else {
				return;
			}
		} else {
			gatherBlocks(level);
			soak(level);
		}

		setLegs();
		for (int step = 0; step < SUBSTEPS; step++) {
			integrate();
			for (int pass = 0; pass < 2; pass++) {
				solveJoints();
				stiffLegs();
				solveContacts();
			}

			// Once a substep is enough for bodies, which push out gently anyway (see BODY_PUSH).
			solveBodies();
			updateVelocities();
			contactVelocities();
			jointFriction();
		}

		// It has moved: other bodies see its segments where they are now.
		blocksStale = true;

		// At rest by how far it got this tick, not how fast it is going: contacts and joints trading tiny corrections
		// show as speed but go nowhere.
		float fastest = 0.0F;
		float spin = 0.0F;
		for (int i = 0; i < n; i++) {
			fastest = (float) Math.max(fastest, Math.sqrt(Math.pow(x[i] - tx[i], 2) + Math.pow(y[i] - ty[i], 2) + Math.pow(z[i] - tz[i], 2)) / TICK);
			float turned = 2.0F * (float) Math.acos(Math.min(1.0F, Math.abs(q[i].dot(tq[i])))) / TICK;
			if (turned > spin) {
				spin = turned;
			}
		}

		lastSpeed = fastest;
		lastSpin = spin;
		// Nearly still: a body lying on the ground settles. What is left is the solver trading corrections between
		// the ground and the joints; it is damped away harder the longer the body has lain there, then it sleeps.
		calm = fastest < CALM_SPEED && spin < CALM_SPIN && grounded ? calm + 1 : 0;
		if (calm > 0) {
			float keep = 1.0F - Math.min(0.6F, calm / (float) CALM_TICKS * 0.6F);
			for (int i = 0; i < n; i++) {
				vx[i] *= keep;
				vy[i] *= keep;
				vz[i] *= keep;
				w[i].mul(keep);
			}
		}

		if (fastest < SLEEP_SPEED && spin < SLEEP_SPIN || calm >= CALM_TICKS) {
			if (++still >= SLEEP_TICKS || calm >= CALM_TICKS) {
				asleep = true;
				sleepTicks = 0;
				for (int i = 0; i < n; i++) {
					vx[i] = vy[i] = vz[i] = 0.0;
					w[i].zero();
				}
			}
		} else {
			still = 0;
		}
	}

	private void integrate() {
		for (int i = 0; i < n; i++) {
			lx[i] = x[i];
			ly[i] = y[i];
			lz[i] = z[i];
			lq[i].set(q[i]);
			float under = wet[i];
			vy[i] -= GRAVITY * H * (1.0F - lift * under);
			if (under > 0.0F) {
				// Water holds it back, and carries it along with the current.
				double keep = 1.0 / (1.0 + WATER_DRAG * H * under);
				vx[i] = currentX + (vx[i] - currentX) * keep;
				vy[i] *= keep;
				vz[i] = currentZ + (vz[i] - currentZ) * keep;
				w[i].mul((float) keep);
			}

			// Never more than half its own thickness in a step: nothing goes through a wall between two checks.
			double speed = Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]);
			double limit = 0.5 * thick[i] / H;
			if (speed > limit) {
				double k = limit / speed;
				vx[i] *= k;
				vy[i] *= k;
				vz[i] *= k;
			}

			float reach = Math.max(half[i].x, Math.max(half[i].y, half[i].z));
			float spin = w[i].length();
			if (spin * reach > limit) {
				w[i].mul((float) (limit / (spin * reach)));
			}

			x[i] += vx[i] * H;
			y[i] += vy[i] * H;
			z[i] += vz[i] * H;
			rotate(q[i], w[i], H);
		}
	}

	/** {@code q += h/2 * (w, 0) * q}, normalised. */
	private void rotate(Quaternionf quat, Vector3f omega, float h) {
		dq.set(omega.x * 0.5F * h, omega.y * 0.5F * h, omega.z * 0.5F * h, 0.0F).mul(quat);
		quat.set(quat.x + dq.x, quat.y + dq.y, quat.z + dq.z, quat.w + dq.w).normalize();
	}

	private void updateVelocities() {
		for (int i = 0; i < n; i++) {
			vx[i] = (x[i] - lx[i]) / H;
			vy[i] = (y[i] - ly[i]) / H;
			vz[i] = (z[i] - lz[i]) / H;
			Quaternionf d = dq.set(q[i]).mul(new Quaternionf(lq[i]).conjugate());
			float sign = d.w >= 0.0F ? 2.0F : -2.0F;
			w[i].set(d.x * sign / H, d.y * sign / H, d.z * sign / H);
			w[i].mul((float) Math.exp(-ANGULAR_DAMPING * H));
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Joints

	private void solveJoints() {
		Skeleton.Joint[] joints = skeleton.joints;
		for (int j = 0; j < joints.length; j++) {
			Skeleton.Joint joint = joints[j];
			int a = joint.parent();
			int b = joint.child();
			// The two ends of the joint come together.
			Vector3f ra = q[a].transform(new Vector3f(anchorParent[j]));
			Vector3f rb = q[b].transform(new Vector3f(anchorChild[j]));
			double dx = x[b] + rb.x - (x[a] + ra.x);
			double dy = y[b] + rb.y - (y[a] + ra.y);
			double dz = z[b] + rb.z - (z[a] + ra.z);
			double c = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (c > 1.0E-6) {
				Vector3f normal = new Vector3f((float) (dx / c), (float) (dy / c), (float) (dz / c));
				positional(a, ra, b, rb, normal, (float) c);
			}

			Vector3f axisA = q[a].transform(new Vector3f(joint.axisParent()));
			Vector3f axisB = q[b].transform(new Vector3f(joint.axisChild()));
			if (joint.hinge()) {
				// A hinge turns about its axis only: both ends' axes stay together.
				Vector3f across = new Vector3f(axisA).cross(axisB);
				angular(a, b, across);
				axisA = q[a].transform(new Vector3f(joint.axisParent()));
				Vector3f refA = q[a].transform(new Vector3f(joint.refParent()));
				Vector3f refB = q[b].transform(new Vector3f(joint.refChild()));
				limit(a, b, axisA, refA, refB, joint.min(), joint.max());
			} else {
				float cos = Mth.clamp(axisA.dot(axisB), -1.0F, 1.0F);
				float angle = (float) Math.acos(cos);
				if (angle > joint.swing()) {
					Vector3f n = new Vector3f(axisA).cross(axisB);
					if (n.lengthSquared() > 1.0E-10F) {
						angular(a, b, n.normalize().mul(angle - joint.swing()));
					}
				}

				axisA = q[a].transform(new Vector3f(joint.axisParent()));
				axisB = q[b].transform(new Vector3f(joint.axisChild()));
				Vector3f mid = new Vector3f(axisA).add(axisB);
				if (mid.lengthSquared() > 1.0E-8F) {
					mid.normalize();
					Vector3f refA = q[a].transform(new Vector3f(joint.refParent()));
					Vector3f refB = q[b].transform(new Vector3f(joint.refChild()));
					refA.sub(new Vector3f(mid).mul(mid.dot(refA)));
					refB.sub(new Vector3f(mid).mul(mid.dot(refB)));
					if (refA.lengthSquared() > 1.0E-8F && refB.lengthSquared() > 1.0E-8F) {
						limit(a, b, mid, refA.normalize(), refB.normalize(), joint.min(), joint.max());
					}
				}
			}
		}
	}

	/** A joint in a leg: one whose child is a limb's top segment, or a knee or hock below it. */
	private static boolean leg(Skeleton skeleton, Skeleton.Joint joint) {
		if (joint.hinge()) {
			return true;
		}

		for (int limb : skeleton.limbs) {
			if (limb == joint.child()) {
				return true;
			}
		}

		return false;
	}

	/** How stiff the legs are from now on, 0..1 (see {@link #stiff}). Stiffening a body at rest wakes it, so its legs straighten. */
	public void stiffen(float amount) {
		amount = Mth.clamp(amount, 0.0F, 1.0F);
		if (amount > stiff) {
			wake();
		}

		stiff = amount;
	}

	public float stiffness() {
		return stiff;
	}

	/** How far the most bent leg joint is from straight, radians: for tests. */
	public float legBend() {
		float most = 0.0F;
		for (int j = 0; j < skeleton.joints.length; j++) {
			if (legJoint[j]) {
				Quaternionf rel = new Quaternionf(q[skeleton.joints[j].parent()]).conjugate().mul(q[skeleton.joints[j].child()]);
				Quaternionf off = rel.mul(new Quaternionf(restTurn[j]).conjugate());
				most = Math.max(most, 2.0F * (float) Math.acos(Math.min(1.0F, Math.abs(off.w))));
			}
		}

		return most;
	}

	/** Counts how long the legs have been stiff, and sets them where they got to once they have had time to straighten. */
	private void setLegs() {
		if (stiff <= 0.0F) {
			stiffTicks = 0;
			return;
		}

		if (++stiffTicks == STRAIGHTEN_TICKS) {
			Skeleton.Joint[] joints = skeleton.joints;
			for (int j = 0; j < joints.length; j++) {
				if (legJoint[j]) {
					heldTurn[j].set(q[joints[j].parent()]).conjugate().mul(q[joints[j].child()]);
				}
			}
		}
	}

	/**
	 * Turns each stiff leg joint part of the way back to straight, the trunk taking the other end of it; once set (see
	 * {@link #setLegs}), back to where it set.
	 */
	private void stiffLegs() {
		if (stiff <= 0.0F) {
			return;
		}

		float share = STIFF_DRIVE * stiff;
		Skeleton.Joint[] joints = skeleton.joints;
		for (int j = 0; j < joints.length; j++) {
			if (!legJoint[j]) {
				continue;
			}

			int a = joints[j].parent();
			int b = joints[j].child();
			// The turn from where the child should be on its parent (straight, then held as it set) to where it is.
			Quaternionf wanted = new Quaternionf(q[a]).mul(stiffTicks < STRAIGHTEN_TICKS ? restTurn[j] : heldTurn[j]);
			Quaternionf off = new Quaternionf(q[b]).mul(wanted.conjugate());
			float sign = off.w >= 0.0F ? 2.0F : -2.0F;
			angular(a, b, new Vector3f(off.x * sign * share, off.y * sign * share, off.z * sign * share));
		}
	}

	/** Keeps the turn of {@code refB} from {@code refA} about {@code axis} within {@code min..max}. */
	private void limit(int a, int b, Vector3f axis, Vector3f refA, Vector3f refB, float min, float max) {
		Vector3f cross = new Vector3f(refA).cross(refB);
		float angle = (float) Math.atan2(cross.dot(axis), refA.dot(refB));
		float over = angle < min ? angle - min : angle > max ? angle - max : 0.0F;
		if (over != 0.0F) {
			angular(a, b, new Vector3f(axis).mul(over));
		}
	}

	/**
	 * Turns {@code a} by {@code +error} and {@code b} by {@code -error} between them, shared by how hard each is to turn
	 * about that axis: what closes a turn of {@code |error|} from a towards b.
	 */
	private void angular(int a, int b, Vector3f error) {
		float angle = error.length();
		if (angle < 1.0E-7F) {
			return;
		}

		Vector3f n = new Vector3f(error).div(angle);
		float wa = inverseInertia(a, n);
		float wb = inverseInertia(b, n);
		float sum = wa + wb;
		if (sum <= 0.0F) {
			return;
		}

		float lambda = angle / sum;
		applyRotation(a, n, lambda);
		applyRotation(b, n, -lambda);
	}

	/** Moves a's point at {@code ra} along {@code normal} and b's at {@code rb} against it until they meet: {@code c} apart now. */
	private void positional(int a, Vector3f ra, int b, Vector3f rb, Vector3f normal, float c) {
		float wa = generalized(a, ra, normal);
		float wb = generalized(b, rb, normal);
		float sum = wa + wb;
		if (sum <= 0.0F) {
			return;
		}

		float lambda = c / sum;
		applyImpulse(a, ra, normal, lambda);
		applyImpulse(b, rb, normal, -lambda);
	}

	/** {@code n . I^-1 n} for body {@code i}, in world axes. */
	private float inverseInertia(int i, Vector3f n) {
		q[i].transformInverse(t.set(n));
		Vector3f k = invInertia[i];
		return t.x * t.x * k.x + t.y * t.y * k.y + t.z * t.z * k.z;
	}

	/** {@code 1/m + (r x n) . I^-1 (r x n)}: how easily a push at {@code r} along {@code n} moves the point. */
	private float generalized(int i, Vector3f arm, Vector3f n) {
		u.set(arm).cross(n);
		return invMass[i] + inverseInertia(i, u);
	}

	/** Moves body {@code i} by a positional impulse {@code lambda * n} at {@code arm}. */
	private void applyImpulse(int i, Vector3f arm, Vector3f n, float lambda) {
		float m = invMass[i] * lambda;
		x[i] += n.x * m;
		y[i] += n.y * m;
		z[i] += n.z * m;
		u.set(arm).cross(n).mul(lambda);
		applyRotationVector(i, u);
	}

	/** Turns body {@code i} by {@code I^-1 (lambda n)}. */
	private void applyRotation(int i, Vector3f n, float lambda) {
		applyRotationVector(i, u.set(n).mul(lambda));
	}

	private void applyRotationVector(int i, Vector3f torque) {
		q[i].transformInverse(t.set(torque));
		Vector3f k = invInertia[i];
		t.mul(k);
		q[i].transform(t);
		dq.set(t.x * 0.5F, t.y * 0.5F, t.z * 0.5F, 0.0F).mul(q[i]);
		q[i].set(q[i].x + dq.x, q[i].y + dq.y, q[i].z + dq.z, q[i].w + dq.w).normalize();
	}

	/** Dead tissue resists turning in a joint a little: relative spin dies away. */
	private void jointFriction() {
		float keep = (float) Math.exp(-JOINT_FRICTION * H);
		for (Skeleton.Joint joint : skeleton.joints) {
			int a = joint.parent();
			int b = joint.child();
			Vector3f rel = new Vector3f(w[b]).sub(w[a]);
			float spin = rel.length();
			if (spin < 1.0E-5F) {
				continue;
			}

			Vector3f n = rel.div(spin);
			float wa = inverseInertia(a, n);
			float wb = inverseInertia(b, n);
			if (wa + wb <= 0.0F) {
				continue;
			}

			float impulse = spin * (1.0F - keep) / (wa + wb);
			addSpin(a, n, impulse);
			addSpin(b, n, -impulse);
		}
	}

	private void addSpin(int i, Vector3f n, float impulse) {
		q[i].transformInverse(t.set(n).mul(impulse));
		t.mul(invInertia[i]);
		q[i].transform(t);
		w[i].add(t);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Contacts

	/** The block boxes near each segment this tick, from one look at the blocks round the whole body. */
	private void gatherBlocks(BlockGetter level) {
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		double[] reach = new double[n];
		for (int i = 0; i < n; i++) {
			double speed = Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]);
			reach[i] = half[i].length() + speed * TICK + GRAVITY * TICK * TICK + 0.1;
			minX = Math.min(minX, x[i] - reach[i]);
			minY = Math.min(minY, y[i] - reach[i]);
			minZ = Math.min(minZ, z[i] - reach[i]);
			maxX = Math.max(maxX, x[i] + reach[i]);
			maxY = Math.max(maxY, y[i] + reach[i]);
			maxZ = Math.max(maxZ, z[i] + reach[i]);
		}

		List<AABB> boxes = new ArrayList<>();
		if (maxX - minX < 64.0 && maxY - minY < 64.0 && maxZ - minZ < 64.0) {
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int bx = Mth.floor(minX); bx <= Mth.floor(maxX); bx++) {
				for (int by = Mth.floor(minY); by <= Mth.floor(maxY); by++) {
					for (int bz = Mth.floor(minZ); bz <= Mth.floor(maxZ); bz++) {
						pos.set(bx, by, bz);
						BlockState state = level.getBlockState(pos);
						if (state.isAir()) {
							continue;
						}

						VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
						if (!shape.isEmpty()) {
							for (AABB box : shape.toAabbs()) {
								boxes.add(box.move(bx, by, bz));
							}
						}
					}
				}
			}
		}

		for (int i = 0; i < n; i++) {
			List<AABB> list = near.get(i);
			list.clear();
			AABB around = new AABB(x[i] - reach[i], y[i] - reach[i], z[i] - reach[i], x[i] + reach[i], y[i] + reach[i], z[i] + reach[i]);
			for (AABB box : boxes) {
				if (box.intersects(around)) {
					list.add(box);
				}
			}
		}
	}

	/** True if something is still under the body: a sleeping body over a removed block wakes and falls. */
	/** How much of each segment is under water, and the current there. */
	private void soak(BlockGetter level) {
		inWater = false;
		for (int i = 0; i < n; i++) {
			// Half its height as it lies: its box's three half-sizes, turned, onto the vertical.
			Vector3f h = half[i];
			float ext = Math.abs(q[i].transform(r.set(h.x, 0.0F, 0.0F)).y) + Math.abs(q[i].transform(r.set(0.0F, h.y, 0.0F)).y)
					+ Math.abs(q[i].transform(r.set(0.0F, 0.0F, h.z)).y);
			wet[i] = Buoyancy.submerged(level, x[i], y[i] - ext, y[i] + ext, z[i]);
			inWater |= wet[i] > 0.0F;
		}

		currentX = 0.0;
		currentZ = 0.0;
		if (inWater) {
			Vec3 c = centre();
			Vec3 flow = Buoyancy.current(level, c.x, c.y, c.z);
			currentX = flow.x / TICK;
			currentZ = flow.z / TICK;
		}
	}

	/** Lift less weight in the water it lies in now, as a share of its weight: above 0 it rises, below it sinks. */
	private float buoyancy() {
		float held = 0.0F;
		float total = 0.0F;
		for (int i = 0; i < n; i++) {
			float mass = 1.0F / invMass[i];
			held += mass * lift * wet[i];
			total += mass;
		}

		return held / total - 1.0F;
	}

	private boolean supported() {
		for (int i = 0; i < n; i++) {
			float[] s = samples[i];
			for (int p = 0; p < s.length; p += 3) {
				q[i].transform(r.set(s[p], s[p + 1], s[p + 2]));
				double px = x[i] + r.x;
				double py = y[i] + r.y - 0.05;
				double pz = z[i] + r.z;
				for (AABB box : near.get(i)) {
					if (box.contains(px, py, pz)) {
						return true;
					}
				}

				if (inBody(others.get(i), px, py, pz, u, SCRATCH_DEPTH) != null) {
					// Lying on another body.
					return true;
				}
			}
		}

		return false;
	}

	private void solveContacts() {
		contactCount = 0;
		for (int i = 0; i < n; i++) {
			List<AABB> boxes = near.get(i);
			if (boxes.isEmpty()) {
				continue;
			}

			float[] s = samples[i];
			for (int p = 0; p < s.length; p += 3) {
				q[i].transform(r.set(s[p], s[p + 1], s[p + 2]));
				double px = x[i] + r.x;
				double py = y[i] + r.y;
				double pz = z[i] + r.z;
				AABB box = null;
				for (AABB candidate : boxes) {
					if (px > candidate.minX + MARGIN && px < candidate.maxX - MARGIN && py > candidate.minY + MARGIN && py < candidate.maxY - MARGIN
							&& pz > candidate.minZ + MARGIN && pz < candidate.maxZ - MARGIN) {
						box = candidate;
						break;
					}
				}

				if (box == null) {
					continue;
				}

				// Where the point was a step ago: it came in through the face it was outside of.
				lq[i].transform(t.set(s[p], s[p + 1], s[p + 2]));
				double ox = lx[i] + t.x;
				double oy = ly[i] + t.y;
				double oz = lz[i] + t.z;
				Vector3f normal = new Vector3f();
				float depth = exit(box, boxes, px, py, pz, ox, oy, oz, normal);
				if (depth <= 0.0F) {
					continue;
				}

				float speed = -(float) ((px - ox) * normal.x + (py - oy) * normal.y + (pz - oz) * normal.z) / H;
				Vector3f arm = new Vector3f(r);
				float wn = generalized(i, arm, normal);
				if (wn <= 0.0F) {
					continue;
				}

				applyImpulse(i, arm, normal, depth / wn);
				record(i, arm, normal, depth);
				if (normal.y > 0.5F) {
					grounded = true;
				}

				if (speed > impact) {
					impact = speed;
					impactBone = i;
				}

				// Friction: the point does not slide along the surface unless pushed harder than its weight holds it.
				double sx = px - ox;
				double sy = py - oy;
				double sz = pz - oz;
				double along = sx * normal.x + sy * normal.y + sz * normal.z;
				Vector3f slip = new Vector3f((float) (sx - normal.x * along), (float) (sy - normal.y * along), (float) (sz - normal.z * along));
				float slide = slip.length();
				if (slide > 1.0E-7F) {
					float hold = slide < STATIC_FRICTION * depth ? slide : Math.min(slide, FRICTION * depth);
					Vector3f back = slip.div(-slide);
					q[i].transform(r.set(s[p], s[p + 1], s[p + 2]));
					float wt = generalized(i, r, back);
					if (wt > 0.0F) {
						applyImpulse(i, new Vector3f(r), back, hold / wt);
					}
				}
			}
		}
	}

	/**
	 * Another body's segment, as a box turned with it: what this body's segments rest against instead of passing through
	 * (see {@link #neighbours}). {@code half} is its half size along its own axes, blocks.
	 */
	public record Block(double x, double y, double z, Quaternionf turn, Vector3f half) {
	}

	/** How far one substep pushes a point out of another body: two bodies dropped into each other part over a few ticks, not at once. */
	private static final float BODY_PUSH = 0.01F;
	/** Other bodies' boxes are a little smaller than their segments: a model part does not fill its box to the corners. */
	private static final float BODY_FILL = 0.9F;

	/** The segments of other bodies near this one, for this tick (see {@link #blocks}): each segment keeps those it could reach. */
	public void neighbours(List<Block> near) {
		hasOthers = false;
		for (int i = 0; i < n; i++) {
			List<Block> mine = others.get(i);
			mine.clear();
			float reach = half[i].length() + 0.25F;
			for (Block b : near) {
				double dx = b.x() - x[i];
				double dy = b.y() - y[i];
				double dz = b.z() - z[i];
				double within = reach + b.half().length();
				if (dx * dx + dy * dy + dz * dz < within * within) {
					mine.add(b);
				}
			}

			hasOthers |= !mine.isEmpty();
		}
	}

	/** This body's segments as boxes for other bodies to lie against, as of its last step. */
	public List<Block> blocks() {
		if (blocksStale) {
			blocks.clear();
			for (int i = 0; i < n; i++) {
				blocks.add(new Block(x[i], y[i], z[i], new Quaternionf(q[i]), new Vector3f(half[i]).mul(BODY_FILL)));
			}

			blocksStale = false;
		}

		return blocks;
	}

	/** The block of {@code boxes} that holds a point, or null; {@code normal} and the returned depth say how it gets out. */
	private @Nullable Block inBody(List<Block> boxes, double px, double py, double pz, Vector3f normal, float[] depth) {
		for (Block b : boxes) {
			b.turn().transformInverse(t.set((float) (px - b.x()), (float) (py - b.y()), (float) (pz - b.z())));
			float ox = b.half().x - Math.abs(t.x);
			float oy = b.half().y - Math.abs(t.y);
			float oz = b.half().z - Math.abs(t.z);
			if (ox <= 0.0F || oy <= 0.0F || oz <= 0.0F) {
				continue;
			}

			// Out through the nearest face of it.
			if (ox <= oy && ox <= oz) {
				normal.set(t.x < 0.0F ? -1.0F : 1.0F, 0.0F, 0.0F);
				depth[0] = ox;
			} else if (oy <= oz) {
				normal.set(0.0F, t.y < 0.0F ? -1.0F : 1.0F, 0.0F);
				depth[0] = oy;
			} else {
				normal.set(0.0F, 0.0F, t.z < 0.0F ? -1.0F : 1.0F);
				depth[0] = oz;
			}

			b.turn().transform(normal);
			return b;
		}

		return null;
	}

	/** Every point of every segment that is inside another body is moved out of it: bodies lie against each other. */
	private void solveBodies() {
		if (!hasOthers) {
			return;
		}

		Vector3f normal = new Vector3f();
		float[] depth = new float[1];
		for (int i = 0; i < n; i++) {
			List<Block> boxes = others.get(i);
			if (boxes.isEmpty()) {
				continue;
			}

			float[] s = samples[i];
			for (int p = 0; p < s.length; p += 3) {
				q[i].transform(r.set(s[p], s[p + 1], s[p + 2]));
				if (inBody(boxes, x[i] + r.x, y[i] + r.y, z[i] + r.z, normal, depth) == null) {
					continue;
				}

				Vector3f arm = new Vector3f(r);
				float wn = generalized(i, arm, normal);
				if (wn <= 0.0F) {
					continue;
				}

				float push = Math.min(depth[0], BODY_PUSH);
				applyImpulse(i, arm, normal, push / wn);
				record(i, arm, normal, push);
				if (normal.y > 0.5F) {
					grounded = true;
				}
			}
		}
	}

	private void record(int i, Vector3f arm, Vector3f normal, float depth) {
		if (8 * (contactCount + 1) > contacts.length) {
			contacts = java.util.Arrays.copyOf(contacts, contacts.length * 2);
		}

		int k = 8 * contactCount++;
		contacts[k] = i;
		contacts[k + 1] = arm.x;
		contacts[k + 2] = arm.y;
		contacts[k + 3] = arm.z;
		contacts[k + 4] = normal.x;
		contacts[k + 5] = normal.y;
		contacts[k + 6] = normal.z;
		contacts[k + 7] = depth;
	}

	/**
	 * After the positions, the motion at each contact: nothing keeps moving into the surface, and sliding along it is
	 * slowed by friction in proportion to how hard the contact pushed (Coulomb, as velocity).
	 */
	private void contactVelocities() {
		Vector3f arm = new Vector3f();
		Vector3f normal = new Vector3f();
		Vector3f vel = new Vector3f();
		for (int c = 0; c < contactCount; c++) {
			int k = 8 * c;
			int i = (int) contacts[k];
			arm.set(contacts[k + 1], contacts[k + 2], contacts[k + 3]);
			normal.set(contacts[k + 4], contacts[k + 5], contacts[k + 6]);
			float depth = contacts[k + 7];
			vel.set(w[i]).cross(arm).add((float) vx[i], (float) vy[i], (float) vz[i]);
			float vn = vel.dot(normal);
			if (vn < 0.0F) {
				velocityImpulse(i, arm, normal, -vn);
				vel.set(w[i]).cross(arm).add((float) vx[i], (float) vy[i], (float) vz[i]);
				vn = vel.dot(normal);
			}

			Vector3f tangent = new Vector3f(vel).sub(new Vector3f(normal).mul(vn));
			float slide = tangent.length();
			if (slide > 1.0E-6F) {
				float stop = Math.min(slide, FRICTION * depth / H + STATIC_FRICTION * GRAVITY * H);
				velocityImpulse(i, arm, tangent.div(-slide), stop);
			}

			// Flesh on the ground does not roll freely: each contact takes some of the segment's spin.
			w[i].mul(ROLL_KEEP);
		}
	}

	/** Changes the velocity of body {@code i}'s point at {@code arm} by {@code change} along unit {@code dir}. */
	private void velocityImpulse(int i, Vector3f arm, Vector3f dir, float change) {
		float wt = generalized(i, arm, dir);
		if (wt <= 0.0F) {
			return;
		}

		float j = change / wt;
		vx[i] += dir.x * j * invMass[i];
		vy[i] += dir.y * j * invMass[i];
		vz[i] += dir.z * j * invMass[i];
		Vector3f torque = new Vector3f(arm).cross(dir).mul(j);
		q[i].transformInverse(torque);
		torque.mul(invInertia[i]);
		q[i].transform(torque);
		w[i].add(torque);
	}

	/**
	 * How deep a point is in {@code box} and the way out
 ({@code normal}): through the face it came in by if it was
	 * outside last step, else the shallowest face that does not lead straight into a neighbouring block.
	 */
	private static float exit(AABB box, List<AABB> boxes, double px, double py, double pz, double ox, double oy, double oz, Vector3f normal) {
		float best = Float.MAX_VALUE;
		int bestFace = -1;
		for (int face = 0; face < 6; face++) {
			double depth = switch (face) {
				case 0 -> px - box.minX;
				case 1 -> box.maxX - px;
				case 2 -> py - box.minY;
				case 3 -> box.maxY - py;
				case 4 -> pz - box.minZ;
				default -> box.maxZ - pz;
			};
			boolean entered = switch (face) {
				case 0 -> ox <= box.minX;
				case 1 -> ox >= box.maxX;
				case 2 -> oy <= box.minY;
				case 3 -> oy >= box.maxY;
				case 4 -> oz <= box.minZ;
				default -> oz >= box.maxZ;
			};
			double ex = px + (face == 0 ? -depth - 1.0E-3 : face == 1 ? depth + 1.0E-3 : 0.0);
			double ey = py + (face == 2 ? -depth - 1.0E-3 : face == 3 ? depth + 1.0E-3 : 0.0);
			double ez = pz + (face == 4 ? -depth - 1.0E-3 : face == 5 ? depth + 1.0E-3 : 0.0);
			boolean blocked = false;
			for (AABB other : boxes) {
				if (other != box && other.contains(ex, ey, ez)) {
					blocked = true;
					break;
				}
			}

			// Coming in through a face beats any other; an exposed face beats one into the next block.
			float score = (float) depth + (entered ? 0.0F : 0.5F) + (blocked ? 4.0F : 0.0F);
			if (score < best) {
				best = score;
				bestFace = face;
			}
		}

		float depth = switch (bestFace) {
			case 0 -> (float) (px - box.minX);
			case 1 -> (float) (box.maxX - px);
			case 2 -> (float) (py - box.minY);
			case 3 -> (float) (box.maxY - py);
			case 4 -> (float) (pz - box.minZ);
			default -> (float) (box.maxZ - pz);
		};
		normal.set(0.0F).setComponent(bestFace >> 1, (bestFace & 1) == 0 ? -1.0F : 1.0F);
		return depth + MARGIN;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Pushes from outside

	/** Adds a velocity to every segment (blocks per second). */
	public void push(double dx, double dy, double dz) {
		for (int i = 0; i < n; i++) {
			vx[i] += dx;
			vy[i] += dy;
			vz[i] += dz;
		}

		wake();
	}

	/** Adds a spin to one segment, radians per second, world axes. */
	public void spin(int bone, Vector3f omega) {
		w[bone].add(omega);
		wake();
	}

	/** An impulse (kilogram blocks per second) at a world point on segment {@code bone}. */
	public void hit(int bone, Vec3 point, Vec3 impulse) {
		vx[bone] += impulse.x * invMass[bone];
		vy[bone] += impulse.y * invMass[bone];
		vz[bone] += impulse.z * invMass[bone];
		Vector3f torque = new Vector3f((float) (point.x - x[bone]), (float) (point.y - y[bone]), (float) (point.z - z[bone]))
				.cross((float) impulse.x, (float) impulse.y, (float) impulse.z);
		float size = torque.length();
		if (size > 1.0E-7F) {
			addSpin(bone, torque.div(size), size);
		}

		wake();
	}

	/** Moves every segment by the same amount, keeping their motion (the server moved the body); a nudge does not wake it. */
	public void shift(double dx, double dy, double dz) {
		blocksStale = true;
		for (int i = 0; i < n; i++) {
			x[i] += dx;
			y[i] += dy;
			z[i] += dz;
			tx[i] += dx;
			ty[i] += dy;
			tz[i] += dz;
		}

		if (dx * dx + dy * dy + dz * dz > 4.0E-4) {
			wake();
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Reading it back

	/** The body's centre of mass. */
	public Vec3 centre() {
		double sx = 0.0;
		double sy = 0.0;
		double sz = 0.0;
		double total = 0.0;
		for (int i = 0; i < n; i++) {
			double m = 1.0 / invMass[i];
			sx += x[i] * m;
			sy += y[i] * m;
			sz += z[i] * m;
			total += m;
		}

		return new Vec3(sx / total, sy / total, sz / total);
	}

	/** The mass-weighted velocity, blocks per second. */
	public Vec3 velocity() {
		double sx = 0.0;
		double sy = 0.0;
		double sz = 0.0;
		double total = 0.0;
		for (int i = 0; i < n; i++) {
			double m = 1.0 / invMass[i];
			sx += vx[i] * m;
			sy += vy[i] * m;
			sz += vz[i] * m;
			total += m;
		}

		return new Vec3(sx / total, sy / total, sz / total);
	}

	/** The lowest point of any segment. */
	public double bottom() {
		double low = Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			float[] s = samples[i];
			for (int p = 0; p < s.length; p += 3) {
				q[i].transform(r.set(s[p], s[p + 1], s[p + 2]));
				low = Math.min(low, y[i] + r.y);
			}
		}

		return low;
	}

	/** The lowest point of the trunk (chest and hips). */
	public double trunkBottom() {
		double low = Double.MAX_VALUE;
		for (int i : new int[] {skeleton.chest, skeleton.hips}) {
			float[] s = samples[i];
			for (int p = 0; p < s.length; p += 3) {
				q[i].transform(r.set(s[p], s[p + 1], s[p + 2]));
				low = Math.min(low, y[i] + r.y);
			}
		}

		return low;
	}

	/** World box round every segment. */

	public AABB bounds() {
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			float reach = half[i].length();
			minX = Math.min(minX, x[i] - reach);
			minY = Math.min(minY, y[i] - reach);
			minZ = Math.min(minZ, z[i] - reach);
			maxX = Math.max(maxX, x[i] + reach);
			maxY = Math.max(maxY, y[i] + reach);
			maxZ = Math.max(maxZ, z[i] + reach);
		}

		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/** Heading of the trunk as a Minecraft yaw in degrees, or {@code fallback} while it points straight up or down. */
	public float yaw(float fallback) {
		// Where the trunk faces: the model's forward (-z) through the trunk's rest frame and its turn now.
		Skeleton.Bone trunk = skeleton.bones[skeleton.hips];
		Vector3f forward = q[skeleton.hips].transform(trunk.rest.getNormalizedRotation(new Quaternionf()).conjugate().transform(new Vector3f(0.0F, 0.0F, -1.0F)));
		double dx = forward.x;
		double dz = forward.z;
		if (dx * dx + dz * dz < 1.0E-4) {
			return fallback;
		}

		return (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
	}

	/** Lean of the trunk to its own right (radians, 0 upright, +-PI/2 on a side), given its heading. */
	public float roll(float yawDegrees) {
		Skeleton.Bone hips = skeleton.bones[skeleton.hips];
		// Up in model space is -y; through the rest frame into the segment, then by the segment's turn into the world
		// (which already holds the half turn from model space).
		Vector3f up = hips.rest.getNormalizedRotation(new Quaternionf()).conjugate().transform(new Vector3f(0.0F, -1.0F, 0.0F));
		q[skeleton.hips].transform(up);

		float yawRad = yawDegrees * Mth.DEG_TO_RAD;
		float right = up.x * Mth.cos(yawRad) + up.z * Mth.sin(yawRad);
		return (float) Math.atan2(right, up.y);
	}

	/** Where every segment is, between the last tick ({@code t} 0) and this one ({@code t} 1), measured from a point. */
	public RigPose pose(float partial, double ox, double oy, double oz) {
		float[] pos = new float[3 * n];
		float[] rot = new float[4 * n];
		Quaternionf turn = new Quaternionf();
		Vector3f off = new Vector3f();
		for (int i = 0; i < n; i++) {
			tq[i].slerp(q[i], partial, turn);
			turn.transform(off.set(frameOffset[i]));
			pos[3 * i] = (float) (Mth.lerp(partial, tx[i], x[i]) + off.x - ox);
			pos[3 * i + 1] = (float) (Mth.lerp(partial, ty[i], y[i]) + off.y - oy);
			pos[3 * i + 2] = (float) (Mth.lerp(partial, tz[i], z[i]) + off.z - oz);
			rot[4 * i] = turn.x;
			rot[4 * i + 1] = turn.y;
			rot[4 * i + 2] = turn.z;
			rot[4 * i + 3] = turn.w;
		}

		return new RigPose(skeleton, ox, oy, oz, scale, pos, rot);
	}

	/** Segment {@code i}'s world box corners, for the debug view: 8 points, {@code out[8][3]}. */
	public void corners(int i, double[][] out) {
		Vector3f h = half[i];
		for (int c = 0; c < 8; c++) {
			q[i].transform(r.set((c & 1) == 0 ? -h.x : h.x, (c & 2) == 0 ? -h.y : h.y, (c & 4) == 0 ? -h.z : h.z));
			out[c][0] = x[i] + r.x;
			out[c][1] = y[i] + r.y;
			out[c][2] = z[i] + r.z;
		}
	}

	/** Angle of a hinge from straight, radians, as its limits measure it: for tests. */
	public float hingeAngle(int j) {
		Skeleton.Joint joint = skeleton.joints[j];
		Vector3f axis = q[joint.parent()].transform(new Vector3f(joint.axisParent()));
		Vector3f refA = q[joint.parent()].transform(new Vector3f(joint.refParent()));
		Vector3f refB = q[joint.child()].transform(new Vector3f(joint.refChild()));
		return (float) Math.atan2(new Vector3f(refA).cross(refB).dot(axis), refA.dot(refB));
	}

	/** How far apart the two ends of joint {@code j} are, blocks: for tests. */
	public double jointGap(int j) {
		Skeleton.Joint joint = skeleton.joints[j];
		Vector3f ra = q[joint.parent()].transform(new Vector3f(anchorParent[j]));
		Vector3f rb = q[joint.child()].transform(new Vector3f(anchorChild[j]));
		return Math.sqrt(Math.pow(x[joint.child()] + rb.x - x[joint.parent()] - ra.x, 2) + Math.pow(y[joint.child()] + rb.y - y[joint.parent()] - ra.y, 2)
				+ Math.pow(z[joint.child()] + rb.z - z[joint.parent()] - ra.z, 2));
	}

	/** World position of joint {@code j} (on its parent's side). */

	public Vec3 joint(int j) {
		Skeleton.Joint joint = skeleton.joints[j];
		Vector3f arm = q[joint.parent()].transform(new Vector3f(anchorParent[j]));
		return new Vec3(x[joint.parent()] + arm.x, y[joint.parent()] + arm.y, z[joint.parent()] + arm.z);
	}

	/** The segment nearest a world point, or -1. */
	public int nearest(Vec3 point) {
		int best = -1;
		double bestDist = Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			double d = point.distanceToSqr(x[i], y[i], z[i]);
			if (d < bestDist) {
				bestDist = d;
				best = i;
			}
		}

		return best;
	}

	/** True if any sample of any segment is inside a block now: for tests. */
	public boolean inBlock(BlockGetter level, double tolerance) {
		gatherBlocks(level);
		for (int i = 0; i < n; i++) {
			float[] s = samples[i];
			for (int p = 0; p < s.length; p += 3) {
				q[i].transform(r.set(s[p], s[p + 1], s[p + 2]));
				double px = x[i] + r.x;
				double py = y[i] + r.y;
				double pz = z[i] + r.z;
				for (AABB box : near.get(i)) {
					if (px > box.minX + tolerance && px < box.maxX - tolerance && py > box.minY + tolerance && py < box.maxY - tolerance
							&& pz > box.minZ + tolerance && pz < box.maxZ - tolerance) {
						return true;
					}
				}
			}
		}

		return false;
	}

	public @Nullable Vec3 segment(int i) {
		return i < 0 || i >= n ? null : new Vec3(x[i], y[i], z[i]);
	}
}
