package dev.fluidgore.anatomy;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fluidgore.anatomy.Anatomy.Kind;
import dev.fluidgore.anatomy.Anatomy.Piece;
import dev.fluidgore.anatomy.Anatomy.Region;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.ragdoll.Bends;
import dev.fluidgore.wound.BurntBone;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.animal.sheep.SheepFurModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Draws a person burning, in place of their model's own skin (the mixin skips vanilla's draw of it), in the same part
 * transforms, so every layer moves with the limbs.
 *
 * <p>Every face of every part is cut into its texels, one cell per pixel of the skin, and the fire takes each cell at
 * its own time, from {@code bare} and that cell's dice (a smooth noise over the body, seeded by its UUID, so holes open
 * in patches and grow, the same on every screen). A cell goes through the layers in order:
 * <ol>
 * <li>Clothes and hair (the outer layer of a skin) singe and burn off first, the burning edge glowing.</li>
 * <li>The skin reddens, blisters and peels in places, browns and chars black, the char cracking over embers, and burns
 *     through, the rim of each hole glowing while it is alight.</li>
 * <li>Under it the yellow fat, which runs amber and drips while it burns.</li>
 * <li>Under that the muscle (the chest, the back and the face in their own shapes), which cooks brown and black and
 *     shrinks onto the bone. Over the eyes and the lips there is none.</li>
 * <li>Once the muscle is through, the inside ({@link Anatomy}): the organs cooking and shrivelling, the bones scorched
 *     and charred black, mottled (grey and calcined white only after a long fire under them); the jaw dropped open.</li>
 * </ol>
 * Each hole shows the cut edges of the layers round it, so it has depth. Texels on the skin are its own; the rest come
 * from anatomy.png at the same one texel per pixel.
 */
public final class MeltRenderer {
	public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("fluidgore", "textures/misc/anatomy.png");
	private static final float ATLAS_W = 128.0F;
	private static final float ATLAS_H = 128.0F;
	/** The four layers of a cell, in the order the fire takes them. */
	private static final int OUT = 0;
	private static final int SKIN = 1;
	private static final int FAT = 2;
	private static final int MEAT = 3;
	private static final int LAYERS = 4;
	/** Per cell: when each layer goes, then the blister and peeling fields. */
	private static final int BLISTER = 4;
	private static final int PEEL = 5;
	/** Per cell: where the belly wall splits in the heat, 0 outside the split, to 1 at its middle (see {@link Region#rupture}). */
	private static final int RUPTURE = 6;
	private static final int STRIDE = 7;
	/** How far the bowel pushes out of a burst belly, in canonical pixels. */
	private static final float PROTRUDES = 1.3F;
	/** How thick the muscle is under its own surface, drawn at the walls of a hole through it. */
	private static final float MEAT_DEPTH = 0.6F;
	private static final int ROAST = 0x9A6A48;
	/** Pork rind in a fire: it dries, puffs and crisps golden into crackling before it chars. */
	private static final int CRACKLING = 0xD8A458;
	private static final int CRACKLING_BURNT = 0x5E3418;
	/** Skin charred leathery, dark brown, before it goes black. */
	private static final int SCORCHED = 0x5A3A26;
	/** Skin reddened by the heat (a multiplier for the skin's own colour). */
	private static final int RED = 0xFFB09C;
	/** Meat this far cooked has turned from red to grey-brown. */
	private static final float COOKS = 0.3F;
	private static final int PALING = 0xE8D2CA;
	private static final int BURNT = 0x5A4032;
	private static final int FAT_COOKED = 0xFFE0A0;
	/** The layers under the skin lie in its shadow: a little darker the deeper they are. */
	private static final int DEEP_FAT = 0xF2F2F2;
	private static final int DEEP_MEAT = 0xDADADA;
	/**
	 * A walking corpse's tissues, as multipliers on fresh ones. The fat has gone to grey, waxy adipocere; the muscle is
	 * grey-green and soft; the organs have digested themselves to dark, greenish-black pulp; the bones are stained
	 * yellow-brown by what soaked into them; a decay blister holds dark red-brown fluid, not clear.
	 */
	private static final int ADIPOCERE = 0xC9C6AE;
	private static final int PUTRID = 0x8E9668;
	private static final int AUTOLYSED = 0x6A6C4C;
	private static final int STAINED = 0xD8C49C;
	private static final int DECAY_BLISTER = 0x9A7A5E;
	private static final int MAX_CACHED = 96;
	/** Wool scorched yellow-brown, then charred to brittle black beads. */
	private static final int SCORCHED_WOOL = 0xB89058;
	private static final int CHARRED_WOOL = 0x2A1E16;
	/** Horn and hoof in a fire: scorched, then charred black. */
	private static final int SCORCHED_HORN = 0x8A6A4A;
	private static final int CHARRED_HORN = 0x2E2620;
	/** How much later the fire gets through the skin under a sheep's fleece. */
	private static final float FLEECE_SHIELD = 0.1F;

	private record Key(long seed, ModelPart.Cube cube, boolean fleece) {
	}

	/** What an outer layer is: hair and clothes, a sheep's fleece, or horn and hoof. */
	private enum Coat {
		/** Singes and is gone in the first seconds, its burning edge glowing. */
		HAIR,
		/** Slow to catch and self-extinguishing: it scorches, chars black and falls away in patches, smouldering at the edges. */
		FLEECE,
		/** Keratin: it scorches and chars black but never burns away. */
		HORN
	}

	/** Each cube's thresholds per face: for every cell the amount of {@code bare} at which each layer is gone. */
	private static final Map<Key, float[][]> THRESHOLDS = new HashMap<>();
	/** Each cube's real extent (its polygons, so the outer layer's growth is in): min xyz, max xyz. */
	private static final Map<ModelPart.Cube, float[]> EXTENT = new IdentityHashMap<>();

	private static final float[] SCALE = new float[3];
	private static final float[] OFFSET = new float[3];
	private static final float[][] BOX = new float[LAYERS][6];
	private static final float[] P = new float[3];
	private static final float[] Q0 = new float[3];
	private static final float[] Q1 = new float[3];
	private static final float[] Q2 = new float[3];
	private static final float[] Q3 = new float[3];
	private static final float[] N = new float[3];
	private static final float[] UV = new float[6];
	private static final Vector3f POS = new Vector3f();
	private static final Vector3f NORMAL = new Vector3f();
	private static final Vector3f DOWN = new Vector3f();
	private static final Matrix3f TURN = new Matrix3f();
	private static byte[] state = new byte[64];

	/** One cell's look: which buffer and texels, its tint, whether it glows. */
	private static boolean useSkin;
	private static int lookTile;
	private static int lookTint;
	private static boolean lookGlow;
	/** How far a cell of skin has slipped down, melting, in pixels; and that, along the face being drawn. */
	private static float lookSag;
	private static float sagB;
	private static float sagC;
	/** The body is a walking corpse: every tissue under the skin is rotting (see {@link Melt#rotten}). */
	private static boolean rotten;
	/** The beast being drawn (null for a person), and whether it is in its fleece. */
	private static Anatomy.@Nullable Beast beast;
	private static boolean fleece;
	/** The part being drawn is a grown beast's trunk, whose cube the model turns a right angle (see {@link Region#min(boolean, boolean)}). */
	private static boolean turned;
	/** The body is opened with no fire (see {@link Melt#raw}): no layer scorches or cooks. */
	private static boolean raw;

	private MeltRenderer() {
	}

	public static RenderType renderType() {
		return RenderTypes.entityCutout(TEXTURE);
	}

	/**
	 * Draws the burning body {@code model} ({@code poseStack} at the model's parent, as after renderToBuffer):
	 * {@code skin} is the model's own buffer, {@code flesh} {@link #renderType()}'s, {@code color} the tint the model
	 * was drawn with. Only one buffer can be filled at a time, so it is drawn twice: once with the skin's buffer and no
	 * other, then with the anatomy's. Each pass draws only what goes in the buffer it has.
	 */
	public static void draw(Model<?> model, Melt melt, PoseStack poseStack, @Nullable VertexConsumer skin, @Nullable VertexConsumer flesh, int light, int overlay, int color) {
		rotten = melt.rotten();
		beast = melt.beast();
		fleece = melt.fleece();
		raw = melt.raw();
		ModelPart root = model.root();
		ModelPart[] parts = ModelParts.index(model);
		boolean b = beast != null;
		part(root, ModelParts.torso(model), b ? Region.BEAST_TRUNK : Region.TORSO, false, 0, melt, poseStack, skin, flesh, light, overlay, color);
		part(root, parts[BodyPart.HEAD.ordinal()], b ? Region.BEAST_HEAD : Region.HEAD, false, 1, melt, poseStack, skin, flesh, light, overlay, color);
		part(root, parts[BodyPart.RIGHT_ARM.ordinal()], b ? Region.FORELEG : Region.ARM, false, 2, melt, poseStack, skin, flesh, light, overlay, color);
		part(root, parts[BodyPart.LEFT_ARM.ordinal()], b ? Region.FORELEG : Region.ARM, true, 3, melt, poseStack, skin, flesh, light, overlay, color);
		part(root, parts[BodyPart.RIGHT_LEG.ordinal()], b ? Region.HINDLEG : Region.LEG, false, 4, melt, poseStack, skin, flesh, light, overlay, color);
		part(root, parts[BodyPart.LEFT_LEG.ordinal()], b ? Region.HINDLEG : Region.LEG, true, 5, melt, poseStack, skin, flesh, light, overlay, color);
	}

