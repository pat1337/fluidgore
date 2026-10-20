package dev.fluidgore.wound;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.ragdoll.Bends;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Paints a burning body, drawn right after its model in the same part transforms, with the wound tiles (see
 * {@code WoundRenderer}). Every face of every cube gets a patch, centred by the body's dice, that goes through the fire
 * at its own pace: the blackened skin splits over cracks that glow while it burns, the roasted meat shows in them and
 * goes black, then bone comes through and spreads until the face is bone, charred black first and calcined white at
 * the end. Once the flesh is gone, the trunk shows its ribs.
 */
public final class CremationRenderer {
	/** One layer of a patch: its row in wounds.png, when it opens (flesh gone after the patch starts) and how it grows. */
	private record Layer(int row, float from, float grow, float size0, float size1) {
	}

	/** The skin splits black. */
	private static final Layer CRUST = new Layer(6, 0.0F, 0.15F, 0.5F, 1.6F);
	/** The embers in the splits. */
	private static final Layer EMBERS = new Layer(6, 0.0F, 0.15F, 0.38F, 1.25F);
	private static final Layer MEAT = new Layer(2, 0.12F, 0.3F, 0.4F, 1.4F);
	private static final Layer BONE = new Layer(3, 0.4F, 0.45F, 0.3F, 3.2F);
	private static final int RIBS = 13;
	/** Patches start this far apart in flesh burnt: the fire does not take the whole body at once. */
	private static final float SPREAD = 0.25F;
	/** Outward lift per layer, in model pixels: under the wounds, which start at 0.02. */
	private static final float LIFT = 0.0035F;
	private static final float TILE_U = 1.0F / 8.0F;
	private static final float TILE_V = 1.0F / 18.0F;

	private static final Vector3f POS = new Vector3f();
	private static final Vector3f NORMAL = new Vector3f();
	private static final float[] CORNER = new float[3];
	private static final float[] MIN = new float[3];
	private static final float[] MAX = new float[3];

	private CremationRenderer() {
	}

	/**
	 * Draws the burns on the part tree {@code root} ({@code poseStack} at the root's parent, as after renderToBuffer);
	 * {@code torso} gets the ribs. Hidden parts (broken off) are skipped.
	 */
	public static void draw(ModelPart root, Cremation.Charred charred, @Nullable ModelPart torso, PoseStack poseStack, VertexConsumer buffer, int light,
			int overlay) {
		walk(null, root, charred, torso, 1, poseStack, buffer, light, overlay);
	}

