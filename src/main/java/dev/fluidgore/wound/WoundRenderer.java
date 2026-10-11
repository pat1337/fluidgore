package dev.fluidgore.wound;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.mixin.ModelPartAccessor;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws a body's wounds right after its model, inside the same part transforms, so they move with every limb.
 * Each wound is a stack of pixel-art quads, one per tissue it has been opened down to (see {@link Layer}), each a
 * hair further out than the one below, clipped to the cube face they sit on. Cutout with a polygon offset: no sorting
 * and no z-fighting with the skin underneath.
 */
public final class WoundRenderer {
	public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("fluidgore", "textures/misc/wounds.png");
	/**
	 * One tissue of a wound: its row in wounds.png, the severity at which it opens, its size relative to the wound
	 * radius and what it is (for its colour).
	 */
	private record Layer(int row, float opens, float size, Flesh.Tissue tissue) {
	}

	/** A round wound: the bruise spreads wider than the tear, the meat under it, a knob of bone. */
	private static final Layer[] ROUND = {
			new Layer(0, 0.0F, 1.3F, Flesh.Tissue.BRUISE), new Layer(1, 0.25F, 1.0F, Flesh.Tissue.SPLIT),
			new Layer(2, 0.5F, 0.8F, Flesh.Tissue.MUSCLE), new Layer(3, 0.75F, 0.5F, Flesh.Tissue.BONE)};
	/** A slash: bruised margins, the fat under the skin, the gaping split, muscle in its depth, a line of bone. */
	private static final Layer[] SLASH = {
			new Layer(4, 0.0F, 1.15F, Flesh.Tissue.BRUISE), new Layer(5, 0.2F, 1.0F, Flesh.Tissue.FAT),
			new Layer(6, 0.25F, 0.95F, Flesh.Tissue.SPLIT), new Layer(7, 0.45F, 0.85F, Flesh.Tissue.MUSCLE),
			new Layer(8, 0.75F, 0.7F, Flesh.Tissue.BONE)};
	/** Rot in a zombie's wound, over the meat and under the bone: pus once it is open, maggots once it is deep. */
	private static final Layer PUS = new Layer(9, 0.3F, 0.9F, Flesh.Tissue.PUS);
	private static final Layer MAGGOTS = new Layer(10, 0.5F, 0.85F, Flesh.Tissue.MAGGOTS);
	private static final Layer[] ROUND_ROTTEN = rotten(ROUND);
	/** Bare skin where the feathers were plucked. */
	private static final Layer[] PLUCKED = {new Layer(11, 0.0F, 1.0F, Flesh.Tissue.SKIN)};
	/** A broken bone: a spreading bruise, the skin split over it, crushed bone. */
	private static final Layer[] CRUSHED = {
			new Layer(0, 0.0F, 1.4F, Flesh.Tissue.BRUISE), new Layer(1, 0.0F, 1.0F, Flesh.Tissue.SPLIT), new Layer(12, 0.0F, 0.9F, Flesh.Tissue.BONE)};
	/** Ribs stove in: a wide bruise, the skin split, broken rib ends in a dark hollow. */
	private static final Layer[] STOVE_IN = {
			new Layer(0, 0.0F, 1.5F, Flesh.Tissue.BRUISE), new Layer(1, 0.0F, 1.1F, Flesh.Tissue.SPLIT), new Layer(13, 0.0F, 1.0F, Flesh.Tissue.BONE)};
	private static final Layer[] SLASH_ROTTEN = rotten(SLASH);
	/**
	 * The cut face of a piece of meat, edge to edge as on a butcher's block: the grain of the muscle, veins of fat through
	 * it, a rim of fat along one edge, wet smears of blood, and the bone in a piece big enough to have it. Steak is
	 * marbled; pork has its rind of fat; chicken is plain pink meat.
	 */
	private static final Layer GRAIN = new Layer(14, 0.0F, 1.0F, Flesh.Tissue.MUSCLE);
	private static final Layer MARBLE = new Layer(15, 0.0F, 1.0F, Flesh.Tissue.FAT);
	private static final Layer RIM = new Layer(16, 0.0F, 1.0F, Flesh.Tissue.FAT);
	private static final Layer SMEAR = new Layer(17, 0.0F, 0.8F, Flesh.Tissue.SPLIT);
	private static final Layer CUT_BONE = new Layer(3, 0.75F, 0.3F, Flesh.Tissue.BONE);
	private static final Layer[] CUT_MEAT = {GRAIN, MARBLE, RIM, SMEAR, CUT_BONE};
	private static final Layer[] CUT_POULTRY = {GRAIN, SMEAR, CUT_BONE};
	private static final Layer[] CUT_ROTTEN = rotten(CUT_MEAT);
	/** Outward lift per layer, in model pixels, so the layers stack instead of fighting. */
	private static final float LIFT = 0.02F;
	/** wounds.png is 8 tiles across and 18 down. */
	private static final float TILE_U = 1.0F / 8.0F;
	private static final float TILE_V = 1.0F / 18.0F;

