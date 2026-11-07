package dev.fluidgore.anatomy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import org.jspecify.annotations.Nullable;

/**
 * The inside of a person, built of blocks the way Minecraft builds everything else: what is under a player's skin, laid
 * out in the vanilla humanoid's own pixels so it sits right inside every part of the model and moves with it.
 *
 * <p>Under the skin of every part are two shells, a layer of fat and a layer of muscle (see {@link Region}: how far
 * each lies under the skin), and inside those the {@link Piece}s: the skeleton (the skull with its eye sockets, cheek
 * bones, teeth and a jaw that hangs open once the muscles that hold it are gone; the spine, vertebra by vertebra; the
 * collar bones, breastbone and the ribs, drawn as four hoops the way the vanilla skeleton draws them, the upper ones
 * joined to the breastbone by cartilage and the last floating; the
 * shoulder blades and pelvis; the long bones of the limbs with their knobbed ends, the kneecaps, the bones of the hands
 * and feet) and the organs, where they really lie: the windpipe, the lungs either side of the heart, the liver under
 * the right ribs, the stomach and spleen under the left, the kidneys against the back, the colon framing the coils of
 * the small intestine, the bladder low in the pelvis.
 *
 * <p>Coordinates are those of the vanilla humanoid model ({@code HumanoidModel.createMesh}): pixels, part-local, y down,
 * the face towards -z, the player's right towards -x. Each part's pieces are fitted to its real cube by
 * {@link MeltRenderer}, so slim arms, zombies and piglins get their own proportions. Limbs are given for the right
 * side; the left is its mirror image.
 *
 * <p>Sheep, pigs and cows (a {@link Beast}) are built the same way in the quadruped's parts (the {@code BEAST_}
 * regions and the legs): an ungulate's skeleton, its organs where they lie in a grazer or in a pig, its own fat and lean,
 * and hooves of horn that outlast the fire.
 */
public final class Anatomy {
	/** What a block of the body is: its tile in anatomy.png and how it goes in a fire. */
	public enum Kind {
		BONE(0, Cooks.BONE), BONE_END(1, Cooks.BONE), SKULL(2, Cooks.BONE), TEETH(3, Cooks.BONE), SOCKET(4, Cooks.NOT),
		EYE(5, Cooks.EYE), CARTILAGE(6, Cooks.ORGAN), VERTEBRA(7, Cooks.BONE),
		MUSCLE(8, Cooks.ORGAN), CHEST(9, Cooks.ORGAN), BACK(10, Cooks.ORGAN), FACE(11, Cooks.ORGAN), TONGUE(12, Cooks.ORGAN),
		FAT(13, Cooks.ORGAN), DERMIS(14, Cooks.ORGAN), BLISTER(15, Cooks.ORGAN),
		LUNG(16, Cooks.ORGAN), HEART(17, Cooks.ORGAN), AORTA(18, Cooks.ORGAN), LIVER(19, Cooks.ORGAN), STOMACH(20, Cooks.ORGAN),
		INTESTINE(21, Cooks.ORGAN), COLON(22, Cooks.ORGAN), KIDNEY(23, Cooks.ORGAN),
		SPLEEN(24, Cooks.ORGAN), BLADDER(25, Cooks.ORGAN), TRACHEA(26, Cooks.ORGAN), CHAR(27, Cooks.NOT), EMBER(28, Cooks.NOT),
		CALCINED(29, Cooks.NOT), MOLTEN(30, Cooks.NOT), BRAIN(31, Cooks.ORGAN),
		PHALANGES(32, Cooks.BONE), COOKED_MUSCLE(33, Cooks.NOT), COOKED_CHEST(34, Cooks.NOT), COOKED_BACK(35, Cooks.NOT),
		COOKED_FACE(36, Cooks.NOT), HEAT_FIXED(37, Cooks.NOT),
		/** A sheep's fat: hard, brittle, chalk white (suet round the kidneys, the same under the skin). */
		SUET(38, Cooks.ORGAN),
		/** A pig's fat: soft, smooth and white, the leaf lard round its kidneys and the back fat under its hide. */
		LARD(39, Cooks.ORGAN),
		/** The lean of a sheep, a pig and a cow: dark brick-red mutton, pale pink pork, cherry-red beef. */
		MUTTON(40, Cooks.ORGAN), PORK(41, Cooks.ORGAN), BEEF(42, Cooks.ORGAN),
		/** A cloven hoof's horn and a cow's horn: keratin, which chars but does not burn away. */
		HOOF(43, Cooks.HORN), HORN(44, Cooks.HORN),
		/** The rumen, the great first stomach of a ruminant: its lining shaggy with papillae, green-brown with cud. */
		RUMEN(45, Cooks.ORGAN),
		COOKED_MUTTON(46, Cooks.NOT), COOKED_PORK(47, Cooks.NOT), COOKED_BEEF(48, Cooks.NOT),
		/** A ruminant's gallbladder (green with bile), the honeycombed reticulum, and a vein, dark where the arteries are pale. */
		GALLBLADDER(49, Cooks.ORGAN), RETICULUM(50, Cooks.ORGAN), VEIN(51, Cooks.ORGAN),
		/** An organ cut through with a blade (see {@link #section}): the liver's dark glossy parenchyma and veins, the lung's pink sponge and bronchi. */
		CUT_LIVER(52, Cooks.NOT), CUT_LUNG(53, Cooks.NOT),
		/** A gland: the pancreas, thymus, salivary glands, thyroid and adrenals, pale lobules packed together. */
		GLAND(54, Cooks.ORGAN),
		/** A ram's testis: the tough glossy white tunic, fine vessels winding over it. */
		TESTIS(55, Cooks.ORGAN),
		/** More cut faces: the heart through its ventricles, the brain's grey and white matter, the kidney's cortex, medulla and pelvis, spongy bone and marrow. */
		CUT_HEART(56, Cooks.NOT), CUT_BRAIN(57, Cooks.NOT), CUT_KIDNEY(58, Cooks.NOT), CUT_BONE(59, Cooks.NOT);

		/**
		 * The plain name of this tissue (a translation id, see {@link Piece#name}), for a piece with no name of its own:
		 * "liver", "kidney", "heart". Bones are named by the piece.
		 */
		public String noun() {
			return switch (this) {
				case BONE, BONE_END -> "bone";
				case SKULL -> "skull";
				case TEETH -> "teeth";
				case SOCKET -> "socket";
				case EYE -> "eye";
				case CARTILAGE -> "cartilage";
				case VERTEBRA -> "vertebra";
				case MUSCLE, CHEST, BACK, FACE, MUTTON, PORK, BEEF -> "muscle";
				case TONGUE -> "tongue";
				case FAT, LARD -> "fat";
				case SUET -> "suet";
				case DERMIS -> "skin";
				case BLISTER -> "blister";
				case LUNG -> "lung";
				case HEART -> "heart";
				case AORTA -> "aorta";
				case LIVER -> "liver";
				case STOMACH -> "stomach";
				case INTESTINE -> "small_intestine";
				case COLON -> "colon";
				case KIDNEY -> "kidney";
				case SPLEEN -> "spleen";
				case BLADDER -> "bladder";
				case TRACHEA -> "windpipe";
				case BRAIN -> "brain";
				case PHALANGES -> "toe_bones";
				case HOOF -> "hoof";
				case HORN -> "horn";
				case RUMEN -> "rumen";
				case GALLBLADDER -> "gallbladder";
				case RETICULUM -> "reticulum";
				case VEIN -> "vena_cava";
				case GLAND -> "gland";
				case TESTIS -> "testis";
				default -> "remains";
			};
		}

		/** Index in anatomy.png (128x128): eight 16px tiles a row. */
		public final int tile;
		public final Cooks cooks;

		Kind(int tile, Cooks cooks) {
			this.tile = tile;
			this.cooks = cooks;
		}

		/**
		 * What a blade shows cutting through this tissue: the liver (with its gallbladder and veins) its cut parenchyma, a lung
		 * its cut sponge, the heart its chambers, the brain its grey and white matter, the kidney its cortex, medulla and pelvis,
		 * a bone its marrow; any other tissue looks the same through as on its surface.
		 */
		public Kind section() {
			return switch (this) {
				case LIVER, GALLBLADDER, VEIN -> CUT_LIVER;
				case LUNG, TRACHEA -> CUT_LUNG;
				case HEART -> CUT_HEART;
				case BRAIN -> CUT_BRAIN;
				case KIDNEY -> CUT_KIDNEY;
				case BONE, BONE_END, VERTEBRA, SKULL, PHALANGES -> CUT_BONE;
				default -> this;
			};
		}

		/**
		 * The same tissue cooked through: muscle (and the heart, which is muscle) goes the grey-brown of well-done meat in
		 * its own shape; any other organ is heat-fixed, firm and grey-brown like every other.
		 */
		public Kind cooked() {
			return switch (this) {
				case MUSCLE, HEART -> COOKED_MUSCLE;
				case CHEST -> COOKED_CHEST;
				case BACK -> COOKED_BACK;
				case FACE -> COOKED_FACE;
				case MUTTON -> COOKED_MUTTON;
				case PORK -> COOKED_PORK;
				case BEEF -> COOKED_BEEF;
				default -> HEAT_FIXED;
			};
		}
	}

	/**
	 * A four-legged farm animal, burning down through the same layers as a person (the {@code BEAST_} regions and the
	 * legs): its own fat, its own lean, and the inside of a ruminant (a sheep, a cow: the four-chambered stomach, the
	 * rumen filling the left of the belly) or of a pig (one simple stomach, the spiral colon).
	 *
	 * @param fat the fat under its skin and round its kidneys
	 * @param lean its muscle
	 * @param ruminant chews the cud: a rumen, no upper front teeth (a hard dental pad instead)
	 * @param hide how far under the skin of the trunk its fat lies, in model pixels
	 * @param cover how far under the skin of the trunk its muscle lies: a pig's thick back fat, a sheep's thinner cover
	 */
	public enum Beast {
		SHEEP(Kind.SUET, Kind.MUTTON, true, 0.2F, 0.5F),
		PIG(Kind.LARD, Kind.PORK, false, 0.25F, 0.75F),
		// Grass-fed beef fat is cream to yellow, like a person's.
		COW(Kind.FAT, Kind.BEEF, true, 0.3F, 0.6F);

