package dev.fluidgore.wound;

import dev.fluidgore.dismember.ModelParts;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Blood running down the skin. From a bleeding wound it is followed down the model as posed now, a little at a time:
 * it keeps to any skin within about a pixel of where it falls, the way a film of blood clings to a chest or the front
 * of a leg, runs down the sides of cubes, and drops off where nothing is close enough below. A cube's underside sheds
 * it and a face turned up holds it (there it pools instead). Each straight stretch on one face is kept as a
 * {@link WoundSet.Run} in the part's own space, so it stays put on the skin however the body moves afterwards.
 * <p>
 * Traced when a blow opens a wound (the animal on its feet), and again once a body has come to rest, so a cut throat
 * runs down the chest and forelegs, and later across the neck and flank of the side the body lies on.
 */
final class Runs {
	/** How far blood falls per step, and how near skin must be to hold it, in model pixels. */
	private static final float STEP = 0.35F;
	private static final float CLING = 1.3F;
	private static final int MAX_STEPS = 120;
	/** A run lifts off the skin this far, under the wound's own layers. */
	private static final float OUT = 0.3F;

	private record Sample(int box, int axis, int sign, float b, float c) {
	}

	private Runs() {
	}

	/**
	 * Lets blood run from wound {@code wound} of {@code set}. A sticking wound pours: a wide sheet in three streams
	 * that reaches the hooves. Anything else trickles, as far as its depth gives it blood.
	 */
	static void trace(WoundSet set, LivingEntity entity, ModelParts.Posed posed, int wound, long now, RandomSource random) {
		if (!set.flesh.bleeds() || set.severity[wound] < 0.25F || set.morph[wound] == WoundSet.PLUCK || set.morph[wound] == WoundSet.CUT) {
			return;
		}

		Vec3 base = new Vec3(posed.state().x, posed.state().y, posed.state().z);
		List<ModelHit.Box> boxes = ModelHit.boxes(entity, posed, base);
		Bounds[] all = new Bounds[boxes.size()];
		for (int k = 0; k < all.length; k++) {
			all[k] = Bounds.of(boxes.get(k));
		}

		int from = find(boxes, set, wound);
		if (from < 0) {
			return;
		}

		float severity = set.severity[wound];
		float px = posed.state().scale / 16.0F;
		if (set.morph[wound] != WoundSet.STICK) {
			float width = Math.min(set.radius[wound] * 0.8F, 0.6F + 1.4F * severity);
			stream(set, boxes, all, from, wound, 0.0F, width, (2.0F + 12.0F * severity) * (0.8F + 0.4F * random.nextFloat()), px, now, random);
			return;
		}

		// A stuck animal pours: a sheet most of the width of the throat, coming down the chest in five streams that
		// reach the hooves and coat the fronts of both forelegs.
		int a = set.axis[wound];
		int across = set.variant[wound] % 4 == 0 ? (a + 2) % 3 : (a + 1) % 3;
		Bounds bounds = Bounds.of(set.cube[wound]);
		float width = (bounds.max[across] - bounds.min[across]) * (0.55F + 0.3F * severity);
		int streams = 5;
		for (int s = 0; s < streams; s++) {
			float offset = (s / (streams - 1.0F) - 0.5F) * 0.85F * width;
			stream(set, boxes, all, from, wound, offset, width / streams * 1.5F, (10.0F + 18.0F * severity) * (0.85F + 0.3F * random.nextFloat()), px, now,
					random);
		}
	}

	/** The box the wound is on: its part, its cube, and the stretch of it that holds the spot. */
	private static int find(List<ModelHit.Box> boxes, WoundSet set, int wound) {
		for (int k = 0; k < boxes.size(); k++) {
			ModelHit.Box box = boxes.get(k);
			if (box.part() != set.part[wound] || box.cube() != set.cube[wound]) {
				continue;
			}

			float along = coordinate(set, wound, box.cutAxis());
			if (along >= box.lo() && along <= box.hi()) {
				return k;
			}
		}

		return -1;
	}

	/** The wound's spot, in part space pixels, along model axis {@code axis}. */
	private static float coordinate(WoundSet set, int wound, int axis) {
		int a = set.axis[wound];
		Bounds bounds = Bounds.of(set.cube[wound]);
		if (axis == a) {
			return set.sign[wound] > 0 ? bounds.max[a] : bounds.min[a];
		}

		return axis == (a + 1) % 3 ? set.u[wound] : set.v[wound];
	}

