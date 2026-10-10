package dev.fluidgore.wound;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fluidgore.dismember.ModelParts;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws a body's wounds right after its model, inside the same part transforms, so they move with every limb.
 * Each wound is up to four stacked pixel-art quads (bruise, gash, meat, bone), each a little smaller and a hair
 * further out than the one below, clipped to the cube face they sit on. Cutout with a polygon offset: no sorting
 * and no z-fighting with the skin underneath.
 */
public final class WoundRenderer {
	public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("fluidgore", "textures/misc/wounds.png");
	/** Size of each layer relative to the wound radius: the bruise spreads wider than the cut, bone is a knob. */
	private static final float[] LAYER_SIZE = {1.3F, 1.0F, 0.8F, 0.5F};
	/** Outward lift per layer, in model pixels, so the layers stack instead of fighting. */
	private static final float LIFT = 0.02F;
	private static final float TILE = 0.25F;

	private static final Vector3f POS = new Vector3f();
	private static final Vector3f NORMAL = new Vector3f();
	private static final float[] CORNER = new float[3];

	private WoundRenderer() {
	}

	public static RenderType renderType() {
		return RenderTypes.entityCutoutZOffset(TEXTURE);
	}

	/** Draws the wounds of {@code set}; {@code poseStack} holds the model's root transform, as after renderToBuffer. */
	public static void draw(WoundSet set, PoseStack poseStack, VertexConsumer buffer, int light, int overlay) {
		ModelPart root = set.root;
		for (int i = 0; i < set.count; i++) {
			ModelPart[] chain = ModelParts.chain(root, set.part[i]);
			if (chain.length == 0 || !visible(chain)) {
				continue;
			}

			poseStack.pushPose();
			for (ModelPart part : chain) {
				part.translateAndRotate(poseStack);
			}

			wound(set, i, poseStack.last(), buffer, light, overlay);
			poseStack.popPose();
		}
	}

	/** Draws the wounds of a severed part; {@code pose} is where the part itself is drawn. */
	public static void draw(WoundSet set, PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay) {
		PoseStack stack = new PoseStack();
		stack.last().set(pose);
		draw(set, stack, buffer, light, overlay);
	}

	private static boolean visible(ModelPart[] chain) {
		for (ModelPart part : chain) {
			if (!part.visible) {
				return false;
			}
		}

		return true;
	}

	private static void wound(WoundSet set, int i, PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay) {
		ModelPart.Cube cube = set.cube[i];
		int a = set.axis[i];
		int b = (a + 1) % 3;
		int c = (a + 2) % 3;
		float[] min = {cube.minX, cube.minY, cube.minZ};
		float[] max = {cube.maxX, cube.maxY, cube.maxZ};
		float face = set.sign[i] > 0 ? max[a] : min[a];
		float severity = set.severity[i];
		pose.transformNormal(NORMAL.set(0.0F).setComponent(a, set.sign[i]), NORMAL);
		for (int layer = 0; layer < WoundSet.LAYERS; layer++) {
			float start = layer * 0.25F;
			if (layer > 0 && severity <= start) {
				break;
			}

			// Each layer grows in as the wound deepens through it.
			float grow = Mth.clamp((severity - start) / 0.25F + 0.4F, 0.4F, 1.0F);
			float r = set.radius[i] * LAYER_SIZE[layer] * grow;
			float b0 = Math.max(min[b], set.u[i] - r);
			float b1 = Math.min(max[b], set.u[i] + r);
			float c0 = Math.max(min[c], set.v[i] - r);
			float c1 = Math.min(max[c], set.v[i] + r);
			if (b0 >= b1 || c0 >= c1) {
				continue;
			}

			float tu = set.variant[i] * TILE;
			float tv = layer * TILE;
			float span = TILE / (2.0F * r);
			float u0 = tu + (b0 - (set.u[i] - r)) * span;
			float u1 = tu + (b1 - (set.u[i] - r)) * span;
			float v0 = tv + (c0 - (set.v[i] - r)) * span;
			float v1 = tv + (c1 - (set.v[i] - r)) * span;
			float depth = face + set.sign[i] * LIFT * (layer + 1);
			int color = 0xFF000000 | set.flesh.tint(layer, set.blood);
			Matrix4f m = pose.pose();
			vertex(buffer, m, a, b, c, depth, b0, c0, color, u0, v0, light, overlay);
			vertex(buffer, m, a, b, c, depth, b1, c0, color, u1, v0, light, overlay);
			vertex(buffer, m, a, b, c, depth, b1, c1, color, u1, v1, light, overlay);
			vertex(buffer, m, a, b, c, depth, b0, c1, color, u0, v1, light, overlay);
		}
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