		public final Kind fat;
		public final Kind lean;
		public final boolean ruminant;
		public final float hide;
		public final float cover;

		Beast(Kind fat, Kind lean, boolean ruminant, float hide, float cover) {
			this.fat = fat;
			this.lean = lean;
			this.ruminant = ruminant;
			this.hide = hide;
			this.cover = cover;
		}

		/** The animal {@code type} is, or null if it is not one of these. */
		public static @Nullable Beast of(EntityType<?> type) {
			if (type == EntityTypes.SHEEP) {
				return SHEEP;
			}

			if (type == EntityTypes.PIG) {
				return PIG;
			}

			return type == EntityTypes.COW || type == EntityTypes.MOOSHROOM ? COW : null;
		}

		/**
		 * Whether this animal's anatomy carries its own body wall (the hide, the fat under it and the named muscles) out to
		 * the faces of its trunk, so a blast throws real flesh and needs few made-up scraps besides.
		 */
		public boolean walled() {
			return this == SHEEP;
		}
	}

	/**
	 * How finely a sheep's trunk is cut. Skin, fat and muscle are always a cell per skin pixel; this is the grain of what lies
	 * under them. {@link #MIXED} is the anatomy every body has; the even grains are only drawn, for comparing detail by
	 * eye (the {@code resolution} demo). Which organ a piece is always comes from {@link #MIXED}'s table, so a body drawn
	 * at another grain is still the same body.
	 */
	public enum Detail {
		/** A cube a pixel, like the skin over it. */
		COARSE,
		/** Half pixels everywhere. */
		HALF,
		/** Quarter pixels everywhere. */
		FINE,
		/**
		 * Half pixels, with the small organs that need it most (the heart, the great vessels, the windpipe and gullet, the
		 * kidneys) cut to quarter pixels in the same cavity (see {@code tools/SheepAnatomyModels.java}).
		 */
		MIXED
	}

	/** How a tissue goes in a fire. */
	public enum Cooks {
		/** Scorched brown, then charred black; grey and calcined white only under a long fire, shrinking a little as it whitens. */
		BONE,
		/** Keratin (hoof and horn): scorches, then chars black and cracks, but stays where it is. */
		HORN,
		/** Browns, blackens and shrivels away to nothing. */
		ORGAN,
		/** The lens and white go milky and opaque, then the eye shrivels. */
		EYE,
		/** Already what it is (a cavity, char, embers). */
		NOT
	}

	/**
	 * A part of the body: the cube it fills (its own, unfitted), how far under the skin the fat and the muscle lie, and
	 * how far in the muscle shrinks as it cooks. A head has little of either over the skull.
	 */
	public enum Region {
		HEAD(-4, -8, -4, 4, 0, 4, 0.2F, 0.4F, 0.4F, -2.0F, 0.5F),
		TORSO(-4, 0, -2, 4, 12, 2, 0.2F, 0.4F, 0.5F, 0.0F, 0.0F),
		ARM(-3, -2, -2, 1, 10, 2, 0.25F, 0.6F, 0.9F, 0.0F, 0.0F),
		LEG(-2, 0, -2, 2, 12, 2, 0.25F, 0.6F, 0.9F, 0.0F, 0.0F),
		/**
		 * A beast's head, the pig's own cube: the muzzle towards -z, the poll at 0. The jaw hinges low at the back of the
		 * cheek, under the arch of the cheekbone. Little but skin over the face.
		 */
		BEAST_HEAD(-4, -4, -8, 4, 4, 0, 0.2F, 0.4F, 0.6F, -0.25F, -1.5F),
		/**
		 * A beast's trunk, laid as the animal stands (the model turns a grown one's cube a right angle, see
		 * {@link #turned}): the back at -y, the belly at +y, the chest towards -z, the rump towards +z. How deep its
		 * fat and muscle lie is the {@link Beast}'s.
		 */
		BEAST_TRUNK(-5, -4, -8, 5, 4, 8, 0.25F, 0.6F, 0.85F, 0.0F, 0.0F),
		/** A foreleg from the elbow down, front at -z; the right one, the body towards +x. */
		FORELEG(-2, 0, -2, 2, 12, 2, 0.2F, 0.5F, 0.8F, 0.0F, 0.0F),
		/** A hind leg from the stifle down. */
		HINDLEG(-2, 0, -2, 2, 12, 2, 0.2F, 0.5F, 0.8F, 0.0F, 0.0F);

		/** The vanilla cube, right side for a limb. */
		public final float[] min;
		public final float[] max;
		public final float fat;
		public final float muscle;
		public final float shrunk;
		/** The jaw's hinge, canonical pixels: it swings down about the x axis through here. */
		public final float hingeY;
		public final float hingeZ;

		Region(float x0, float y0, float z0, float x1, float y1, float z1, float fat, float muscle, float shrunk, float hingeY, float hingeZ) {
			this.min = new float[] {x0, y0, z0};
			this.max = new float[] {x1, y1, z1};
			this.fat = fat;
			this.muscle = muscle;
			this.shrunk = shrunk;
			this.hingeY = hingeY;
			this.hingeZ = hingeZ;
		}

		/** The cube for the left side: the right one mirrored. */
		public float[] min(boolean left) {
			return left ? new float[] {-max[0], min[1], min[2]} : min;
		}

		public float[] max(boolean left) {
			return left ? new float[] {-min[0], max[1], max[2]} : max;
		}

		/** The cube in the part's own frame: {@link #turned} for a grown beast's trunk. */
		public float[] min(boolean left, boolean turned) {
			return turned ? new float[] {min[0], min[2], -max[1]} : min(left);
		}

		public float[] max(boolean left, boolean turned) {
			return turned ? new float[] {max[0], max[2], -min[1]} : max(left);
		}

		/** One of a four-legged animal's. */
		public boolean beast() {
			return this == BEAST_HEAD || this == BEAST_TRUNK || this == FORELEG || this == HINDLEG;
		}

		/**
		 * Where a sheep's fleece covers it (canonical pixels): all the trunk, the legs down to the knees and hocks, the
		 * head but for the face. The wool keeps the fire off the skin under it a while.
		 */
		public boolean fleeced(float x, float y, float z) {
			return switch (this) {
				case BEAST_TRUNK -> true;
				case BEAST_HEAD -> z > -6.0F;
				case FORELEG, HINDLEG -> y < 6.0F;
				default -> false;
			};
		}

		/** The muscle tile on one face of the muscle shell: chest and back on the trunk, the face on the head. */
		public Kind muscleOn(int axis, int sign) {
			if (beast()) {
				return Kind.MUSCLE;
			}

			if (axis == 2 && this == TORSO) {
				return sign < 0 ? Kind.CHEST : Kind.BACK;
			}

			return axis == 2 && sign < 0 && this == HEAD ? Kind.FACE : Kind.MUSCLE;
		}

		/**
		 * How much sooner the fire gets through the skin at a point (canonical pixels, right side): the thin skin of the
		 * hands and feet, the eyelids and lips, the face. Negative is sooner.
		 */
		public float thin(float x, float y, float z) {
			return switch (this) {
				case HEAD -> z < -3.9F ? (eye(x, y) || mouth(x, y) ? -0.07F : -0.03F) : 0.0F;
				case ARM -> y > 8.0F ? -0.06F : 0.0F;
				case LEG -> y > 10.5F ? -0.05F : 0.0F;
				case TORSO -> 0.02F;
				// The eyes on the sides of the head, the bare muzzle and lips in front.
				case BEAST_HEAD -> beastEye(x, y, z) ? -0.07F : z < -7.9F ? (y > 1.0F ? -0.05F : -0.03F) : 0.0F;
				// The belly's skin is thinner than the back's.
				case BEAST_TRUNK -> y > 3.9F ? -0.02F : y < -3.9F ? 0.02F : 0.0F;
				// From the knee or hock down the skin lies straight on tendon and bone (the hoof itself, see
				// MeltRenderer, is horn and never burns through).
				case FORELEG, HINDLEG -> y > 6.5F ? -0.05F : 0.0F;
			};
		}

		/**
		 * True where a face cell has no muscle under its skin and fat: the eyes and the lips. Burnt through there, the
		 * eyes look out of their sockets and the teeth show in the grin of every burnt body. A beast has none under its
		 * eyes, its lips, or the lower legs (only tendon over the cannon bones).
		 */
		public boolean bare(float x, float y, float z) {
			return switch (this) {
				case HEAD -> z < -3.9F && (eye(x, y) || mouth(x, y));
				case BEAST_HEAD -> beastEye(x, y, z) || z < -7.9F && y > 1.0F;
				case FORELEG, HINDLEG -> y > 6.5F;
				default -> false;
			};
		}

		/**
		 * Where the belly wall of a burning body splits from the heat (canonical pixels), 0 outside it, up to 1 at the
		 * middle of the split, where it opens first. As the fire dries and shrinks the skin and muscle of the belly over the
		 * swelling guts, the wall tears along its thinnest line (down the middle of a beast's belly, across a person's lower
		 * abdomen) and the bowel pushes out through it: a heat rupture, one of the commonest findings on a burnt body.
		 */
		public float rupture(float x, float y, float z) {
			return switch (this) {
				case BEAST_TRUNK -> y > 3.9F && y < 4.1F && Math.abs(x) < 1.6F && z > 0.5F && z < 5.5F ? 1.0F - Math.abs(z - 3.0F) / 2.5F * 0.9F - Math.abs(x) * 0.05F : 0.0F;
				case TORSO -> z < -1.9F && z > -2.1F && Math.abs(x) < 2.6F && y > 7.5F && y < 10.5F ? 1.0F - Math.abs(x) / 2.6F * 0.9F - Math.abs(y - 9.0F) * 0.05F : 0.0F;
				default -> 0.0F;
			};
		}

		/** True where there is no fat under the skin: a beast's legs from the knee or hock down, all tendon and bone. */
		public boolean fatless(float x, float y, float z) {
			return (this == FORELEG || this == HINDLEG) && y > 6.5F;
		}