	/** One stream, {@code offset} pixels across the wound from its middle. */
	private static void stream(WoundSet set, List<ModelHit.Box> boxes, Bounds[] all, int from, int wound, float offset, float width, float reach, float px, long now,
			RandomSource random) {
		int a = set.axis[wound];
		int b = (a + 1) % 3;
		int c = (a + 2) % 3;
		Bounds bounds = Bounds.of(set.cube[wound]);
		float[] local = new float[3];
		local[a] = set.sign[wound] > 0 ? bounds.max[a] : bounds.min[a];
		// A sticking cut lies along the neck (its variant's angle); the streams spread across it.
		boolean alongB = set.variant[wound] % 4 == 0;
		local[b] = clamp(set.u[wound] + (alongB ? 0.0F : offset), bounds.min[b], bounds.max[b]);
		local[c] = clamp(set.v[wound] + (alongB ? offset : 0.0F), bounds.min[c], bounds.max[c]);
		ModelHit.Box box = boxes.get(from);
		Vector3f q = box.pose().transformPosition(new Vector3f(local[0], local[1], local[2]).div(16.0F));
		Vector3f n = normal(box, a, set.sign[wound]);
		q.add(new Vector3f(n).mul(OUT * px));

		List<Sample> samples = new ArrayList<>();
		Vector3f point = new Vector3f();
		Vector3f p = new Vector3f();
		float run = 0.0F;
		for (int step = 0; step < MAX_STEPS && run < reach; step++) {
			q.y -= STEP * px;
			Sample sample = nearest(boxes, all, q, px, point, p);
			if (sample == null) {
				break;
			}

			ModelHit.Box on = boxes.get(sample.box());
			q.set(point).add(normal(on, sample.axis(), sample.sign()).mul(0.05F * px));
			samples.add(sample);
			run += STEP;
		}

		keep(set, boxes, samples, width, now, random);
	}

	/**
	 * The nearest skin to {@code q} that can hold blood falling there: within {@link #CLING}, no higher than it, a
	 * face that is neither an underside nor turned up, and not buried inside another cube. Its world point goes in
	 * {@code point}.
	 */
	private static @Nullable Sample nearest(List<ModelHit.Box> boxes, Bounds[] all, Vector3f q, float px, Vector3f point, Vector3f scratch) {
		Sample best = null;
		float bestDist = CLING * px;
		for (int k = 0; k < boxes.size(); k++) {
			ModelHit.Box box = boxes.get(k);
			Bounds bounds = all[k];
			Vector3f l = box.inverse().transformPosition(scratch.set(q)).mul(16.0F);
			float[] p = {l.x, l.y, l.z};
			int axis = -1;
			int sign = 0;
			float outside = 0.0F;
			boolean inside = true;
			for (int i = 0; i < 3; i++) {
				if (p[i] < bounds.min[i] || p[i] > bounds.max[i]) {
					inside = false;
					float by = p[i] < bounds.min[i] ? bounds.min[i] - p[i] : p[i] - bounds.max[i];
					if (by > outside) {
						outside = by;
						axis = i;
						sign = p[i] > bounds.max[i] ? 1 : -1;
					}
				}
			}

			if (inside) {
				float nearestFace = Float.MAX_VALUE;
				for (int i = 0; i < 3; i++) {
					if (p[i] - bounds.min[i] < nearestFace) {
						nearestFace = p[i] - bounds.min[i];
						axis = i;
						sign = -1;
					}

					if (bounds.max[i] - p[i] < nearestFace) {
						nearestFace = bounds.max[i] - p[i];
						axis = i;
						sign = 1;
					}
				}
			}

			if (axis == box.cutAxis() && bounds.cut(sign)) {
				// The cut end of a ragdoll's stretch is inside the limb.
				continue;
			}

			for (int i = 0; i < 3; i++) {
				p[i] = clamp(p[i], bounds.min[i], bounds.max[i]);
			}

			p[axis] = sign > 0 ? bounds.max[axis] : bounds.min[axis];
			Vector3f world = box.pose().transformPosition(new Vector3f(p[0], p[1], p[2]).div(16.0F));
			float dist = world.distance(q);
			if (dist > bestDist || world.y > q.y + 1.0E-5F) {
				continue;
			}

			Vector3f n = normal(box, axis, sign);
			if (n.y < -0.6F || n.y > 0.8F || buried(boxes, all, k, new Vector3f(world).add(new Vector3f(n).mul(OUT * px)))) {
				continue;
			}

			bestDist = dist;
			point.set(world);
			best = new Sample(k, axis, sign, p[(axis + 1) % 3], p[(axis + 2) % 3]);
		}

		return best;
	}