	private static int walk(@Nullable ModelPart parent, ModelPart part, Cremation.Charred charred, @Nullable ModelPart torso, int index, PoseStack poseStack, VertexConsumer buffer,
			int light, int overlay) {
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
				cube(part, cubes.get(k), index * 16 + k, charred, part == torso, poseStack.last(), buffer, light, overlay);
			}
		}

		int next = index + 1;
		for (ModelPart child : access.fluidgore$getChildren().values()) {
			next = walk(part, child, charred, torso, next, poseStack, buffer, light, overlay);
		}

		poseStack.popPose();
		return next;
	}

	private static void cube(ModelPart part, ModelPart.Cube cube, int key, Cremation.Charred charred, boolean torso, PoseStack.Pose pose, VertexConsumer buffer, int light,
			int overlay) {
		MIN[0] = cube.minX;
		MIN[1] = cube.minY;
		MIN[2] = cube.minZ;
		MAX[0] = cube.maxX;
		MAX[1] = cube.maxY;
		MAX[2] = cube.maxZ;
		int spine = 0;
		for (int i = 1; i < 3; i++) {
			if (MAX[i] - MIN[i] > MAX[spine] - MIN[spine]) {
				spine = i;
			}
		}

		long seed = charred.seed();
		float bare = charred.bare();
		int crust = 0xFF120C08;
		int meat = 0xFF000000 | lerp(0x6A2A16, 0x2C1A12, Cremation.ramp(charred.burn(), 0.3F, 0.8F));
		int bone = 0xFF000000 | bone(charred.burn());
		float glow = charred.glow();
		int embers = 0xFF000000 | lerp(0x3A1408, 0xFF6A1C, glow);
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
			float q = bare - rand(seed, patch, 0) * SPREAD;
			if (q <= 0.0F) {
				continue;
			}

			float radius = Math.max(halfB, halfC) * (0.45F + 0.35F * rand(seed, patch, 2));
			float pu = (MIN[b] + MAX[b]) * 0.5F + (rand(seed, patch, 3) - 0.5F) * halfB * 0.8F;
			float pv = (MIN[c] + MAX[c]) * 0.5F + (rand(seed, patch, 4) - 0.5F) * halfC * 0.8F;
			int variant = (int) (rand(seed, patch, 5) * 8.0F) & 7;
			float depth = sign > 0 ? MAX[a] : MIN[a];
			pose.transformNormal(NORMAL.set(0.0F).setComponent(a, sign), NORMAL);
			patch(buffer, pose, part, a, b, c, sign, depth + sign * LIFT, CRUST, q, radius, pu, pv, variant, crust, light, overlay);
			if (glow > 0.05F && q < BONE.from() + BONE.grow()) {
				// The cracks glow while it burns, and for a while after; the bone, once it spreads, covers them.
				patch(buffer, pose, part, a, b, c, sign, depth + sign * LIFT * 2, EMBERS, q, radius, pu, pv, variant, embers, LightCoordsUtil.FULL_BRIGHT, overlay);
			}

			patch(buffer, pose, part, a, b, c, sign, depth + sign * LIFT * 3, MEAT, q, radius, pu, pv, variant, meat, light, overlay);
			patch(buffer, pose, part, a, b, c, sign, depth + sign * LIFT * 4, BONE, q, radius, pu, pv, (variant + 3) & 7, bone, light, overlay);
			if (torso && a != spine && bare > 0.75F) {
				ribs(buffer, pose, part, a, b, c, depth + sign * LIFT * 5, spine, variant, bone, light, overlay);
			}
		}
	}

	private static void patch(VertexConsumer buffer, PoseStack.Pose pose, ModelPart part, int a, int b, int c, int sign, float d, Layer layer, float q, float radius, float pu, float pv,
			int variant, int color, int light, int overlay) {
		if (q < layer.from()) {
			return;
		}

		float grown = Mth.clamp((q - layer.from()) / layer.grow(), 0.0F, 1.0F);
		float r = radius * Mth.lerp(grown, layer.size0(), layer.size1());
		float b0 = Math.max(MIN[b], pu - r);
		float b1 = Math.min(MAX[b], pu + r);
		float c0 = Math.max(MIN[c], pv - r);
		float c1 = Math.min(MAX[c], pv + r);
		if (b0 >= b1 || c0 >= c1) {
			return;
		}

		float tu = variant * TILE_U;
		float tv = layer.row() * TILE_V;
		float span = 1.0F / (2.0F * r);
		float u0 = tu + (b0 - (pu - r)) * span * TILE_U;
		float u1 = tu + (b1 - (pu - r)) * span * TILE_U;
		float v0 = tv + (c0 - (pv - r)) * span * TILE_V;
		float v1 = tv + (c1 - (pv - r)) * span * TILE_V;
		Bends.quad(buffer, pose, part, a, b, c, d, sign, b0, b1, c0, c1, u0, u1, v0, v1, color, light, overlay);
	}

	/**
	 * The ribs, across the whole face, one tile of them after another along the spine (the cube's longest side), the bars
	 * running round the body.
	 */
	private static void ribs(VertexConsumer buffer, PoseStack.Pose pose, ModelPart part, int a, int b, int c, float d, int spine, int variant, int color, int light, int overlay) {
		// The tile's bars run along u, stacked along v: v goes along the spine.
		int along = spine == b ? b : c;
		int round = along == b ? c : b;
		float length = MAX[along] - MIN[along];
		float width = MAX[round] - MIN[round];
		int tiles = Math.max(1, Math.round(length / Math.max(1.0F, width)));
		float step = length / tiles;
		float tu = variant * TILE_U;
		float tv = RIBS * TILE_V;
		for (int t = 0; t < tiles; t++) {
			float s0 = MIN[along] + t * step;
			float s1 = s0 + step;
			// Each tile goes where its stretch of the trunk is drawn, if a ragdoll has cut it.
			Matrix4f m = Bends.matrixAt(pose, part, along, (s0 + s1) * 0.5F);
			if (along == b) {
				vertex(buffer, m, a, b, c, d, s0, MIN[c], color, tu, tv, light, overlay);
				vertex(buffer, m, a, b, c, d, s1, MIN[c], color, tu, tv + TILE_V, light, overlay);
				vertex(buffer, m, a, b, c, d, s1, MAX[c], color, tu + TILE_U, tv + TILE_V, light, overlay);
				vertex(buffer, m, a, b, c, d, s0, MAX[c], color, tu + TILE_U, tv, light, overlay);
			} else {
				vertex(buffer, m, a, b, c, d, MIN[b], s0, color, tu, tv, light, overlay);
				vertex(buffer, m, a, b, c, d, MAX[b], s0, color, tu + TILE_U, tv, light, overlay);
				vertex(buffer, m, a, b, c, d, MAX[b], s1, color, tu + TILE_U, tv + TILE_V, light, overlay);
				vertex(buffer, m, a, b, c, d, MIN[b], s1, color, tu, tv + TILE_V, light, overlay);
			}
		}
	}

	/** Bone in a fire: charred black, then grey, then white once it is calcined. */
	static int bone(float burn) {
		if (burn < 0.6F) {
			return 0x3A2A20;
		}

		return burn < 0.85F ? lerp(0x3A2A20, 0x6E675E, (burn - 0.6F) / 0.25F) : lerp(0x6E675E, 0xDCD6CA, (burn - 0.85F) / 0.15F);
	}

	/** A number in [0, 1) from the body's seed, the patch and which draw it is: the same on every screen and frame. */
	private static float rand(long seed, int patch, int which) {
		long h = seed ^ (patch * 0x9E3779B97F4A7C15L) ^ (which * 0xC2B2AE3D27D4EB4FL);
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		h ^= h >>> 31;
		return (h >>> 40) / (float) (1 << 24);
	}

	private static int lerp(int from, int to, float t) {
		t = Mth.clamp(t, 0.0F, 1.0F);
		int r = Math.round(Mth.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF));
		int g = Math.round(Mth.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF));
		int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
		return r << 16 | g << 8 | b;
	}

	private static void vertex(VertexConsumer buffer, Matrix4f m, int a, int b, int c, float depth, float pb, float pc,
			int color, float u, float v, int light, int overlay) {
		CORNER[a] = depth / 16.0F;
		CORNER[b] = pb / 16.0F;
		CORNER[c] = pc / 16.0F;
		m.transformPosition(CORNER[0], CORNER[1], CORNER[2], POS);
		buffer.addVertex(POS.x, POS.y, POS.z, color, u, v, overlay, light, NORMAL.x, NORMAL.y, NORMAL.z);
	}
}