		/** A beast's eye, on the side of its head, as a grazer's are. */
		private static boolean beastEye(float x, float y, float z) {
			return Math.abs(x) > 3.9F && y > -2.5F && y < -1.25F && z > -5.25F && z < -3.5F;
		}

		private static boolean eye(float x, float y) {
			float ax = Math.abs(x);
			return y > -4.0F && y < -3.0F && ax > 1.0F && ax < 3.0F;
		}

		private static boolean mouth(float x, float y) {
			return y > -2.0F && y < -1.0F && Math.abs(x) < 2.0F;
		}
	}

	/**
	 * One block of the inside, in canonical pixels. {@code owner}: an optional anatomical object id, including its side;
	 * disconnected lobes with that id remain one organ. {@code jaw}: it hangs on the jaw hinge and swings open with it.
	 * {@code texels}: texture texels per pixel (1 like a skin; more for the eyes and teeth, so a block that small still
	 * shows an iris or the line between two teeth).
	 */
	public record Piece(Kind kind, float x0, float y0, float z0, float x1, float y1, float z1, boolean jaw, float texels, String name,
			@Nullable String owner) {
		public Piece(Kind kind, float x0, float y0, float z0, float x1, float y1, float z1, boolean jaw, float texels, String name) {
			this(kind, x0, y0, z0, x1, y1, z1, jaw, texels, name, null);
		}

		public float min(int axis) {
			return axis == 0 ? x0 : axis == 1 ? y0 : z0;
		}

		public float max(int axis) {
			return axis == 0 ? x1 : axis == 1 ? y1 : z1;
		}

		Piece mirrored() {
			String mirroredOwner = owner == null ? null : owner.startsWith("left:") ? "right:" + owner.substring(5)
					: owner.startsWith("right:") ? "left:" + owner.substring(6) : owner;
			return new Piece(kind, -x1, y0, z0, -x0, y1, z1, jaw, texels, name, mirroredOwner);
		}

		/**
		 * Which side of the body a trunk or head piece is on, from where it lies: the body's right is -x. Empty for one on
		 * the middle line (the heart is "the heart", a lung is "the left lung").
		 */
		public String side() {
			if (owner != null) {
				return owner.startsWith("left:") ? "left" : owner.startsWith("right:") ? "right" : "";
			}
			float mid = (x0 + x1) * 0.5F;
			return mid < -0.4F ? "right" : mid > 0.4F ? "left" : "";
		}
	}

	private static final Piece[] HEAD = head();
	private static final Piece[] TORSO = torso();
	private static final Piece[] RIGHT_ARM = arm();
	private static final Piece[] LEFT_ARM = mirror(RIGHT_ARM);
	private static final Piece[] RIGHT_LEG = leg();
	private static final Piece[] LEFT_LEG = mirror(RIGHT_LEG);
	private static final Piece[] RUMINANT_HEAD = ruminantHead();
	private static final Piece[] SHEEP_HEAD = SheepHead.pieces();
	/** A lamb's head: milk teeth, and its thymus up the throat. */
	private static final Piece[] LAMB_HEAD = SheepHead.lamb();
	private static final Piece[] PIG_HEAD = pigHead();
	private static final Piece[] RUMINANT_TRUNK = SheepTrunk.pieces();
	private static final Piece[] COW_TRUNK = CowTrunk.pieces();
	private static final Piece[] COW_COMPACT = CowTrunk.compact();
	private static final Piece[] COW_TRUNK_TURNED = turn(COW_TRUNK);
	private static final Piece[] COW_COMPACT_TURNED = turn(COW_COMPACT);
	private static final Piece[] PIG_TRUNK = pigTrunk();
	private static final Piece[] RUMINANT_TRUNK_TURNED = turn(RUMINANT_TRUNK);
	/** A ram's trunk: the ewe's with his own reproductive organs (see {@link #male}). */
	private static final Piece[] RAM_TRUNK = SheepTrunk.ram();
	private static final Piece[] RAM_TRUNK_TURNED = turn(RAM_TRUNK);
	/** A lamb's trunk (the vanilla baby sheep), ewe and ram: a small rumen, a big abomasum and thymus, an immature tract. */
	private static final Piece[] EWE_LAMB_TRUNK = SheepTrunk.eweLamb();
	private static final Piece[] EWE_LAMB_TRUNK_TURNED = turn(EWE_LAMB_TRUNK);
	private static final Piece[] RAM_LAMB_TRUNK = SheepTrunk.ramLamb();
	private static final Piece[] RAM_LAMB_TRUNK_TURNED = turn(RAM_LAMB_TRUNK);
	private static final Piece[] PIG_TRUNK_TURNED = turn(PIG_TRUNK);
	private static final Piece[] RUMINANT_COARSE = SheepTrunk.coarse();
	private static final Piece[] RUMINANT_FINE = SheepTrunk.fine();
	private static final Piece[] RUMINANT_HALF = SheepTrunk.standard();
	private static final Piece[][] RUMINANT_DETAIL = {RUMINANT_COARSE, RUMINANT_HALF, RUMINANT_FINE, RUMINANT_TRUNK};
	private static final Piece[][] RUMINANT_DETAIL_TURNED = {turn(RUMINANT_COARSE), turn(RUMINANT_HALF), turn(RUMINANT_FINE), RUMINANT_TRUNK_TURNED};
	private static final Piece[] RIGHT_FORELEG = beastLeg(true);
	private static final Piece[] LEFT_FORELEG = mirror(RIGHT_FORELEG);
	private static final Piece[] RIGHT_HINDLEG = beastLeg(false);
	private static final Piece[] LEFT_HINDLEG = mirror(RIGHT_HINDLEG);
	// A sheep's legs are generated (SheepLegs): its own bones, muscles, tendons, skin and claws.
	private static final Piece[] SHEEP_RIGHT_FORELEG = SheepLegs.fore();
	private static final Piece[] SHEEP_LEFT_FORELEG = mirror(SHEEP_RIGHT_FORELEG);
	private static final Piece[] SHEEP_RIGHT_HINDLEG = SheepLegs.hind();
	private static final Piece[] SHEEP_LEFT_HINDLEG = mirror(SHEEP_RIGHT_HINDLEG);

	private Anatomy() {
	}

	private static final Map<Piece[], Joined> OBJECTS = Collections.synchronizedMap(new IdentityHashMap<>());

	/** A table's objects and, for each, the cubes it is made of (null for an object of one cube). */
	private record Joined(Piece[] objects, @Nullable Shape[] shapes, java.util.Set<String> paired) {
	}

	/**
	 * True if the objects of {@code pieces} called {@code name} come in a right and a left (the lungs, the kidneys, the
	 * eyes): only then is one of them "the right lung". An organ there is one of (the liver, the heart) has no side, wherever
	 * in the body it lies.
	 */
	public static boolean paired(Piece[] pieces, String name) {
		return OBJECTS.computeIfAbsent(pieces, Anatomy::join).paired().contains(name);
	}

	/**
	 * The anatomical objects the pieces make: one organ is often several cubes (the generated ruminant liver is a dozen,
	 * with its gallbladder and veins), but it is one thing until something breaks it. Pieces with the same name that touch
	 * are one object, as their joint box, of the tissue that fills most of it: the liver, each lung, each rib with its
	 * cartilage. Cached per table.
	 */
	public static Piece[] objects(Piece[] pieces) {
		return OBJECTS.computeIfAbsent(pieces, Anatomy::join).objects();
	}

	/**
	 * What each of {@link #objects}'s objects looks like, in the same order: the cubes it is made of, or null for one that
	 * is a single cube (its box is all there is to it). Cached per table and shared by every piece thrown of it.
	 */
	public static @Nullable Shape[] shapes(Piece[] pieces) {
		return OBJECTS.computeIfAbsent(pieces, Anatomy::join).shapes();
	}

	/**
	 * The cubes one anatomical object is made of (see {@link #objects}), for drawing it whole once it is out of the body: a
	 * liver's lobes, gallbladder and veins, a lung's lobes, each of its own tissue and where it lies in the object. A thrown
	 * organ moves and lands as its joint box; this is only what it looks like. Each cube is a box in fractions of the joint
	 * box's half sides (-1 to 1 on every axis), with the faces that show (a face lying flat on a neighbour is inside the
	 * organ) and their texel windows on anatomy.png, the same windows the body draws them with (see MeltRenderer).
	 * Immutable: the mirror images are made once and kept ({@link #mirrored}).
	 */
	public static final class Shape {
		/** Maximum for a carried/cut organ within the existing evidence record budget. */
		public static final int INTERACTIVE_CUBES = 56;
		/** Most cubes a shape keeps: an object of more is thrown as its box. */
		public static final int MOST = 96;
		private final Kind[] kinds;
		/** Six per cube: x0, y0, z0, x1, y1, z1 as fractions of the half sides. */
		private final float[] box;
		/** Per cube, a bit per face that shows: bit {@code axis * 2 + (max side ? 1 : 0)}. */
		private final byte[] faces;
		/** Per cube and face, four floats: u0, v0, u1, v1 on the atlas; and whether v runs along the face's first axis. */
		private final float[] uv;
		private final boolean[] vAlongB;
		private final Shape[] mirrors;

		private Shape(Kind[] kinds, float[] box, byte[] faces, float[] uv, boolean[] vAlongB, @Nullable Shape plain) {
			this.kinds = kinds;
			this.box = box;
			this.faces = faces;
			this.uv = uv;
			this.vAlongB = vAlongB;
			this.mirrors = plain != null ? plain.mirrors : new Shape[8];
			if (plain == null) {
				mirrors[0] = this;
			}
		}

		public int cubes() {
			return kinds.length;
		}

		public Kind kind(int cube) {
			return kinds[cube];
		}

		/** Side {@code axis} of {@code cube}, low ({@code max} false) or high, as a fraction of the object's half side. */
		public float side(int cube, int axis, boolean max) {
			return box[cube * 6 + (max ? 3 : 0) + axis];
		}

		/** True if face {@code axis}, low or high side, of {@code cube} can be seen from outside the object. */
		public boolean shows(int cube, int axis, boolean max) {
			return (faces[cube] >> (axis * 2 + (max ? 1 : 0)) & 1) != 0;
		}

