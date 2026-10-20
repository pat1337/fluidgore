package dev.fluidgore.ragdoll;

import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.wound.ModelHit;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Where every segment of a ragdolled body is this frame: each bone's frame (origin and turn) in world axes, measured
 * from a world point. The renderer turns these into model-part poses ({@link Poser}).
 *
 * <p>A rigged body is drawn with {@code bodyRot} 180 and no roll, offset to {@link #ox}/{@link #oy}/{@link #oz}, so its
 * model space is world space turned half a turn about z ({@code (x, y, z) -> (-x, -y, z)}), scaled by the entity's
 * scale and shifted down by vanilla's 1.501.
 */
public final class RigPose {
	public static final RenderStateDataKey<RigPose> KEY = RenderStateDataKey.create(() -> "fluidgore:rig_pose");
	/** Vanilla living-entity renderers shift the model down by this amount after flipping it. */
	public static final float MODEL_Y_OFFSET = 1.501F;
	/** Half a turn about z: model space to world axes and back. */
	private static final Quaternionf FLIP = new Quaternionf(0.0F, 0.0F, 1.0F, 0.0F);

	public final Skeleton skeleton;
	public final double ox;
	public final double oy;
	public final double oz;
	public final float scale;
	/** Frame origin of bone i at [3i..3i+2], blocks from the origin, world axes. */
	public final float[] pos;
	/** Frame turn of bone i at [4i..4i+3] (x, y, z, w), world axes. */
	public final float[] rot;

	public RigPose(Skeleton skeleton, double ox, double oy, double oz, float scale, float[] pos, float[] rot) {
		this.skeleton = skeleton;
		this.ox = ox;
		this.oy = oy;
		this.oz = oz;
		this.scale = scale;
		this.pos = pos;
		this.rot = rot;
	}

	/** Bone {@code i}'s frame in model space (blocks, rigid), as the renderer needs it. */
	public Matrix4f modelFrame(int i, Matrix4f out) {
		float s = Math.max(1.0E-4F, scale);
		float x = -pos[3 * i] / s;
		float y = -pos[3 * i + 1] / s + MODEL_Y_OFFSET;
		float z = pos[3 * i + 2] / s;
		Quaternionf world = new Quaternionf(rot[4 * i], rot[4 * i + 1], rot[4 * i + 2], rot[4 * i + 3]);
		Quaternionf model = new Quaternionf(FLIP).mul(world);
		return out.translation(x, y, z).rotate(model);
	}

	/** Bone {@code i}'s frame origin in the world. */
	public Vec3 origin(int i) {
		return new Vec3(ox + pos[3 * i], oy + pos[3 * i + 1], oz + pos[3 * i + 2]);
	}

	public Quaternionf turn(int i) {
		return new Quaternionf(rot[4 * i], rot[4 * i + 1], rot[4 * i + 2], rot[4 * i + 3]);
	}

	/**
	 * The segments of a body exactly where its model is drawn now (posed for it, with whatever lean and joints the old
	 * ragdoll gave it), measured from {@code origin}. Null if the skeleton does not fit the model.
	 */
	public static @Nullable RigPose capture(LivingEntity entity, ModelParts.Posed posed, Skeleton skeleton, Vec3 origin) {
		int n = skeleton.bones.length;
		float[] pos = new float[3 * n];
		float[] rot = new float[4 * n];
		Matrix4f root = ModelHit.root(entity, posed.state(), origin);
		ModelPart modelRoot = posed.model().root();
		var lookup = modelRoot.createPartLookup();
		Matrix4f world = new Matrix4f();
		Quaternionf turn = new Quaternionf();
		Vector3f at = new Vector3f();
		for (int i = 0; i < n; i++) {
			Skeleton.Bone bone = skeleton.bones[i];
			if (bone.part == null) {
				continue;
			}

			ModelPart part = lookup.apply(bone.part);
			if (part == null) {
				return null;
			}

			Poser.partMatrix(modelRoot, part, world.set(root));
			Vector3f origin0 = new Vector3f(bone.origin).div(16.0F);
			world.transformPosition(at.set(origin0));
			world.getNormalizedRotation(turn);
			put(pos, rot, i, at, turn);
		}

		// The neck has no block: it starts at the head's pivot and turns with the chest, as it does at rest.
		for (int i = 0; i < n; i++) {
			Skeleton.Bone bone = skeleton.bones[i];
			if (bone.part != null) {
				continue;
			}

			int chest = skeleton.chest;
			int head = skeleton.head >= 0 ? skeleton.head : chest;
			Quaternionf chestTurn = new Quaternionf(rot[4 * chest], rot[4 * chest + 1], rot[4 * chest + 2], rot[4 * chest + 3]);
			Quaternionf rel = skeleton.bones[chest].rest.getNormalizedRotation(new Quaternionf()).conjugate()
					.mul(bone.rest.getNormalizedRotation(new Quaternionf()));
			put(pos, rot, i, new Vector3f(pos[3 * head], pos[3 * head + 1], pos[3 * head + 2]), chestTurn.mul(rel));
		}

		return new RigPose(skeleton, origin.x, origin.y, origin.z, posed.state().scale, pos, rot);
	}

	private static void put(float[] pos, float[] rot, int i, Vector3f at, Quaternionf turn) {
		pos[3 * i] = at.x;
		pos[3 * i + 1] = at.y;
		pos[3 * i + 2] = at.z;
		rot[4 * i] = turn.x;
		rot[4 * i + 1] = turn.y;
		rot[4 * i + 2] = turn.z;
		rot[4 * i + 3] = turn.w;
	}
}
