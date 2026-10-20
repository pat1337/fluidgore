package dev.fluidgore.rot;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.ragdoll.Bends;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Paints rot onto a body, drawn right after its model in the same part transforms, with the wound tiles (see
 * {@code WoundRenderer}). Every face of every cube gets one patch, centred somewhere on it by the body's dice, that
 * starts at its own time and goes through the layers below as it ages: the skin marbles, slips and splits, pus and
 * maggots work in the flesh under it, the flesh goes and bone comes through and spreads until the face is mostly bone.
 * A mummy only darkens and splits, and shows bone only after a long time.
 */
public final class RotRenderer {
	/**
	 * One layer of a patch: its row in wounds.png, when it opens and (if ever) goes again in decay-days after the
	 * patch starts, its size relative to the patch radius as it grows in over {@code grow} days, and its colour then
	 * and once the body is dry.
	 */
	private record Layer(int row, float from, float until, float grow, float size0, float size1, int wet, int dried) {
	}

	private static final float NEVER = Float.MAX_VALUE;
	private static final Layer[] WET = {
			// Marbling: the veins and the skin over them gone green-black and purple.
			new Layer(0, 0.0F, NEVER, 6.0F, 0.5F, 1.6F, 0x34442A, 0x3A3024),
			// The skin slips and splits, black and wet under it.
			new Layer(1, 3.0F, NEVER, 6.0F, 0.4F, 1.3F, 0x2A1A10, 0x3A2E22),
			new Layer(9, 4.0F, 12.0F, 2.0F, 0.6F, 0.9F, 0x9FA63A, 0x9FA63A),
			// Putrid, grey-green flesh, drying to brown.
			new Layer(2, 5.0F, NEVER, 5.0F, 0.5F, 1.1F, 0x6B5E3A, 0x4A3826),
			new Layer(10, 5.0F, 13.0F, 2.0F, 0.6F, 0.9F, 0xEDE6CF, 0xEDE6CF),
			// Bone, stained by what lay on it, spreading until the face is bone.
			new Layer(3, 12.0F, NEVER, 8.0F, 0.3F, 3.0F, 0xBFAE7A, 0xE2DACA)};
	private static final Layer[] MUMMY = {
			new Layer(0, 0.0F, NEVER, 8.0F, 0.5F, 1.4F, 0x5A4028, 0x4A3420),
			new Layer(1, 6.0F, NEVER, 10.0F, 0.3F, 0.9F, 0x3A2818, 0x2E2014),
			new Layer(3, 30.0F, NEVER, 20.0F, 0.3F, 1.2F, 0xCBBB92, 0xD8CCAA)};
	/** Days after the start of the bloat over which the patches start. */
	private static final float SPREAD = 4.0F;
	/** Outward lift per layer, in model pixels: under the wounds, which start at 0.02. */
	private static final float LIFT = 0.004F;
	private static final float TILE_U = 1.0F / 8.0F;
	private static final float TILE_V = 1.0F / 18.0F;


	private static final float[] MIN = new float[3];
	private static final float[] MAX = new float[3];

	private RotRenderer() {
	}

	/** Nothing shows before the bloat. */
	public static boolean shows(float days) {
		return days > Rot.BLOAT;
	}

	/**
	 * Draws the rot on the part tree {@code root} ({@code poseStack} at the root's parent: the model's root transform
	 * as after renderToBuffer, or where a severed piece is drawn). Hidden parts (severed limbs) are skipped.
	 */
	public static void draw(ModelPart root, float days, boolean arid, long seed, PoseStack poseStack, VertexConsumer buffer, int light, int overlay) {
		if (!shows(days)) {
			return;
		}

		walk(null, root, days, arid, seed, 1, poseStack, buffer, light, overlay);
	}

	/** Draws the rot on a severed piece; {@code pose} is where the piece itself is drawn. */
	public static void draw(ModelPart root, float days, boolean arid, long seed, PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay) {
		PoseStack stack = new PoseStack();
		stack.last().set(pose);
		draw(root, days, arid, seed, stack, buffer, light, overlay);
	}

