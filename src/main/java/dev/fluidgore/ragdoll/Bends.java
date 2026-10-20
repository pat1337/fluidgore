package dev.fluidgore.ragdoll;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Where the stretches of cut parts are this frame. Gore data (wounds, rot, char, hits) stays on the original part and
 * block in its own coordinates; a point past a cut is moved into its stretch with {@link Split#toSegment}. Set around
 * each draw by the renderer ({@link #current}), and carried by a posed model for hit tests.
 */
public final class Bends {
	/**
	 * One cut part as posed: {@code toSegment[j]} takes a point of the part (blocks, its own frame) on stretch {@code j} to
	 * where that stretch is drawn now, in the same frame. {@code childSegment} says which stretch each child rides on.
	 */
	public record Split(Skeleton.Cut cut, Matrix4f[] toSegment, Map<ModelPart, Integer> childSegment) {
		public int segmentOf(float coordinate) {
			return cut.segmentOf(coordinate);
		}
	}

	private static @Nullable Bends current;
	private static final Matrix4f M = new Matrix4f();
	private static final Matrix3f N = new Matrix3f();
	private static final Matrix3f R = new Matrix3f();
	private static final Vector3f NORMAL = new Vector3f();
	private static final Vector3f POS = new Vector3f();
	private static final float[] CORNER = new float[3];

	private final Map<ModelPart, Split> splits = new IdentityHashMap<>();

	public void put(ModelPart part, Split split) {
		splits.put(part, split);
	}

	public @Nullable Split of(ModelPart part) {
		return splits.get(part);
	}

	public boolean isEmpty() {
		return splits.isEmpty();
	}

	public void clear() {
		splits.clear();
	}

	/** The bends of the model being drawn now, or null outside a ragdolled body's draw. */
	public static @Nullable Bends current() {
		return current;
	}

	public static void begin(@Nullable Bends bends) {
		current = bends;
	}

	public static void end() {
		current = null;
	}

	/**
	 * Moves into {@code child} as drawn: through the stretch of its parent it rides on (if the parent is cut), then its own
	 * pose. The caller pushes and pops around it.
	 */
	public static void enter(PoseStack stack, ModelPart parent, ModelPart child) {
		Bends bends = current;
		Split split = bends != null ? bends.of(parent) : null;
		if (split != null) {
			Integer segment = split.childSegment().get(child);
			if (segment != null && segment > 0) {
				stack.mulPose(split.toSegment()[segment]);
			}
		}

		child.translateAndRotate(stack);
	}

	/** {@code translateAndRotate} down a chain from the root, through the stretches each link rides on. */
	public static void chain(PoseStack stack, ModelPart[] chain) {
		for (int i = 0; i < chain.length; i++) {
			if (i == 0) {
				chain[i].translateAndRotate(stack);
			} else {
				enter(stack, chain[i - 1], chain[i]);
			}
		}
	}

	/**
	 * The matrix to draw at a point of a part whose coordinate on {@code axis} is {@code coordinate} (pixels): the
	 * part's own, or that of the stretch the point is on. Valid until the next call.
	 */
	public static Matrix4f matrixAt(PoseStack.Pose pose, @Nullable ModelPart part, int axis, float coordinate) {
		Bends bends = current;
		Split split = bends != null && part != null ? bends.of(part) : null;
		if (split == null || split.cut().axis() != axis) {
			return pose.pose();
		}

		return M.set(pose.pose()).mul(split.toSegment()[split.segmentOf(coordinate)]);
	}

	/**
	 * Draws a face-aligned quad in a part's own coordinates (pixels): {@code depth} on axis {@code a}, spanning
	 * {@code b0..b1} on axis {@code b} and {@code c0..c1} on axis {@code c}, with uv from {@code u0, v0} to
	 * {@code u1, v1}. Where the part is cut across the quad, each stretch of it is drawn where that stretch is.
	 */
	public static void quad(VertexConsumer buffer, PoseStack.Pose pose, @Nullable ModelPart part, int a, int b, int c, float depth, float sign,
			float b0, float b1, float c0, float c1, float u0, float u1, float v0, float v1, int color, int light, int overlay) {
		Bends bends = current;
		Split split = bends != null && part != null ? bends.of(part) : null;
		if (split == null) {
			emit(buffer, pose.pose(), pose.normal(), a, b, c, depth, sign, b0, b1, c0, c1, u0, u1, v0, v1, color, light, overlay);
			return;
		}

		int axis = split.cut().axis();
		if (axis == a) {
			int j = split.segmentOf(depth);
			emitIn(buffer, pose, split.toSegment()[j], a, b, c, depth, sign, b0, b1, c0, c1, u0, u1, v0, v1, color, light, overlay);
			return;
		}

		float[] bounds = split.cut().bounds();
		boolean alongB = axis == b;
		float lo = alongB ? b0 : c0;
		float hi = alongB ? b1 : c1;
		for (int j = 0; j + 1 < bounds.length; j++) {
			float from = Math.max(lo, bounds[j]);
			float to = Math.min(hi, bounds[j + 1]);
			if (to <= from) {
				continue;
			}

			float f0 = (from - lo) / (hi - lo);
			float f1 = (to - lo) / (hi - lo);
			if (alongB) {
				emitIn(buffer, pose, split.toSegment()[j], a, b, c, depth, sign, from, to, c0, c1, u0 + (u1 - u0) * f0, u0 + (u1 - u0) * f1, v0, v1,
						color, light, overlay);
			} else {
				emitIn(buffer, pose, split.toSegment()[j], a, b, c, depth, sign, b0, b1, from, to, u0, u1, v0 + (v1 - v0) * f0, v0 + (v1 - v0) * f1,
						color, light, overlay);
			}
		}
	}

	private static void emitIn(VertexConsumer buffer, PoseStack.Pose pose, Matrix4f toSegment, int a, int b, int c, float depth, float sign,
			float b0, float b1, float c0, float c1, float u0, float u1, float v0, float v1, int color, int light, int overlay) {
		M.set(pose.pose()).mul(toSegment);
		N.set(pose.normal()).mul(toSegment.get3x3(R));
		emit(buffer, M, N, a, b, c, depth, sign, b0, b1, c0, c1, u0, u1, v0, v1, color, light, overlay);
	}

	private static void emit(VertexConsumer buffer, Matrix4f m, Matrix3f normal, int a, int b, int c, float depth, float sign,
			float b0, float b1, float c0, float c1, float u0, float u1, float v0, float v1, int color, int light, int overlay) {
		normal.transform(NORMAL.set(0.0F).setComponent(a, sign)).normalize();
		vertex(buffer, m, a, b, c, depth, b0, c0, color, u0, v0, light, overlay);
		vertex(buffer, m, a, b, c, depth, b1, c0, color, u1, v0, light, overlay);
		vertex(buffer, m, a, b, c, depth, b1, c1, color, u1, v1, light, overlay);
		vertex(buffer, m, a, b, c, depth, b0, c1, color, u0, v1, light, overlay);
	}

	private static void vertex(VertexConsumer buffer, Matrix4f m, int a, int b, int c, float depth, float pb, float pc, int color, float u, float v,
			int light, int overlay) {
		CORNER[a] = depth / 16.0F;
		CORNER[b] = pb / 16.0F;
		CORNER[c] = pc / 16.0F;
		m.transformPosition(CORNER[0], CORNER[1], CORNER[2], POS);
		buffer.addVertex(POS.x, POS.y, POS.z, color, u, v, overlay, light, NORMAL.x, NORMAL.y, NORMAL.z);
	}
}