		/** Texel window {@code k} (u0, v0, u1, v1) of that face on the atlas. */
		public float uv(int cube, int axis, boolean max, int k) {
			return uv[(cube * 6 + axis * 2 + (max ? 1 : 0)) * 4 + k];
		}

		/** True if v runs along the face's first axis ({@code (axis + 1) % 3}) rather than its second. */
		public boolean vAlongB(int cube, int axis, boolean max) {
			return vAlongB[cube * 6 + axis * 2 + (max ? 1 : 0)];
		}

		/**
		 * This shape turned inside out along the axes set in {@code mask} (bit 0 x, 1 y, 2 z): what it looks like where the
		 * body's frame runs the other way. Made once per mask.
		 */
		public Shape mirrored(int mask) {
			mask &= 7;
			Shape made = mirrors[mask];
			if (made != null) {
				return made;
			}

			Shape plain = mirrors[0];
			int n = plain.kinds.length;
			float[] box = plain.box.clone();
			byte[] faces = plain.faces.clone();
			float[] uv = new float[plain.uv.length];
			boolean[] along = new boolean[plain.vAlongB.length];
			for (int c = 0; c < n; c++) {
				int flags = 0;
				for (int a = 0; a < 3; a++) {
					boolean flip = (mask >> a & 1) != 0;
					if (flip) {
						box[c * 6 + a] = -plain.box[c * 6 + 3 + a];
						box[c * 6 + 3 + a] = -plain.box[c * 6 + a];
					}

					for (int s = 0; s < 2; s++) {
						// A face on the low side turns to the high side, with its own texels.
						int from = c * 6 + a * 2 + (flip ? 1 - s : s);
						int to = c * 6 + a * 2 + s;
						System.arraycopy(plain.uv, from * 4, uv, to * 4, 4);
						along[to] = plain.vAlongB[from];
						flags |= (plain.faces[c] >> (from - c * 6) & 1) << (to - c * 6);
					}
				}

				faces[c] = (byte) flags;
			}

			made = new Shape(plain.kinds, box, faces, uv, along, plain);
			mirrors[mask] = made;
			return made;
		}

		/** The shape of {@code group} (indices into {@code table}) inside the box {@code lo}..{@code hi}. */
		static Shape of(Piece[] table, List<Integer> group, float[] lo, float[] hi) {
			int n = group.size();
			Kind[] kinds = new Kind[n];
			float[] box = new float[n * 6];
			byte[] faces = new byte[n];
			float[] uv = new float[n * 24];
			boolean[] along = new boolean[n * 6];
			for (int c = 0; c < n; c++) {
				int index = group.get(c);
				Piece p = table[index];
				kinds[c] = p.kind();
				for (int a = 0; a < 3; a++) {
					float mid = (lo[a] + hi[a]) * 0.5F;
					float half = Math.max((hi[a] - lo[a]) * 0.5F, 1.0E-4F);
					box[c * 6 + a] = (p.min(a) - mid) / half;
					box[c * 6 + 3 + a] = (p.max(a) - mid) / half;
				}

				int flags = 0;
				for (int a = 0; a < 3; a++) {
					for (int s = 0; s < 2; s++) {
						if (!covered(table, group, index, a, s == 1)) {
							flags |= 1 << (a * 2 + s);
						}

						int face = a * 2 + s;
						int b = (a + 1) % 3;
						int cc = (a + 2) % 3;
						boolean vAlongB = b == 1;
						float spanU = Math.min(((vAlongB ? p.max(cc) - p.min(cc) : p.max(b) - p.min(b))) * p.texels(), 16.0F);
						float spanV = Math.min(((vAlongB ? p.max(b) - p.min(b) : p.max(cc) - p.min(cc))) * p.texels(), 16.0F);
						float[] window = MeltRenderer.window(p.kind().tile, index, face, spanU, spanV, p.texels());
						System.arraycopy(window, 0, uv, (c * 6 + face) * 4, 4);
						along[c * 6 + face] = vAlongB;
					}
				}

				faces[c] = (byte) flags;
			}

			return new Shape(kinds, box, faces, uv, along, null);
		}

		/** True if face {@code axis}/{@code max} of piece {@code index} lies flat against another piece of the group that covers it. */
		private static boolean covered(Piece[] table, List<Integer> group, int index, int axis, boolean max) {
			Piece p = table[index];
			float plane = max ? p.max(axis) : p.min(axis);
			int b = (axis + 1) % 3;
			int c = (axis + 2) % 3;
			for (int other : group) {
				Piece q = table[other];
				if (other == index || Math.abs((max ? q.min(axis) : q.max(axis)) - plane) > 1.0E-3F) {
					continue;
				}

				if (q.min(b) <= p.min(b) + 1.0E-3F && q.max(b) >= p.max(b) - 1.0E-3F && q.min(c) <= p.min(c) + 1.0E-3F && q.max(c) >= p.max(c) - 1.0E-3F) {
					return true;
				}
			}

			return false;
		}
	}

	private static Joined join(Piece[] pieces) {
		int n = pieces.length;
		int[] root = new int[n];
		for (int i = 0; i < n; i++) {
			root[i] = i;
		}

		for (int i = 0; i < n; i++) {
			for (int j = i + 1; j < n; j++) {
				boolean explicit = pieces[i].owner() != null && pieces[i].owner().equals(pieces[j].owner());
				if (explicit || pieces[i].owner() == null && pieces[j].owner() == null
						&& pieces[i].name().equals(pieces[j].name()) && touch(pieces[i], pieces[j])) {
					root[find(root, i)] = find(root, j);
				}
			}
		}

		Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			groups.computeIfAbsent(find(root, i), k -> new ArrayList<>()).add(i);
		}

		List<List<Integer>> joined = new ArrayList<>(groups.values());
		if (pieces == RUMINANT_TRUNK || pieces == RUMINANT_TRUNK_TURNED || pieces == RAM_TRUNK || pieces == RAM_TRUNK_TURNED || pieces == EWE_LAMB_TRUNK
				|| pieces == EWE_LAMB_TRUNK_TURNED || pieces == RAM_LAMB_TRUNK || pieces == RAM_LAMB_TRUNK_TURNED || pieces == PIG_TRUNK || pieces == PIG_TRUNK_TURNED) {
			// A beast's lungs have an accessory lobe (a person's do not).
			attachFragments(pieces, joined);
		}