	/** True if {@code model} is the one {@link #draw} draws {@code melt} on: a person's humanoid, or a beast's own body (not its fleece). */
	public static boolean drawsOn(Model<?> model, Melt melt) {
		return melt.beast() == null ? model instanceof HumanoidModel<?> : model instanceof QuadrupedModel<?> && !(model instanceof SheepFurModel);
	}

	/**
	 * A burning sheep's fleece ({@code fur}, its own model drawn over the body): it scorches yellow-brown, chars black and
	 * falls away in patches, smouldering at the edges, the bare singed hide showing through (see {@link Coat#FLEECE}).
	 * Two passes like {@link #draw}: the wool's own buffer, then the anatomy's for the glowing edges.
	 */
	public static void fleece(Model<?> fur, Melt melt, PoseStack stack, @Nullable VertexConsumer skin, @Nullable VertexConsumer flesh, int light, int overlay, int color) {
		rotten = melt.rotten();
		beast = melt.beast();
		fleece = true;
		raw = melt.raw();
		ModelPart root = fur.root();
		ModelPart[] parts = ModelParts.index(fur);
		Region[] regions = {Region.BEAST_HEAD, Region.FORELEG, Region.FORELEG, Region.HINDLEG, Region.HINDLEG};
		for (BodyPart bodyPart : BodyPart.values()) {
			wool(root, parts[bodyPart.ordinal()], regions[bodyPart.ordinal()], bodyPart == BodyPart.LEFT_ARM || bodyPart == BodyPart.LEFT_LEG,
					1 + bodyPart.ordinal(), melt, stack, skin, flesh, light, overlay, color);
		}

		wool(root, ModelParts.torso(fur), Region.BEAST_TRUNK, false, 0, melt, stack, skin, flesh, light, overlay, color);
	}

	private static void wool(ModelPart root, @Nullable ModelPart part, Region region, boolean left, int index, Melt melt, PoseStack stack,
			@Nullable VertexConsumer skin, @Nullable VertexConsumer flesh, int light, int overlay, int color) {
		if (part == null || !part.visible) {
			return;
		}

		List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part).fluidgore$getCubes();
		ModelPart[] chain = ModelParts.chain(root, part);
		if (cubes.isEmpty() || chain.length == 0) {
			return;
		}

		stack.pushPose();
		Bends.chain(stack, chain);
		turned = region == Region.BEAST_TRUNK && turned(part);
		fit(region, left, cubes.get(0));
		for (int n = 0; n < cubes.size(); n++) {
			clothes(part, cubes.get(n), region, left, index + 8 * n, melt, stack.last(), skin, flesh, light, overlay, color, Coat.FLEECE);
		}

