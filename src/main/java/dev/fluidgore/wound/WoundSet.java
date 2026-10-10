package dev.fluidgore.wound;

import dev.fluidgore.dismember.BodyPart;
import java.util.Map;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Every wound on one body, painted on the faces of its model's cubes in each part's own space, so they follow the
 * animation for free. A wound is a spot on one cube face with a radius and a severity from 0 to 1 that walks
 * through four layers: bruise, gash (0.25), open meat (0.5) and bone (0.75). Fixed-size arrays; when full, the
 * shallowest wound makes room.
 */
public final class WoundSet {
	public static final int MAX = 32;
	public static final int LAYERS = 4;
	/** Widest a wound can be hacked open, in model pixels. */
	private static final float MAX_RADIUS = 4.5F;

	/**
	 * Wounds are only drawn on this model (not on armour or other layers that share the render state). Null for a
	 * severed part, which is drawn on its own.
	 */
	final @Nullable Model<?> model;
	/** The part tree the wounds sit in: the model's root, or a severed part. */
	final ModelPart root;
	final Flesh flesh;
	final int blood;

	final ModelPart[] part = new ModelPart[MAX];
	final ModelPart.Cube[] cube = new ModelPart.Cube[MAX];
	final @Nullable BodyPart[] bodyPart = new BodyPart[MAX];
	final byte[] axis = new byte[MAX];
	final byte[] sign = new byte[MAX];
	final byte[] variant = new byte[MAX];
	/** Position on the face along the two in-plane axes (axis + 1, axis + 2), in model pixels. */
	final float[] u = new float[MAX];
	final float[] v = new float[MAX];
	final float[] radius = new float[MAX];
	final float[] severity = new float[MAX];
	/** Offset from the entity's feet when struck, and its body yaw then, so blood can drip from roughly the wound. */
	final float[] ox = new float[MAX];
	final float[] oy = new float[MAX];
	final float[] oz = new float[MAX];
	final float[] yaw = new float[MAX];
	int count;

	WoundSet(Model<?> model, Flesh flesh, int blood) {
		this.model = model;
		this.root = model.root();
		this.flesh = flesh;
		this.blood = blood;
	}

	/** Wounds on a severed part, carried with it. */
	WoundSet(ModelPart root, Flesh flesh, int blood) {
		this.model = null;
		this.root = root;
		this.flesh = flesh;
		this.blood = blood;
	}

	/** Result of a blow: the wound's severity before and after it. */
	public record Change(int index, float before, float after) {
	}

	/**
	 * Deepens the wound under the hit, or opens a new one. A wound never gets deeper than {@code cap}; a cut through
	 * one that is already there is how parts come off (see {@link Wounds}).
	 */
	Change add(ModelHit.Result hit, Weapon weapon, float amount, float cap, Vec3 offset, float bodyYaw, RandomSource random) {
		int b = (hit.axis() + 1) % 3;
		int c = (hit.axis() + 2) % 3;
		float hu = hit.local().get(b);
		float hv = hit.local().get(c);
		int i = find(hit, hu, hv, weapon);
		if (i >= 0) {
			float before = severity[i];
			severity[i] = Math.max(before, Math.min(cap, before + amount));
			radius[i] = Math.min(MAX_RADIUS, radius[i] + weapon.radius * 0.2F * Math.min(1.0F, amount * 2.0F));
			return new Change(i, before, severity[i]);
		}

		i = count < MAX ? count++ : shallowest();
		part[i] = hit.part();
		cube[i] = hit.cube();
		bodyPart[i] = hit.bodyPart();
		axis[i] = (byte) hit.axis();
		sign[i] = (byte) hit.sign();
		variant[i] = (byte) random.nextInt(4);
		u[i] = hu;
		v[i] = hv;
		radius[i] = weapon.radius * (0.85F + random.nextFloat() * 0.3F);
		severity[i] = Math.min(cap, amount);
		ox[i] = (float) offset.x;
		oy[i] = (float) offset.y;
		oz[i] = (float) offset.z;
		yaw[i] = bodyYaw;
		return new Change(i, 0.0F, severity[i]);
	}

