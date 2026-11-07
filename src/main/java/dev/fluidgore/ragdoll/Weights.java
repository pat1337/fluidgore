package dev.fluidgore.ragdoll;

import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;

/**
 * How a body's weight is shared out among its segments: as the real animal's is, not by the size of its blocks. A
 * Minecraft leg is a thick block, so shared by volume a sheep's four legs would carry two fifths of its weight and its
 * head a sixth, and a falling body would land on its legs like a table. In the animal the trunk carries most of it, and
 * the head and legs swing light off it.
 *
 * <p>A four-legged model's leg is the limb below the trunk, from the elbow or the stifle down (shank and foot): the
 * shoulder and the thigh lie inside the trunk's block. Its head is the head alone; the neck the model lacks is a short
 * segment of its own ({@link Skeleton}). Shares are of the whole live weight. What the head, neck and limbs do not take
 * is the trunk's, and a limb cut at the knee shares its part by volume.
 */
final class Weights {
	/** Share of the head, the neck, each fore limb (leg, arm or wing) and each hind limb; the trunk has the rest. */
	record Shares(float head, float neck, float fore, float hind) {
	}

	/**
	 * Lambs at slaughter: the skinned head 3.6 to 4.4% of live weight, a little more with its skin and wool on; the four
	 * feet together 2 to 2.6%, and each shank below the elbow or stifle about as much again as its foot.
	 */
	private static final Shares SHEEP = new Shares(0.05F, 0.02F, 0.022F, 0.022F);
	/** A pig's head is heavy (with the jowl, about 6%); its trotters and hocks light. */
	private static final Shares PIG = new Shares(0.06F, 0.02F, 0.018F, 0.018F);
	/** Cattle: the head with its hide about 3.5%, a foot about 0.5%, a shank about 1.5%. */
	private static final Shares CATTLE = new Shares(0.035F, 0.02F, 0.02F, 0.02F);
	/** Any other four-legged animal, between the sheep and the cow. */
	private static final Shares QUADRUPED = new Shares(0.045F, 0.02F, 0.022F, 0.022F);
	/**
	 * Anything human-shaped: Dempster's cadaver shares as Winter tables them (Biomechanics and Motor Control of Human
	 * Movement): head and neck 8.1%, each whole arm 5.0%, each whole leg 16.1%, the trunk 49.7%. The model has no neck.
	 */
	private static final Shares BIPED = new Shares(0.081F, 0.0F, 0.05F, 0.161F);
	/** A chicken: each wing about 4% of live weight, the head 3%, each shank and foot 2% (the thigh is in the body). */
	private static final Shares BIRD = new Shares(0.03F, 0.02F, 0.04F, 0.02F);

	private static final Map<String, Shares> SPECIES = Map.of(
			"sheep", SHEEP,
			"pig", PIG,
			"cow", CATTLE,
			"mooshroom", CATTLE);

	private Weights() {
	}

	/** Each segment's share of the body's weight, summing to 1. */
	static float[] of(Skeleton skeleton, LivingEntity entity) {
		Skeleton.Bone[] bones = skeleton.bones;
		float[] out = new float[bones.length];
		if (skeleton.plan == Skeleton.Plan.THIRDS) {
			// A model nothing is known about: by volume.
			for (int i = 0; i < bones.length; i++) {
				out[i] = bones[i].volume;
			}

			return normalised(out);
		}

		Shares shares = switch (skeleton.plan) {
			case BIPED -> BIPED;
			case BIRD -> BIRD;
			default -> SPECIES.getOrDefault(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(), QUADRUPED);
		};

		boolean[] given = new boolean[bones.length];
		if (skeleton.head >= 0) {
			out[skeleton.head] = shares.head();
			given[skeleton.head] = true;
		}

		int neck = skeleton.index("neck");
		if (neck >= 0) {
			out[neck] = shares.neck();
			given[neck] = true;
		}

		for (int l = 0; l < 4; l++) {
			int top = skeleton.limbs[l];
			if (top < 0 || bones[top].part == null) {
				continue;
			}

			int[] segments = skeleton.bonesOf(bones[top].part);
			share(bones, segments, l < 2 ? shares.fore() : shares.hind(), out, given);
		}

		// The trunk, and anything else on it, has what is left.
		float taken = 0.0F;
		int rest = 0;
		for (int i = 0; i < bones.length; i++) {
			if (given[i]) {
				taken += out[i];
			} else {
				rest++;
			}
		}

		int[] trunk = new int[rest];
		for (int i = 0, k = 0; i < bones.length; i++) {
			if (!given[i]) {
				trunk[k++] = i;
			}
		}

		share(bones, trunk, Math.max(0.2F, 1.0F - taken), out, given);
		return normalised(out);
	}

	/** Shares {@code total} among {@code segments} by their volume. */
	private static void share(Skeleton.Bone[] bones, int[] segments, float total, float[] out, boolean[] given) {
		float volume = 0.0F;
		for (int i : segments) {
			volume += Math.max(1.0F, bones[i].volume);
		}

		for (int i : segments) {
			out[i] = total * Math.max(1.0F, bones[i].volume) / volume;
			given[i] = true;
		}
	}

	private static float[] normalised(float[] shares) {
		float sum = 0.0F;
		for (float s : shares) {
			sum += s;
		}

		for (int i = 0; i < shares.length; i++) {
			shares[i] = sum > 0.0F ? shares[i] / sum : 1.0F / shares.length;
		}

		return shares;
	}
}
