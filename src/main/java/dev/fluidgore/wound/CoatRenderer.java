package dev.fluidgore.wound;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.ragdoll.Bends;
import java.util.List;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import org.jspecify.annotations.Nullable;

/**
 * Draws the blood on a coat ({@link BloodyWool.Drawn}) over a model's cubes, in the same part transforms, under the
 * wounds and runs. Two layers per region, both from wounds.png and tinted with the blood:
 * <ul>
 * <li>the <b>soak</b> covers every face of the region with one of eight nested levels of mottled stain (row 19), so
 *     more blood only ever spreads the same stain; spatter adds to it on the faces it came at;</li>
 * <li>the <b>wade</b> fills each side face from the ground up to a ragged line (row 20) at the height the blood has
 *     climbed, and covers an underside it reached.</li>
 * </ul>
 * One texel of the tile to one model pixel, as the skins are drawn, cut from the tile where this body's seed says.
 * The ground is the bottom of the region in the body's own model (+y in the part's space, which is the world's down
 * for a standing animal), so a fleece, whose legs stop above the hoof, is reddened from the same line as the leg
 * under it. A body lying in a pool is soaked all over instead (see {@link BloodyWool}).
 */
public final class CoatRenderer {
	private static final int SOAK_ROW = 19;
	private static final int TIDE_ROW = 20;
	private static final float TILE_U = 1.0F / 8.0F;
	/** Rows of the tide tile above its ragged line, so the line sits at the height the blood reached. */
	private static final float TIDE_EDGE = 4.0F;
	/** Out from the face: under the runs (half a wound layer) and the wounds. */
	private static final float LIFT = 0.006F;

	private CoatRenderer() {
	}