		List<Piece> out = new ArrayList<>();
		List<@Nullable Shape> shapes = new ArrayList<>();
		java.util.Set<String> left = new java.util.HashSet<>();
		java.util.Set<String> right = new java.util.HashSet<>();
		for (List<Integer> group : joined) {
			float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
			float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			Map<Kind, Float> volume = new EnumMap<>(Kind.class);
			for (int index : group) {
				Piece p = pieces[index];
				for (int a = 0; a < 3; a++) {
					lo[a] = Math.min(lo[a], p.min(a));
					hi[a] = Math.max(hi[a], p.max(a));
				}

				volume.merge(p.kind(), (p.x1() - p.x0()) * (p.y1() - p.y0()) * (p.z1() - p.z0()), Float::sum);
			}

			Kind kind = volume.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
			Piece first = pieces[group.get(0)];
			Piece object = new Piece(kind, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], first.jaw(), first.texels(), first.name(), first.owner());
			out.add(object);
			Shape shape = null;
			if ((pieces == COW_TRUNK || pieces == COW_TRUNK_TURNED) && first.owner() != null && group.size() > Shape.INTERACTIVE_CUBES) {
				// Coarser authored geometry for large organs, without increasing network or cut-complexity limits.
				Piece[] compact = pieces == COW_TRUNK ? COW_COMPACT : COW_COMPACT_TURNED;
				List<Piece> clipped = new ArrayList<>();
				for (Piece p : compact) {
					if (!first.owner().equals(p.owner())) {
						continue;
					}
					float x0 = Math.max(lo[0], p.x0()), y0 = Math.max(lo[1], p.y0()), z0 = Math.max(lo[2], p.z0());
					float x1 = Math.min(hi[0], p.x1()), y1 = Math.min(hi[1], p.y1()), z1 = Math.min(hi[2], p.z1());
					if (x1 > x0 && y1 > y0 && z1 > z0) {
						clipped.add(new Piece(p.kind(), x0, y0, z0, x1, y1, z1, p.jaw(), p.texels(), p.name(), p.owner()));
					}
				}
				// The coarse grid may miss a small tissue entirely (notably the gallbladder).
				// Retain those fine cubes instead of silently deleting a defining anatomical feature.
				java.util.Set<Kind> present = new java.util.HashSet<>();
				for (Piece p : clipped) present.add(p.kind());
				for (int index : group) {
					Piece p = pieces[index];
					if (!present.contains(p.kind())) clipped.add(p);
				}
				if (!clipped.isEmpty() && clipped.size() <= Shape.MOST) {
					Piece[] table = clipped.toArray(Piece[]::new);
					List<Integer> indices = new ArrayList<>();
					for (int i = 0; i < table.length; i++) indices.add(i);
					shape = Shape.of(table, indices, lo, hi);
				}
			}
			if (shape == null && group.size() > 1 && group.size() <= Shape.MOST) {
				shape = Shape.of(pieces, group, lo, hi);
			}
			shapes.add(shape);
			(object.side().equals("left") ? left : object.side().equals("right") ? right : new java.util.HashSet<String>()).add(object.name());
		}

		left.retainAll(right);
		return new Joined(out.toArray(Piece[]::new), shapes.toArray(Shape[]::new), java.util.Set.copyOf(left));
	}

	/**
	 * Which side's organ owns a lobe of it that lies on the midline, by anatomy: a ruminant's (and a pig's) lungs have an
	 * accessory lobe, tucked into the mediastinum behind the heart, and it is a lobe of the right lung (its own bronchus is
	 * off the right). The generated trunk names every lobe only "lung", and its cut to half pixels leaves the accessory lobe
	 * a hair apart from the rest of the right lung, so this says whose it is. Name -> the side of its owner and the tissue a
	 * piece must be to be that lobe.
	 */
	private record Owner(String side, Kind tissue) {
	}

	private static final Map<String, Owner> MIDLINE_OWNERS = Map.of("lung", new Owner("right", Kind.LUNG));
	/**
	 * How far a midline lobe may lie from the organ that owns it, in pixels: a guard, not a rule (the owner is
	 * {@link #MIDLINE_OWNERS}'), so a lobe is never joined to an organ across the body or to one it does not touch at all.
	 */
	private static final float FRAGMENT_GAP = 1.0F;

	/**
	 * Gives each midline lobe its owner (see {@link #MIDLINE_OWNERS}): a group of one name lying on the midline, every
	 * piece of it that owner's tissue, joins the one group of that name on the owner's side, if that group lies within
	 * {@link #FRAGMENT_GAP} of it and is bigger than it. Never the other side's (the left lung stays the left lung), never a
	 * group of another name or tissue (the heart, the ribs' cartilage), and nothing at all where the owner is not one and
	 * near: such a lobe stays an object of its own, with no side.
	 */
	private static void attachFragments(Piece[] pieces, List<List<Integer>> groups) {
		for (List<Integer> lobe : groups) {
			if (lobe.isEmpty() || !side(pieces, lobe).isEmpty()) {
				continue;
			}

			String name = pieces[lobe.get(0)].name();
			Owner owner = MIDLINE_OWNERS.get(name);
			if (owner == null || !lobe.stream().allMatch(k -> pieces[k].kind() == owner.tissue() && pieces[k].name().equals(name))) {
				continue;
			}

			List<Integer> found = null;
			int candidates = 0;
			for (List<Integer> other : groups) {
				if (other != lobe && !other.isEmpty() && pieces[other.get(0)].name().equals(name) && side(pieces, other).equals(owner.side())) {
					candidates++;
					found = other;
				}
			}

			if (candidates == 1 && found.size() > lobe.size() && gap(pieces, lobe, found) <= FRAGMENT_GAP) {
				found.addAll(lobe);
				lobe.clear();
			}
		}

		groups.removeIf(List::isEmpty);
	}

	/** The side of the joint box of {@code group}, as {@link Piece#side}. */
	private static String side(Piece[] pieces, List<Integer> group) {
		float lo = Float.MAX_VALUE;
		float hi = -Float.MAX_VALUE;
		for (int i : group) {
			lo = Math.min(lo, pieces[i].x0());
			hi = Math.max(hi, pieces[i].x1());
		}

		float mid = (lo + hi) * 0.5F;
		return mid < -0.4F ? "right" : mid > 0.4F ? "left" : "";
	}

	/** The smallest gap between a piece of {@code a} and one of {@code b}, in pixels (0 for touching). */
	private static float gap(Piece[] pieces, List<Integer> a, List<Integer> b) {
		float best = Float.MAX_VALUE;
		for (int i : a) {
			for (int j : b) {
				float d = 0.0F;
				for (int axis = 0; axis < 3; axis++) {
					d = Math.max(d, Math.max(pieces[j].min(axis) - pieces[i].max(axis), pieces[i].min(axis) - pieces[j].max(axis)));
				}

				best = Math.min(best, d);
			}
		}

		return best;
	}

	private static int find(int[] root, int i) {
		while (root[i] != i) {
			root[i] = root[root[i]];
			i = root[i];
		}

		return i;
	}

	/** Two pieces overlap or share a face (within a hair). */
	private static boolean touch(Piece a, Piece b) {
		for (int i = 0; i < 3; i++) {
			if (a.max(i) < b.min(i) - 0.01F || b.max(i) < a.min(i) - 0.01F) {
				return false;
			}
		}

		return true;
	}

	/** The pieces of a region, right or left. */
	public static Piece[] pieces(Region region, boolean left) {
		return pieces(region, left, Beast.SHEEP, false);
	}

	/**
	 * The pieces of a region, right or left, of {@code beast} for its regions (a person's ignore it), {@code turned} for
	 * a grown beast's trunk, whose cube the model lays along its own y (see {@link Region#min(boolean, boolean)}).
	 */
	public static Piece[] pieces(Region region, boolean left, Beast beast, boolean turned) {
		return switch (region) {
			case HEAD -> HEAD;
			case TORSO -> TORSO;
			case ARM -> left ? LEFT_ARM : RIGHT_ARM;
			case LEG -> left ? LEFT_LEG : RIGHT_LEG;
			case BEAST_HEAD -> beast == Beast.SHEEP ? SHEEP_HEAD : beast.ruminant ? RUMINANT_HEAD : PIG_HEAD;
			case BEAST_TRUNK -> beast == Beast.COW ? turned ? COW_TRUNK_TURNED : COW_TRUNK
					: beast.ruminant ? turned ? RUMINANT_TRUNK_TURNED : RUMINANT_TRUNK : turned ? PIG_TRUNK_TURNED : PIG_TRUNK;
			case FORELEG -> beast == Beast.SHEEP ? left ? SHEEP_LEFT_FORELEG : SHEEP_RIGHT_FORELEG : left ? LEFT_FORELEG : RIGHT_FORELEG;
			case HINDLEG -> beast == Beast.SHEEP ? left ? SHEEP_LEFT_HINDLEG : SHEEP_RIGHT_HINDLEG : left ? LEFT_HINDLEG : RIGHT_HINDLEG;
		};
	}

	/**
	 * As {@link #pieces(Region, boolean, Beast, boolean)}, drawn at {@code detail}: a sheep's trunk at that grain, anything
	 * else as it always is. For drawing only (see {@link Detail}).
	 */
	public static Piece[] pieces(Region region, boolean left, Beast beast, boolean turned, Detail detail) {
		return pieces(region, left, beast, turned, detail, false);
	}

	/**
	 * As {@link #pieces(Region, boolean, Beast, boolean, Detail)}, of a male if {@code male}: a ram's trunk holds his own
	 * reproductive organs. The other grains (the {@code resolution} demo) and the other beasts are a ewe's and their own.
	 */
	public static Piece[] pieces(Region region, boolean left, Beast beast, boolean turned, Detail detail, boolean male) {
		return pieces(region, left, beast, turned, detail, male, false);
	}

	/**
	 * As {@link #pieces(Region, boolean, Beast, boolean, Detail, boolean)}, of a lamb if {@code young} (the vanilla baby
	 * sheep): its milk teeth, small rumen, big abomasum and thymus, and an immature tract.
	 */
	public static Piece[] pieces(Region region, boolean left, Beast beast, boolean turned, Detail detail, boolean male, boolean young) {
		if (beast == Beast.SHEEP && young && region == Region.BEAST_HEAD) {
			return LAMB_HEAD;
		}

		if (region != Region.BEAST_TRUNK || beast != Beast.SHEEP) {
			return pieces(region, left, beast, turned);
		}

		if (young) {
			return male ? turned ? RAM_LAMB_TRUNK_TURNED : RAM_LAMB_TRUNK : turned ? EWE_LAMB_TRUNK_TURNED : EWE_LAMB_TRUNK;
		}

		if (detail == Detail.MIXED) {
			return male ? turned ? RAM_TRUNK_TURNED : RAM_TRUNK : pieces(region, left, beast, turned);
		}

		return (turned ? RUMINANT_DETAIL_TURNED : RUMINANT_DETAIL)[detail.ordinal()];
	}

	/**
	 * Whether the animal with this UUID is male, half of them: fixed by the UUID, which every screen knows, so all of them
	 * open the same ram or ewe. Its own bits of the UUID, apart from the ones that seed a body's dice.
	 */
	public static boolean male(java.util.UUID id) {
		long h = id.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ id.getLeastSignificantBits();
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		return (h >>> 40 & 1L) == 1L;
	}

	/**
	 * A cow's head (a sheep's is generated, {@link SheepHead}). The long face of a grazer: the braincase small and far back, the bones of the nose long;
	 * the eyes in their bony rims on the sides of the head, to watch all round while it grazes; the cheek teeth in long
	 * rows; no upper front teeth, only the hard dental pad the lower ones bite against; the deep jaw hinged far back.
	 */
	private static Piece[] ruminantHead() {
		Builder b = new Builder();
		// The braincase behind the eyes, the poll at the back; the long nasal bones and the dark nostrils in front.
		b.label("cranium");
		b.add(Kind.SKULL, -3.25F, -3.5F, -3.25F, 3.25F, -0.75F, -0.25F);
		b.label("nasal_bone");
		b.add(Kind.SKULL, -2.5F, -3.25F, -7.5F, 2.5F, -1.75F, -3.25F);
		b.label("nasal_cavity");
		b.add(Kind.SOCKET, -1.5F, -1.75F, -7.75F, 1.5F, -0.75F, -7.25F);
		b.label("upper_jaw");
		b.pair(Kind.SKULL, -2.5F, -1.75F, -7.5F, -1.75F, -0.25F, -3.25F);
		// The orbits, rimmed with bone, each with its eye looking out sideways; the arch of the cheekbone under them.
		b.label("eye_socket");
		b.pair(Kind.SKULL, -3.5F, -3.0F, -5.25F, -2.5F, -0.75F, -3.25F);
		b.pair(Kind.SOCKET, -3.6F, -2.4F, -4.9F, -3.5F, -1.35F, -3.6F);
		b.label(null);
		b.pair(Kind.EYE, -3.75F, -2.3F, -4.8F, -3.45F, -1.4F, -3.7F, false, 4.0F);
		b.label("cheekbone");
		b.pair(Kind.SKULL, -3.25F, -0.75F, -5.25F, -2.5F, -0.25F, -2.0F);
		// The upper cheek teeth, and in front the dental pad, gum hard as horn.
		b.label("upper_molars");
		b.pair(Kind.TEETH, -2.25F, -0.25F, -6.0F, -1.5F, 0.25F, -3.0F, false, 2.0F);
		b.label("dental_pad");
		b.add(Kind.DERMIS, -1.5F, -0.25F, -7.5F, 1.5F, 0.25F, -6.5F);
		b.label("atlas");
		b.add(Kind.VERTEBRA, -1.0F, -1.5F, -0.75F, 1.0F, 0.0F, 0.0F, false, 2.0F);
		b.label("jawbone");
		// The jaw: the branches up to the hinge, the long body of it, the chin; the lower cheek teeth, the incisors that
		// bite on the pad, the tongue.
		b.pair(Kind.SKULL, -3.0F, -0.25F, -2.5F, -2.25F, 2.5F, -0.75F, true, 1.0F);
		b.pair(Kind.SKULL, -2.25F, 1.5F, -7.25F, -1.5F, 2.5F, -2.5F, true, 1.0F);
		b.add(Kind.SKULL, -1.5F, 1.75F, -7.75F, 1.5F, 2.5F, -6.5F, true, 1.0F);
		b.label("lower_molars");
		b.pair(Kind.TEETH, -2.25F, 0.25F, -6.0F, -1.5F, 1.5F, -3.0F, true, 2.0F);
		b.label("incisors");
		b.add(Kind.TEETH, -1.25F, 0.25F, -7.75F, 1.25F, 1.75F, -7.25F, true, 2.0F);
		b.label(null);
		b.add(Kind.TONGUE, -1.25F, 0.5F, -7.0F, 1.25F, 1.75F, -2.25F, true, 1.0F);
		return b.build();
	}

	/**
	 * A pig's head: the long, straight skull of a rooter ending in the disc of the snout (its own block of the model,
	 * gristle under skin), the small eyes set further forward, upper and lower incisors and the tusks, the canines,
	 * curving up out of both jaws.
	 */
	private static Piece[] pigHead() {
		Builder b = new Builder();
		b.label("cranium");
		b.add(Kind.SKULL, -3.25F, -3.5F, -3.5F, 3.25F, -1.0F, -0.25F);
		b.label("snout_bones");
		b.add(Kind.SKULL, -2.25F, -2.75F, -7.75F, 2.25F, -1.0F, -3.5F);
		b.label("nasal_cavity");
		b.add(Kind.SOCKET, -1.0F, -1.0F, -7.9F, 1.0F, -0.25F, -7.5F);
		b.label("eye_socket");
		b.pair(Kind.SKULL, -3.5F, -3.25F, -6.0F, -2.25F, -1.0F, -3.5F);
		b.pair(Kind.SOCKET, -3.6F, -2.75F, -5.5F, -3.5F, -1.75F, -4.5F);
		b.label(null);
		b.pair(Kind.EYE, -3.75F, -2.65F, -5.4F, -3.45F, -1.85F, -4.6F, false, 4.0F);
		b.label("cheekbone");
		b.pair(Kind.SKULL, -3.25F, -1.0F, -6.0F, -2.25F, -0.25F, -2.5F);
		b.label("incisors");
		b.add(Kind.TEETH, -1.25F, -0.5F, -7.9F, 1.25F, 0.0F, -7.5F, false, 2.0F);
		b.label("tusk");
		b.pair(Kind.TEETH, -2.5F, -1.0F, -7.0F, -2.0F, 0.75F, -6.5F, false, 2.0F);
		b.label("upper_molars");
		b.pair(Kind.TEETH, -2.0F, -0.25F, -6.25F, -1.25F, 0.25F, -3.0F, false, 2.0F);
		b.label("atlas");
		b.add(Kind.VERTEBRA, -1.0F, -1.5F, -0.75F, 1.0F, 0.0F, 0.0F, false, 2.0F);
		b.label("jawbone");
		b.pair(Kind.SKULL, -3.0F, -0.25F, -3.0F, -2.25F, 3.0F, -1.0F, true, 1.0F);
		b.pair(Kind.SKULL, -2.0F, 2.0F, -7.5F, -1.25F, 3.0F, -3.0F, true, 1.0F);
		b.add(Kind.SKULL, -1.25F, 2.0F, -7.9F, 1.25F, 3.0F, -7.0F, true, 1.0F);
		b.label("tusk");
		b.pair(Kind.TEETH, -2.25F, -0.25F, -7.25F, -1.75F, 1.5F, -6.75F, true, 2.0F);
		b.label("incisors");
		b.add(Kind.TEETH, -1.25F, 0.5F, -7.9F, 1.25F, 1.25F, -7.25F, true, 2.0F);
		b.label("lower_molars");
		b.pair(Kind.TEETH, -2.0F, 0.25F, -6.25F, -1.25F, 1.75F, -3.0F, true, 2.0F);
		b.label(null);
		b.add(Kind.TONGUE, -1.25F, 0.5F, -7.0F, 1.25F, 2.0F, -2.5F, true, 1.0F);
		return b.build();
	}

	/**
	 * A pig's trunk as it stands (a ruminant's is {@link SheepTrunk}, generated from a veterinary model). The spine along the top, the spines of the withers standing up over the shoulders
	 * and the wide wings of the loin vertebrae; the ribcage over the front half, four hoops of rib down to the
	 * breastbone along the brisket, the last floating; the shoulder blades flat on the sides of the chest; the pelvis at
	 * the rump, its sockets for the thigh bones and the pin bones behind. In the chest the lungs round the heart, which
	 * lies low between the forelegs, a little to the left; the diaphragm; then the belly: one big stomach on the left, a
	 * long spleen against it, the cone of the spiral colon behind and the small intestine to the right. The kidneys lie
	 * under the loin wrapped in leaf lard.
	 */
	private static Piece[] pigTrunk() {
		Builder b = new Builder();
		Kind fat = Kind.LARD;
		// The backbone: the chest, the spines of the withers, the loin and its wings, the sacrum, the root of the tail.
		b.label("thoracic_spine");
		b.add(Kind.VERTEBRA, -0.5F, -3.25F, -7.5F, 0.5F, -2.25F, 0.5F, false, 2.0F);
		b.label("withers");
		b.add(Kind.BONE, -0.25F, -3.6F, -7.0F, 0.25F, -3.25F, -1.5F);
		b.label("lumbar_spine");
		b.add(Kind.VERTEBRA, -0.75F, -3.25F, 0.5F, 0.75F, -2.25F, 5.0F, false, 2.0F);
		b.pair(Kind.BONE, -2.0F, -2.75F, 1.0F, -0.75F, -2.5F, 4.5F);
		b.label("sacrum");
		b.add(Kind.BONE, -1.0F, -3.25F, 5.0F, 1.0F, -2.5F, 7.0F);
		b.label("tail_vertebrae");
		b.add(Kind.VERTEBRA, -0.4F, -3.4F, 7.0F, 0.4F, -2.9F, 8.0F, false, 2.0F);
		b.label("rib");
		// The ribs, each sloping back as it goes down; all but the last meet the breastbone in cartilage.
		for (int k = 0; k < 4; k++) {
			float z = -7.0F + 2.0F * k;
			b.pair(Kind.BONE, -3.0F, -3.0F, z, -0.5F, -2.5F, z + 0.75F);
			b.pair(Kind.BONE, -3.75F, -2.5F, z + 0.25F, -3.0F, 1.5F, z + 1.0F);
			if (k < 3) {
				b.pair(Kind.BONE, -3.25F, 1.5F, z + 0.5F, -2.25F, 2.75F, z + 1.25F);
				b.pair(Kind.CARTILAGE, -2.25F, 2.75F, z + 0.5F, -0.75F, 3.25F, z + 1.25F);
			} else {
				b.pair(Kind.BONE, -3.25F, 1.5F, z + 0.5F, -2.5F, 2.25F, z + 1.25F);
			}
		}

		b.label("breastbone");
		b.add(Kind.BONE, -0.75F, 3.0F, -7.5F, 0.75F, 3.5F, -2.5F);
		b.label("xiphoid");
		b.add(Kind.CARTILAGE, -0.5F, 3.0F, -2.5F, 0.5F, 3.4F, -1.5F);
		// The shoulder blades and the shoulder joints under them.
		b.label("shoulder_blade");
		b.pair(Kind.BONE, -4.1F, -3.25F, -7.75F, -3.8F, 0.5F, -5.75F);
		b.label("shoulder_joint");
		b.pair(Kind.BONE_END, -4.1F, 0.5F, -7.75F, -3.4F, 1.5F, -6.5F);
		// The pelvis: the wings of the hip, the shafts down to the hip sockets, the pin bones, the floor of it.
		b.label("hip_bone");
		b.pair(Kind.BONE, -3.25F, -3.0F, 4.5F, -1.0F, -2.25F, 6.25F);
		b.pair(Kind.BONE, -3.0F, -2.25F, 6.0F, -2.25F, 0.25F, 7.0F);
		b.label("hip_socket");
		b.pair(Kind.BONE_END, -3.5F, -0.5F, 6.25F, -2.5F, 0.75F, 7.25F);
		b.label("pin_bone");
		b.pair(Kind.BONE, -2.25F, -0.25F, 7.0F, -1.0F, 0.75F, 7.75F);
		b.label("pubic_bone");
		b.add(Kind.BONE, -1.25F, 0.75F, 6.25F, 1.25F, 1.25F, 7.25F);
		b.label(null);
		// The windpipe into the chest; the lungs, the heart low and to the left, the aorta under the spine; the diaphragm.
		b.add(Kind.TRACHEA, -0.5F, -1.5F, -8.0F, 0.5F, -0.5F, -5.5F);
		b.add(Kind.LUNG, -2.75F, -2.0F, -7.0F, -1.0F, 1.25F, -1.5F);
		b.add(Kind.LUNG, -2.25F, 1.25F, -6.5F, -1.0F, 2.25F, -2.0F);
		b.add(Kind.LUNG, 1.25F, -2.0F, -7.0F, 2.75F, 1.25F, -1.5F);
		b.add(Kind.LUNG, 1.25F, 1.25F, -6.5F, 2.25F, 2.25F, -2.0F);
		b.add(Kind.LUNG, -1.0F, -2.0F, -7.0F, 1.25F, 0.0F, -1.5F);
		b.add(Kind.HEART, -1.0F, 0.0F, -6.5F, 1.25F, 2.75F, -3.5F);
		b.add(Kind.AORTA, 0.0F, -2.25F, -6.0F, 0.5F, -2.0F, 5.0F, false, 2.0F);
		b.label("diaphragm");
		b.add(Kind.MUSCLE, -3.25F, -2.0F, -1.5F, 3.25F, 3.0F, -1.0F);
		b.label(null);
		b.add(Kind.STOMACH, 0.0F, -1.5F, -1.0F, 3.0F, 1.75F, 1.75F);
		b.add(Kind.LIVER, -3.0F, -2.0F, -1.0F, 0.0F, 1.25F, 0.75F);
		b.add(Kind.SPLEEN, 3.0F, -1.0F, -0.5F, 3.6F, 2.5F, 2.0F);
		b.label("spiral_colon");
		b.add(Kind.COLON, 0.5F, -0.75F, 2.0F, 3.25F, 2.25F, 5.0F);
		b.label(null);
		b.add(Kind.INTESTINE, -3.0F, 0.0F, 1.0F, 0.5F, 3.25F, 5.5F);
		b.pair(Kind.KIDNEY, -2.0F, -2.25F, 1.75F, -0.75F, -1.25F, 3.25F);
		b.label("leaf_lard");
		b.pair(fat, -2.75F, -2.5F, 1.5F, -2.0F, -0.75F, 5.0F);
		b.label(null);

		b.add(Kind.BLADDER, -0.75F, -0.5F, 5.75F, 0.75F, 0.75F, 7.0F);
		return b.build();
	}

	/**
	 * A beast's right leg below the body, the body towards +x. A foreleg: the elbow (its point behind), the radius with
	 * the thin ulna fused behind it, the knee (the wrist bones), the cannon bone with the flexor tendons down the back of
	 * it, the fetlock, the two toes of a cloven foot each in its claw of hoof. A hind leg the same from the stifle, its
	 * kneecap in front, the shin bone, the point of the hock standing out behind on the Achilles tendon.
	 */
	private static Piece[] beastLeg(boolean fore) {
		Builder b = new Builder();
		if (fore) {
			b.label("humerus");
			b.add(Kind.BONE_END, -1.0F, 0.0F, -1.0F, 1.0F, 1.0F, 0.75F);
			b.label("point_of_elbow");
			b.add(Kind.BONE, -0.6F, 0.0F, 0.75F, 0.6F, 1.75F, 1.5F);
			b.label("radius");
			b.add(Kind.BONE, -0.75F, 1.0F, -0.9F, 0.75F, 5.25F, 0.4F);
			b.label("ulna");
			b.add(Kind.BONE, -0.4F, 1.75F, 0.4F, 0.4F, 4.5F, 0.9F);
			b.label("knee_carpus");
			b.add(Kind.BONE_END, -0.9F, 5.25F, -0.9F, 0.9F, 6.5F, 0.6F);
		} else {
			b.label("femur");
			b.add(Kind.BONE_END, -1.0F, 0.0F, -0.9F, 1.0F, 1.0F, 0.9F);
			b.label("kneecap");
			b.add(Kind.BONE, -0.5F, 0.1F, -1.4F, 0.5F, 1.0F, -0.9F);
			b.label("tibia");
			b.add(Kind.BONE, -0.75F, 1.0F, -0.75F, 0.75F, 5.25F, 0.6F);
			b.label("achilles_tendon");
			b.add(Kind.CARTILAGE, -0.35F, 1.5F, 0.9F, 0.35F, 4.5F, 1.4F);
			b.label("point_of_hock");
			b.add(Kind.BONE, -0.45F, 4.5F, 0.6F, 0.45F, 6.0F, 1.5F);
			b.label("hock");
			b.add(Kind.BONE_END, -0.9F, 5.25F, -0.8F, 0.9F, 6.5F, 0.6F);
		}

		b.label("cannon_bone");
		b.add(Kind.BONE, -0.5F, 6.5F, -0.6F, 0.5F, 10.0F, 0.4F);
		b.label("flexor_tendon");
		b.add(Kind.CARTILAGE, -0.35F, 6.75F, 0.4F, 0.35F, 9.75F, 0.9F);
		b.label("fetlock");
		b.add(Kind.BONE_END, -0.75F, 10.0F, -0.75F, 0.75F, 10.75F, 0.5F);
		b.label("sesamoid");
		b.add(Kind.BONE, -0.5F, 9.75F, 0.5F, 0.5F, 10.5F, 0.85F);
		b.label("pastern");
		b.pair(Kind.PHALANGES, -0.75F, 10.75F, -1.0F, -0.1F, 11.25F, 0.25F, false, 2.0F);
		b.label("hoof");
		b.pair(Kind.HOOF, -0.9F, 11.25F, -1.6F, -0.05F, 11.95F, 0.6F, false, 1.0F);
		return b.build();
	}

	/**
	 * How {@code region}'s canonical pixels map onto a real cube ({@code min}, {@code max}, its own pixels), per axis:
	 * {@code real = canonical * scale + offset}, as {scaleX, scaleY, scaleZ, offsetX, offsetY, offsetZ}.
	 */
	public static float[] fit(Region region, boolean left, boolean turned, float[] min, float[] max) {
		float[] c0 = region.min(left, turned);
		float[] c1 = region.max(left, turned);
		float[] out = new float[6];
		for (int i = 0; i < 3; i++) {
			out[i] = (max[i] - min[i]) / (c1[i] - c0[i]);
			out[3 + i] = min[i] - c0[i] * out[i];
		}

		return out;
	}

	/** A beast's trunk pieces in the frame of a grown one's cube, which the model turns a right angle about x. */
	private static Piece[] turn(Piece[] pieces) {
		Piece[] out = new Piece[pieces.length];
		for (int i = 0; i < pieces.length; i++) {
			Piece p = pieces[i];
			out[i] = new Piece(p.kind(), p.x0(), p.z0(), -p.y1(), p.x1(), p.z1(), -p.y0(), p.jaw(), p.texels(), p.name(), p.owner());
		}

		return out;
	}

	private static Piece[] head() {
		Builder b = new Builder();
		// Whole pixels, like the vanilla skeleton's head: the vault of the skull over the brows.
		b.label("cranium");
		b.add(Kind.SKULL, -3.5F, -7.5F, -3.5F, 3.5F, -4.0F, 3.5F);
		b.label("skull");
		// The row of the eyes, level with a skin's eyes: the bridge of the nose, the outer rims, each orbit a pixel deep
		// with its dark back and the eye in it; the skull behind them.
		b.add(Kind.SKULL, -1.0F, -4.0F, -3.5F, 1.0F, -3.0F, -2.5F);
		b.pair(Kind.SKULL, -3.5F, -4.0F, -3.5F, -3.0F, -3.0F, -2.5F);
		b.label("eye_socket");
		b.pair(Kind.SOCKET, -3.0F, -4.0F, -2.75F, -1.0F, -3.0F, -2.5F);
		b.label(null);
		b.pair(Kind.EYE, -2.75F, -3.9F, -3.1F, -1.25F, -3.1F, -2.75F, false, 4.0F);
		b.label("skull");
		b.add(Kind.SKULL, -3.5F, -4.0F, -2.5F, 3.5F, -3.0F, 3.5F);
		// The cheekbones either side of the dark hole of the nose.
		b.label("cheekbone");
		b.pair(Kind.SKULL, -3.5F, -3.0F, -3.5F, -0.5F, -2.0F, 3.0F);
		b.label("skull");
		b.add(Kind.SOCKET, -0.5F, -3.0F, -3.25F, 0.5F, -2.0F, -2.75F);
		b.add(Kind.SKULL, -0.5F, -3.0F, -2.75F, 0.5F, -2.0F, 3.0F);
		// The upper teeth in their arch, level with a skin's mouth; the upper jaw beside them; the base of the skull; the
		// back of the throat, seen once the jaw drops; the top of the neck.
		b.label("upper_teeth");
		b.add(Kind.TEETH, -2.0F, -2.0F, -3.4F, 2.0F, -1.5F, -2.9F, false, 2.0F);
		b.pair(Kind.TEETH, -2.0F, -2.0F, -2.9F, -1.5F, -1.5F, -1.5F, false, 2.0F);
		b.label("upper_jaw");
		b.pair(Kind.SKULL, -3.0F, -2.0F, -3.0F, -2.0F, -1.5F, -1.0F);
		b.label("skull");
		b.add(Kind.SKULL, -3.0F, -2.0F, 1.0F, 3.0F, -1.0F, 3.0F);
		b.label("throat");
		b.add(Kind.SOCKET, -2.0F, -1.5F, -1.25F, 2.0F, -1.0F, -1.0F);
		b.label("atlas");
		b.add(Kind.VERTEBRA, -0.5F, -1.0F, 0.25F, 0.5F, 0.0F, 1.25F, false, 2.0F);
		b.label("lower_teeth");
		// The jaw: the lower teeth in their arch, the chin, the two branches up to the hinge, the tongue in the arch.
		b.add(Kind.TEETH, -2.0F, -1.5F, -3.3F, 2.0F, -1.0F, -2.8F, true, 2.0F);
		b.pair(Kind.TEETH, -2.0F, -1.5F, -2.8F, -1.5F, -1.0F, -1.5F, true, 2.0F);
		b.label("jawbone");
		b.add(Kind.SKULL, -2.5F, -1.0F, -3.5F, 2.5F, -0.25F, -1.0F, true, 1.0F);
		b.pair(Kind.SKULL, -3.5F, -2.0F, -1.0F, -2.5F, -0.25F, 0.75F, true, 1.0F);
		b.label(null);
		b.add(Kind.TONGUE, -1.5F, -1.4F, -2.8F, 1.5F, -1.0F, -1.0F, true, 1.0F);
		return b.build();
	}

	/**
	 * The trunk. The ribcage fills the chest the way it does under thin muscle: four hoops a pixel thick and a pixel
	 * apart, the vanilla skeleton's ribs, each a pixel lower in front than behind, as ribs slope.
	 */
	private static Piece[] torso() {
		Builder b = new Builder();
		// The spine down the back, thicker in the small of the back; the sacrum and tailbone close the pelvis behind.
		b.label("thoracic_spine");
		b.add(Kind.VERTEBRA, -0.5F, 0.0F, 0.5F, 0.5F, 7.0F, 1.5F, false, 2.0F);
		b.label("lumbar_spine");
		b.add(Kind.VERTEBRA, -1.0F, 7.0F, 0.25F, 1.0F, 10.0F, 1.5F, false, 2.0F);
		b.label("sacrum");
		b.add(Kind.BONE, -1.0F, 10.0F, 0.5F, 1.0F, 11.5F, 1.5F);
		b.label("tailbone");
		b.add(Kind.BONE, -0.5F, 11.5F, 0.75F, 0.5F, 12.0F, 1.25F);
		// The collarbones out to the shoulders, the breastbone and its cartilage tip, the shoulder blades over the ribs.
		b.label("collarbone");
		b.pair(Kind.BONE, -3.5F, 0.0F, -1.5F, -0.5F, 1.0F, -1.0F);
		b.label("breastbone");
		b.add(Kind.BONE, -0.5F, 1.0F, -1.5F, 0.5F, 5.5F, -1.0F);
		b.label("xiphoid");
		b.add(Kind.CARTILAGE, -0.5F, 5.5F, -1.4F, 0.5F, 6.5F, -1.1F);
		b.label("shoulder_blade");
		b.pair(Kind.BONE, -3.5F, 1.0F, 1.1F, -1.5F, 4.0F, 1.5F);
		b.label("rib");
		for (int k = 0; k < 4; k++) {
			float back = 1.0F + 2.0F * k;
			float front = back + 1.0F;
			// Behind, along the back to the spine; down the side, a step lower halfway round.
			b.pair(Kind.BONE, -3.5F, back, 1.0F, -0.5F, back + 1.0F, 1.4F);
			b.pair(Kind.BONE, -3.5F, back, 0.0F, -2.5F, back + 1.0F, 1.0F);
			if (k < 3) {
				// In front, bone round to the cartilage that joins the breastbone.
				b.pair(Kind.BONE, -3.5F, front, -1.5F, -2.5F, front + 1.0F, 0.0F);
				b.pair(Kind.BONE, -2.5F, front, -1.5F, -1.5F, front + 1.0F, -1.0F);
				b.pair(Kind.CARTILAGE, -1.5F, front, -1.4F, -0.5F, front + 1.0F, -1.1F);
			} else {
				// The lowest ribs float, short of the front.
				b.pair(Kind.BONE, -3.5F, front, -1.0F, -2.5F, front + 1.0F, 0.0F);
			}
		}

		// The pelvis: the wings of the hip bones under the last ribs, the sockets the thigh bones sit in, the pubic bones
		// meeting in front.
		b.label("hip_bone");
		b.pair(Kind.BONE, -3.5F, 9.0F, -0.5F, -1.5F, 10.5F, 1.0F);
		b.label("hip_socket");
		b.pair(Kind.BONE_END, -3.0F, 10.5F, -0.75F, -1.5F, 11.5F, 0.75F);
		b.label("pubic_bone");
		b.add(Kind.BONE, -1.5F, 11.0F, -1.0F, 1.5F, 12.0F, -0.5F);
		b.label(null);
		// The windpipe; the lungs either side of the heart, which sits to the left in front of them. The left lung is the
		// smaller: the heart lies in a notch of it, with its upper lobe over the heart and a thin edge behind.
		b.add(Kind.TRACHEA, -0.5F, 0.0F, -1.0F, 0.5F, 2.5F, -0.5F);
		b.add(Kind.LUNG, -2.5F, 1.0F, -0.9F, -0.5F, 6.0F, 0.5F);
		b.add(Kind.LUNG, 1.5F, 1.0F, -0.9F, 2.5F, 6.0F, 0.5F);
		b.add(Kind.LUNG, 0.5F, 1.0F, -0.9F, 1.5F, 3.0F, 0.5F);
		b.add(Kind.LUNG, 0.5F, 3.0F, 0.2F, 1.5F, 6.0F, 0.5F);
		b.add(Kind.HEART, -0.5F, 3.0F, -1.1F, 1.5F, 5.5F, 0.2F);
		// The aorta, down the left of the spine behind the heart and lungs.
		b.add(Kind.AORTA, 0.5F, 2.0F, 0.5F, 1.0F, 7.0F, 1.0F, false, 2.0F);
		// Under the diaphragm: the liver on the right, the stomach and the spleen behind it on the left, the kidneys
		// against the back either side of the spine, the right one a little lower, under the liver.
		b.add(Kind.LIVER, -2.5F, 6.0F, -1.0F, 0.5F, 8.0F, 0.25F);
		b.add(Kind.STOMACH, 0.5F, 6.0F, -1.0F, 2.5F, 7.75F, 0.25F);
		b.add(Kind.SPLEEN, 1.5F, 6.0F, 0.25F, 2.5F, 7.5F, 1.0F);
		b.add(Kind.KIDNEY, -2.25F, 7.75F, 0.25F, -1.25F, 9.0F, 1.0F);
		b.add(Kind.KIDNEY, 1.25F, 7.5F, 0.25F, 2.25F, 9.0F, 1.0F);
		// The colon up the right side, across and down the left, framing the coils of the small intestine; the bladder.
		b.pair(Kind.COLON, -2.5F, 8.0F, -1.0F, -1.5F, 10.5F, 0.25F);
		b.add(Kind.COLON, -1.5F, 8.0F, -1.0F, 1.5F, 9.0F, 0.0F);
		b.add(Kind.INTESTINE, -1.5F, 9.0F, -1.0F, 1.5F, 10.75F, 0.25F);
		b.add(Kind.BLADDER, -1.0F, 10.75F, -0.5F, 1.0F, 11.75F, 0.25F);
		return b.build();
	}

	/**
	 * The right arm, hanging with the palm to the body and the thumb forward. The body is towards +x. Two pixels thick,
	 * like the vanilla skeleton's arm; the forearm's two bones a pixel each, the radius on the thumb side in front.
	 */
	private static Piece[] arm() {
		Builder b = new Builder();
		b.label("humerus");
		b.add(Kind.BONE_END, -2.0F, -1.5F, -1.0F, 0.0F, -0.5F, 1.0F);
		b.add(Kind.BONE, -2.0F, -0.5F, -1.0F, 0.0F, 3.5F, 1.0F);
		b.label("elbow");
		b.add(Kind.BONE_END, -2.0F, 3.5F, -1.0F, 0.0F, 4.5F, 1.0F);
		b.label("radius");
		b.add(Kind.BONE, -2.0F, 4.5F, -1.0F, -1.0F, 8.0F, 0.0F);
		b.label("ulna");
		b.add(Kind.BONE, -1.0F, 4.5F, 0.0F, 0.0F, 8.0F, 1.0F);
		b.label("wrist");
		b.add(Kind.BONE_END, -2.0F, 8.0F, -1.0F, 0.0F, 8.5F, 1.0F);
		b.label("hand_bones");
		b.add(Kind.PHALANGES, -2.0F, 8.5F, -1.0F, 0.0F, 9.75F, 1.0F, false, 2.0F);
		return b.build();
	}

	/**
	 * The right leg. The body is towards +x. The thigh bone two pixels thick with the kneecap on the knee; the shin bone
	 * in front, the thin fibula behind and outside it; the foot forward from the ankle, the heel behind.
	 */
	private static Piece[] leg() {
		Builder b = new Builder();
		b.label("femur");
		b.add(Kind.BONE_END, -1.0F, 0.0F, -1.0F, 1.0F, 1.0F, 1.0F);
		b.add(Kind.BONE, -1.0F, 1.0F, -1.0F, 1.0F, 5.5F, 1.0F);
		b.label("knee");
		b.add(Kind.BONE_END, -1.0F, 5.5F, -1.0F, 1.0F, 6.5F, 1.0F);
		b.label("kneecap");
		b.add(Kind.BONE, -0.5F, 5.5F, -1.25F, 0.5F, 6.5F, -1.0F);
		b.label("tibia");
		b.add(Kind.BONE, -1.0F, 6.5F, -1.0F, 1.0F, 10.5F, 0.0F);
		b.label("fibula");
		b.add(Kind.BONE, -1.0F, 6.5F, 0.0F, 0.0F, 10.5F, 1.0F);
		b.label("ankle");
		b.add(Kind.BONE_END, -1.0F, 10.5F, -1.0F, 1.0F, 11.0F, 1.0F);
		b.label("foot_bones");
		b.add(Kind.PHALANGES, -1.0F, 11.0F, -1.75F, 1.0F, 11.75F, 0.5F, false, 2.0F);
		b.label("heel_bone");
		b.add(Kind.BONE_END, -0.75F, 11.0F, 0.5F, 0.75F, 11.75F, 1.25F);
		return b.build();
	}

	private static Piece[] mirror(Piece[] pieces) {
		Piece[] out = new Piece[pieces.length];
		for (int i = 0; i < pieces.length; i++) {
			out[i] = pieces[i].mirrored();
		}

		return out;
	}

	private static final class Builder {
		private final List<Piece> pieces = new ArrayList<>();
		/** What the pieces added next are called (see {@link #label}); null: their tissue's own name. */
		private @Nullable String label;

		/** The anatomical name of the pieces added from here (a translation id: {@code fluidgore.organ.<name>}), until the next. */
		Builder label(@Nullable String name) {
			label = name;
			return this;
		}

		private String name(Kind kind) {
			return label != null ? label : kind.noun();
		}

		void add(Kind kind, float x0, float y0, float z0, float x1, float y1, float z1) {
			add(kind, x0, y0, z0, x1, y1, z1, false, 1.0F);
		}

		void add(Kind kind, float x0, float y0, float z0, float x1, float y1, float z1, boolean jaw, float texels) {
			pieces.add(new Piece(kind, x0, y0, z0, x1, y1, z1, jaw, texels, name(kind)));
		}

		/** A piece on the right (-x) and its mirror image on the left. */
		void pair(Kind kind, float x0, float y0, float z0, float x1, float y1, float z1) {
			pair(kind, x0, y0, z0, x1, y1, z1, false, 1.0F);
		}

		void pair(Kind kind, float x0, float y0, float z0, float x1, float y1, float z1, boolean jaw, float texels) {
			Piece right = new Piece(kind, x0, y0, z0, x1, y1, z1, jaw, texels, name(kind));
			pieces.add(right);
			pieces.add(right.mirrored());
		}

		Piece[] build() {
			return pieces.toArray(Piece[]::new);
		}
	}
}