	private static int walk(@Nullable ModelPart parent, ModelPart part, float days, boolean arid, long seed, int index, PoseStack poseStack, VertexConsumer buffer, int light, int overlay) {
		if (!part.visible) {
			return index + 1;
		}

		poseStack.pushPose();
		if (parent == null) {
			part.translateAndRotate(poseStack);
		} else {
			Bends.enter(poseStack, parent, part);
		}

		ModelPartAccessor access = (ModelPartAccessor) (Object) part;
		if (!part.skipDraw) {
			List<ModelPart.Cube> cubes = access.fluidgore$getCubes();
			for (int k = 0; k < cubes.size(); k++) {
				cube(part, cubes.get(k), index * 16 + k, days, arid, seed, poseStack.last(), buffer, light, overlay);
			}
		}

		int next = index + 1;
		for (ModelPart child : access.fluidgore$getChildren().values()) {
			next = walk(part, child, days, arid, seed, next, poseStack, buffer, light, overlay);
		}

		poseStack.popPose();
		return next;
	}

	private static void cube(ModelPart part, ModelPart.Cube cube, int key, float days, boolean arid, long seed, PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay) {
		MIN[0] = cube.minX;
		MIN[1] = cube.minY;
		MIN[2] = cube.minZ;
		MAX[0] = cube.maxX;
		MAX[1] = cube.maxY;
		MAX[2] = cube.maxZ;
		Layer[] layers = arid ? MUMMY : WET;
		boolean dry = days > Rot.DRY;
		for (int face = 0; face < 6; face++) {
			int a = face >> 1;
			int sign = (face & 1) == 0 ? -1 : 1;
			int b = (a + 1) % 3;
			int c = (a + 2) % 3;
			float halfB = (MAX[b] - MIN[b]) * 0.5F;
			float halfC = (MAX[c] - MIN[c]) * 0.5F;
			if (halfB <= 0.0F || halfC <= 0.0F) {
				continue;
			}

			int patch = key * 8 + face;
			// Most patches start in the bloat; one in six is a late one, under the side it lies on.
			float start = Rot.BLOAT + rand(seed, patch, 0) * SPREAD + (rand(seed, patch, 1) < 0.17F ? 6.0F : 0.0F);
			float q = days - start;
			if (q <= 0.0F) {
				continue;
			}

			float radius = Math.max(halfB, halfC) * (0.45F + 0.35F * rand(seed, patch, 2));
			float pu = (MIN[b] + MAX[b]) * 0.5F + (rand(seed, patch, 3) - 0.5F) * halfB * 0.8F;
			float pv = (MIN[c] + MAX[c]) * 0.5F + (rand(seed, patch, 4) - 0.5F) * halfC * 0.8F;
			int variant = (int) (rand(seed, patch, 5) * 8.0F) & 7;
			float depth = sign > 0 ? MAX[a] : MIN[a];
			for (int n = 0; n < layers.length; n++) {
				Layer layer = layers[n];
				if (q < layer.from() || q >= layer.until()) {
					continue;
				}

				float grown = Mth.clamp((q - layer.from()) / layer.grow(), 0.0F, 1.0F);
				float r = radius * Mth.lerp(grown, layer.size0(), layer.size1());
				if (layer.until() != NEVER) {
					// Maggots and pus shrink away as they go.
					r *= Mth.clamp((layer.until() - q) / 2.0F, 0.0F, 1.0F);
				}

				float b0 = Math.max(MIN[b], pu - r);
				float b1 = Math.min(MAX[b], pu + r);
				float c0 = Math.max(MIN[c], pv - r);
				float c1 = Math.min(MAX[c], pv + r);
				if (b0 >= b1 || c0 >= c1) {
					continue;
				}

				float tu = variant * TILE_U;
				float tv = layer.row() * TILE_V;
				float span = 1.0F / (2.0F * r);
				float u0 = tu + (b0 - (pu - r)) * span * TILE_U;
				float u1 = tu + (b1 - (pu - r)) * span * TILE_U;
				float v0 = tv + (c0 - (pv - r)) * span * TILE_V;
				float v1 = tv + (c1 - (pv - r)) * span * TILE_V;
				float d = depth + sign * LIFT * (n + 1);
				int color = 0xFF000000 | (dry ? Rot.lerp(layer.wet(), layer.dried(), (days - Rot.DRY) / 20.0F) : layer.wet());
				Bends.quad(buffer, pose, part, a, b, c, d, sign, b0, b1, c0, c1, u0, u1, v0, v1, color, light, overlay);
			}
		}
	}

	/** A number in [0, 1) from the body's seed, the patch and which draw it is: the same on every screen and frame. */
	private static float rand(long seed, int patch, int which) {
		long h = seed ^ (patch * 0x9E3779B97F4A7C15L) ^ (which * 0xC2B2AE3D27D4EB4FL);
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		h ^= h >>> 31;
		return (h >>> 40) / (float) (1 << 24);
	}

}
