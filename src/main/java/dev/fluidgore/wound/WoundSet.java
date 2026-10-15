package dev.fluidgore.wound;

import dev.fluidgore.config.GoreConfig;
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
 * through four layers: bruise, gash (0.25), open meat (0.5) and bone (0.75). A wound is either round (a ragged
 * laceration from a fist, club, point or tooth) or a slash (a long incised or chop wound laid along the swing). Fixed-size
 * arrays; when full, the shallowest wound makes room.
 */
public final class WoundSet {
	public static final int MAX = 32;
	public static final int LAYERS = 4;
	public static final byte ROUND = 0;
	public static final byte SLASH = 1;
	/** Not a wound: a patch of skin where the feathers have been plucked out. */
	public static final byte PLUCK = 2;
	/** A broken bone under the skin: the skull or a limb caved in, shards of bone in it. */
	public static final byte CRUSH = 3;
	/** The ribcage stove in: broken rib ends in a dark hollow. */
	public static final byte RIBS = 4;
	/** The cut face of a diced piece: the whole face is meat, as on a butcher's block. */
	public static final byte CUT = 5;
	/** Where the trunk's bone damage is kept, after the severable parts. */
	public static final int TORSO = BodyPart.values().length;
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
	/** Texture column 0-7. For a slash, {@code variant % 4} is its angle on the face in steps of 45 degrees. */
	final byte[] variant = new byte[MAX];
	/** {@link #ROUND}, {@link #SLASH}, {@link #PLUCK}, {@link #CRUSH} or {@link #RIBS}. */
	final byte[] morph = new byte[MAX];
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
	/**
	 * Crushing injury taken without the skin giving way: blows from fists, clubs and blasts. Past a point the animal
	 * bleeds from the mouth and nose instead of from wounds.
	 */
	float trauma;
	/**
	 * Damage done to the bone of each severable part and of the trunk ({@link #TORSO}), added up blow by blow: past
	 * {@link Wounds#FRACTURE} it breaks, past {@link Wounds#DESTROY} it is smashed.
	 */
	final float[] bone = new float[TORSO + 1];
	/** Bits (1 << part index, {@link #TORSO} for the trunk) of bones that are broken, and of those smashed beyond that. */
	int broken;
	int smashed;
	/** Ticks since the last blow opened or deepened a wound; a living body's wounds clot as this grows. */
	int quiet;

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
		if (weapon == Weapon.BLUNT || weapon == Weapon.BLAST) {
			trauma += amount;
		}

		quiet = 0;
		int b = (hit.axis() + 1) % 3;
		int c = (hit.axis() + 2) % 3;
		float hu = hit.local().get(b);
		float hv = hit.local().get(c);
		int i = find(hit, hu, hv, weapon);
		if (i >= 0) {
			float before = severity[i];
			severity[i] = Math.max(before, Math.min(cap, before + amount));
			radius[i] = Math.min(MAX_RADIUS, radius[i] + weapon.radius * weapon.spread * Math.min(1.0F, amount * 2.0F));
			if (weapon.slashes() && morph[i] == ROUND) {
				// A blade through a bruise: now it is a cut.
				morph[i] = SLASH;
				variant[i] = (byte) slashVariant(hit.axis(), random);
			}

			return new Change(i, before, severity[i]);
		}