	/**
	 * Draws {@code coat} on {@code model}; {@code body} is the body's own model (the same as {@code model} unless this
	 * is a coat drawn over it, like a fleece), which sets the ground. {@code poseStack} holds the model's root transform,
	 * as after renderToBuffer.
	 */
	public static void draw(BloodyWool.Drawn coat, Model<?> model, @Nullable Model<?> body, PoseStack poseStack, VertexConsumer buffer, int light,
			int overlay) {
		ModelPart[] parts = ModelParts.index(model);
		ModelPart torso = ModelParts.torso(model);
		boolean own = body == null || body == model;
		ModelPart[] bodyParts = own ? parts : ModelParts.index(body);
		ModelPart bodyTorso = own ? torso : ModelParts.torso(body);
		int color = 0xFF000000 | coat.rgb();
		for (int r = 0; r < BloodyWool.REGIONS; r++) {
			float soak = coat.soak()[r];
			float wade = coat.wade()[r];
			if (!shows(soak, coat.splash(), r * BloodyWool.FACES, wade)) {
				continue;
			}

			ModelPart part = r == WoundSet.TORSO ? torso : parts[r];
			if (part == null) {
				continue;
			}

			List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part).fluidgore$getCubes();
			if (cubes.isEmpty()) {
				continue;
			}

			ModelPart[] chain = ModelParts.chain(model.root(), part);
			if (chain.length == 0 || !visible(chain)) {
				continue;
			}

			float ground = ground(r == WoundSet.TORSO ? bodyTorso : bodyParts[r], cubes);
			poseStack.pushPose();
			Bends.chain(poseStack, chain);
			region(buffer, poseStack.last(), part, cubes, soak, coat.splash(), r * BloodyWool.FACES, wade, ground, coat.seed() * 31 + r * 97, color,
					light, overlay);
			poseStack.popPose();
		}
	}

	/**
	 * Draws the blood a piece cut off a body took with it ({@code coat}, its region's) on the piece's {@code model}:
	 * {@code pose} holds the piece's transform, as for its wounds.
	 */
	public static void drawPiece(BloodyWool.Piece coat, ModelPart model, PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay) {
		List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) model).fluidgore$getCubes();
		if (cubes.isEmpty() || !model.visible || !shows(coat.soak(), coat.splash(), 0, coat.wade())) {
			return;
		}

		PoseStack stack = new PoseStack();
		stack.last().set(pose);
		Bends.chain(stack, new ModelPart[] {model});
		region(buffer, stack.last(), model, cubes, coat.soak(), coat.splash(), 0, coat.wade(), ground(null, cubes), coat.seed() * 31 + coat.region() * 97,
				0xFF000000 | coat.shown(), light, overlay);
	}

	/** Anything to draw: a soak or a spatter of at least the first level, or blood climbed up it. */
	private static boolean shows(float soak, float[] splash, int at, float wade) {
		float most = soak;
		for (int face = 0; face < BloodyWool.FACES; face++) {
			most = Math.max(most, soak + splash[at + face]);
		}

		return most * 8.0F >= 1.0F || wade > 0.0F;
	}

	/** One region's blood on its cubes: the soak and the spatter on each face ({@code splash} from {@code at}), the wade. */
	private static void region(VertexConsumer buffer, PoseStack.Pose pose, ModelPart part, List<ModelPart.Cube> cubes, float soak, float[] splash, int at,
			float wade, float ground, int seedBase, int color, int light, int overlay) {
		float[] min = new float[3];
		float[] max = new float[3];
		for (int k = 0; k < cubes.size(); k++) {
			bounds(cubes.get(k), min, max);
			for (int face = 0; face < BloodyWool.FACES; face++) {
				int a = face >> 1;
				int sign = (face & 1) == 0 ? -1 : 1;
				int seed = seedBase + k * 13 + face;
				// Its soak, and the spatter on this side of it.
				int level = Math.min(8, (int) ((soak + splash[at + face]) * 8.0F));
				if (level >= 1) {
					soak(buffer, pose, part, a, sign, min, max, level - 1, seed, color, light, overlay);
				}

				if (wade <= 0.0F) {
					continue;
				}

				if (a != 1) {
					tide(buffer, pose, part, a, sign, min, max, ground, wade, seed, color, light, overlay);
				} else if (sign > 0 && ground - max[1] < wade) {
					// The underside stood in it, or the blood climbed to it.
					soak(buffer, pose, part, a, sign, min, max, 7, seed, color, light, overlay);
				}
			}
		}
	}

	/** The bottom of a region in the body's model, in the part's own space; the coat's own cubes if the body has none. */
	private static float ground(@Nullable ModelPart bodyPart, List<ModelPart.Cube> own) {
		List<ModelPart.Cube> cubes = bodyPart == null ? own : ((ModelPartAccessor) (Object) bodyPart).fluidgore$getCubes();
		if (cubes.isEmpty()) {
			cubes = own;
		}

		float ground = -Float.MAX_VALUE;
		for (ModelPart.Cube cube : cubes) {
			ground = Math.max(ground, cube.maxY);
		}

		return ground;
	}

	/** A face covered with soak level {@code column}, cut from the tile at a place picked by {@code seed}. */
	private static void soak(VertexConsumer buffer, PoseStack.Pose pose, ModelPart part, int a, int sign, float[] min, float[] max, int column,
			int seed, int color, int light, int overlay) {
		int b = (a + 1) % 3;
		int c = (a + 2) % 3;
		float tu = column * TILE_U;
		float tv = SOAK_ROW * WoundRenderer.TILE_V;
		float[] ub = span(max[b] - min[b], seed);
		float[] vc = span(max[c] - min[c], seed >>> 8);
		float depth = (sign > 0 ? max[a] : min[a]) + sign * LIFT;
		Bends.quad(buffer, pose, part, a, b, c, depth, sign, min[b], max[b], min[c], max[c], tu + ub[0] * TILE_U, tu + ub[1] * TILE_U,
				tv + vc[0] * WoundRenderer.TILE_V, tv + vc[1] * WoundRenderer.TILE_V, color, light, overlay);
	}

	/**
	 * A side face reddened from the ground up to {@code wade} pixels, the tile's ragged line at the top. Variants 0-3 of
	 * the tide row run down the tile, 4-7 across it, so the line lies level whichever face axis is y.
	 */
	private static void tide(VertexConsumer buffer, PoseStack.Pose pose, ModelPart part, int a, int sign, float[] min, float[] max, float ground,
			float wade, int seed, int color, int light, int overlay) {
		int b = (a + 1) % 3;
		int c = (a + 2) % 3;
		boolean yOnB = b == 1;
		float reach = wade + TIDE_EDGE;
		float top = Math.max(min[1], ground - reach);
		float bottom = Math.min(max[1], ground);
		if (bottom <= top) {
			return;
		}

		// Tile position of a height: its bottom row on the ground, its line at the blood's height (stretched past 16).
		float span = Math.max(16.0F, reach);
		float t0 = 1.0F - (ground - top) / span;
		float t1 = 1.0F - (ground - bottom) / span;
		int variant = (seed & 3) + (yOnB ? 4 : 0);
		float tu = variant * TILE_U;
		float tv = TIDE_ROW * WoundRenderer.TILE_V;
		float depth = (sign > 0 ? max[a] : min[a]) + sign * LIFT;
		if (yOnB) {
			// y runs along b: across the tile, its solid end at the right.
			float[] vc = span(max[c] - min[c], seed >>> 8);
			Bends.quad(buffer, pose, part, a, b, c, depth, sign, top, bottom, min[c], max[c], tu + t0 * TILE_U, tu + t1 * TILE_U,
					tv + vc[0] * WoundRenderer.TILE_V, tv + vc[1] * WoundRenderer.TILE_V, color, light, overlay);
		} else {
			float[] ub = span(max[b] - min[b], seed >>> 8);
			Bends.quad(buffer, pose, part, a, b, c, depth, sign, min[b], max[b], top, bottom, tu + ub[0] * TILE_U, tu + ub[1] * TILE_U,
					tv + t0 * WoundRenderer.TILE_V, tv + t1 * WoundRenderer.TILE_V, color, light, overlay);
		}
	}

	/** Where in a 16-texel tile a face {@code size} pixels across is cut from, as fractions of the tile. */
	private static float[] span(float size, int seed) {
		if (size >= 16.0F) {
			return new float[] {0.0F, 1.0F};
		}

		int room = 16 - (int) Math.ceil(size);
		float start = room <= 0 ? 0.0F : Math.floorMod(seed, room + 1);
		return new float[] {start / 16.0F, (start + size) / 16.0F};
	}

	/** The cube as drawn: from its polygons, so a fleece's grown cubes are measured where they really are. */
	private static void bounds(ModelPart.Cube cube, float[] min, float[] max) {
		min[0] = min[1] = min[2] = Float.MAX_VALUE;
		max[0] = max[1] = max[2] = -Float.MAX_VALUE;
		for (ModelPart.Polygon polygon : cube.polygons) {
			for (ModelPart.Vertex vertex : polygon.vertices()) {
				min[0] = Math.min(min[0], vertex.x());
				min[1] = Math.min(min[1], vertex.y());
				min[2] = Math.min(min[2], vertex.z());
				max[0] = Math.max(max[0], vertex.x());
				max[1] = Math.max(max[1], vertex.y());
				max[2] = Math.max(max[2], vertex.z());
			}
		}
	}

	private static boolean visible(ModelPart[] chain) {
		for (ModelPart part : chain) {
			if (!part.visible) {
				return false;
			}
		}

		return true;
	}
}
