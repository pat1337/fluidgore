package dev.fluidgore.wound;

import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.ModelParts;
import java.util.List;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.animal.sheep.SheepFurModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Where a blade to the head of a four-legged animal goes in: the throat. Sticking a pig, a sheep or a beast is a
 * short cut low in the neck, just ahead of the breastbone, through the great vessels there; it bleeds the animal
 * out and the blood pours down its chest and forelegs (see {@link Runs}). On a block model the neck is where the
 * head's underside meets the trunk, so the cut goes on the skull's underside at its back end, as close to the
 * chest as the skin there is clear of the trunk.
 */
final class Sticking {
	/** How far along the head's underside each trial point is moved, and how far clear of the trunk the cut goes. */
	private static final float STEP = 0.25F;
	private static final float CLEAR = 0.75F;

	/** The spot, and the cut's angle on the face: 0 along its first axis, 2 along its second (see {@link WoundSet}). */
	record Spot(ModelHit.Result hit, int angle) {
	}

	private Sticking() {
	}

	/** True if a blow with {@code weapon} on {@code hit} sticks the animal rather than cutting where it landed. */
	static boolean sticks(Model<?> model, ModelHit.Result hit, Weapon weapon) {
		return (weapon == Weapon.BLADE || weapon == Weapon.FLINT) && hit.bodyPart() == BodyPart.HEAD && model instanceof QuadrupedModel<?>
				&& !(model instanceof SheepFurModel);
	}

	/** The throat of the body as posed now, or null if its model has no head and trunk to find one between. */
	static @Nullable Spot at(LivingEntity entity, ModelParts.Posed posed) {
		Vec3 base = new Vec3(posed.state().x, posed.state().y, posed.state().z);
		List<ModelHit.Box> boxes = ModelHit.boxes(entity, posed, base);
		ModelHit.Box skull = largest(boxes, BodyPart.HEAD);
		ModelHit.Box trunk = largest(boxes, null);
		if (skull == null || trunk == null) {
			return null;
		}

		// The underside: the face of the skull turned most nearly the way the body's own underside faces.
		Vector3f down = ModelHit.root(entity, posed.state(), base).transformDirection(new Vector3f(0.0F, 1.0F, 0.0F)).normalize();
		int axis = 1;
		int sign = 1;
		float facing = -Float.MAX_VALUE;
		for (int a = 0; a < 3; a++) {
			for (int s = -1; s <= 1; s += 2) {
				float dot = normal(skull, a, s).dot(down);
				if (dot > facing) {
					facing = dot;
					axis = a;
					sign = s;
				}
			}
		}

		ModelPart.Cube cube = skull.cube();
		float[] min = {cube.minX, cube.minY, cube.minZ};
		float[] max = {cube.maxX, cube.maxY, cube.maxZ};
		int b = (axis + 1) % 3;
		int c = (axis + 2) % 3;
		Vector3f toTrunk = centre(trunk).sub(centre(skull));
		// The neck runs along whichever axis of the face points more towards the trunk.
		Vector3f eb = skull.pose().transformDirection(new Vector3f().setComponent(b, 1.0F));
		Vector3f ec = skull.pose().transformDirection(new Vector3f().setComponent(c, 1.0F));
		boolean alongB = Math.abs(eb.dot(toTrunk)) >= Math.abs(ec.dot(toTrunk));
		int along = alongB ? b : c;
		int across = alongB ? c : b;
		float towards = (alongB ? eb : ec).dot(toTrunk) > 0.0F ? 1.0F : -1.0F;

		float[] local = new float[3];
		local[axis] = sign > 0 ? max[axis] : min[axis];
		local[across] = (min[across] + max[across]) * 0.5F;
		float end = towards > 0.0F ? max[along] : min[along];
		float snout = towards > 0.0F ? min[along] : max[along];
		Vector3f n = normal(skull, axis, sign);
		float px = posed.state().scale / 16.0F;
		// From the end against the chest towards the snout, the first stretch of skin clear of the trunk and everything else.
		for (float t = end; (t - snout) * towards > CLEAR; t -= towards * STEP) {
			local[along] = t;
			Vector3f world = skull.pose().transformPosition(new Vector3f(local[0], local[1], local[2]).div(16.0F));
			if (!covered(boxes, skull, new Vector3f(world).add(new Vector3f(n).mul(0.3F * px)))) {
				local[along] = t - towards * CLEAR;
				world = skull.pose().transformPosition(new Vector3f(local[0], local[1], local[2]).div(16.0F));
				ModelHit.Result hit = new ModelHit.Result(skull.part(), cube, axis, sign, new Vector3f(local[0], local[1], local[2]), BodyPart.HEAD,
						base.add(world.x, world.y, world.z), new Vec3(n.x, n.y, n.z), 0.0);
				return new Spot(hit, alongB ? 0 : 2);
			}
		}

		return null;
	}

	private static ModelHit.@Nullable Box largest(List<ModelHit.Box> boxes, @Nullable BodyPart part) {
		ModelHit.Box best = null;
		float most = 0.0F;
		for (ModelHit.Box box : boxes) {
			ModelPart.Cube c = box.cube();
			float volume = (c.maxX - c.minX) * (c.maxY - c.minY) * (c.maxZ - c.minZ);
			if (box.bodyPart() == part && volume > most) {
				most = volume;
				best = box;
			}
		}

		return best;
	}

	/** True if {@code world} is inside a cube other than {@code skull}'s. */
	private static boolean covered(List<ModelHit.Box> boxes, ModelHit.Box skull, Vector3f world) {
		Vector3f l = new Vector3f();
		for (ModelHit.Box box : boxes) {
			if (box == skull) {
				continue;
			}

			ModelPart.Cube c = box.cube();
			box.inverse().transformPosition(l.set(world)).mul(16.0F);
			if (l.x > c.minX && l.x < c.maxX && l.y > c.minY && l.y < c.maxY && l.z > c.minZ && l.z < c.maxZ) {
				return true;
			}
		}

		return false;
	}

	private static Vector3f centre(ModelHit.Box box) {
		ModelPart.Cube c = box.cube();
		return box.pose().transformPosition(new Vector3f(c.minX + c.maxX, c.minY + c.maxY, c.minZ + c.maxZ).mul(0.5F / 16.0F));
	}

	private static Vector3f normal(ModelHit.Box box, int axis, int sign) {
		return box.pose().transformDirection(new Vector3f().setComponent(axis, sign)).normalize();
	}
}
