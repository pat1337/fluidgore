package dev.fluidgore.ragdoll;

import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.mixin.ModelPartAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A body as a chain of rigid segments joined where a real animal bends, read from its model.
 *
 * <p>Minecraft limbs are single blocks, so the joints the model lacks are made by cutting parts across their length:
 * legs at the knee or hock, the trunk into chest and hips, and a short neck (with no block of its own) between chest
 * and head so a dead head lolls. Which joints, and how far each bends, comes from the body plan:
 * <ul>
 * <li>{@link Plan#QUADRUPED}: four legs. With knees, each is cut in two: the front leg's middle joint is the wrist (a
 *     cow's "knee"), which folds the foot backward; the hind leg's is the hock, which folds it forward.</li>
 * <li>{@link Plan#BIPED}: arms and legs; with knees, cut at the elbow (folds forward) and the knee (folds back).</li>
 * <li>{@link Plan#BIRD}: wings and short legs whole, a neck.</li>
 * <li>{@link Plan#THIRDS}: an unknown (modded) model: every part on the root is a segment on the trunk; with knees, long
 *     ones are cut in thirds.</li>
 * </ul>
 * The trunk is never cut: two halves of one block folding apart look like two blocks, not a spine. Without knees every
 * segment is a whole part of the model, so the body looks exactly like the vanilla one, only limp.
 * Model space throughout: x across, y down, z backwards (the head is at -z), blocks; frames are rigid (a part's scale
 * goes into its sizes).
 */
public final class Skeleton {
	public enum Plan {
		QUADRUPED, BIPED, BIRD, THIRDS
	}

	/** A rigid segment: a model part, one stretch of a part cut at a joint, or the neck the model does not have. */
	public static final class Bone {
		public final String name;
		/** The model part it moves, or null for the neck. */
		public final @Nullable String part;
		/** Which stretch of a cut part, from 0 at the part's own frame. */
		public final int segment;
		/** Where its frame starts, in the part's own pixels (the cut; zero for segment 0). */
		public final Vector3f origin;
		/** The frame at rest, in model space: rigid. */
		public final Matrix4f rest;
		/** Collision box in the frame, in blocks: min x, y, z, max x, y, z. */
		public final float[] box;
		/** Volume of the flesh in it, in model pixels cubed. */
		public final float volume;

		Bone(String name, @Nullable String part, int segment, Vector3f origin, Matrix4f rest, float[] box, float volume) {
			this.name = name;
			this.part = part;
			this.segment = segment;
			this.origin = origin;
			this.rest = rest;
			this.box = box;
			this.volume = volume;
		}

		public float halfX() {
			return (box[3] - box[0]) * 0.5F;
		}

		public float halfY() {
			return (box[4] - box[1]) * 0.5F;
		}

		public float halfZ() {
			return (box[5] - box[2]) * 0.5F;
		}

		/** The middle of the box in the frame, blocks. */
		public Vector3f centre() {
			return new Vector3f((box[0] + box[3]) * 0.5F, (box[1] + box[4]) * 0.5F, (box[2] + box[5]) * 0.5F);
		}
	}

	/**
	 * Two segments held together at a point. A ball joint swings within a cone round its axis and twists within
	 * {@code min..max}; a hinge turns about its axis only, within {@code min..max}, measured from {@code ref}.
	 * Points and directions are in each bone's own frame.
	 */
	public record Joint(int parent, int child, Vector3f anchorParent, Vector3f anchorChild, boolean hinge, Vector3f axisParent, Vector3f axisChild,
			Vector3f refParent, Vector3f refChild, float swing, float min, float max) {
	}

	/** A part cut across {@code axis} at {@code at} (its own pixels, ascending); segment j's frame starts at {@code origins[j]}. */
	public record Cut(int axis, float[] at, Vector3f[] origins) {
		public int segments() {
			return at.length + 1;
		}

		/** The segment a coordinate along the cut axis falls in. */
		public int segmentOf(float coordinate) {
			int j = 0;
			while (j < at.length && coordinate >= at[j]) {
				j++;
			}

			return j;
		}

		/** The boundaries of every segment, from far below the part to far above it. */
		public float[] bounds() {
			float[] out = new float[at.length + 2];
			out[0] = -1.0E6F;
			System.arraycopy(at, 0, out, 1, at.length);
			out[out.length - 1] = 1.0E6F;
			return out;
		}
	}

	private static final Map<Model<?>, Optional<Skeleton>> WHOLE = new WeakHashMap<>();
	private static final Map<Model<?>, Optional<Skeleton>> KNEED = new WeakHashMap<>();
	private static final float DEG = Mth.DEG_TO_RAD;

	public final Plan plan;
	public final Bone[] bones;
	public final Joint[] joints;
	public final Map<String, Cut> cuts;
	/** Parts on the root with no segment of their own (a tail, a mane), and the bone each rides on, rigidly. */
	public final Map<String, Integer> followers;
	/** The trunk's segment nearest the tail (or the trunk itself), and the one nearest the head. */
	public final int hips;
	public final int chest;
	public final int head;
	/** The upper segment of each limb in {@code ModelParts.LIMBS} order, or -1. */
	public final int[] limbs;

	private Skeleton(Plan plan, Bone[] bones, Joint[] joints, Map<String, Cut> cuts, Map<String, Integer> followers, int hips, int chest, int head,
			int[] limbs) {
		this.plan = plan;
		this.bones = bones;
		this.joints = joints;
		this.cuts = cuts;
		this.followers = followers;
		this.hips = hips;
		this.chest = chest;
		this.head = head;
		this.limbs = limbs;
	}

	/**
	 * The skeleton of a model, built once per model: whole parts, or with limbs cut at the knee ({@code knees}). Null for
	 * a model with no trunk to hang anything from.
	 */
	public static @Nullable Skeleton of(Model<?> model, boolean knees) {
		return (knees ? KNEED : WHOLE).computeIfAbsent(model, m -> Optional.ofNullable(new Builder(m, knees).build())).orElse(null);
	}

	public int index(String name) {
		for (int i = 0; i < bones.length; i++) {
			if (bones[i].name.equals(name)) {
				return i;
			}
		}

		return -1;
	}

	/** Bones that move a part of this name, segment 0 first. */
	public int[] bonesOf(String part) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < bones.length; i++) {
			if (part.equals(bones[i].part)) {
				out.add(i);
			}
		}

		out.sort((a, b) -> Integer.compare(bones[a].segment, bones[b].segment));
		return out.stream().mapToInt(Integer::intValue).toArray();
	}

	/** Builds a skeleton from a model's part tree at rest. */
	private static final class Builder {
		final Model<?> model;
		final ModelPart root;
		final Map<ModelPart, String> names = new IdentityHashMap<>();
		final Map<ModelPart, ModelPart> parents = new IdentityHashMap<>();
		final Map<String, ModelPart> parts = new HashMap<>();
		final List<Bone> bones = new ArrayList<>();
		final List<Joint> joints = new ArrayList<>();
		final Map<String, Cut> cuts = new LinkedHashMap<>();

		final boolean knees;

		Builder(Model<?> model, boolean knees) {
			this.model = model;
			this.knees = knees;
			this.root = model.root();
			index(root, "root", null);
		}

		private void index(ModelPart part, String name, @Nullable ModelPart parent) {
			names.put(part, name);
			parts.putIfAbsent(name, part);
			if (parent != null) {
				parents.put(part, parent);
			}

			children(part).forEach((childName, child) -> index(child, childName, part));
		}

		@Nullable Skeleton build() {
			ModelPart[] index = ModelParts.index(model);
			ModelPart torso = parts.get("body");
			ModelPart head = index[BodyPart.HEAD.ordinal()];
			ModelPart[] limbs = new ModelPart[4];
			for (int i = 0; i < 4; i++) {
				limbs[i] = index[ModelParts.LIMBS[i].ordinal()];
			}

			boolean allLimbs = limbs[0] != null && limbs[1] != null && limbs[2] != null && limbs[3] != null;
			Plan plan;
			if (torso != null && allLimbs && name(limbs[0]).endsWith("_front_leg")) {
				plan = Plan.QUADRUPED;
			} else if (torso != null && allLimbs && name(limbs[0]).endsWith("_arm")) {
				plan = Plan.BIPED;
			} else if (torso != null && allLimbs && name(limbs[0]).endsWith("_wing")) {
				plan = Plan.BIRD;
			} else {
				plan = Plan.THIRDS;
			}

			if (plan == Plan.THIRDS) {
				return thirds();
			}

			if (!hasGeometry(torso) || mainCube(torso) == null) {
				return null;
			}

			// Decide the cuts first, then drop any that would put a moving part under a cut one.
			Map<ModelPart, Integer> pieces = new LinkedHashMap<>();
			for (int i = 0; i < 4; i++) {
				if (knees && plan != Plan.BIRD) {
					pieces.put(limbs[i], 2);
				}
			}

			List<ModelPart> moving = new ArrayList<>(List.of(torso));
			if (head != null) {
				moving.add(head);
			}

			moving.addAll(List.of(limbs));
			pieces.entrySet().removeIf(e -> !cuttable(e.getKey(), e.getValue(), moving, plan == Plan.QUADRUPED && e.getKey() == torso ? 10.0F : 6.0F));

			int[] torsoBones = segments(torso, pieces.getOrDefault(torso, 1));
			Vector3f headPivot = head != null ? pivot(head) : null;
			int chest = torsoBones[0];
			int hips = torsoBones[torsoBones.length - 1];
			if (headPivot != null && torsoBones.length > 1) {
				Vector3f first = centreOf(torsoBones[0]);
				Vector3f last = centreOf(torsoBones[torsoBones.length - 1]);
				if (last.distanceSquared(headPivot) < first.distanceSquared(headPivot)) {
					chest = torsoBones[torsoBones.length - 1];
					hips = torsoBones[0];
				}
			}

			chain(torsoBones, 25.0F * DEG, -18.0F * DEG, 18.0F * DEG);

			int headBone = -1;
			if (head != null && hasGeometry(head)) {
				headBone = segments(head, 1)[0];
				if (plan == Plan.BIPED) {
					ball(chest, headBone, headPivot, up(), 50.0F * DEG, -60.0F * DEG, 60.0F * DEG);
				} else {
					neck(chest, headBone, headPivot);
				}
			}

			int[] limbBones = {-1, -1, -1, -1};
			for (int i = 0; i < 4; i++) {
				ModelPart limb = limbs[i];
				if (!hasGeometry(limb)) {
					continue;
				}

				boolean front = i < 2;
				int[] segs = segments(limb, pieces.getOrDefault(limb, 1));
				limbBones[i] = segs[0];
				Vector3f pivot = pivot(limb);
				int body = nearestTorso(torsoBones, pivot, front ? chest : hips);
				Vector3f along = longAxisModel(limb);
				switch (plan) {
					case QUADRUPED -> {
						ball(body, segs[0], pivot, along, 70.0F * DEG, -20.0F * DEG, 20.0F * DEG);
						// The wrist folds the foot back under the body, the hock folds it forward.
						knee(segs, front ? -5.0F * DEG : -140.0F * DEG, front ? 140.0F * DEG : 5.0F * DEG);
					}
					case BIPED -> {
						ball(body, segs[0], pivot, along, front ? 150.0F * DEG : 95.0F * DEG, front ? -70.0F * DEG : -35.0F * DEG,
								front ? 70.0F * DEG : 35.0F * DEG);
						// An elbow folds the hand forward, a knee the foot back.
						knee(segs, front ? -150.0F * DEG : -3.0F * DEG, front ? 3.0F * DEG : 150.0F * DEG);
					}
					default -> ball(body, segs[0], pivot, along, front ? 85.0F * DEG : 50.0F * DEG, -35.0F * DEG, 35.0F * DEG);
				}
			}

			Map<String, Integer> followers = followers();
			return new Skeleton(plan, bones.toArray(Bone[]::new), joints.toArray(Joint[]::new), cuts, followers, hips, chest, headBone, limbBones);
		}

		/** A modded model: the trunk with everything on the root hung from it, long things cut in thirds. */
		private @Nullable Skeleton thirds() {
			ModelPart torso = parts.get("body");
			if (!hasGeometry(torso) || parents.get(torso) != root) {
				torso = null;
				float best = 0.0F;
				for (ModelPart child : children(root).values()) {
					float v = subtreeVolume(child);
					if (v > best) {
						best = v;
						torso = child;
					}
				}
			}

			if (torso == null || mainCube(torso) == null) {
				return null;
			}

			int trunk = segments(torso, 1)[0];
			for (ModelPart child : children(root).values()) {
				if (child == torso || !hasGeometry(child)) {
					continue;
				}

				ModelPart.Cube cube = mainCube(child);
				int count = 1;
				if (cube != null) {
					float[] size = {cube.maxX - cube.minX, cube.maxY - cube.minY, cube.maxZ - cube.minZ};
					float longest = Math.max(size[0], Math.max(size[1], size[2]));
					float thinnest = Math.min(size[0], Math.min(size[1], size[2]));
					if (knees && longest >= 9.0F && longest >= 2.5F * thinnest && cuttable(child, 3, List.of(child), 9.0F)) {
						count = 3;
					}
				}

				int[] segs = segments(child, count);
				Vector3f pivot = pivot(child);
				Vector3f out = centreOf(segs[segs.length - 1]).sub(pivot);
				if (out.lengthSquared() < 1.0E-8F) {
					out.set(0.0F, 1.0F, 0.0F);
				}

				ball(trunk, segs[0], pivot, out.normalize(), 45.0F * DEG, -25.0F * DEG, 25.0F * DEG);
				chain(segs, 35.0F * DEG, -15.0F * DEG, 15.0F * DEG);
			}

			return new Skeleton(Plan.THIRDS, bones.toArray(Bone[]::new), joints.toArray(Joint[]::new), cuts, followers(), trunk, trunk, -1,
					new int[] {-1, -1, -1, -1});
		}

		/**
		 * Whether a part can be cut into {@code count} along its longest side: long enough, and nothing that moves on its own
		 * hanging under it.
		 */
		private boolean cuttable(ModelPart part, int count, List<ModelPart> moving, float shortest) {
			ModelPart.Cube cube = mainCube(part);
			if (cube == null || count < 2) {
				return false;
			}

			float[] size = {cube.maxX - cube.minX, cube.maxY - cube.minY, cube.maxZ - cube.minZ};
			if (Math.max(size[0], Math.max(size[1], size[2])) < shortest) {
				return false;
			}

			for (ModelPart other : moving) {
				if (other != part && other != null && ancestors(other).contains(part)) {
					return false;
				}
			}

			return true;
		}

		/**
		 * Makes the segments of one part: the part whole, or cut across its longest side into {@code count} equal stretches
		 * on whole pixels of its main block. Returns the bone indices in order along the cut.
		 */
		private int[] segments(ModelPart part, int count) {
			String name = name(part);
			Matrix4f rest = restOf(part);
			float scale = rest.getScale(new Vector3f()).x;
			Matrix4f rigid = rigid(rest);
			ModelPart.Cube main = mainCube(part);
			if (count < 2 || main == null) {
				float[] box = bounds(part, null, 0, 0.0F, scale);
				bones.add(new Bone(name, name, 0, new Vector3f(), rigid, box, volume(part, null, 0)));
				return new int[] {bones.size() - 1};
			}

			float[] min = {main.minX, main.minY, main.minZ};
			float[] max = {main.maxX, main.maxY, main.maxZ};
			int axis = 0;
			for (int i = 1; i < 3; i++) {
				if (max[i] - min[i] > max[axis] - min[axis]) {
					axis = i;
				}
			}

			float length = max[axis] - min[axis];
			float[] at = new float[count - 1];
			for (int k = 1; k < count; k++) {
				at[k - 1] = min[axis] + Math.round(length * k / count);
			}

			Vector3f[] origins = new Vector3f[count];
			origins[0] = new Vector3f();
			for (int k = 1; k < count; k++) {
				// The joint sits in the middle of the block's cross-section, where it is cut.
				Vector3f o = new Vector3f((min[0] + max[0]) * 0.5F, (min[1] + max[1]) * 0.5F, (min[2] + max[2]) * 0.5F);
				o.setComponent(axis, at[k - 1]);
				origins[k] = o;
			}

			Cut cut = new Cut(axis, at, origins);
			cuts.put(name, cut);
			int[] out = new int[count];
			for (int k = 0; k < count; k++) {
				Matrix4f frame = new Matrix4f(rigid);
				if (k > 0) {
					Vector3f offset = rest.transformPosition(new Vector3f(origins[k]).div(16.0F)).sub(rest.getTranslation(new Vector3f()));
					frame.setTranslation(rest.getTranslation(new Vector3f()).add(offset));
				}

				float[] box = bounds(part, cut, k, 0.0F, scale);
				if (k > 0) {
					float ox = origins[k].x / 16.0F * scale;
					float oy = origins[k].y / 16.0F * scale;
					float oz = origins[k].z / 16.0F * scale;
					box = new float[] {box[0] - ox, box[1] - oy, box[2] - oz, box[3] - ox, box[4] - oy, box[5] - oz};
				}

				bones.add(new Bone(count > 1 ? name + "#" + k : name, name, k, origins[k], frame, box, volume(part, cut, k)));
				out[k] = bones.size() - 1;
			}

			return out;
		}

		/** Ball joints down a cut part, each stretch on the one before, twisting about the part's length. */
		private void chain(int[] segs, float swing, float min, float max) {
			for (int k = 1; k < segs.length; k++) {
				Bone child = bones.get(segs[k]);
				Vector3f at = child.rest.getTranslation(new Vector3f());
				Vector3f along = centreOf(segs[k]).sub(centreOf(segs[k - 1]));
				if (along.lengthSquared() < 1.0E-8F) {
					along.set(0.0F, 1.0F, 0.0F);
				}

				ball(segs[k - 1], segs[k], at, along.normalize(), swing, min, max);
			}
		}

		/** The middle joint of a limb: a hinge across the body (model x), bending from straight within {@code min..max}. */
		private void knee(int[] segs, float min, float max) {
			if (segs.length < 2) {
				return;
			}

			Bone upper = bones.get(segs[0]);
			Bone lower = bones.get(segs[1]);
			Vector3f at = lower.rest.getTranslation(new Vector3f());
			Vector3f along = centreOf(segs[1]).sub(centreOf(segs[0]));
			if (along.lengthSquared() < 1.0E-8F) {
				along.set(0.0F, 1.0F, 0.0F);
			}

			along.normalize();
			Vector3f across = new Vector3f(1.0F, 0.0F, 0.0F);
			joints.add(new Joint(segs[0], segs[1], local(upper, at), local(lower, at), true, direction(upper, across), direction(lower, across),
					direction(upper, along), direction(lower, along), 0.0F, min, max));
		}

		/** A ball joint at a point in model space, twisting about {@code axis} (pointing from parent to child). */
		private void ball(int parent, int child, Vector3f at, Vector3f axis, float swing, float min, float max) {
			Bone p = bones.get(parent);
			Bone c = bones.get(child);
			Vector3f ref = new Vector3f(1.0F, 0.0F, 0.0F);
			if (Math.abs(ref.dot(axis)) > 0.9F) {
				ref.set(0.0F, 0.0F, 1.0F);
			}

			ref.sub(new Vector3f(axis).mul(ref.dot(axis))).normalize();
			joints.add(new Joint(parent, child, local(p, at), local(c, at), false, direction(p, axis), direction(c, axis), direction(p, ref),
					direction(c, ref), swing, min, max));
		}

		/**
		 * A neck the model does not have: a short segment from the head's pivot back towards the chest, so the head can drop
		 * as well as turn. Two ball joints, at the chest and at the head.
		 */
		private void neck(int chest, int head, Vector3f headPivot) {
			Bone headBone = bones.get(head);
			Vector3f towardChest = centreOf(chest).sub(headPivot);
			towardChest.y = Math.max(towardChest.y, 0.0F);
			if (towardChest.lengthSquared() < 1.0E-8F) {
				towardChest.set(0.0F, 0.0F, 1.0F);
			}

			towardChest.normalize();
			float size = Math.max(headBone.halfX(), Math.max(headBone.halfY(), headBone.halfZ())) * 2.0F;
			float length = Mth.clamp(size * 0.3F, 1.5F / 16.0F, 4.0F / 16.0F);
			float thick = Math.max(1.0F / 16.0F, Math.min(headBone.halfX(), headBone.halfY()) * 0.9F);
			Vector3f end = new Vector3f(towardChest).mul(length);
			float[] box = {Math.min(0.0F, end.x) - thick * 0.5F, Math.min(0.0F, end.y) - thick * 0.5F, Math.min(0.0F, end.z) - thick * 0.5F,
					Math.max(0.0F, end.x) + thick * 0.5F, Math.max(0.0F, end.y) + thick * 0.5F, Math.max(0.0F, end.z) + thick * 0.5F};
			float volume = (box[3] - box[0]) * (box[4] - box[1]) * (box[5] - box[2]) * 4096.0F;
			bones.add(new Bone("neck", null, 0, new Vector3f(), new Matrix4f().translation(headPivot), box, volume));
			int neck = bones.size() - 1;
			Vector3f toHead = new Vector3f(towardChest).negate();
			// Together about 50 degrees each way: a dead head lolls and drops, but a short, thick farm-animal neck does not
			// fold the head back against the shoulder (95 did, and the head sank into the body: the rig has no self-collision).
			ball(chest, neck, new Vector3f(headPivot).add(end), toHead, 20.0F * DEG, -12.0F * DEG, 12.0F * DEG);
			ball(neck, head, headPivot, toHead, 30.0F * DEG, -18.0F * DEG, 18.0F * DEG);
		}

		/** Root-level parts without a segment ride on the bone whose middle is nearest their pivot. */
		private Map<String, Integer> followers() {
			Map<String, Integer> out = new LinkedHashMap<>();
			for (Map.Entry<String, ModelPart> entry : children(root).entrySet()) {
				String name = entry.getKey();
				boolean moved = false;
				for (Bone bone : bones) {
					if (name.equals(bone.part)) {
						moved = true;
						break;
					}
				}

				if (!moved) {
					out.put(name, nearestBone(pivot(entry.getValue())));
				}
			}

			return out;
		}

		private int nearestBone(Vector3f point) {
			int best = 0;
			float bestDist = Float.MAX_VALUE;
			for (int i = 0; i < bones.size(); i++) {
				float d = centreOf(i).distanceSquared(point);
				if (d < bestDist) {
					bestDist = d;
					best = i;
				}
			}

			return best;
		}

		private int nearestTorso(int[] torso, Vector3f point, int fallback) {
			if (torso.length == 1) {
				return torso[0];
			}

			int best = fallback;
			float bestDist = Float.MAX_VALUE;
			for (int i : torso) {
				float d = centreOf(i).distanceSquared(point);
				if (d < bestDist) {
					bestDist = d;
					best = i;
				}
			}

			return best;
		}

		/** A bone's box middle in model space. */
		private Vector3f centreOf(int bone) {
			Bone b = bones.get(bone);
			return b.rest.transformPosition(b.centre());
		}

		private static Vector3f local(Bone bone, Vector3f modelPoint) {
			return new Matrix4f(bone.rest).invert().transformPosition(new Vector3f(modelPoint));
		}

		private static Vector3f direction(Bone bone, Vector3f modelDirection) {
			return new Matrix4f(bone.rest).invert().transformDirection(new Vector3f(modelDirection)).normalize();
		}

		private static Vector3f up() {
			return new Vector3f(0.0F, -1.0F, 0.0F);
		}

		/** The direction a part's main block runs, in model space at rest (towards its far end from the pivot). */
		private Vector3f longAxisModel(ModelPart part) {
			ModelPart.Cube cube = mainCube(part);
			if (cube == null) {
				return new Vector3f(0.0F, 1.0F, 0.0F);
			}

			float[] min = {cube.minX, cube.minY, cube.minZ};
			float[] max = {cube.maxX, cube.maxY, cube.maxZ};
			int axis = 0;
			for (int i = 1; i < 3; i++) {
				if (max[i] - min[i] > max[axis] - min[axis]) {
					axis = i;
				}
			}

			Vector3f local = new Vector3f().setComponent(axis, Math.abs(max[axis]) >= Math.abs(min[axis]) ? 1.0F : -1.0F);
			return restOf(part).transformDirection(local).normalize();
		}

		/** A part's pivot in model space at rest. */
		private Vector3f pivot(ModelPart part) {
			return restOf(part).getTranslation(new Vector3f());
		}

		/** A part's transform at rest in model space (blocks), from the root down, with scale. */
		private Matrix4f restOf(ModelPart part) {
			List<ModelPart> chain = ancestors(part);
			java.util.Collections.reverse(chain);
			chain.add(part);
			Matrix4f m = new Matrix4f();
			for (ModelPart link : chain) {
				PartPose pose = link.getInitialPose();
				m.translate(pose.x() / 16.0F, pose.y() / 16.0F, pose.z() / 16.0F);
				if (pose.xRot() != 0.0F || pose.yRot() != 0.0F || pose.zRot() != 0.0F) {
					m.rotate(new Quaternionf().rotationZYX(pose.zRot(), pose.yRot(), pose.xRot()));
				}

				if (pose.xScale() != 1.0F || pose.yScale() != 1.0F || pose.zScale() != 1.0F) {
					m.scale(pose.xScale(), pose.yScale(), pose.zScale());
				}
			}

			return m;
		}

		private List<ModelPart> ancestors(ModelPart part) {
			List<ModelPart> out = new ArrayList<>();
			ModelPart p = parents.get(part);
			while (p != null) {
				out.add(p);
				p = parents.get(p);
			}

			return out;
		}

		/**
		 * The box of one stretch of a part, in the part's frame (blocks, scaled): its own blocks clipped to the stretch, and
		 * the blocks of everything hanging from it whose pivot falls in the stretch.
		 */
		private float[] bounds(ModelPart part, @Nullable Cut cut, int segment, float pad, float scale) {
			float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			float[] bounds = cut != null ? cut.bounds() : null;
			for (ModelPart.Cube cube : cubes(part)) {
				float[] lo = {cube.minX, cube.minY, cube.minZ};
				float[] hi = {cube.maxX, cube.maxY, cube.maxZ};
				if (cut != null) {
					lo[cut.axis()] = Math.max(lo[cut.axis()], bounds[segment]);
					hi[cut.axis()] = Math.min(hi[cut.axis()], bounds[segment + 1]);
					if (hi[cut.axis()] <= lo[cut.axis()]) {
						continue;
					}
				}

				include(b, lo, hi, null);
			}

			for (ModelPart child : children(part).values()) {
				Matrix4f rel = new Matrix4f(restOf(part)).invert().mul(restOf(child));
				if (cut != null && cut.segmentOf(rel.m30() * 16.0F * (cut.axis() == 0 ? 1 : 0) + rel.m31() * 16.0F * (cut.axis() == 1 ? 1 : 0)
						+ rel.m32() * 16.0F * (cut.axis() == 2 ? 1 : 0)) != segment) {
					continue;
				}

				subtree(child, rel, b);
			}

			if (b[0] > b[3]) {
				return new float[] {-pad - 0.05F, -pad - 0.05F, -pad - 0.05F, pad + 0.05F, pad + 0.05F, pad + 0.05F};
			}

			for (int i = 0; i < 6; i++) {
				b[i] = b[i] / 16.0F * scale;
			}

			return b;
		}

		private void subtree(ModelPart part, Matrix4f toFrame, float[] b) {
			for (ModelPart.Cube cube : cubes(part)) {
				include(b, new float[] {cube.minX, cube.minY, cube.minZ}, new float[] {cube.maxX, cube.maxY, cube.maxZ}, toFrame);
			}

			for (ModelPart child : children(part).values()) {
				PartPose pose = child.getInitialPose();
				Matrix4f m = new Matrix4f(toFrame).translate(pose.x() / 16.0F, pose.y() / 16.0F, pose.z() / 16.0F)
						.rotate(new Quaternionf().rotationZYX(pose.zRot(), pose.yRot(), pose.xRot()));
				subtree(child, m, b);
			}
		}

		/** Grows {@code b} (part pixels) by a block given in pixels, through {@code toFrame} (blocks) if not null. */
		private static void include(float[] b, float[] lo, float[] hi, @Nullable Matrix4f toFrame) {
			for (int corner = 0; corner < 8; corner++) {
				Vector3f p = new Vector3f((corner & 1) == 0 ? lo[0] : hi[0], (corner & 2) == 0 ? lo[1] : hi[1], (corner & 4) == 0 ? lo[2] : hi[2]);
				if (toFrame != null) {
					toFrame.transformPosition(p.div(16.0F)).mul(16.0F);
				}

				b[0] = Math.min(b[0], p.x);
				b[1] = Math.min(b[1], p.y);
				b[2] = Math.min(b[2], p.z);
				b[3] = Math.max(b[3], p.x);
				b[4] = Math.max(b[4], p.y);
				b[5] = Math.max(b[5], p.z);
			}
		}

		private float volume(ModelPart part, @Nullable Cut cut, int segment) {
			float total = 0.0F;
			float[] bounds = cut != null ? cut.bounds() : null;
			for (ModelPart.Cube cube : cubes(part)) {
				float[] lo = {cube.minX, cube.minY, cube.minZ};
				float[] hi = {cube.maxX, cube.maxY, cube.maxZ};
				if (cut != null) {
					lo[cut.axis()] = Math.max(lo[cut.axis()], bounds[segment]);
					hi[cut.axis()] = Math.min(hi[cut.axis()], bounds[segment + 1]);
				}

				total += Math.max(0.0F, hi[0] - lo[0]) * Math.max(0.0F, hi[1] - lo[1]) * Math.max(0.0F, hi[2] - lo[2]);
			}

			for (ModelPart child : children(part).values()) {
				if (cut == null || cut.segmentOf(child.getInitialPose().x() * (cut.axis() == 0 ? 1 : 0) + child.getInitialPose().y() * (cut.axis() == 1 ? 1 : 0)
						+ child.getInitialPose().z() * (cut.axis() == 2 ? 1 : 0)) == segment) {
					total += subtreeVolume(child);
				}
			}

			return Math.max(1.0F, total);
		}

		private float subtreeVolume(ModelPart part) {
			float total = 0.0F;
			for (ModelPart.Cube cube : cubes(part)) {
				total += (cube.maxX - cube.minX) * (cube.maxY - cube.minY) * (cube.maxZ - cube.minZ);
			}

			for (ModelPart child : children(part).values()) {
				total += subtreeVolume(child);
			}

			return total;
		}

		private boolean hasGeometry(@Nullable ModelPart part) {
			return part != null && subtreeVolume(part) > 0.0F;
		}

		private String name(ModelPart part) {
			return names.getOrDefault(part, "");
		}

		private static Matrix4f rigid(Matrix4f m) {
			Quaternionf rotation = m.getNormalizedRotation(new Quaternionf());
			return new Matrix4f().translation(m.getTranslation(new Vector3f())).rotate(rotation);
		}

		static ModelPart.@Nullable Cube mainCube(@Nullable ModelPart part) {
			if (part == null) {
				return null;
			}

			ModelPart.Cube best = null;
			float bestVolume = 0.0F;
			for (ModelPart.Cube cube : cubes(part)) {
				float v = (cube.maxX - cube.minX) * (cube.maxY - cube.minY) * (cube.maxZ - cube.minZ);
				if (v > bestVolume) {
					bestVolume = v;
					best = cube;
				}
			}

			return best;
		}
	}

	static List<ModelPart.Cube> cubes(ModelPart part) {
		return ((ModelPartAccessor) (Object) part).fluidgore$getCubes();
	}

	static Map<String, ModelPart> children(ModelPart part) {
		return ((ModelPartAccessor) (Object) part).fluidgore$getChildren();
	}
}