	private static final Vector3f POS = new Vector3f();
	private static final Vector3f NORMAL = new Vector3f();
	private static final float[] CORNER = new float[3];

	private WoundRenderer() {
	}

	/** The layers with the rot slotted in before the bone. */
	private static Layer[] rotten(Layer[] layers) {
		Layer[] out = new Layer[layers.length + 2];
		System.arraycopy(layers, 0, out, 0, layers.length - 1);
		out[layers.length - 1] = PUS;
		out[layers.length] = MAGGOTS;
		out[layers.length + 1] = layers[layers.length - 1];
		return out;
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

	/**
	 * Blood soaked into a coat drawn over the body ({@code coat}, a sheep's wool): a wet stain on the fleece over every
	 * wound that bleeds, wider than the wound as the wool wicks it, the deeper the wider. {@code poseStack} holds the
	 * coat's root transform, as after renderToBuffer.
	 */
	public static void drawStains(WoundSet set, Model<?> coat, PoseStack poseStack, VertexConsumer buffer, int light, int overlay) {
		ModelPart[] parts = ModelParts.index(coat);
		ModelPart torso = ModelParts.torso(coat);
		for (int i = 0; i < set.count; i++) {
			if (set.severity[i] < 0.25F || set.morph[i] == WoundSet.PLUCK) {
				continue;
			}

			ModelPart part = set.bodyPart[i] == null ? torso : parts[set.bodyPart[i].ordinal()];
			if (part == null || ((ModelPartAccessor) (Object) part).fluidgore$getCubes().isEmpty()) {
				continue;
			}

			ModelPart[] chain = ModelParts.chain(coat.root(), part);
			if (chain.length == 0 || !visible(chain)) {
				continue;
			}

			// The fleece stands off the body: its faces are where the wool's grown vertices are.
			ModelPart.Cube cube = ((ModelPartAccessor) (Object) part).fluidgore$getCubes().getFirst();
			float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
			float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (ModelPart.Polygon polygon : cube.polygons) {
				for (ModelPart.Vertex vertex : polygon.vertices()) {
					float[] p = {vertex.x(), vertex.y(), vertex.z()};
					for (int k = 0; k < 3; k++) {
						min[k] = Math.min(min[k], p[k]);
						max[k] = Math.max(max[k], p[k]);
					}
				}
			}

			int a = set.axis[i];
			int b = (a + 1) % 3;
			int c = (a + 2) % 3;
			float r = set.radius[i] * (1.4F + 1.2F * set.severity[i]);
			float b0 = Math.max(min[b], set.u[i] - r);
			float b1 = Math.min(max[b], set.u[i] + r);
			float c0 = Math.max(min[c], set.v[i] - r);
			float c1 = Math.min(max[c], set.v[i] + r);
			if (b0 >= b1 || c0 >= c1) {
				continue;
			}

			poseStack.pushPose();
			for (ModelPart link : chain) {
				link.translateAndRotate(poseStack);
			}

			PoseStack.Pose pose = poseStack.last();
			pose.transformNormal(NORMAL.set(0.0F).setComponent(a, set.sign[i]), NORMAL);
			float tu = set.variant[i] * TILE_U;
			float tv = SMEAR.row() * TILE_V;
			float span = 1.0F / (2.0F * r);
			float u0 = tu + (b0 - (set.u[i] - r)) * span * TILE_U;
			float u1 = tu + (b1 - (set.u[i] - r)) * span * TILE_U;
			float v0 = tv + (c0 - (set.v[i] - r)) * span * TILE_V;
			float v1 = tv + (c1 - (set.v[i] - r)) * span * TILE_V;
			float depth = (set.sign[i] > 0 ? max[a] : min[a]) + set.sign[i] * LIFT;
			// Blood in wool dries darker than it ran.
			int color = 0xFF000000 | set.flesh.tint(Flesh.Tissue.SPLIT, set.blood);
			Matrix4f m = pose.pose();
			vertex(buffer, m, a, b, c, depth, b0, c0, color, u0, v0, light, overlay);
			vertex(buffer, m, a, b, c, depth, b1, c0, color, u1, v0, light, overlay);
			vertex(buffer, m, a, b, c, depth, b1, c1, color, u1, v1, light, overlay);
			vertex(buffer, m, a, b, c, depth, b0, c1, color, u0, v1, light, overlay);
			poseStack.popPose();
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
		boolean slash = set.morph[i] == WoundSet.SLASH;
		Layer[] layers = switch (set.morph[i]) {
			case WoundSet.PLUCK -> PLUCKED;
			case WoundSet.CRUSH -> CRUSHED;
			case WoundSet.RIBS -> STOVE_IN;
			case WoundSet.CUT -> set.flesh.rots() ? CUT_ROTTEN : set.flesh == Flesh.POULTRY ? CUT_POULTRY : CUT_MEAT;
			default -> set.flesh.rots() ? slash ? SLASH_ROTTEN : ROUND_ROTTEN : slash ? SLASH : ROUND;
		};
		for (int n = 0; n < layers.length; n++) {
			Layer layer = layers[n];
			if (layer.opens() > 0.0F && severity <= layer.opens()) {
				continue;
			}

			// Each layer grows in as the wound deepens through it.
			float grow = Mth.clamp((severity - layer.opens()) / 0.25F + 0.4F, 0.4F, 1.0F);
			// A cut face is meat right across, so its layers span the whole face whatever its size.
			float r = (set.morph[i] == WoundSet.CUT ? Math.max(max[b] - min[b], max[c] - min[c]) * 0.5F : set.radius[i]) * layer.size() * grow;
			float b0 = Math.max(min[b], set.u[i] - r);
			float b1 = Math.min(max[b], set.u[i] + r);
			float c0 = Math.max(min[c], set.v[i] - r);
			float c1 = Math.min(max[c], set.v[i] + r);
			if (b0 >= b1 || c0 >= c1) {
				continue;
			}

			float tu = set.variant[i] * TILE_U;
			float tv = layer.row() * TILE_V;
			float spanU = TILE_U / (2.0F * r);
			float spanV = TILE_V / (2.0F * r);
			float u0 = tu + (b0 - (set.u[i] - r)) * spanU;
			float u1 = tu + (b1 - (set.u[i] - r)) * spanU;
			float v0 = tv + (c0 - (set.v[i] - r)) * spanV;
			float v1 = tv + (c1 - (set.v[i] - r)) * spanV;
			float depth = face + set.sign[i] * LIFT * (n + 1);
			int color = 0xFF000000 | set.flesh.tint(layer.tissue(), set.blood);
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