	/** True if {@code world} is inside any cube but box {@code except}. */
	private static boolean buried(List<ModelHit.Box> boxes, Bounds[] all, int except, Vector3f world) {
		Vector3f l = new Vector3f();
		for (int k = 0; k < boxes.size(); k++) {
			if (k == except) {
				continue;
			}

			ModelHit.Box box = boxes.get(k);
			Bounds bounds = all[k];
			box.inverse().transformPosition(l.set(world)).mul(16.0F);
			if (l.x > bounds.min[0] + 0.05F && l.x < bounds.max[0] - 0.05F && l.y > bounds.min[1] + 0.05F && l.y < bounds.max[1] - 0.05F
					&& l.z > bounds.min[2] + 0.05F && l.z < bounds.max[2] - 0.05F) {
				return true;
			}
		}

		return false;
	}

	/** Groups the samples into one run per face crossed, {@code width} wide. */
	private static void keep(WoundSet set, List<ModelHit.Box> boxes, List<Sample> samples, float width, long now, RandomSource random) {
		int start = 0;
		for (int i = 1; i <= samples.size(); i++) {
			Sample first = samples.get(start);
			if (i < samples.size()) {
				Sample next = samples.get(i);
				if (next.box() == first.box() && next.axis() == first.axis() && next.sign() == first.sign()) {
					continue;
				}
			}

			Sample last = samples.get(i - 1);
			float db = last.b() - first.b();
			float dc = last.c() - first.c();
			boolean alongC = Math.abs(dc) >= Math.abs(db);
			float half = width * 0.5F;
			float lead = STEP * 0.5F;
			float b0 = Math.min(first.b(), last.b());
			float b1 = Math.max(first.b(), last.b());
			float c0 = Math.min(first.c(), last.c());
			float c1 = Math.max(first.c(), last.c());
			if (alongC) {
				b0 -= half;
				b1 += half;
				c0 -= lead;
				c1 += lead;
			} else {
				c0 -= half;
				c1 += half;
				b0 -= lead;
				b1 += lead;
			}

			ModelHit.Box box = boxes.get(first.box());
			set.addRun(new WoundSet.Run(box.part(), box.cube(), box.bodyPart(), first.axis(), first.sign(), alongC, alongC ? dc < 0.0F : db < 0.0F,
					b0, b1, c0, c1, random.nextInt(4), now));
			start = i;
		}
	}

	private static Vector3f normal(ModelHit.Box box, int axis, int sign) {
		return box.pose().transformDirection(new Vector3f().setComponent(axis, sign)).normalize();
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	/** A cube's bounds in model pixels, narrowed to a ragdoll stretch's span along its cut axis. */
	private static final class Bounds {
		final float[] min = new float[3];
		final float[] max = new float[3];
		private final float whole0;
		private final float whole1;
		private final int cutAxis;

		private Bounds(ModelPart.Cube cube, int cutAxis, float lo, float hi) {
			min[0] = cube.minX;
			min[1] = cube.minY;
			min[2] = cube.minZ;
			max[0] = cube.maxX;
			max[1] = cube.maxY;
			max[2] = cube.maxZ;
			this.cutAxis = cutAxis;
			whole0 = min[cutAxis];
			whole1 = max[cutAxis];
			min[cutAxis] = Math.max(min[cutAxis], lo);
			max[cutAxis] = Math.min(max[cutAxis], hi);
		}

		static Bounds of(ModelPart.Cube cube) {
			return new Bounds(cube, 0, -Float.MAX_VALUE, Float.MAX_VALUE);
		}

		static Bounds of(ModelHit.Box box) {
			return new Bounds(box.cube(), box.cutAxis(), box.lo(), box.hi());
		}

		/** True if that face along the cut axis is a stretch's cut end, inside the limb. */
		boolean cut(int sign) {
			return sign < 0 ? min[cutAxis] > whole0 : max[cutAxis] < whole1;
		}
	}
}