		stack.popPose();
	}

	/** The model parts {@link #draw} draws itself, whose vanilla skin must not be drawn under it. */
	public static void skins(Model<?> model, java.util.function.Consumer<ModelPart> each) {
		ModelPart[] parts = ModelParts.index(model);
		ModelPart torso = ModelParts.torso(model);
		if (torso != null) {
			skinTree(torso, each);
		}

		for (BodyPart part : BodyPart.values()) {
			if (parts[part.ordinal()] != null) {
				skinTree(parts[part.ordinal()], each);
			}
		}
	}

	private static void skinTree(ModelPart part, java.util.function.Consumer<ModelPart> each) {
		each.accept(part);
		for (ModelPart child : ((ModelPartAccessor) (Object) part).fluidgore$getChildren().values()) {
			each.accept(child);
		}
	}

	private static void part(ModelPart root, @Nullable ModelPart part, Region region, boolean left, int index, Melt melt, PoseStack stack,
			VertexConsumer skin, VertexConsumer flesh, int light, int overlay, int color) {
		if (part == null || !part.visible) {
			return;
		}

		List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part).fluidgore$getCubes();
		ModelPart[] chain = ModelParts.chain(root, part);
		if (cubes.isEmpty() || chain.length == 0) {
			return;
		}

		ModelPart.Cube cube = core(cubes);
		stack.pushPose();
		Bends.chain(stack, chain);
		PoseStack.Pose pose = stack.last();
		turned = region == Region.BEAST_TRUNK && turned(part);
		fit(region, left, cube);
		// Which way is down, in the part's own frame: the fat drips that way. The pose stack holds no camera turn.
		TURN.set(pose.normal()).transpose().transform(DOWN.set(0.0F, -1.0F, 0.0F));
		if (DOWN.lengthSquared() > 1.0E-6F) {
			DOWN.normalize();
		}

		boolean exposed = shell(part, cube, region, left, index, melt, pose, skin, flesh, light, overlay, color);
		// The rest of the part's blocks: a coat grown over it (hair, a cold-climate animal's shaggy fur), horns beside or
		// on top of a head, or flesh of its own (a snout, a muzzle, an udder), burning like the rest.
		int extra = 0;
		for (ModelPart.Cube other : cubes) {
			if (other != cube) {
				extra++;
				attached(part, cube, other, region, left, index + 8 * extra, false, melt, pose, skin, flesh, light, overlay, color);
			}
		}

		for (ModelPart child : ((ModelPartAccessor) (Object) part).fluidgore$getChildren().values()) {
			List<ModelPart.Cube> worn = ((ModelPartAccessor) (Object) child).fluidgore$getCubes();
			if (!child.visible || worn.isEmpty()) {
				continue;
			}

			stack.pushPose();
			Bends.enter(stack, part, child);
			for (ModelPart.Cube other : worn) {
				extra++;
				attached(child, cube, other, region, left, index + 8 * extra, true, melt, stack.last(), skin, flesh, light, overlay, color);
			}

			stack.popPose();
		}

		if (exposed && flesh != null) {
			inside(part, region, left, melt, pose, flesh, light, overlay);
		}

		stack.popPose();
	}

	/**
	 * Another block of a part than its {@link #core}: grown over it, it is an outer layer that burns off first; on a
	 * beast's head, beside it or on top ({@code child}: hung from it), it is horn; otherwise flesh like the rest.
	 */
	private static void attached(ModelPart part, ModelPart.Cube core, ModelPart.Cube cube, Region region, boolean left, int index, boolean child, Melt melt,
			PoseStack.Pose pose, @Nullable VertexConsumer skin, @Nullable VertexConsumer flesh, int light, int overlay, int color) {
		if (grown(cube) || child && region != Region.BEAST_HEAD) {
			clothes(part, cube, region, left, index, melt, pose, skin, flesh, light, overlay, color, Coat.HAIR);
		} else if (region == Region.BEAST_HEAD && (child || cube.maxY <= core.minY + 1.0E-3F || cube.maxX <= core.minX + 1.0E-3F || cube.minX >= core.maxX - 1.0E-3F)) {
			clothes(part, cube, region, left, index, melt, pose, skin, flesh, light, overlay, color, Coat.HORN);
		} else {
			shell(part, cube, region, left, index, melt, pose, skin, flesh, light, overlay, color);
		}
	}

	/** The block of a part its anatomy is fitted to: the biggest that is not grown (a coat over it). */
	public static ModelPart.Cube core(List<ModelPart.Cube> cubes) {
		ModelPart.Cube best = cubes.get(0);
		float most = -1.0F;
		for (ModelPart.Cube cube : cubes) {
			float volume = (cube.maxX - cube.minX) * (cube.maxY - cube.minY) * (cube.maxZ - cube.minZ);
			if (!grown(cube) && volume > most) {
				most = volume;
				best = cube;
			}
		}

		return best;
	}

	/** A block grown past its own size: an outer layer (a hat, a jacket, a coat of fur). */
	private static boolean grown(ModelPart.Cube cube) {
		float[] e = extent(cube);
		return e[0] < cube.minX - 1.0E-3F || e[1] < cube.minY - 1.0E-3F || e[2] < cube.minZ - 1.0E-3F || e[3] > cube.maxX + 1.0E-3F
				|| e[4] > cube.maxY + 1.0E-3F || e[5] > cube.maxZ + 1.0E-3F;
	}

	/** A grown beast's trunk: the model stands its cube on end and turns it a right angle about x to lie along the back. */
	public static boolean turned(ModelPart part) {
		return Math.abs(part.getInitialPose().xRot() - Mth.HALF_PI) < 0.2F;
	}

	/** The fat under a part's skin: a beast's own (suet, lard), a person's yellow. */
	private static Kind fat() {
		return beast != null ? beast.fat : Kind.FAT;
	}

	/** Muscle as the body being drawn has it: a beast's own lean. */
	private static Kind lean(Kind kind) {
		return beast != null && kind == Kind.MUSCLE ? beast.lean : kind;
	}

	/** How far under the skin a part's fat and its muscle lie: a beast's trunk by its kind. */
	private static float fatDepth(Region region) {
		return beast != null && region == Region.BEAST_TRUNK ? beast.hide : region.fat;
	}

	private static float muscleDepth(Region region) {
		return beast != null && region == Region.BEAST_TRUNK ? beast.cover : region.muscle;
	}

	/** Canonical pixels of {@code region} onto the real cube: {@code real = canonical * SCALE + OFFSET}, per axis. */
	private static void fit(Region region, boolean left, ModelPart.Cube cube) {
		float[] min = region.min(left, turned);
		float[] max = region.max(left, turned);
		float[] real0 = {cube.minX, cube.minY, cube.minZ};
		float[] real1 = {cube.maxX, cube.maxY, cube.maxZ};
		for (int i = 0; i < 3; i++) {
			SCALE[i] = (real1[i] - real0[i]) / (max[i] - min[i]);
			OFFSET[i] = real0[i] - min[i] * SCALE[i];
		}
	}

	/**
	 * The skin of one part and the fat and muscle under it. Returns true if the muscle is burnt through anywhere, so
	 * the inside shows.
	 */
	private static boolean shell(ModelPart part, ModelPart.Cube cube, Region region, boolean left, int index, Melt melt, PoseStack.Pose pose,
			VertexConsumer skin, VertexConsumer flesh, int light, int overlay, int color) {
		float q = melt.bare();
		float[][] thresholds = thresholds(melt.seed(), cube, region, left, index);
		float[] extent = extent(cube);
		float muscle = muscleDepth(region);
		float shrunk = Mth.lerp(Melt.ramp(melt.burn(), 0.3F, 0.75F), muscle, muscle + region.shrunk - region.muscle);
		inset(extent, 0.0F, BOX[SKIN]);
		inset(extent, fatDepth(region), BOX[FAT]);
		inset(extent, shrunk, BOX[MEAT]);
		inset(extent, shrunk + MEAT_DEPTH, BOX[OUT]);
		boolean exposed = false;
		boolean merge = !Bends.cut(part);
		float[] dims = {cube.maxX - cube.minX, cube.maxY - cube.minY, cube.maxZ - cube.minZ};
		for (int face = 0; face < 6; face++) {
			float[] t = thresholds[face];
			int a = face >> 1;
			int sign = (face & 1) == 0 ? -1 : 1;
			int b = (a + 1) % 3;
			int c = (a + 2) % 3;
			ModelPart.Polygon polygon = t == null ? null : polygon(cube, extent, a, sign);
			if (polygon == null) {
				continue;
			}

			int nb = cells(dims[b]);
			int nc = cells(dims[c]);
			uvPlane(polygon, b, c);
			if (state.length < nb * nc) {
				state = new byte[nb * nc];
			}

			for (int k = 0; k < nb * nc; k++) {
				int s = q >= t[k * STRIDE + MEAT] ? 3 : q >= t[k * STRIDE + FAT] ? 2 : q >= t[k * STRIDE + SKIN] ? 1 : 0;
				float split = t[k * STRIDE + RUPTURE];
				if (split > 0.0F && melt.rupture() >= 1.0F - split) {
					// The belly wall torn open from the middle of the split outwards: through to the guts.
					s = 3;
				}

				state[k] = (byte) s;
				exposed |= s == 3;
			}

			N[0] = N[1] = N[2] = 0.0F;
			N[a] = sign;
			// The skin, a row at a time; untouched stretches of a row go as one quad.
			for (int j = 0; j < nc; j++) {
				int run = -1;
				int runTint = 0;
				for (int i = 0; i <= nb; i++) {
					boolean plain = false;
					int k = j * nb + i;
					if (i < nb && state[k] == 0) {
						skinLook(t, k, q, melt, rim(nb, nc, i, j, 0), pick(melt.seed(), index, face, k, 0));
						plain = useSkin && merge;
						if (!plain) {
							flush(skin, pose, part, a, b, c, sign, j, nb, nc, run, i, runTint, color, light, overlay);
							run = -1;
							sagB = component(DOWN, b) * lookSag;
							sagC = component(DOWN, c) * lookSag;
							cell(useSkin ? skin : flesh, pose, part, BOX[SKIN], a, b, c, sign, i, j, nb, nc, useSkin, lookTile, lookTint, color,
									lookGlow ? LightCoordsUtil.FULL_BRIGHT : light, overlay);
							sagB = sagC = 0.0F;
							continue;
						}
					}

					if (plain && run >= 0 && lookTint == runTint) {
						continue;
					}

					flush(skin, pose, part, a, b, c, sign, j, nb, nc, run, i, runTint, color, light, overlay);
					run = plain ? i : -1;
					runTint = lookTint;
				}
			}

			for (int j = 0; j < nc; j++) {
				for (int i = 0; i < nb; i++) {
					int k = j * nb + i;
					int s = state[k];
					long dice = pick(melt.seed(), index, face, k, 1);
					if (s == 1) {
						// The fat, where the skin over it is gone.
						float cook = heat(Melt.ramp(q, t[k * STRIDE + SKIN], t[k * STRIDE + FAT]));
						boolean flaming = melt.lit() && rim(nb, nc, i, j, 1);
						int tile = melt.lit() && cook > 0.45F || flaming ? Kind.MOLTEN.tile : fat().tile;
						cell(flesh, pose, part, BOX[FAT], a, b, c, sign, i, j, nb, nc, false, tile, rot(multiply(lerp(0xFFFFFF, FAT_COOKED, cook), DEEP_FAT), ADIPOCERE), 0xFFFFFFFF,
								flaming ? LightCoordsUtil.FULL_BRIGHT : light, overlay);
						if (cook > 0.25F && unit(dice) < 0.16F && DOWN.dot(N[0], N[1], N[2]) > -0.3F) {
							drip(flesh, pose, part, BOX[FAT], a, b, c, sign, i, j, nb, nc, 0.25F + 1.6F * cook * (0.6F + 0.4F * unit(dice >>> 20)),
									melt.lit() ? LightCoordsUtil.FULL_BRIGHT : light, overlay);
						}
					} else if (s == 2) {
						// The muscle, where the fat over it has melted away.
						float cook = heat(Melt.ramp(q, t[k * STRIDE + FAT], t[k * STRIDE + FAT] + 0.28F));
						boolean glowing = melt.lit() && rim(nb, nc, i, j, 2) && unit(dice) < 0.35F;
						int tile = glowing ? Kind.EMBER.tile : meatTile(lean(region.muscleOn(a, sign)), cook);
						cell(flesh, pose, part, BOX[MEAT], a, b, c, sign, i, j, nb, nc, false, tile, glowing ? 0xFFFFFF : rot(multiply(meatTint(cook), DEEP_MEAT), PUTRID), 0xFFFFFFFF,
								glowing ? LightCoordsUtil.FULL_BRIGHT : light, overlay);
					}

					if (s > 0) {
						walls(flesh, pose, part, t, a, b, c, sign, i, j, nb, nc, q, lean(region.muscleOn(a, sign)), light, overlay);
					}
				}
			}
		}

		return exposed;
	}

	/** Decides how an unburnt skin cell looks, into {@code useSkin}, {@code lookTile}, {@code lookTint}, {@code lookGlow}. */
	private static void skinLook(float[] t, int k, float q, Melt melt, boolean rim, long dice) {
		boolean pig = beast == Anatomy.Beast.PIG;
		float through = t[k * STRIDE + SKIN];
		// The skin chars from where the fire gets through outwards: leathery brown well before a hole, black just before it.
		float charred = Melt.ramp(q, through - 0.2F, through - 0.01F);
		float blister = t[k * STRIDE + BLISTER];
		float peel = t[k * STRIDE + PEEL];
		useSkin = false;
		lookGlow = false;
		lookTint = 0xFFFFFF;
		if (raw) {
			// Cut, not burnt: the skin is only skin to the edge of the opening.
			useSkin = true;
			lookSag = 0.0F;
			return;
		}

		// Charred skin at the edge of a hole slides down as the fat under it runs.
		lookSag = rim ? 0.4F * Melt.ramp(charred, 0.4F, 1.0F) : 0.0F;
		if (rim && melt.lit()) {
			// The edge of a hole, burning like paper.
			lookTile = Kind.EMBER.tile;
			lookGlow = true;
		} else if (charred > 0.9F) {
			// Black crust, cracked over embers while it burns.
			boolean ember = melt.lit() && unit(dice) < 0.12F;
			lookTile = ember ? Kind.EMBER.tile : Kind.CHAR.tile;
			lookGlow = ember;
		} else if (charred < 0.4F && q > (rotten ? 0.06F : 0.14F) && peel > (rotten ? 0.74F : 0.84F) - 0.14F * Melt.ramp(q, 0.14F, 0.3F)) {
			// Sloughed: a sheet of skin slid off, the raw dermis under it. Rotten skin is loose on the body already and
			// slips off sooner and wider, the dermis under it grey-green, not pink.
			lookTile = Kind.DERMIS.tile;
			lookTint = rot(0xFFFFFF, PUTRID);
		} else if (charred < 0.3F && q > 0.035F && blister > (pig ? 0.68F : 0.8F) - 0.12F * Melt.ramp(q, 0.035F, 0.2F)) {
			// A blister, in patches that spread; the oldest, in their middles, have burst. On a corpse they are the
			// blisters of decay, full of dark fluid, the heat only bursting them. Pork rind puffs up all over, golden.
			lookTile = q > 0.12F && blister > 0.92F - 0.08F * Melt.ramp(q, 0.12F, 0.3F) ? Kind.DERMIS.tile : Kind.BLISTER.tile;
			lookTint = pig && lookTile == Kind.BLISTER.tile ? CRACKLING | 0x202020 : rot(0xFFFFFF, lookTile == Kind.BLISTER.tile ? DECAY_BLISTER : PUTRID);
		} else {
			useSkin = true;
			// Quantized, so a stretch of skin burnt alike goes as one quad. Only skin burnt in life reddens: the redness
			// of a burn is blood rushing in, and a corpse has no circulation to send it (no vital reaction). A body set
			// alight after death, or a walking corpse, browns and chars without it.
			float red = rotten || !melt.vital() ? 0.0F : Math.round(Melt.ramp(q, 0.0F, 0.06F) * 8.0F) / 8.0F;
			float c = Math.round(charred * 12.0F) / 12.0F;
			int brown = pig ? CRACKLING : ROAST;
			int black = pig ? CRACKLING_BURNT : SCORCHED;
			int roast = c < 0.55F ? lerp(0xFFFFFF, brown, c / 0.55F) : lerp(brown, black, (c - 0.55F) / 0.35F);
			lookTint = multiply(lerp(0xFFFFFF, RED, red * (1.0F - c)), roast);
		}
	}

	/** How cooked or charred a layer is from {@code x}, the fire's own measure: none at all on a body opened without one. */
	private static float heat(float x) {
		return raw ? 0.0F : x;
	}

	/** True if a neighbour of cell {@code i, j} on the face is burnt deeper than {@code layer} (0 skin, 1 fat, 2 muscle). */
	private static boolean rim(int nb, int nc, int i, int j, int layer) {
		return i > 0 && state[j * nb + i - 1] > layer || i + 1 < nb && state[j * nb + i + 1] > layer || j > 0 && state[(j - 1) * nb + i] > layer
				|| j + 1 < nc && state[(j + 1) * nb + i] > layer;
	}

	/** Draws the plain skin from cell {@code from} to before {@code to} in row {@code j} as one quad, if there is a run. */
	private static void flush(VertexConsumer skin, PoseStack.Pose pose, ModelPart part, int a, int b, int c, int sign, int j, int nb, int nc, int from, int to,
			int tint, int color, int light, int overlay) {
		if (from < 0 || to <= from) {
			return;
		}

		float[] box = BOX[SKIN];
		float d = sign > 0 ? box[3 + a] : box[a];
		float b0 = grid(box, b, from, nb);
		float b1 = grid(box, b, to, nb);
		float c0 = grid(box, c, j, nc);
		float c1 = grid(box, c, j + 1, nc);
		face(skin, pose, part, a, b, c, sign, d, b0, b1, c0, c1, true, 0, 0, 0, 0, 0, multiply(color, tint) | color & 0xFF000000, light, overlay);
	}

	/** One cell of a layer's face, from the skin's own texels or one texel of an anatomy tile. */
	private static void cell(VertexConsumer buffer, PoseStack.Pose pose, ModelPart part, float[] box, int a, int b, int c, int sign, int i, int j, int nb,
			int nc, boolean ownSkin, int tile, int tint, int color, int light, int overlay) {
		float d = sign > 0 ? box[3 + a] : box[a];
		float b0 = grid(box, b, i, nb);
		float b1 = grid(box, b, i + 1, nb);
		float c0 = grid(box, c, j, nc);
		float c1 = grid(box, c, j + 1, nc);
		int argb = (ownSkin ? multiply(color, tint) : tint) | (ownSkin ? color & 0xFF000000 : 0xFF000000);
		face(buffer, pose, part, a, b, c, sign, d, b0, b1, c0, c1, ownSkin, tile, i, j, b, c, argb, light, overlay);
	}

	/**
	 * A face-aligned quad at depth {@code d} on axis {@code a}. With {@code ownSkin} its uv comes from the skin's
	 * polygon (see {@link #uvPlane}); otherwise it is texel {@code i, j} (wrapped) of anatomy tile {@code tile}, laid so
	 * the texture's v runs down the body.
	 */
	private static void face(VertexConsumer buffer, PoseStack.Pose pose, ModelPart part, int a, int b, int c, int sign, float d, float b0, float b1, float c0,
			float c1, boolean ownSkin, int tile, int i, int j, int axisB, int axisC, int argb, int light, int overlay) {
		set(Q0, a, b, c, d, b0 + sagB, c0 + sagC);
		set(Q1, a, b, c, d, b1 + sagB, c0 + sagC);
		set(Q2, a, b, c, d, b1 + sagB, c1 + sagC);
		set(Q3, a, b, c, d, b0 + sagB, c1 + sagC);
		N[0] = N[1] = N[2] = 0.0F;
		N[a] = sign;
		if (ownSkin) {
			quad(buffer, pose, part, argb, light, overlay, uAt(b0, c0), vAt(b0, c0), uAt(b1, c0), vAt(b1, c0), uAt(b1, c1), vAt(b1, c1), uAt(b0, c1),
					vAt(b0, c1), sign);
			return;
		}

		// v down the body: along whichever of the face's axes is y; on a top or bottom face, u along the first.
		boolean vAlongB = axisB == 1;
		int iu = vAlongB ? j : i;
		int iv = vAlongB ? i : j;
		float u0 = (tile % 8 * 16 + Math.floorMod(iu, 16)) / ATLAS_W;
		float v0 = (tile / 8 * 16 + Math.floorMod(iv, 16)) / ATLAS_H;
		float u1 = u0 + 1.0F / ATLAS_W;
		float v1 = v0 + 1.0F / ATLAS_H;
		if (vAlongB) {
			quad(buffer, pose, part, argb, light, overlay, u0, v0, u0, v1, u1, v1, u1, v0, sign);
		} else {
			quad(buffer, pose, part, argb, light, overlay, u0, v0, u1, v0, u1, v1, u0, v1, sign);
		}
	}

	/**
	 * The cut edges of the layers round a burnt cell: wherever a neighbour on the face still has a layer this cell has
	 * lost, that layer's side shows, from its surface down to the next layer's, as a slanted strip.
	 */
	private static void walls(VertexConsumer flesh, PoseStack.Pose pose, ModelPart part, float[] t, int a, int b, int c, int sign, int i, int j, int nb, int nc,
			float q, Kind muscle, int light, int overlay) {
		int s = state[j * nb + i];
		for (int side = 0; side < 4; side++) {
			int ni = i + (side == 0 ? -1 : side == 1 ? 1 : 0);
			int nj = j + (side == 2 ? -1 : side == 3 ? 1 : 0);
			if (ni < 0 || nj < 0 || ni >= nb || nj >= nc) {
				continue;
			}

			int n = nj * nb + ni;
			int ns = state[n];
			for (int layer = ns; layer < s; layer++) {
				int tile;
				int tint = 0xFFFFFF;
				if (layer == 0) {
					float charred = heat(Melt.ramp(q, t[n * STRIDE + SKIN] - 0.2F, t[n * STRIDE + SKIN] - 0.01F));
					tile = charred > 0.5F ? Kind.CHAR.tile : Kind.DERMIS.tile;
				} else if (layer == 1) {
					tile = fat().tile;
					tint = rot(lerp(0xFFFFFF, FAT_COOKED, heat(Melt.ramp(q, t[n * STRIDE + SKIN], t[n * STRIDE + FAT]))), ADIPOCERE);
				} else {
					float cook = heat(Melt.ramp(q, t[n * STRIDE + FAT], t[n * STRIDE + FAT] + 0.28F));
					tile = meatTile(muscle, cook);
					tint = rot(meatTint(cook), PUTRID);
				}

				float[] top = BOX[layer == 0 ? SKIN : layer == 1 ? FAT : MEAT];
				float[] bottom = BOX[layer == 0 ? FAT : layer == 1 ? MEAT : OUT];
				wall(flesh, pose, part, top, bottom, a, b, c, sign, i, j, nb, nc, side, tile, i + j, layer, tint, light, overlay);
			}
		}
	}

	/** The strip on side {@code side} (-b, +b, -c, +c) of cell {@code i, j}, from {@code top}'s surface to {@code bottom}'s. */
	private static void wall(VertexConsumer flesh, PoseStack.Pose pose, ModelPart part, float[] top, float[] bottom, int a, int b, int c, int sign, int i, int j,
			int nb, int nc, int side, int tile, int iu, int iv, int tint, int light, int overlay) {
		float dTop = sign > 0 ? top[3 + a] : top[a];
		float dBottom = sign > 0 ? bottom[3 + a] : bottom[a];
		boolean alongC = side < 2;
		int edge = side == 0 ? i : side == 1 ? i + 1 : side == 2 ? j : j + 1;
		int other = alongC ? j : i;
		if (alongC) {
			float eTop = grid(top, b, edge, nb);
			float eBottom = grid(bottom, b, edge, nb);
			set(Q0, a, b, c, dTop, eTop, grid(top, c, other, nc));
			set(Q1, a, b, c, dTop, eTop, grid(top, c, other + 1, nc));
			set(Q2, a, b, c, dBottom, eBottom, grid(bottom, c, other + 1, nc));
			set(Q3, a, b, c, dBottom, eBottom, grid(bottom, c, other, nc));
			N[0] = N[1] = N[2] = 0.0F;
			N[b] = side == 0 ? 1.0F : -1.0F;
		} else {
			float eTop = grid(top, c, edge, nc);
			float eBottom = grid(bottom, c, edge, nc);
			set(Q0, a, b, c, dTop, grid(top, b, other, nb), eTop);
			set(Q1, a, b, c, dTop, grid(top, b, other + 1, nb), eTop);
			set(Q2, a, b, c, dBottom, grid(bottom, b, other + 1, nb), eBottom);
			set(Q3, a, b, c, dBottom, grid(bottom, b, other, nb), eBottom);
			N[0] = N[1] = N[2] = 0.0F;
			N[c] = side == 2 ? 1.0F : -1.0F;
		}

		float u0 = (tile % 8 * 16 + Math.floorMod(iu, 16)) / ATLAS_W;
		float v0 = (tile / 8 * 16 + Math.floorMod(iv * 5 + side, 16)) / ATLAS_H;
		quad(flesh, pose, part, 0xFF000000 | tint, light, overlay, u0, v0, u0 + 1.0F / ATLAS_W, v0, u0 + 1.0F / ATLAS_W, v0 + 1.0F / ATLAS_H, u0,
				v0 + 1.0F / ATLAS_H, 1);
	}

	/** A strand of melted fat hanging from the middle of a cell, {@code length} pixels straight down. */
	private static void drip(VertexConsumer flesh, PoseStack.Pose pose, ModelPart part, float[] box, int a, int b, int c, int sign, int i, int j, int nb, int nc,
			float length, int light, int overlay) {
		P[a] = sign > 0 ? box[3 + a] : box[a];
		P[b] = (grid(box, b, i, nb) + grid(box, b, i + 1, nb)) * 0.5F;
		P[c] = (grid(box, c, j, nc) + grid(box, c, j + 1, nc)) * 0.5F;
		// Two directions across the strand.
		float dx = DOWN.x;
		float dy = DOWN.y;
		float dz = DOWN.z;
		float ex = Math.abs(dy) < 0.9F ? 0.0F : 1.0F;
		float ey = Math.abs(dy) < 0.9F ? 1.0F : 0.0F;
		float ez = 0.0F;
		// c = d x e, normalized, and f = d x c: the strand's two sides.
		float cx = dy * ez - dz * ey;
		float cy = dz * ex - dx * ez;
		float cz = dx * ey - dy * ex;
		float len = Mth.sqrt(cx * cx + cy * cy + cz * cz);
		if (len < 1.0E-4F) {
			return;
		}

		cx /= len;
		cy /= len;
		cz /= len;
		float fx = dy * cz - dz * cy;
		float fy = dz * cx - dx * cz;
		float fz = dx * cy - dy * cx;
		float w = 0.17F;
		float u0 = (Kind.MOLTEN.tile % 8 * 16 + (i & 15)) / ATLAS_W;
		float v0 = (Kind.MOLTEN.tile / 8 * 16 + (j & 15)) / ATLAS_H;
		float u1 = u0 + 1.0F / ATLAS_W;
		float v1 = v0 + 1.0F / ATLAS_H;
		for (int s = 0; s < 4; s++) {
			float ox = s == 0 ? cx : s == 1 ? fx : s == 2 ? -cx : -fx;
			float oy = s == 0 ? cy : s == 1 ? fy : s == 2 ? -cy : -fy;
			float oz = s == 0 ? cz : s == 1 ? fz : s == 2 ? -cz : -fz;
			float px = s == 0 ? fx : s == 1 ? -cx : s == 2 ? -fx : cx;
			float py = s == 0 ? fy : s == 1 ? -cy : s == 2 ? -fy : cy;
			float pz = s == 0 ? fz : s == 1 ? -cz : s == 2 ? -fz : cz;
			// The strand thins towards its tip.
			Q0[0] = P[0] + (ox - px) * w;
			Q0[1] = P[1] + (oy - py) * w;
			Q0[2] = P[2] + (oz - pz) * w;
			Q1[0] = P[0] + (ox + px) * w;
			Q1[1] = P[1] + (oy + py) * w;
			Q1[2] = P[2] + (oz + pz) * w;
			Q2[0] = P[0] + (ox + px) * w * 0.5F + dx * length;
			Q2[1] = P[1] + (oy + py) * w * 0.5F + dy * length;
			Q2[2] = P[2] + (oz + pz) * w * 0.5F + dz * length;
			Q3[0] = P[0] + (ox - px) * w * 0.5F + dx * length;
			Q3[1] = P[1] + (oy - py) * w * 0.5F + dy * length;
			Q3[2] = P[2] + (oz - pz) * w * 0.5F + dz * length;
			N[0] = ox;
			N[1] = oy;
			N[2] = oz;
			quad(flesh, pose, part, 0xFFFFFFFF, light, overlay, u0, v0, u1, v0, u1, v1, u0, v1, 1);
		}
	}

	/**
	 * An outer layer ({@link Coat}): hair and clothes go first, singeing dark and burning off with a glowing edge; a
	 * fleece later and slower, scorching and charring before it falls away, smouldering at the edges; horn only chars.
	 */
	private static void clothes(ModelPart part, ModelPart.Cube cube, Region region, boolean left, int index, Melt melt, PoseStack.Pose pose, VertexConsumer skin,
			VertexConsumer flesh, int light, int overlay, int color, Coat coat) {
		float q = melt.bare();
		float[][] thresholds = thresholds(melt.seed(), cube, region, left, index);
		float[] extent = extent(cube);
		inset(extent, 0.0F, BOX[SKIN]);
		boolean merge = !Bends.cut(part);
		float[] dims = {cube.maxX - cube.minX, cube.maxY - cube.minY, cube.maxZ - cube.minZ};
		for (int face = 0; face < 6; face++) {
			float[] t = thresholds[face];
			int a = face >> 1;
			int sign = (face & 1) == 0 ? -1 : 1;
			int b = (a + 1) % 3;
			int c = (a + 2) % 3;
			ModelPart.Polygon polygon = t == null ? null : polygon(cube, extent, a, sign);
			if (polygon == null) {
				continue;
			}

			int nb = cells(dims[b]);
			int nc = cells(dims[c]);
			uvPlane(polygon, b, c);
			if (state.length < nb * nc) {
				state = new byte[nb * nc];
			}

			for (int k = 0; k < nb * nc; k++) {
				state[k] = (byte) (coat != Coat.HORN && q >= gone(t, k, coat) ? 1 : 0);
			}

			for (int j = 0; j < nc; j++) {
				int run = -1;
				int runTint = 0;
				for (int i = 0; i <= nb; i++) {
					int k = j * nb + i;
					boolean plain = false;
					int tint = 0xFFFFFF;
					if (i < nb && state[k] == 0) {
						tint = singe(t, k, q, coat);
						if (melt.lit() && rim(nb, nc, i, j, 0) || !merge) {
							flush(skin, pose, part, a, b, c, sign, j, nb, nc, run, i, runTint, color, light, overlay);
							run = -1;
							boolean glow = melt.lit() && rim(nb, nc, i, j, 0);
							cell(glow ? flesh : skin, pose, part, BOX[SKIN], a, b, c, sign, i, j, nb, nc, !glow, Kind.EMBER.tile, tint, color,
									glow ? LightCoordsUtil.FULL_BRIGHT : light, overlay);
							continue;
						}

						plain = true;
					}

					if (plain && run >= 0 && tint == runTint) {
						continue;
					}

					flush(skin, pose, part, a, b, c, sign, j, nb, nc, run, i, runTint, color, light, overlay);
					run = plain ? i : -1;
					runTint = tint;
				}
			}
		}
	}

	/** The skeleton and organs of a part, fitted to its cube, cooked as far as the body has burnt. */
	private static void inside(ModelPart part, Region region, boolean left, Melt melt, PoseStack.Pose pose, VertexConsumer flesh, int light, int overlay) {
		Piece[] pieces = beast != null ? Anatomy.pieces(region, left, beast, turned, melt.detail(), melt.male(), melt.young()) : Anatomy.pieces(region, left);
		float burn = melt.burn();
		float jaw = region == Region.HEAD || region == Region.BEAST_HEAD ? melt.jaw() : 0.0F;
		// The bowel pushed out through a burst belly: down for a beast (along -z of a turned trunk), forward for a person.
		float bulge = region == Region.BEAST_TRUNK || region == Region.TORSO ? melt.rupture() * PROTRUDES * (1.0F - Melt.ramp(burn, 0.85F, 1.0F)) : 0.0F;
		int outAxis = region == Region.TORSO || turned ? 2 : 1;
		float outSign = region == Region.BEAST_TRUNK && !turned ? 1.0F : -1.0F;
		float hy = region.hingeY * SCALE[1] + OFFSET[1];
		float hz = region.hingeZ * SCALE[2] + OFFSET[2];
		float cos = Mth.cos(jaw);
		float sin = Mth.sin(jaw);
		for (int n = 0; n < pieces.length; n++) {
			Piece piece = pieces[n];
			Kind kind = lean(piece.kind());
			int tile = kind.tile;
			int tint = 0xFFFFFF;
			float size = 1.0F;
			switch (kind.cooks) {
				case BONE -> {
					// Thin bones (ribs, fingers, the face) burn ahead and thick long bones behind, and each lies more open to
					// the heat or more sheltered than the next, so a skeleton in a fire is mottled, never one even colour.
					// Only a fire that went on under the bare bones takes any of them past black (see BurntBone).
					float volume = (piece.x1() - piece.x0()) * (piece.y1() - piece.y0()) * (piece.z1() - piece.z0());
					float own = BurntBone.local(melt.bone() + 0.12F * (1.0F - Math.min(volume / 8.0F, 1.0F)), unit(pick(melt.seed(), n, 6, 0, 4)));
					if (own > BurntBone.CALCINED && kind != Kind.TEETH) {
						tile = Kind.CALCINED.tile;
						tint = BurntBone.chalk(own);
						// Calcined bone has lost its water and collagen: it shrinks by a tenth or more as it whitens.
						size = 1.0F - BurntBone.shrink(own);
					} else {
						tint = rot(BurntBone.tint(own), STAINED);
					}
				}
				case ORGAN -> {
					// Raw at first, then heat-fixed grey-brown (the heart like any meat), then roasted and black, shrivelling.
					float cook = Melt.ramp(burn, 0.4F, 0.95F);
					tile = meatTile(kind, cook);
					tint = rot(meatTint(cook), AUTOLYSED);
					// A corpse's organs digest themselves soft and slump smaller long before any fire reaches them.
					size = (rotten ? 0.8F : 1.0F) - 0.75F * Melt.ramp(burn, 0.5F, 1.0F);
					if (kind == Kind.SUET || kind == Kind.LARD || kind == Kind.FAT) {
						// Fat does not cook, it renders: it goes clear and amber, runs and is gone before the organs it wraps.
						tile = cook > 0.15F ? Kind.MOLTEN.tile : kind.tile;
						tint = rot(lerp(0xFFFFFF, BURNT, Melt.ramp(burn, 0.6F, 0.95F)), ADIPOCERE);
						size = 1.0F - 0.8F * Melt.ramp(burn, 0.4F, 0.75F);
					}
				}
				case HORN -> {
					// Horn and hoof scorch and char black, and crack a little smaller, but stay.
					tint = lerp(lerp(0xFFFFFF, SCORCHED_HORN, Melt.ramp(burn, 0.2F, 0.5F)), CHARRED_HORN, Melt.ramp(burn, 0.5F, 0.85F));
					size = 1.0F - 0.1F * Melt.ramp(burn, 0.5F, 1.0F);
				}
				case EYE -> {
					// Cooked, the lens and the white go opaque like a boiled egg's: no iris looks out of a burnt face.
					if (burn > 0.4F) {
						tile = Kind.CALCINED.tile;
						tint = lerp(0xF0EADC, 0xA89C88, Melt.ramp(burn, 0.4F, 0.8F));
					}
					size = 1.0F - Melt.ramp(burn, 0.6F, 0.9F);
				}
				case NOT -> {
				}
			}

			if (size < 0.3F) {
				continue;
			}

			for (int i = 0; i < 3; i++) {
				float pushed = i == outAxis && (kind == Kind.INTESTINE || kind == Kind.COLON) ? outSign * bulge : 0.0F;
				float lo = (piece.min(i) + pushed) * SCALE[i] + OFFSET[i];
				float hi = (piece.max(i) + pushed) * SCALE[i] + OFFSET[i];
				if (lo > hi) {
					float swap = lo;
					lo = hi;
					hi = swap;
				}

				if (i == 2 && kind == Kind.TONGUE && jaw > 0.0F) {
					// The tongue of a burnt body swells and is pushed out between the teeth as the neck muscles tighten.
					float out = 0.8F * Math.abs(SCALE[2]) * jaw / Melt.JAW;
					lo -= out;
					hi -= out;
				}

				float mid = (lo + hi) * 0.5F;
				BOX[OUT][i] = mid + (lo - mid) * size;
				BOX[OUT][3 + i] = mid + (hi - mid) * size;
			}

			box(flesh, pose, part, BOX[OUT], tile, tint, piece.texels(), n, piece.jaw() && jaw > 0.0F, hy, hz, cos, sin, light, overlay);
		}
	}

	/** A solid block: six faces, each a window onto its tile at {@code texels} per pixel, turned on the jaw if it hangs on it. */
	private static void box(VertexConsumer flesh, PoseStack.Pose pose, ModelPart part, float[] box, int tile, int tint, float texels, int n, boolean hinged,
			float hy, float hz, float cos, float sin, int light, int overlay) {
		for (int face = 0; face < 6; face++) {
			int a = face >> 1;
			int sign = (face & 1) == 0 ? -1 : 1;
			int b = (a + 1) % 3;
			int c = (a + 2) % 3;
			float d = sign > 0 ? box[3 + a] : box[a];
			float b0 = box[b];
			float b1 = box[3 + b];
			float c0 = box[c];
			float c1 = box[3 + c];
			set(Q0, a, b, c, d, b0, c0);
			set(Q1, a, b, c, d, b1, c0);
			set(Q2, a, b, c, d, b1, c1);
			set(Q3, a, b, c, d, b0, c1);
			N[0] = N[1] = N[2] = 0.0F;
			N[a] = sign;
			if (hinged) {
				hinge(Q0, hy, hz, cos, sin);
				hinge(Q1, hy, hz, cos, sin);
				hinge(Q2, hy, hz, cos, sin);
				hinge(Q3, hy, hz, cos, sin);
				float ny = N[1] * cos - N[2] * sin;
				float nz = N[1] * sin + N[2] * cos;
				N[1] = ny;
				N[2] = nz;
			}

			// v down the body, as on the shells; a small block shows a window somewhere in its tile, a centred tile (an
			// eye, the teeth) its middle.
			boolean vAlongB = b == 1;
			float spanU = (vAlongB ? c1 - c0 : b1 - b0) * texels;
			float spanV = (vAlongB ? b1 - b0 : c1 - c0) * texels;
			spanU = Math.min(spanU, 16.0F);
			spanV = Math.min(spanV, 16.0F);
			float startU = texels > 1.0F ? (16.0F - spanU) * 0.5F : (16.0F - spanU) * unit(pick(tile, n, face, 0, 2));
			float startV = texels > 1.0F ? (16.0F - spanV) * 0.5F : (16.0F - spanV) * unit(pick(tile, n, face, 0, 3));
			float u0 = (tile % 8 * 16 + startU) / ATLAS_W;
			float v0 = (tile / 8 * 16 + startV) / ATLAS_H;
			float u1 = u0 + spanU / ATLAS_W;
			float v1 = v0 + spanV / ATLAS_H;
			int argb = 0xFF000000 | tint;
			if (vAlongB) {
				quad(flesh, pose, part, argb, light, overlay, u0, v0, u0, v1, u1, v1, u1, v0, sign);
			} else {
				quad(flesh, pose, part, argb, light, overlay, u0, v0, u1, v0, u1, v1, u0, v1, sign);
			}
		}
	}

	/**
	 * The atlas window (u0, v0, u1, v1) face {@code face} of piece {@code n} shows of tile {@code tile}, {@code spanU} by
	 * {@code spanV} texels: somewhere in the tile, the same place every time, or its middle for a centred tile (an eye, the
	 * teeth). The same sums as {@link #box} (kept inline there, which runs every frame), for a thrown organ's
	 * {@link Anatomy.Shape}, made once, so it looks out of the body as it did in it.
	 */
	static float[] window(int tile, int n, int face, float spanU, float spanV, float texels) {
		float startU = texels > 1.0F ? (16.0F - spanU) * 0.5F : (16.0F - spanU) * unit(pick(tile, n, face, 0, 2));
		float startV = texels > 1.0F ? (16.0F - spanV) * 0.5F : (16.0F - spanV) * unit(pick(tile, n, face, 0, 3));
		float u0 = (tile % 8 * 16 + startU) / ATLAS_W;
		float v0 = (tile / 8 * 16 + startV) / ATLAS_H;
		return new float[] {u0, v0, u0 + spanU / ATLAS_W, v0 + spanV / ATLAS_H};
	}

	/** {@link #window} at a texel to a pixel, for the cut faces of an organ out of the body (see {@code OrganModel}). */
	public static float[] windowOf(int tile, int n, int face, float spanU, float spanV) {
		return window(tile, n, face, spanU, spanV, 1.0F);
	}

	/** Swings a point of the jaw down about its hinge. */
	private static void hinge(float[] p, float hy, float hz, float cos, float sin) {
		float y = p[1] - hy;
		float z = p[2] - hz;
		p[1] = hy + y * cos - z * sin;
		p[2] = hz + y * sin + z * cos;
	}

	/** Emits {@code Q0..Q3} (pixels, the part's frame) with normal {@code N}, wound outward for {@code sign} 1. */
	private static void quad(@Nullable VertexConsumer buffer, PoseStack.Pose pose, ModelPart part, int argb, int light, int overlay, float u0, float v0, float u1,
			float v1, float u2, float v2, float u3, float v3, int sign) {
		if (buffer == null) {
			// Not this pass.
			return;
		}

		float cx = (Q0[0] + Q2[0]) * 0.5F;
		float cy = (Q0[1] + Q2[1]) * 0.5F;
		float cz = (Q0[2] + Q2[2]) * 0.5F;
		Matrix4f m = Bends.matrixAt(pose, part, cx, cy, cz);
		pose.transformNormal(N[0], N[1], N[2], NORMAL);
		if (sign > 0) {
			vertex(buffer, m, Q0, argb, u0, v0, light, overlay);
			vertex(buffer, m, Q1, argb, u1, v1, light, overlay);
			vertex(buffer, m, Q2, argb, u2, v2, light, overlay);
			vertex(buffer, m, Q3, argb, u3, v3, light, overlay);
		} else {
			vertex(buffer, m, Q3, argb, u3, v3, light, overlay);
			vertex(buffer, m, Q2, argb, u2, v2, light, overlay);
			vertex(buffer, m, Q1, argb, u1, v1, light, overlay);
			vertex(buffer, m, Q0, argb, u0, v0, light, overlay);
		}
	}

	private static void vertex(VertexConsumer buffer, Matrix4f m, float[] p, int argb, float u, float v, int light, int overlay) {
		m.transformPosition(p[0] / 16.0F, p[1] / 16.0F, p[2] / 16.0F, POS);
		buffer.addVertex(POS.x, POS.y, POS.z, argb, u, v, overlay, light, NORMAL.x, NORMAL.y, NORMAL.z);
	}

	private static void set(float[] p, int a, int b, int c, float d, float pb, float pc) {
		p[a] = d;
		p[b] = pb;
		p[c] = pc;
	}

	/** Where grid line {@code index} of {@code cells} lies along {@code axis} of {@code box}. */
	private static float grid(float[] box, int axis, int index, int cells) {
		return box[axis] + (box[3 + axis] - box[axis]) * index / cells;
	}

	private static int cells(float size) {
		return Math.max(1, Math.round(size));
	}

	/** {@code extent} drawn {@code by} pixels in on every side (never past a little short of its middle). */
	private static void inset(float[] extent, float by, float[] out) {
		for (int i = 0; i < 3; i++) {
			float half = (extent[3 + i] - extent[i]) * 0.5F;
			float in = Math.min(by, half * 0.9F);
			out[i] = extent[i] + in;
			out[3 + i] = extent[3 + i] - in;
		}
	}

	/** A cube's real extent from its polygons (an outer layer is grown), min then max. */
	private static float[] extent(ModelPart.Cube cube) {
		return EXTENT.computeIfAbsent(cube, k -> {
			float[] e = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (ModelPart.Polygon polygon : k.polygons) {
				for (ModelPart.Vertex v : polygon.vertices()) {
					e[0] = Math.min(e[0], v.x());
					e[1] = Math.min(e[1], v.y());
					e[2] = Math.min(e[2], v.z());
					e[3] = Math.max(e[3], v.x());
					e[4] = Math.max(e[4], v.y());
					e[5] = Math.max(e[5], v.z());
				}
			}

			if (e[0] > e[3]) {
				e = new float[] {k.minX, k.minY, k.minZ, k.maxX, k.maxY, k.maxZ};
			}

			return e;
		});
	}

	/** The cube's polygon on side {@code sign} of axis {@code a}, found by where its corners are (mirrored cubes swap their names). */
	private static ModelPart.@Nullable Polygon polygon(ModelPart.Cube cube, float[] extent, int a, int sign) {
		float at = sign > 0 ? extent[3 + a] : extent[a];
		for (ModelPart.Polygon polygon : cube.polygons) {
			boolean on = polygon.vertices().length == 4;
			for (ModelPart.Vertex v : polygon.vertices()) {
				float coordinate = a == 0 ? v.x() : a == 1 ? v.y() : v.z();
				on &= Math.abs(coordinate - at) < 1.0E-3F;
			}

			if (on) {
				return polygon;
			}
		}

		return null;
	}

	/** Solves the skin's uv across a face as {@code uv = UV0 + UVb * (pb - b0) + UVc * (pc - c0)}, from three corners. */
	private static void uvPlane(ModelPart.Polygon polygon, int b, int c) {
		ModelPart.Vertex v0 = polygon.vertices()[0];
		ModelPart.Vertex v1 = polygon.vertices()[1];
		ModelPart.Vertex v2 = polygon.vertices()[2];
		float b0 = coordinate(v0, b);
		float c0 = coordinate(v0, c);
		float db1 = coordinate(v1, b) - b0;
		float dc1 = coordinate(v1, c) - c0;
		float db2 = coordinate(v2, b) - b0;
		float dc2 = coordinate(v2, c) - c0;
		float det = db1 * dc2 - db2 * dc1;
		if (Math.abs(det) < 1.0E-6F) {
			UV[0] = v0.u();
			UV[1] = v0.v();
			UV[2] = UV[3] = UV[4] = UV[5] = 0.0F;
			return;
		}

		float du1 = v1.u() - v0.u();
		float du2 = v2.u() - v0.u();
		float dv1 = v1.v() - v0.v();
		float dv2 = v2.v() - v0.v();
		// [db1 dc1; db2 dc2] [x; y] = [d1; d2]
		float ub = (du1 * dc2 - du2 * dc1) / det;
		float uc = (db1 * du2 - db2 * du1) / det;
		float vb = (dv1 * dc2 - dv2 * dc1) / det;
		float vc = (db1 * dv2 - db2 * dv1) / det;
		UV[0] = v0.u() - ub * b0 - uc * c0;
		UV[1] = v0.v() - vb * b0 - vc * c0;
		UV[2] = ub;
		UV[3] = uc;
		UV[4] = vb;
		UV[5] = vc;
	}

	private static float uAt(float pb, float pc) {
		return UV[0] + UV[2] * pb + UV[3] * pc;
	}

	private static float vAt(float pb, float pc) {
		return UV[1] + UV[4] * pb + UV[5] * pc;
	}

	private static float component(Vector3f v, int axis) {
		return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
	}

	private static float coordinate(ModelPart.Vertex v, int axis) {
		return axis == 0 ? v.x() : axis == 1 ? v.y() : v.z();
	}

	/**
	 * When the fire gets through each layer of every cell of a cube: four numbers per cell. A smooth noise over the
	 * body makes the holes open in patches; thin skin goes sooner (see {@link Region#thin}); over the eyes and lips there
	 * is no muscle. The outer layer of a skin is grown but the same size underneath, so its cells sit over the skin's.
	 */
	private static float[][] thresholds(long seed, ModelPart.Cube cube, Region region, boolean left, int index) {
		Key key = new Key(seed, cube, fleece);
		float[][] cached = THRESHOLDS.get(key);
		if (cached != null) {
			return cached;
		}

		if (THRESHOLDS.size() >= MAX_CACHED) {
			THRESHOLDS.clear();
		}

		float[][] thresholds = new float[6][];
		float[] min = {cube.minX, cube.minY, cube.minZ};
		float[] max = {cube.maxX, cube.maxY, cube.maxZ};
		for (int face = 0; face < 6; face++) {
			int a = face >> 1;
			int sign = (face & 1) == 0 ? -1 : 1;
			int b = (a + 1) % 3;
			int c = (a + 2) % 3;
			if (max[b] - min[b] <= 0.0F || max[c] - min[c] <= 0.0F) {
				continue;
			}

			int nb = cells(max[b] - min[b]);
			int nc = cells(max[c] - min[c]);
			float[] t = new float[nb * nc * STRIDE];
			float[] p = new float[3];
			for (int j = 0; j < nc; j++) {
				for (int i = 0; i < nb; i++) {
					p[a] = sign > 0 ? max[a] : min[a];
					p[b] = min[b] + (i + 0.5F) * (max[b] - min[b]) / nb;
					p[c] = min[c] + (j + 0.5F) * (max[c] - min[c]) / nc;
					float cx = (p[0] - OFFSET[0]) / SCALE[0];
					float cy = (p[1] - OFFSET[1]) / SCALE[1];
					float cz = (p[2] - OFFSET[2]) / SCALE[2];
					if (left) {
						cx = -cx;
					}

					if (turned) {
						// Back from the turned cube's frame to the trunk as it stands.
						float standing = -cz;
						cz = cy;
						cy = standing;
					}

					// The bottom pixel of a beast's leg is its hoof, horn not skin: it chars late and never burns through.
					boolean hoof = (region == Region.FORELEG || region == Region.HINDLEG) && cy > region.max[1] - 1.0F / Math.abs(SCALE[1]);
					float thin = hoof ? 0.55F : region.thin(cx, cy, cz);
					// A sheep's wool keeps the fire off the skin under it for a while.
					float shield = fleece && region.fleeced(cx, cy, cz) ? FLEECE_SHIELD : 0.0F;
					float wx = p[0] + index * 41.0F;
					float wy = p[1] + index * 17.0F;
					float wz = p[2];
					int k = (j * nb + i) * STRIDE;
					t[k + OUT] = Math.max(0.004F, 0.012F + 0.06F * noise(seed, wx, wy, wz) + 0.3F * thin);
					t[k + SKIN] = 0.24F + 0.2F * noise(seed ^ 0x2545F4914F6CDD1DL, wx, wy, wz) + thin + shield;
					t[k + FAT] = region.fatless(cx, cy, cz) ? t[k + SKIN] : t[k + SKIN] + 0.05F + 0.07F * noise(seed ^ 0x6C8E9CF570932BD5L, wx, wy, wz);
					t[k + MEAT] = region.bare(cx, cy, cz) ? t[k + FAT] : t[k + FAT] + 0.1F + 0.16F * noise(seed ^ 0x1B873593L, wx, wy, wz);
					t[k + BLISTER] = patch(seed ^ 0x3C6EF372FE94F82BL, wx, wy, wz, 1.3F);
					t[k + PEEL] = patch(seed ^ 0x7F4A7C15A54FF53AL, wx, wy, wz, 2.2F);
					t[k + RUPTURE] = region.rupture(cx, cy, cz);
				}
			}

			thresholds[face] = t;
		}

		THRESHOLDS.put(key, thresholds);
		return thresholds;
	}

	/** When a cell of an outer layer is gone: hair and clothes in the first seconds, a fleece later, over a wider spread. */
	private static float gone(float[] t, int k, Coat coat) {
		return coat == Coat.FLEECE ? 0.05F + 2.3F * t[k * STRIDE + OUT] : t[k * STRIDE + OUT];
	}

	/** The tint of an outer layer's cell still there: singed dark just before it goes; wool scorched, then charred; horn charred. */
	private static int singe(float[] t, int k, float q, Coat coat) {
		return switch (coat) {
			case HAIR -> {
				float singe = Math.round(Melt.ramp(q, t[k * STRIDE + OUT] - 0.04F, t[k * STRIDE + OUT]) * 8.0F) / 8.0F;
				yield lerp(0xFFFFFF, 0x3A2A20, singe);
			}
			case FLEECE -> {
				float at = gone(t, k, coat);
				float scorch = Math.round(Melt.ramp(q, at - 0.09F, at - 0.04F) * 6.0F) / 6.0F;
				float charred = Math.round(Melt.ramp(q, at - 0.045F, at) * 6.0F) / 6.0F;
				yield lerp(lerp(0xFFFFFF, SCORCHED_WOOL, scorch), CHARRED_WOOL, charred);
			}
			case HORN -> {
				float scorch = Math.round(Melt.ramp(q, 0.1F, 0.4F) * 6.0F) / 6.0F;
				float charred = Math.round(Melt.ramp(q, 0.4F, 0.8F) * 6.0F) / 6.0F;
				yield lerp(lerp(0xFFFFFF, SCORCHED_HORN, scorch), CHARRED_HORN, charred);
			}
		};
	}

	/** Patches about {@code size} pixels across: smooth noise of one scale, stretched so its top tenth stands out. */
	private static float patch(long seed, float x, float y, float z, float size) {
		return Mth.clamp((lattice(seed, x / size, y / size, z / size) - 0.5F) * 1.8F + 0.5F, 0.0F, 1.0F);
	}

	/** Smooth noise in [0, 1] over pixel space: blobs a couple of pixels across with finer ones on them. */
	private static float noise(long seed, float x, float y, float z) {
		float v = 0.65F * lattice(seed, x / 2.1F, y / 2.1F, z / 2.1F) + 0.35F * lattice(seed ^ 0x51ED27L, x / 0.9F, y / 0.9F, z / 0.9F);
		return Mth.clamp((v - 0.5F) * 1.7F + 0.5F, 0.0F, 1.0F);
	}

	private static float lattice(long seed, float x, float y, float z) {
		int ix = Mth.floor(x);
		int iy = Mth.floor(y);
		int iz = Mth.floor(z);
		float fx = smooth(x - ix);
		float fy = smooth(y - iy);
		float fz = smooth(z - iz);
		float x00 = Mth.lerp(fx, hash(seed, ix, iy, iz), hash(seed, ix + 1, iy, iz));
		float x10 = Mth.lerp(fx, hash(seed, ix, iy + 1, iz), hash(seed, ix + 1, iy + 1, iz));
		float x01 = Mth.lerp(fx, hash(seed, ix, iy, iz + 1), hash(seed, ix + 1, iy, iz + 1));
		float x11 = Mth.lerp(fx, hash(seed, ix, iy + 1, iz + 1), hash(seed, ix + 1, iy + 1, iz + 1));
		return Mth.lerp(fz, Mth.lerp(fy, x00, x10), Mth.lerp(fy, x01, x11));
	}

	private static float smooth(float t) {
		return t * t * (3.0F - 2.0F * t);
	}

	private static float hash(long seed, int x, int y, int z) {
		return unit(mix(seed ^ x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L));
	}

	/** Dice for one cell's choices: the same on every screen and every frame. */
	private static long pick(long seed, int index, int face, int k, int which) {
		return mix(seed ^ (index * 8L + face) * 0x9E3779B97F4A7C15L ^ k * 0xC2B2AE3D27D4EB4FL ^ which * 0x94D049BB133111EBL);
	}

	private static long mix(long h) {
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		return h ^ (h >>> 31);
	}

	private static float unit(long h) {
		return (h >>> 40 & 0xFFFFFF) / (float) (1 << 24);
	}

	/**
	 * Meat in a fire, as real meat goes: raw red, then (past about 60 C, as the myoglobin denatures) the grey-brown of
	 * well-done meat, then roasted brown, then charred black. The tile for {@code raw} at that point.
	 */
	private static int meatTile(Kind raw, float cook) {
		return cook > 0.88F ? Kind.CHAR.tile : cook > COOKS ? raw.cooked().tile : raw.tile;
	}

	/** The multiplier on {@link #meatTile}'s tile: paling as it starts to cook, then browning towards black. */
	private static int meatTint(float cook) {
		return cook <= COOKS ? lerp(0xFFFFFF, PALING, cook / COOKS) : lerp(0xFFFFFF, BURNT, (cook - COOKS) / (0.88F - COOKS));
	}

	/** {@code tint} as it is in a rotting body ({@code decay} multiplied in), or as it is if the body is fresh. */
	private static int rot(int tint, int decay) {
		return rotten ? multiply(tint, decay) : tint;
	}

	private static int multiply(int argb, int rgb) {
		int r = (argb >> 16 & 0xFF) * (rgb >> 16 & 0xFF) / 255;
		int g = (argb >> 8 & 0xFF) * (rgb >> 8 & 0xFF) / 255;
		int b = (argb & 0xFF) * (rgb & 0xFF) / 255;
		return r << 16 | g << 8 | b;
	}

	private static int lerp(int from, int to, float t) {
		t = Mth.clamp(t, 0.0F, 1.0F);
		int r = Math.round(Mth.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF));
		int g = Math.round(Mth.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF));
		int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
		return r << 16 | g << 8 | b;
	}
}
