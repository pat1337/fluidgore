package dev.fluidgore.ragdoll;

import dev.fluidgore.mixin.ModelPartAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Poses a model from a ragdoll: every part a segment moves is set (offset and turn, relative to whatever it hangs from)
 * so it is drawn exactly where its segment is. Works for any model drawn with the body's state (its wool, saddle,
 * armour) by part name.
 *
 * <p>A cut part is bent with {@link Bends}; for the vanilla draw itself, {@link #cut} swaps the part's blocks for the
 * stretch on its own frame plus one child per further stretch, posed as that stretch is, and {@link #mend} puts the part
 * back as it was. Nothing of the swap outlives one {@code renderToBuffer}.
 */
public final class Poser {
	private static final Map<Model<?>, Map<Skeleton, Binding>> BINDINGS = new WeakHashMap<>();

	private Poser() {
	}

	/** A part on the root riding rigidly on a bone. */
	private record Follower(ModelPart part, int bone, Matrix4f rel) {
	}

	/** One model's parts for one skeleton. */
	private static final class Binding {
		final ModelPart root;
		final Map<ModelPart, ModelPart> parents = new IdentityHashMap<>();
		/** The part each bone moves in this model (segment 0 bones only), null where it has none. */
		final ModelPart[] parts;
		/** Segment-0 bones with a part here, parents before children. */
		final int[] order;
		final List<Follower> followers = new ArrayList<>();
		final List<SplitPart> splits = new ArrayList<>();

		Binding(Model<?> model, Skeleton skeleton) {
			this.root = model.root();
			Map<String, ModelPart> byName = new HashMap<>();
			Map<ModelPart, Integer> depth = new IdentityHashMap<>();
			index(root, "root", null, 0, byName, depth);
			int n = skeleton.bones.length;
			this.parts = new ModelPart[n];
			List<Integer> order = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				Skeleton.Bone bone = skeleton.bones[i];
				if (bone.part != null && bone.segment == 0) {
					parts[i] = byName.get(bone.part);
					if (parts[i] != null) {
						order.add(i);
					}
				}
			}

			order.sort((a, b) -> Integer.compare(depth.get(parts[a]), depth.get(parts[b])));
			this.order = order.stream().mapToInt(Integer::intValue).toArray();

			for (Map.Entry<String, ModelPart> entry : Skeleton.children(root).entrySet()) {
				ModelPart part = entry.getValue();
				boolean moved = false;
				for (ModelPart p : parts) {
					if (p == part) {
						moved = true;
						break;
					}
				}

				if (moved) {
					continue;
				}

				Integer bone = skeleton.followers.get(entry.getKey());
				Matrix4f rest = rest(part);
				if (bone == null) {
					bone = nearest(skeleton, rest.getTranslation(new Vector3f()));
				}

				Matrix4f rel = new Matrix4f(skeleton.bones[bone].rest).invert().mul(rigid(rest));
				followers.add(new Follower(part, bone, rel));
			}

			for (Map.Entry<String, Skeleton.Cut> entry : skeleton.cuts.entrySet()) {
				ModelPart part = byName.get(entry.getKey());
				if (part != null) {
					int[] bones = skeleton.bonesOf(entry.getKey());
					if (bones.length == entry.getValue().segments()) {
						splits.add(new SplitPart(part, entry.getValue(), bones));
					}
				}
			}
		}

		private void index(ModelPart part, String name, @Nullable ModelPart parent, int level, Map<String, ModelPart> byName, Map<ModelPart, Integer> depth) {
			byName.putIfAbsent(name, part);
			depth.put(part, level);
			if (parent != null) {
				parents.put(part, parent);
			}

			Skeleton.children(part).forEach((childName, child) -> index(child, childName, part, level + 1, byName, depth));
		}

		private Matrix4f rest(ModelPart part) {
			List<ModelPart> chain = new ArrayList<>();
			for (ModelPart p = part; p != null; p = parents.get(p)) {
				chain.addFirst(p);
			}

			Matrix4f m = new Matrix4f();
			for (ModelPart link : chain) {
				PartPose pose = link.getInitialPose();
				m.translate(pose.x() / 16.0F, pose.y() / 16.0F, pose.z() / 16.0F)
						.rotate(new Quaternionf().rotationZYX(pose.zRot(), pose.yRot(), pose.xRot()));
			}

			return m;
		}

		private static int nearest(Skeleton skeleton, Vector3f point) {
			int best = skeleton.hips;
			float bestDist = Float.MAX_VALUE;
			for (int i = 0; i < skeleton.bones.length; i++) {
				Skeleton.Bone bone = skeleton.bones[i];
				float d = bone.rest.transformPosition(bone.centre()).distanceSquared(point);
				if (d < bestDist) {
					bestDist = d;
					best = i;
				}
			}

			return best;
		}
	}

	/**
	 * A cut part in one model: its blocks sorted into stretches (cut where they cross a cut), and for every stretch after
	 * the first a frame part, posed as that stretch is against the one before, holding its blocks and riders in the part's
	 * own coordinates (an inner part set back by the stretch's origin) and the next stretch. Parts on it that sit exactly
	 * in its frame (a player's sleeve on the arm) are cut the same way.
	 */
	private static final class SplitPart {
		final ModelPart part;
		final Skeleton.Cut cut;
		final int[] bones;
		final List<ModelPart.Cube> originalCubes;
		final Map<String, ModelPart> originalChildren;
		final List<ModelPart.Cube> cubes0;
		final Map<String, ModelPart> children0;
		final ModelPart[] stretch;
		final ModelPart[] inner;
		final Map<ModelPart, Integer> childSegment = new IdentityHashMap<>();
		final List<SplitPart> inside = new ArrayList<>();
		final Matrix4f[] rel;
		final Matrix4f[] toSegment;

		SplitPart(ModelPart part, Skeleton.Cut cut, int[] bones) {
			this.part = part;
			this.cut = cut;
			this.bones = bones;
			ModelPartAccessor access = (ModelPartAccessor) (Object) part;
			this.originalCubes = access.fluidgore$getCubes();
			this.originalChildren = access.fluidgore$getChildren();
			int k = cut.segments();
			List<List<ModelPart.Cube>> cubes = new ArrayList<>();
			List<Map<String, ModelPart>> children = new ArrayList<>();
			for (int j = 0; j < k; j++) {
				cubes.add(new ArrayList<>());
				children.add(new LinkedHashMap<>());
			}

			float[] bounds = cut.bounds();
			for (ModelPart.Cube cube : originalCubes) {
				List<List<ModelPart.Cube>> pieces = CubeSplit.split(cube, cut.axis(), bounds);
				if (pieces != null) {
					for (int j = 0; j < k; j++) {
						cubes.get(j).addAll(pieces.get(j));
					}
				} else {
					// A block whose skin cannot be cut exactly goes whole, on the stretch its middle is in.
					float lo = cut.axis() == 0 ? cube.minX : cut.axis() == 1 ? cube.minY : cube.minZ;
					float hi = cut.axis() == 0 ? cube.maxX : cut.axis() == 1 ? cube.maxY : cube.maxZ;
					cubes.get(cut.segmentOf((lo + hi) * 0.5F)).add(cube);
				}
			}

			for (Map.Entry<String, ModelPart> entry : originalChildren.entrySet()) {
				ModelPart child = entry.getValue();
				PartPose pose = child.getInitialPose();
				int j = cut.segmentOf(cut.axis() == 0 ? pose.x() : cut.axis() == 1 ? pose.y() : pose.z());
				childSegment.put(child, j);
				children.get(j).put(entry.getKey(), child);
				if (j == 0 && sitsInFrame(pose) && crosses(child, cut)) {
					inside.add(new SplitPart(child, cut, bones));
				}
			}

			this.stretch = new ModelPart[k];
			this.inner = new ModelPart[k];
			for (int j = k - 1; j >= 1; j--) {
				Vector3f o = cut.origins()[j];
				inner[j] = new ModelPart(cubes.get(j), children.get(j));
				inner[j].setPos(-o.x, -o.y, -o.z);
				Map<String, ModelPart> frame = new LinkedHashMap<>();
				frame.put("fluidgore_flesh", inner[j]);
				if (j + 1 < k) {
					frame.put("fluidgore_stretch", stretch[j + 1]);
				}

				stretch[j] = new ModelPart(new ArrayList<>(), frame);
			}

			if (k > 1) {
				children.getFirst().put("fluidgore_stretch", stretch[1]);
			}

			this.cubes0 = cubes.getFirst();
			this.children0 = children.getFirst();
			this.rel = new Matrix4f[k];
			this.toSegment = new Matrix4f[k];
			for (int j = 0; j < k; j++) {
				rel[j] = new Matrix4f();
				toSegment[j] = new Matrix4f();
			}
		}

		private static boolean sitsInFrame(PartPose pose) {
			return pose.x() == 0.0F && pose.y() == 0.0F && pose.z() == 0.0F && pose.xRot() == 0.0F && pose.yRot() == 0.0F && pose.zRot() == 0.0F
					&& pose.xScale() == 1.0F && pose.yScale() == 1.0F && pose.zScale() == 1.0F;
		}

		private static boolean crosses(ModelPart part, Skeleton.Cut cut) {
			for (ModelPart.Cube cube : Skeleton.cubes(part)) {
				float lo = cut.axis() == 0 ? cube.minX : cut.axis() == 1 ? cube.minY : cube.minZ;
				float hi = cut.axis() == 0 ? cube.maxX : cut.axis() == 1 ? cube.maxY : cube.maxZ;
				for (float at : cut.at()) {
					if (lo < at && at < hi) {
						return true;
					}
				}
			}

			return false;
		}

		/** Takes this frame's stretch turns ({@link #rel}, in the part's own blocks) and works out where each stretch is. */
		void settle(Bends out) {
			int k = cut.segments();
			toSegment[0].identity();
			Matrix4f chain = new Matrix4f();
			for (int j = 1; j < k; j++) {
				chain.mul(rel[j]);
				Vector3f o = cut.origins()[j];
				toSegment[j].set(chain).translate(-o.x / 16.0F, -o.y / 16.0F, -o.z / 16.0F);
			}

			out.put(part, new Bends.Split(cut, toSegment, childSegment));
			for (SplitPart in : inside) {
				for (int j = 0; j < k; j++) {
					in.rel[j].set(rel[j]);
				}

				in.settle(out);
			}
		}

		void cut() {
			ModelPartAccessor access = (ModelPartAccessor) (Object) part;
			access.fluidgore$setCubes(cubes0);
			access.fluidgore$setChildren(children0);
			Vector3f euler = new Vector3f();
			Quaternionf turn = new Quaternionf();
			for (int j = 1; j < stretch.length; j++) {
				ModelPart s = stretch[j];
				Matrix4f r = rel[j];
				s.x = r.m30() * 16.0F;
				s.y = r.m31() * 16.0F;
				s.z = r.m32() * 16.0F;
				euler(r, euler);
				s.xRot = euler.x;
				s.yRot = euler.y;
				s.zRot = euler.z;
				inner[j].skipDraw = part.skipDraw;
			}

			for (SplitPart in : inside) {
				in.cut();
			}
		}

		void mend() {
			for (SplitPart in : inside) {
				in.mend();
			}

			ModelPartAccessor access = (ModelPartAccessor) (Object) part;
			access.fluidgore$setCubes(originalCubes);
			access.fluidgore$setChildren(originalChildren);
		}
	}

	private static Binding binding(Model<?> model, Skeleton skeleton) {
		return BINDINGS.computeIfAbsent(model, m -> new IdentityHashMap<>()).computeIfAbsent(skeleton, s -> new Binding(model, s));
	}

	/**
	 * Poses {@code model} from {@code pose}, writing the bends of its cut parts to {@code bends}; {@code save} sees every
	 * part before it is changed. Returns the poser's handle for {@link #cut}/{@link #mend}.
	 */
	public static Object apply(Model<?> model, RigPose pose, @Nullable Consumer<ModelPart> save, Bends bends) {
		Skeleton skeleton = pose.skeleton;
		Binding bind = binding(model, skeleton);
		int n = skeleton.bones.length;
		Matrix4f[] frames = new Matrix4f[n];
		for (int i = 0; i < n; i++) {
			frames[i] = pose.modelFrame(i, new Matrix4f());
		}

		Map<ModelPart, Matrix4f> placed = new IdentityHashMap<>();
		for (int i : bind.order) {
			place(bind, bind.parts[i], frames[i], save, placed);
		}

		for (Follower f : bind.followers) {
			place(bind, f.part(), new Matrix4f(frames[f.bone()]).mul(f.rel()), save, placed);
		}

		for (SplitPart split : bind.splits) {
			// The stretch offsets are in the part's own units: model blocks over every scale down to it (a horse is drawn 1.1x).
			Matrix4f drawn = placed.get(split.part);
			float scale = drawn != null ? drawn.getScale(new Vector3f()).x : split.part.xScale;
			scale = Math.abs(scale) > 1.0E-4F ? scale : 1.0F;
			for (int j = 1; j < split.bones.length; j++) {
				Matrix4f r = split.rel[j].set(frames[split.bones[j - 1]]).invert().mul(frames[split.bones[j]]);
				r.m30(r.m30() / scale);
				r.m31(r.m31() / scale);
				r.m32(r.m32() / scale);
			}

			split.settle(bends);
		}

		return bind;
	}

	/** Swaps the cut parts of a model posed by {@link #apply} into their stretches for the vanilla draw. */
	public static void cut(Object handle) {
		for (SplitPart split : ((Binding) handle).splits) {
			split.cut();
		}
	}

	/** Undoes {@link #cut}. */
	public static void mend(Object handle) {
		for (SplitPart split : ((Binding) handle).splits) {
			split.mend();
		}
	}

	/** Sets a part's offset and turn so its frame lands on {@code frame} (model space, rigid). */
	private static void place(Binding bind, ModelPart part, Matrix4f frame, @Nullable Consumer<ModelPart> save, Map<ModelPart, Matrix4f> placed) {
		ModelPart parent = bind.parents.get(part);
		Matrix4f parentFrame = parent == null ? new Matrix4f() : modelSpace(bind, parent, placed);
		Matrix4f local = new Matrix4f(parentFrame).invert().mul(frame);
		if (save != null) {
			save.accept(part);
		}

		part.x = local.m30() * 16.0F;
		part.y = local.m31() * 16.0F;
		part.z = local.m32() * 16.0F;
		Vector3f euler = euler(local, new Vector3f());
		part.xRot = euler.x;
		part.yRot = euler.y;
		part.zRot = euler.z;
		placed.put(part, new Matrix4f(parentFrame).mul(localOf(part)));
	}

	/** A part's drawn transform in model space, through its parents as they are posed now. */
	private static Matrix4f modelSpace(Binding bind, ModelPart part, Map<ModelPart, Matrix4f> placed) {
		Matrix4f known = placed.get(part);
		if (known != null) {
			return known;
		}

		ModelPart parent = bind.parents.get(part);
		Matrix4f m = parent == null ? new Matrix4f() : new Matrix4f(modelSpace(bind, parent, placed));
		m.mul(localOf(part));
		placed.put(part, m);
		return m;
	}

	private static Matrix4f localOf(ModelPart part) {
		Matrix4f m = new Matrix4f().translation(part.x / 16.0F, part.y / 16.0F, part.z / 16.0F);
		if (part.xRot != 0.0F || part.yRot != 0.0F || part.zRot != 0.0F) {
			m.rotate(new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot));
		}

		if (part.xScale != 1.0F || part.yScale != 1.0F || part.zScale != 1.0F) {
			m.scale(part.xScale, part.yScale, part.zScale);
		}

		return m;
	}

	/** Multiplies {@code out} by every transform from the model's root down to {@code part}, as drawn now. */
	public static Matrix4f partMatrix(ModelPart root, ModelPart part, Matrix4f out) {
		for (ModelPart link : dev.fluidgore.dismember.ModelParts.chain(root, part)) {
			out.mul(localOf(link));
		}

		return out;
	}

	/** The part angles (x, y, z, applied z then y then x, as {@code ModelPart} does) of a rigid transform's turn. */
	private static Vector3f euler(Matrix4f m, Vector3f out) {
		org.joml.Matrix3f r = new org.joml.Matrix3f().set(m.getNormalizedRotation(new Quaternionf()));
		// Turned a quarter turn about y the other two angles become one: rounding there must not make it NaN.
		float sin = Math.max(-1.0F, Math.min(1.0F, -r.m02));
		out.y = (float) Math.asin(sin);
		if (Math.abs(sin) < 0.99999F) {
			out.x = (float) Math.atan2(r.m12, r.m22);
			out.z = (float) Math.atan2(r.m01, r.m00);
		} else {
			out.x = (float) Math.atan2(-r.m21, r.m11);
			out.z = 0.0F;
		}

		return out;
	}

	private static Matrix4f rigid(Matrix4f m) {
		return new Matrix4f().translation(m.getTranslation(new Vector3f())).rotate(m.getNormalizedRotation(new Quaternionf()));
	}
}
