package dev.fluidgore.wound;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.suffer.BodyPose;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Finds where on an entity's model something landed, against the real cubes posed exactly as they are drawn this
 * frame (animation, body turn, ragdoll lean and joints), not the entity's bounding box. Works for any model built
 * from {@link ModelPart}s, so modded mobs get hit zones too.
 */
public final class ModelHit {
	/** Vanilla living-entity renderers shift the model down by this amount after flipping it. */
	private static final float MODEL_Y_OFFSET = 1.501F;

	/**
	 * A point on the surface of one cube face.
	 *
	 * @param axis     0, 1, 2 for the face's normal axis (x, y, z) in the part's own space
	 * @param sign     +1 for the cube's max face on that axis, -1 for its min face
	 * @param local    the point in the part's own space, in model pixels
	 * @param bodyPart the severable part this cube belongs to, null for the torso and anything else fixed
	 * @param point    the point in world space
	 */
	public record Result(ModelPart part, ModelPart.Cube cube, int axis, int sign, Vector3f local,
			@Nullable BodyPart bodyPart, Vec3 point, Vec3 normal, double distance) {
	}

	private interface Visitor {
		void visit(ModelPart part, Matrix4f pose, @Nullable BodyPart bodyPart);
	}

	private ModelHit() {
	}

	/** The nearest cube face hit by the ray from {@code from} along unit vector {@code dir}, within {@code reach}. */
	public static @Nullable Result cast(LivingEntity entity, ModelParts.Posed posed, Vec3 from, Vec3 dir, double reach) {
		Result[] best = {null};
		Vector3f origin = new Vector3f();
		Vector3f direction = new Vector3f();
		Matrix4f inverse = new Matrix4f();
		walk(entity, posed, from, (part, pose, bodyPart) -> {
			pose.invert(inverse);
			inverse.transformPosition(origin.set(0.0F));
			inverse.transformDirection(direction.set((float) dir.x, (float) dir.y, (float) dir.z));
			for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).fluidgore$getCubes()) {
				double limit = best[0] != null ? best[0].distance() : reach;
				Result hit = slab(part, cube, origin, direction, limit, pose, bodyPart, from, dir);
				if (hit != null) {
					best[0] = hit;
				}
			}
		});
		return best[0];
	}

	/**
	 * The surface point nearest to where {@code severed} was attached, on any part outside the severed one: the
	 * stump left on the body. Null if the model has no other cubes.
	 */
	public static @Nullable Result stump(LivingEntity entity, ModelParts.Posed posed, ModelPart severed) {
		Vec3 base = new Vec3(posed.state().x, posed.state().y, posed.state().z);
		Vector3f pivot = new Vector3f();
		boolean[] found = {false};
		walk(entity, posed, base, (part, pose, bodyPart) -> {
			if (part == severed) {
				pose.getTranslation(pivot);
				found[0] = true;
			}
		});
		if (!found[0]) {
			return null;
		}

		List<ModelPart> severedTree = severed.getAllParts();
		Result[] best = {null};
		double[] bestDist = {Double.MAX_VALUE};
		Vector3f local = new Vector3f();
		Matrix4f inverse = new Matrix4f();
		walk(entity, posed, base, (part, pose, bodyPart) -> {
			if (severedTree.contains(part)) {
				return;
			}

			pose.invert(inverse);
			inverse.transformPosition(local.set(pivot));
			local.mul(16.0F);
			for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).fluidgore$getCubes()) {
				float[] min = {cube.minX, cube.minY, cube.minZ};
				float[] max = {cube.maxX, cube.maxY, cube.maxZ};
				for (int axis = 0; axis < 3; axis++) {
					for (int sign = -1; sign <= 1; sign += 2) {
						Vector3f onFace = new Vector3f(
								Mth.clamp(local.x, min[0], max[0]), Mth.clamp(local.y, min[1], max[1]), Mth.clamp(local.z, min[2], max[2]));
						onFace.setComponent(axis, sign > 0 ? max[axis] : min[axis]);
						Vector3f world = pose.transformPosition(new Vector3f(onFace).div(16.0F));
						double dist = world.distanceSquared(pivot);
						if (dist < bestDist[0]) {
							bestDist[0] = dist;
							Vector3f n = pose.transformDirection(new Vector3f().setComponent(axis, sign)).normalize();
							best[0] = new Result(part, cube, axis, sign, onFace, bodyPart, base.add(world.x, world.y, world.z),
									new Vec3(n.x, n.y, n.z), Math.sqrt(dist));
						}
					}
				}
			}
		});
		return best[0];
	}

	/**
	 * The cut face of a severed part: the spot on its own cubes nearest its pivot, where it was joined to the body.
	 * In the part's own space (no world position). Null if the part has no cubes of its own.
	 */
	public static @Nullable Result cutFace(ModelPart part, @Nullable BodyPart bodyPart) {
		Result best = null;
		float bestDist = Float.MAX_VALUE;
		for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).fluidgore$getCubes()) {
			float[] min = {cube.minX, cube.minY, cube.minZ};
			float[] max = {cube.maxX, cube.maxY, cube.maxZ};
			for (int axis = 0; axis < 3; axis++) {
				for (int sign = -1; sign <= 1; sign += 2) {
					Vector3f onFace = new Vector3f(Mth.clamp(0.0F, min[0], max[0]), Mth.clamp(0.0F, min[1], max[1]), Mth.clamp(0.0F, min[2], max[2]));
					onFace.setComponent(axis, sign > 0 ? max[axis] : min[axis]);
					float dist = onFace.lengthSquared();
					if (dist < bestDist) {
						bestDist = dist;
						best = new Result(part, cube, axis, sign, onFace, bodyPart, Vec3.ZERO, Vec3.ZERO, Math.sqrt(dist));
					}
				}
			}
		}

		return best;
	}

	/** World-space centre of a severable part as posed now, or null if the model has none. For tests and aiming. */
	public static @Nullable Vec3 centre(LivingEntity entity, ModelParts.Posed posed, BodyPart target) {
		return centre(entity, posed, ModelParts.index(posed.model())[target.ordinal()]);
	}

	/** World-space centre of a named model part, including the torso. */
	public static @Nullable Vec3 centre(LivingEntity entity, ModelParts.Posed posed, @Nullable ModelPart wanted) {
		if (wanted == null) {
			return null;
		}

		Vec3 base = new Vec3(posed.state().x, posed.state().y, posed.state().z);
		Vec3[] out = {null};
		walk(entity, posed, base, (part, pose, bodyPart) -> {
			if (part == wanted) {
				float[] b = ModelParts.bounds(part);
				Vector3f c = pose.transformPosition(new Vector3f((b[0] + b[3]) * 0.5F, (b[1] + b[4]) * 0.5F, (b[2] + b[5]) * 0.5F));
				out[0] = base.add(c.x, c.y, c.z);
			}
		});
		return out[0];
	}

	private static @Nullable Result slab(ModelPart part, ModelPart.Cube cube, Vector3f o, Vector3f d, double limit, Matrix4f pose,
			@Nullable BodyPart bodyPart, Vec3 from, Vec3 dir) {
		float[] min = {cube.minX / 16.0F, cube.minY / 16.0F, cube.minZ / 16.0F};
		float[] max = {cube.maxX / 16.0F, cube.maxY / 16.0F, cube.maxZ / 16.0F};
		double near = Double.NEGATIVE_INFINITY;
		double far = Double.POSITIVE_INFINITY;
		int axis = -1;
		int sign = 0;
		for (int i = 0; i < 3; i++) {
			float oi = o.get(i);
			float di = d.get(i);
			if (Math.abs(di) < 1.0E-9F) {
				if (oi < min[i] || oi > max[i]) {
					return null;
				}

				continue;
			}

			double t1 = (min[i] - oi) / di;
			double t2 = (max[i] - oi) / di;
			double enter = Math.min(t1, t2);
			if (enter > near) {
				near = enter;
				axis = i;
				// Moving towards +axis enters through the min face.
				sign = di > 0.0F ? -1 : 1;
			}

			far = Math.min(far, Math.max(t1, t2));
			if (near > far) {
				return null;
			}
		}

		// Starting inside a cube (the camera inside a mob) gives no face to mark.
		if (axis < 0 || near < 0.0 || near > limit) {
			return null;
		}

		Vector3f local = new Vector3f(d).mul((float) near).add(o).mul(16.0F);
		// Exactly on the face, so rounding never puts the wound a hair inside the cube.
		local.setComponent(axis, (sign > 0 ? max[axis] : min[axis]) * 16.0F);
		Vector3f n = pose.transformDirection(new Vector3f().setComponent(axis, sign)).normalize();
		return new Result(part, cube, axis, sign, local, bodyPart, from.add(dir.scale(near)), new Vec3(n.x, n.y, n.z), near);
	}

	/**
	 * Visits every visible part with its pose relative to {@code relativeTo}, mirroring {@code LivingEntityRenderer}
	 * (scale, body turn, our ragdoll offset and lean, vanilla's death tip-over, flip, shift) and
	 * {@code ModelPart.translateAndRotate}. Severed parts are skipped with everything attached to them.
	 */
	private static void walk(LivingEntity entity, ModelParts.Posed posed, Vec3 relativeTo, Visitor visitor) {
		LivingEntityRenderState state = posed.state();
		Matrix4f root = new Matrix4f().translation((float) (state.x - relativeTo.x), (float) (state.y - relativeTo.y), (float) (state.z - relativeTo.z));
		root.scale(state.scale);
		BodyPose body = state.getData(BodyPose.KEY);
		if (body != null) {
			root.translate((float) body.dx(), (float) body.dy(), (float) body.dz());
		}

		root.rotateY((180.0F - state.bodyRot) * Mth.DEG_TO_RAD);
		if (body != null) {
			root.translate(0.0F, state.boundingBoxWidth * 0.5F / state.scale * Mth.abs(Mth.sin(body.roll())), 0.0F);
			root.rotateZ(body.roll());
		} else if (state.deathTime > 0.0F) {
			root.rotateZ(Math.min(1.0F, Mth.sqrt((state.deathTime - 1.0F) / 20.0F * 1.6F)) * Mth.HALF_PI);
		}

		root.scale(-1.0F, -1.0F, 1.0F);
		root.translate(0.0F, -MODEL_Y_OFFSET, 0.0F);
		ModelPart[] index = ModelParts.index(posed.model());
		int missing = GoreSystems.dismemberment().missingMask(entity.getId());
		visit(posed.model().root(), root, null, index, missing, visitor);
	}

	private static void visit(ModelPart part, Matrix4f parent, @Nullable BodyPart bodyPart, ModelPart[] index, int missing, Visitor visitor) {
		if (!part.visible) {
			return;
		}

		BodyPart here = bodyPart;
		for (BodyPart candidate : BodyPart.values()) {
			if (index[candidate.ordinal()] == part) {
				if ((missing & candidate.bit()) != 0) {
					return;
				}

				here = candidate;
			}
		}

		Matrix4f pose = new Matrix4f(parent).translate(part.x / 16.0F, part.y / 16.0F, part.z / 16.0F);
		if (part.xRot != 0.0F || part.yRot != 0.0F || part.zRot != 0.0F) {
			pose.rotate(new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot));
		}

		if (part.xScale != 1.0F || part.yScale != 1.0F || part.zScale != 1.0F) {
			pose.scale(part.xScale, part.yScale, part.zScale);
		}

		if (!part.skipDraw) {
			visitor.visit(part, pose, here);
		}

		for (ModelPart child : ((ModelPartAccessor) (Object) part).fluidgore$getChildren().values()) {
			visit(child, pose, here, index, missing, visitor);
		}
	}
}