		i = count < MAX ? count++ : shallowest();
		part[i] = hit.part();
		cube[i] = hit.cube();
		bodyPart[i] = hit.bodyPart();
		axis[i] = (byte) hit.axis();
		sign[i] = (byte) hit.sign();
		morph[i] = weapon.slashes() ? SLASH : ROUND;
		variant[i] = (byte) (morph[i] == SLASH ? slashVariant(hit.axis(), random) : random.nextInt(8));
		u[i] = hu;
		v[i] = hv;
		// A slash is long: the same blow opens it wider along the cut than a round wound.
		radius[i] = weapon.radius * (morph[i] == SLASH ? 1.35F : 1.0F) * (0.85F + random.nextFloat() * 0.3F);
		severity[i] = Math.min(cap, amount);
		ox[i] = (float) offset.x;
		oy[i] = (float) offset.y;
		oz[i] = (float) offset.z;
		yaw[i] = bodyYaw;
		return new Change(i, 0.0F, severity[i]);
	}

	/**
	 * Feathers plucked out at {@code hit}: a patch of bare skin there, or a wider one if there is already a patch close
	 * by. Patches are never deepened by blows (see {@link #find}). Returns true if a new patch was made.
	 */
	boolean pluck(ModelHit.Result hit, float size, RandomSource random) {
		int b = (hit.axis() + 1) % 3;
		int c = (hit.axis() + 2) % 3;
		float hu = hit.local().get(b);
		float hv = hit.local().get(c);
		for (int i = 0; i < count; i++) {
			if (morph[i] == PLUCK && part[i] == hit.part() && cube[i] == hit.cube() && axis[i] == hit.axis() && sign[i] == hit.sign()) {
				float du = u[i] - hu;
				float dv = v[i] - hv;
				if (du * du + dv * dv < radius[i] * radius[i] * 0.5F) {
					radius[i] = Math.min(MAX_RADIUS * 1.6F, radius[i] + size * 0.3F);
					return false;
				}
			}
		}

		int i = count < MAX ? count++ : shallowest();
		part[i] = hit.part();
		cube[i] = hit.cube();
		bodyPart[i] = hit.bodyPart();
		axis[i] = (byte) hit.axis();
		sign[i] = (byte) hit.sign();
		morph[i] = PLUCK;
		variant[i] = (byte) random.nextInt(8);
		u[i] = hu;
		v[i] = hv;
		radius[i] = size * (0.85F + random.nextFloat() * 0.3F);
		severity[i] = 0.0F;
		ox[i] = oy[i] = oz[i] = Float.NaN;
		return true;
	}

	/** A broken bone shows through at {@code hit}: crushed bone, or stove-in ribs on the trunk. */
	void fracture(ModelHit.Result hit, float size, Vec3 offset, float bodyYaw, RandomSource random) {
		int i = count < MAX ? count++ : shallowest();
		int b = (hit.axis() + 1) % 3;
		int c = (hit.axis() + 2) % 3;
		part[i] = hit.part();
		cube[i] = hit.cube();
		bodyPart[i] = hit.bodyPart();
		axis[i] = (byte) hit.axis();
		sign[i] = (byte) hit.sign();
		morph[i] = hit.bodyPart() == null ? RIBS : CRUSH;
		variant[i] = (byte) random.nextInt(8);
		u[i] = hit.local().get(b);
		v[i] = hit.local().get(c);
		radius[i] = Math.min(MAX_RADIUS, size);
		severity[i] = 1.0F;
		ox[i] = (float) offset.x;
		oy[i] = (float) offset.y;
		oz[i] = (float) offset.z;
		yaw[i] = bodyYaw;
	}

	/** True if the bone of {@code part} is broken (or smashed). */
	public boolean broken(BodyPart part) {
		return (broken & 1 << part.ordinal()) != 0;
	}

	/** True if the ribs are stove in. */
	public boolean ribsBroken() {
		return (broken & 1 << TORSO) != 0;
	}

	/** True if the ribs are smashed in beyond being broken. */
	public boolean ribsSmashed() {
		return (smashed & 1 << TORSO) != 0;
	}

	/** Bits of broken bones, see {@link #broken}. */
	public int brokenMask() {
		return broken;
	}

	/** A stump: bone in raw meat where a part was cut off. */
	void stump(ModelHit.Result at, float size, RandomSource random) {
		stump(at, size, 1.0F, random);
	}

	/** The whole face of a cube cut through, {@code depth} deep (1 shows the bone in it). */
	void cutFace(ModelHit.Result at, float depth, RandomSource random) {
		morph[stump(at, 1.5F, depth, random)] = CUT;
	}

	/** A raw cut face {@code depth} deep: 1 shows the bone, less only the meat. Returns its index. */
	int stump(ModelHit.Result at, float size, float depth, RandomSource random) {
		int i = count < MAX ? count++ : shallowest();
		int b = (at.axis() + 1) % 3;
		int c = (at.axis() + 2) % 3;
		part[i] = at.part();
		cube[i] = at.cube();
		bodyPart[i] = at.bodyPart();
		axis[i] = (byte) at.axis();
		sign[i] = (byte) at.sign();
		morph[i] = ROUND;
		variant[i] = (byte) random.nextInt(8);
		u[i] = at.local().get(b);
		v[i] = at.local().get(c);
		radius[i] = Mth.clamp(size, 1.5F, MAX_RADIUS);
		severity[i] = depth;
		// Stumps bleed through the dismemberment's own stump drip.
		ox[i] = oy[i] = oz[i] = Float.NaN;
		return i;
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

	/** Deepest wound on the trunk, the part that cannot come off (0 if none). */
	public float torsoSeverity() {
		float deepest = 0.0F;
		for (int i = 0; i < count; i++) {
			if (bodyPart[i] == null) {
				deepest = Math.max(deepest, severity[i]);
			}
		}

		return deepest;
	}

	/**
	 * How hard the living body is bleeding right now, 0..1: its deepest open wound, thinning to nothing as the wounds
	 * clot after the last blow (see {@link GoreConfig#woundClotSeconds}).
	 */
	public float bleeding() {
		float open = 0.0F;
		for (int i = 0; i < count; i++) {
			if (severity[i] >= 0.25F && !Float.isNaN(ox[i]) && morph[i] != PLUCK && morph[i] != CUT) {
				open = Math.max(open, severity[i]);
			}
		}

		return open * Math.max(0.0F, 1.0F - quiet / (GoreConfig.get().woundClotSeconds * 20.0F));
	}

	/** Crushing injury so far; see {@link #trauma}. */
	public float trauma() {
		return trauma;
	}

	public int count() {
		return count;
	}

	/** What the body is made of. */
	public Flesh flesh() {
		return flesh;
	}

	/** How many of the wounds are of this shape, {@link #ROUND} or {@link #SLASH}. */
	public int count(byte shape) {
		int n = 0;
		for (int i = 0; i < count; i++) {
			if (morph[i] == shape) {
				n++;
			}
		}

		return n;
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
				target.morph[j] = morph[i];
				target.u[j] = u[i];
				target.v[j] = v[i];
				target.radius[j] = radius[i];
				target.severity[j] = severity[i];
				target.ox[j] = target.oy[j] = target.oz[j] = Float.NaN;
			}
		}
	}

	/**
	 * Health came back: every wound closes by {@code amount} of severity (meat back to a gash, a gash to a bruise, a
	 * bruise gone), the crushing eases and broken bones knit; a fracture's shards go once its bone has mended.
	 * Stumps and cut faces never heal. Returns true if nothing is left.
	 */
	boolean heal(float amount) {
		trauma = Math.max(0.0F, trauma - amount * 2.0F);
		for (int j = 0; j < bone.length; j++) {
			bone[j] = Math.max(0.0F, bone[j] - amount * Wounds.FRACTURE);
			if (bone[j] < Wounds.FRACTURE) {
				broken &= ~(1 << j);
				smashed &= ~(1 << j);
			}
		}

		int kept = 0;
		for (int i = 0; i < count; i++) {
			boolean gone;
			if (morph[i] == CRUSH || morph[i] == RIBS) {
				gone = (broken & 1 << (bodyPart[i] == null ? TORSO : bodyPart[i].ordinal())) == 0;
			} else if (morph[i] == CUT || morph[i] == PLUCK || Float.isNaN(ox[i])) {
				gone = false;
			} else {
				severity[i] -= amount;
				radius[i] = Math.max(0.6F, radius[i] - amount * 2.0F);
				gone = severity[i] <= 0.02F;
			}

			if (!gone) {
				copy(i, kept++);
			}
		}

		for (int i = kept; i < count; i++) {
			part[i] = null;
			cube[i] = null;
			bodyPart[i] = null;
		}

		count = kept;
		return count == 0 && trauma <= 0.0F && broken == 0;
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

	/**
	 * Which way a slash runs on a face whose normal is model axis {@code axis}. A swing is mostly level, so on the side
	 * of a standing body the cut runs across it, often a little diagonal, now and then straight down. Texture u runs
	 * along model axis {@code axis + 1} and v along {@code axis + 2}; angle 0 is along u, 2 (90 degrees) along v.
	 */
	private static int slashVariant(int axis, RandomSource random) {
		int level = switch (axis) {
			case 0 -> 2; // face along X: u is Y (up), v is Z, level means along v
			case 2 -> 0; // face along Z: u is X, v is Y (up), level means along u
			default -> random.nextInt(4); // a top or bottom face: any way
		};
		float roll = random.nextFloat();
		int angle = axis == 1 || roll < 0.45F ? level : roll < 0.9F ? (level + (random.nextBoolean() ? 1 : 3)) % 4 : (level + 2) % 4;
		return angle + 4 * random.nextInt(2);
	}

	private int find(ModelHit.Result hit, float hu, float hv, Weapon weapon) {
		int best = -1;
		float bestDist = Float.MAX_VALUE;
		for (int i = 0; i < count; i++) {
			if (morph[i] == PLUCK || part[i] != hit.part() || cube[i] != hit.cube() || axis[i] != hit.axis() || sign[i] != hit.sign()) {
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
		morph[to] = morph[from];
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