	/** A stump: bone in raw meat where a part was cut off. */
	void stump(ModelHit.Result at, float size, RandomSource random) {
		int i = count < MAX ? count++ : shallowest();
		int b = (at.axis() + 1) % 3;
		int c = (at.axis() + 2) % 3;
		part[i] = at.part();
		cube[i] = at.cube();
		bodyPart[i] = at.bodyPart();
		axis[i] = (byte) at.axis();
		sign[i] = (byte) at.sign();
		variant[i] = (byte) random.nextInt(4);
		u[i] = at.local().get(b);
		v[i] = at.local().get(c);
		radius[i] = Mth.clamp(size, 1.5F, MAX_RADIUS);
		severity[i] = 1.0F;
		// Stumps bleed through the dismemberment's own stump drip.
		ox[i] = oy[i] = oz[i] = Float.NaN;
	}

	/** Deepest wound on a severable part (0 if none). */
	public float severity(BodyPart target) {
		float deepest = 0.0F;
		for (int i = 0; i < count; i++) {
			if (bodyPart[i] == target) {
				deepest = Math.max(deepest, severity[i]);
			}
		}

		return deepest;
	}

	public int count() {
		return count;
	}

	/** True for the model the wounds were cut into; other models drawn with the same render state are skipped. */
	public boolean drawsOn(Model<?> other) {
		return other == model;
	}

	/** Deepest wound anywhere on the body. */
	public float deepest() {
		float deepest = 0.0F;
		for (int i = 0; i < count; i++) {
			deepest = Math.max(deepest, severity[i]);
		}

		return deepest;
	}

	/**
	 * Copies the wounds on parts in {@code parts} (body part -> its copy on the severed piece) into {@code target}, so
	 * a limb that was hacked at keeps its gashes after it comes off.
	 */
	void transferTo(WoundSet target, Map<ModelPart, ModelPart> parts) {
		for (int i = 0; i < count && target.count < MAX; i++) {
			ModelPart moved = parts.get(part[i]);
			if (moved != null) {
				int j = target.count++;
				target.part[j] = moved;
				target.cube[j] = cube[i];
				target.bodyPart[j] = bodyPart[i];
				target.axis[j] = axis[i];
				target.sign[j] = sign[i];
				target.variant[j] = variant[i];
				target.u[j] = u[i];
				target.v[j] = v[i];
				target.radius[j] = radius[i];
				target.severity[j] = severity[i];
				target.ox[j] = target.oy[j] = target.oz[j] = Float.NaN;
			}
		}
	}

	/** Drops the wounds on a part that has just been cut off (it takes them with it). */
	void removePart(BodyPart severed) {
		int kept = 0;
		for (int i = 0; i < count; i++) {
			if (bodyPart[i] != severed) {
				copy(i, kept++);
			}
		}

		for (int i = kept; i < count; i++) {
			part[i] = null;
			cube[i] = null;
			bodyPart[i] = null;
		}

		count = kept;
	}

	private int find(ModelHit.Result hit, float hu, float hv, Weapon weapon) {
		int best = -1;
		float bestDist = Float.MAX_VALUE;
		for (int i = 0; i < count; i++) {
			if (part[i] != hit.part() || cube[i] != hit.cube() || axis[i] != hit.axis() || sign[i] != hit.sign()) {
				continue;
			}

			float du = u[i] - hu;
			float dv = v[i] - hv;
			float dist = du * du + dv * dv;
			float reach = Math.max(radius[i], weapon.radius);
			if (dist < reach * reach && dist < bestDist) {
				best = i;
				bestDist = dist;
			}
		}

		return best;
	}

	private int shallowest() {
		int best = 0;
		for (int i = 1; i < count; i++) {
			if (severity[i] < severity[best]) {
				best = i;
			}
		}

		return best;
	}

	private void copy(int from, int to) {
		if (from == to) {
			return;
		}

		part[to] = part[from];
		cube[to] = cube[from];
		bodyPart[to] = bodyPart[from];
		axis[to] = axis[from];
		sign[to] = sign[from];
		variant[to] = variant[from];
		u[to] = u[from];
		v[to] = v[from];
		radius[to] = radius[from];
		severity[to] = severity[from];
		ox[to] = ox[from];
		oy[to] = oy[from];
		oz[to] = oz[from];
		yaw[to] = yaw[from];
	}
}
