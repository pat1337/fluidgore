import java.util.Locale;

/**
 * The body plan every four-legged animal in the generators shares: the frame of the trunk (how wide, how deep, where the
 * spine and the breastbone run) and the axial skeleton (the neck, the thoracic and lumbar vertebrae, the sacrum and the
 * root of the tail, the ribs with their cartilages, the breastbone, the shoulder blades and the pelvis), with the
 * spinal cord in its canal. It is written once; a species gives only its {@link Body}: its measurements, its vertebral
 * formula and its tissues. The organs and the head stay with each species' generator.
 *
 * <p>Units are the species' own centimetres: +x the animal's right, +y up from the floor of the belly, -z towards the
 * head. A control point is {x, y, z, r} or {x, y, z, rx, ry} on the right side; the left side is its mirror.
 *
 * <p>Not a program on its own: the generators extend it and set {@link #BODY} before they build.
 */
class Quadruped extends AnatomyKit {
	/** The species being built. */
	static Body BODY;

	/** A species' measurements for the shared body plan. Every field is set by the species. */
	static final class Body {
		/** The height of the middle of the trunk, between the breastbone and the back. */
		double yc;
		/**
		 * Profiles along the body as (z, value) pairs for {@link #curve}: the trunk's half width at the ribs, the height
		 * of the breastbone, and the height of the middle of the vertebral bodies in the trunk and in the neck.
		 */
		double[] halfWidth, sternum, spine, neck;
		/** Tissues: the trunk's bones, the neck's, joint surfaces, cartilage, the discs between the vertebrae. */
		Mat bone, neckBone, joint, cartilage, disc;

		/** The thoracic vertebrae: z of the first one's centre and the step to the next. One per pair of ribs. */
		double thoracicZ, thoracicStep;
		/** The thoracic vertebral body: half its length and its radii (across, up); its disc's far end and radius. */
		double thoracicHalf, thoracicRx, thoracicRy, thoracicDiscEnd, thoracicDiscR;
		/** The height of each thoracic spinous process above its root: tall over the withers. Its length is the count. */
		double[] withers;
		/**
		 * A thoracic spinous process: {root above the body, root width, root thickness, rise from the body to the shaft,
		 * how far back it leans per cm of height, width and thickness at 0.6 of its height, width and thickness at the tip}.
		 */
		double[] thoracicSpinous;

		/** The lumbar vertebrae: z of the first one's centre and the step to the next. */
		double lumbarZ, lumbarStep;
		/** The lumbar vertebral body: half its length and its radii; its disc's far end and radius. */
		double lumbarHalf, lumbarRx, lumbarRy, lumbarDiscEnd, lumbarDiscR;
		/** A lumbar spinous process: the control points above the body's centre, as {0, dy, dz, rx, ry}. */
		double[][] lumbarSpinous;
		/** How far out each lumbar transverse process reaches from the midline. Its length is the count. */
		double[] transverse;
		/**
		 * A lumbar transverse process: {root x, root dy, root rx, root ry, mid dy, mid dz, mid rx, mid ry, tip dy, tip dz,
		 * tip rx, tip ry}; the middle lies at 0.55 of the reach.
		 */
		double[] transverseShape;

		/** The sacrum's control points, its median crest's, and a wing's triangle {x, y, z} x 3 with its half thickness. */
		double[][] sacrum, sacralCrest;
		double[] sacralWing;
		double sacralWingHalf;
		/** The tail vertebrae drawn: each one's centre z, its half length, and the first one's radius and the taper per vertebra. */
		double[] tail;
		double tailHalf, tailR, tailTaper;

		/** Where each of the eight sternal ribs' cartilages meets the breastbone. */
		double[] sternalRibs;
		/**
		 * The sternebrae: the front of the first one, the gap kept either side of each, how far its centre lies above
		 * the breastbone's line, and its radii at the ends and in the middle {end rx, end ry, mid rx, mid ry}.
		 */
		double manubriumZ, sternebraGap, sternebraLift;
		double[] sternebraR;
		/** The xiphoid cartilage: its root {z, lift above the breastbone's line, rx, ry}, then its control points. */
		double[] xiphoidRoot;
		double[][] xiphoid;

		/** A rib: its head's offset from the midline and below the vertebral body, with its radii {x, dy, rx, ry}. */
		double[] ribHead;
		/** The first rib's backward sweep, and the others' as {base, per rib}. */
		double firstRibSlope, ribSlope, ribSlopePerRib;
		/** Where the rib meets its cartilage: the first two, the other sternal ribs, the asternal ribs {start, per rib}, the floating rib. */
		double ribJunctionFirst, ribJunctionSternal, ribJunctionAsternal, ribJunctionPerRib, ribJunctionFloating;
		/** How far inside the breastbone's line the ribs' lower arc keeps, and the radius of their upper arc. */
		double ribArcInset, ribArcTop;
		/** The rib's least distance from the midline and how far inside the trunk's half width it runs. */
		double ribMinX, ribInset;
		/** Its width {base, swell at mid length, gain per rib (times ten)} and its thickness. */
		double[] ribWidth;
		double ribThickness;
		/** Coarse grains keep every second rib from this many cm a voxel, every fourth from twice it. */
		double ribLod;
		/** A sternal cartilage's end {x, lift above the breastbone}; an asternal one's bend {out, down}; the floating rib's tip {down, back, down, back}. */
		double[] sternalCartilage, asternalBend, floatingTip;
		/** Cartilage radii {rx, ry} at the rib, at the bend and at the end. */
		double[] cartilageR;

		/** The shoulder blade: the glenoid's ellipsoid {x, y, z, rx, ry, rz}, the blade's triangle with its half thickness, the spine's and the cartilage's control points. */
		double[] glenoid, blade;
		double bladeHalf;
		double[][] scapularSpine, scapularCartilage;

		/** The hip bone: the ilium's wing (triangle, half thickness) and shaft (control points). */
		double[] iliumWing;
		double iliumHalf;
		double[][] iliumShaft;
		/** The acetabulum: the cup's ellipsoid, and the ball cut from it {x, y, z, r}. */
		double[] acetabulum, acetabularFossa;
		/** The pubis's triangle and the ischium's two triangles (sharing their first point), all with one half thickness; the blend between them. */
		double[] pubis, ischium;
		double pelvisHalf, ischiumBlend;
		/** The obturator foramen left open in the ischium {x, y, z, rx, ry, rz}, or null where the grain is too coarse to show it. */
		double[] obturator;

		/** The cervical vertebrae's centres, atlas first. */
		double[] cervical;
		/** The cervical body: half its length and radii. */
		double cervicalHalf, cervicalRx, cervicalRy;
		/**
		 * A cervical spinous process: {root dy, root dz, root rx, root ry, the axis's tip dy and dz, the others' tip dy and dz, tip rx, tip ry}.
		 */
		double[] cervicalSpinous;
		/** How far out the transverse processes reach (atlas, axis, the rest), and their thickness (atlas, the rest). */
		double[] cervicalWings, cervicalThick;
		/** A cervical transverse process: {root x, root dy, root rx, tip dy, tip dz, tip rx, the atlas's tip ry}. */
		double[] cervicalWingShape;

		/** The spinal cord: its tissue, how far above the vertebral bodies' line it runs, and its control points {z, r}. */
		Mat cordTissue;
		double cordLift;
		double[][] cord;

		/**
		 * The soft tissue round the skeleton ({@link #softTissue}): how big the animal is next to a ewe (the muscles are
		 * laid out from the bones' landmarks in a ewe's centimetres times this), and its lean, fat and skin.
		 */
		double size = 1;
		Mat lean, fat, skin;

		/** The limbs, from the shoulder and the hip to the hooves; null where a species has none yet. */
		Limb fore, hind;
		/** The hooves' horn, and the coffin bone inside each (a cut face shows its cortex round the marrow). */
		Mat horn, coffinBone;
	}

	/** A bone's tissue, for {@link Bone#tissue}. */
	static final int BONE = 0, JOINT = 1, CARTILAGE = 2, HORN = 3, COFFIN = 4;

	/**
	 * One piece of a limb: its name, its tissue, the direction a tube's second radius lies along (null: none), whether
	 * a tube's ends are flat, and its control points (a tube's {x, y, z, r} or {x, y, z, rx, ry}) or, with
	 * {@code ellipsoid}, one {x, y, z, rx, ry, rz}. In a {@link Limb#toes toe} each point is {dx, y, z, dz, ...}: x is
	 * the foot's plus dx a claw outward, z moves dz a claw (the lateral claw +1, the medial -1).
	 */
	record Bone(String name, int tissue, double[] up, boolean flat, boolean ellipsoid, double[][] points) {
	}

	static Bone tubeBone(String name, int tissue, double[] up, double[]... points) {
		return new Bone(name, tissue, up, false, false, points);
	}

	static Bone ellBone(String name, int tissue, double... e) {
		return new Bone(name, tissue, null, false, true, new double[][] {e});
	}

	/**
	 * A limb: its outliner group ("forelimb", "hindlimb"), the bones down to the fetlock, the foot's x, the pieces of each
	 * of the two toes (the claws of a cloven foot), and what comes after them (the dewclaws).
	 */
	static final class Limb {
		final String group;
		final Bone[] upper;
		final double footX;
		final Bone[] toes;
		final Bone[] after;

		Limb(String group, Bone[] upper, double footX, Bone[] toes, Bone[] after) {
			this.group = group;
			this.upper = upper;
			this.footX = footX;
			this.toes = toes;
			this.after = after;
		}
	}

	// ------------------------------------------------------------------------------------------------ the body frame

	/** The half width of the trunk at the ribs: narrow at the chest's entrance between the forelegs, a barrel behind. */
	static double halfWidth(double z) {
		return curve(z, BODY.halfWidth);
	}

	/** The height of the breastbone, rising towards the entrance of the chest. */
	static double sternumY(double z) {
		return curve(z, BODY.sternum);
	}

	/** The height of the middle of the vertebral bodies. */
	static double spineY(double z) {
		return curve(z, BODY.spine);
	}

	/** The height of the middle of the cervical vertebral bodies: the neck carried forward to the skull. */
	static double neckY(double z) {
		return curve(z, BODY.neck);
	}

	/** {@code x} on side {@code s} (1 right, -1 left); the midline stays +0. */
	static double side(int s, double x) {
		return x == 0 ? 0 : s * x;
	}

	/** A point of a triangle given as {x, y, z} x 3, on side {@code s}. */
	static double[] corner(int s, double[] t, int n) {
		return v(side(s, t[n * 3]), t[n * 3 + 1], t[n * 3 + 2]);
	}

	/** A tube through control points on side {@code s}. */
	static Tube tube(int s, double[][] points) {
		Tube t = tube();
		for (double[] q : points) {
			if (q.length == 4) {
				t.p(side(s, q[0]), q[1], q[2], q[3]);
			} else {
				t.p(side(s, q[0]), q[1], q[2], q[3], q[4]);
			}
		}

		return t;
	}

	// ------------------------------------------------------------------------------------------------ the skeleton

	/**
	 * The trunk's bones: the thoracic vertebrae with the tall spines of the withers, the lumbar ones with their long
	 * flat transverse processes, the sacrum and the root of the tail; the ribs, the sternal ones joined to the
	 * breastbone by costal cartilage, the next by the cartilage of the costal arch, the last floating; the sternebrae
	 * and the xiphoid; the shoulder blades on the chest wall; the pelvis.
	 */
	static void skeleton() {
		Body b = BODY;
		Mat bone = b.bone;
		Mat cartilage = b.cartilage;
		Mat disc = b.disc;
		double[] sp = b.thoracicSpinous;
		for (int i = 1; i <= b.withers.length; i++) {
			double z = b.thoracicZ + b.thoracicStep * (i - 1);
			double y = spineY(z);
			String g = String.format(Locale.ROOT, "skeleton/spine/thoracic/T%02d", i);
			part(g, "body", bone, tube().p(0, y, z - b.thoracicHalf, b.thoracicRx, b.thoracicRy)
					.p(0, spineY(z + b.thoracicHalf), z + b.thoracicHalf, b.thoracicRx, b.thoracicRy).flat(true, true).build());
			part(g, "disc", disc, tube().p(0, spineY(z + b.thoracicHalf), z + b.thoracicHalf, b.thoracicDiscR)
					.p(0, spineY(z + b.thoracicDiscEnd), z + b.thoracicDiscEnd, b.thoracicDiscR).flat(true, true).build());
			double h = b.withers[i - 1];
			part(g, "spinous_process", bone, tube().up((a, c, d) -> new double[] {1, 0, 0})
					.p(0, y + sp[0], z, sp[1], sp[2]).p(0, y + sp[3] + h * 0.6, z + sp[4] * h * 0.6, sp[5], sp[6])
					.p(0, y + sp[3] + h, z + sp[4] * h, sp[7], sp[8]).build());
		}

		double[] tp = b.transverseShape;
		for (int i = 1; i <= b.transverse.length; i++) {
			double z = b.lumbarZ + b.lumbarStep * (i - 1);
			double y = spineY(z);
			String g = String.format(Locale.ROOT, "skeleton/spine/lumbar/L%02d", i);
			part(g, "body", bone, tube().p(0, spineY(z - b.lumbarHalf), z - b.lumbarHalf, b.lumbarRx, b.lumbarRy)
					.p(0, spineY(z + b.lumbarHalf), z + b.lumbarHalf, b.lumbarRx, b.lumbarRy).flat(true, true).build());
			if (i < b.transverse.length) {
				part(g, "disc", disc, tube().p(0, spineY(z + b.lumbarHalf), z + b.lumbarHalf, b.lumbarDiscR)
						.p(0, spineY(z + b.lumbarDiscEnd), z + b.lumbarDiscEnd, b.lumbarDiscR).flat(true, true).build());
			}

			Tube spinous = tube().up((a, c, d) -> new double[] {1, 0, 0});
			for (double[] q : b.lumbarSpinous) {
				spinous.p(0, y + q[1], z + q[2], q[3], q[4]);
			}

			part(g, "spinous_process", bone, spinous.build());
			double reach = b.transverse[i - 1];
			for (int s = -1; s <= 1; s += 2) {
				part(g, s > 0 ? "right_transverse_process" : "left_transverse_process", bone, tube().up((a, c, d) -> new double[] {0, 1, 0})
						.p(s * tp[0], y + tp[1], z, tp[2], tp[3]).p(s * reach * 0.55, y + tp[4], z + tp[5], tp[6], tp[7])
						.p(s * reach, y + tp[8], z + tp[9], tp[10], tp[11]).build());
			}
		}

		part("skeleton/spine/sacrum", "sacrum", bone, tube(1, b.sacrum).up((a, c, d) -> new double[] {0, 1, 0}).build());
		part("skeleton/spine/sacrum", "median_sacral_crest", bone, tube(1, b.sacralCrest).build());
		for (int s = -1; s <= 1; s += 2) {
			part("skeleton/spine/sacrum", s > 0 ? "right_wing" : "left_wing", bone,
					tri(corner(s, b.sacralWing, 0), corner(s, b.sacralWing, 1), corner(s, b.sacralWing, 2), b.sacralWingHalf));
		}

		for (int i = 0; i < b.tail.length; i++) {
			double r = b.tailR - b.tailTaper * i;
			double z = b.tail[i];
			part(String.format(Locale.ROOT, "skeleton/spine/tail/Cd%02d", i + 1), "body", bone, tube()
					.p(0, spineY(z - b.tailHalf), z - b.tailHalf, r).p(0, spineY(z + b.tailHalf), z + b.tailHalf, r).build());
		}

		ribs(bone, cartilage);
		double[] zs = b.sternalRibs;
		double[] sr = b.sternebraR;
		for (int k = 0; k < zs.length - 1; k++) {
			double a = (k == 0 ? b.manubriumZ : zs[k]) + b.sternebraGap;
			double e = zs[k + 1] - b.sternebraGap;
			part(String.format(Locale.ROOT, "skeleton/sternum/sternebra_%d", k + 1), "sternebra", bone, tube().up((p, q, r) -> new double[] {0, 1, 0})
					.p(0, sternumY(a) + b.sternebraLift, a, sr[0], sr[1]).p(0, sternumY((a + e) / 2) + b.sternebraLift, (a + e) / 2, sr[2], sr[3])
					.p(0, sternumY(e) + b.sternebraLift, e, sr[0], sr[1]).build());
		}

		double[] xr = b.xiphoidRoot;
		Tube xiphoid = tube().up((p, q, r) -> new double[] {0, 1, 0}).p(0, sternumY(xr[0]) + xr[1], xr[0], xr[2], xr[3]);
		for (double[] q : b.xiphoid) {
			xiphoid.p(q[0], q[1], q[2], q[3], q[4]);
		}

		part("skeleton/sternum/xiphoid", "xiphoid_cartilage", cartilage, xiphoid.build());
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right" : "left";
			String g = "skeleton/shoulder_blades/" + side + "_scapula";
			double[] gl = b.glenoid;
			part(g, "glenoid", b.joint, ell(side(s, gl[0]), gl[1], gl[2], gl[3], gl[4], gl[5]));
			part(g, "blade", bone, tri(corner(s, b.blade, 0), corner(s, b.blade, 1), corner(s, b.blade, 2), b.bladeHalf));
			part(g, "spine_of_scapula", bone, tube(s, b.scapularSpine).build());
			part(g, "scapular_cartilage", cartilage, tube(s, b.scapularCartilage).up((p, q, r) -> new double[] {1, 0, 0}).build());
			String pg = "skeleton/pelvis/" + side + "_hip_bone";
			part(pg, "ilium_wing", bone, tri(corner(s, b.iliumWing, 0), corner(s, b.iliumWing, 1), corner(s, b.iliumWing, 2), b.iliumHalf));
			part(pg, "ilium_shaft", bone, tube(s, b.iliumShaft).build());
			double[] ac = b.acetabulum;
			double[] fo = b.acetabularFossa;
			part(pg, "acetabulum", b.joint, cut(ell(side(s, ac[0]), ac[1], ac[2], ac[3], ac[4], ac[5]), ell(side(s, fo[0]), fo[1], fo[2], fo[3], fo[3], fo[3])));
			part(pg, "pubis", bone, tri(corner(s, b.pubis, 0), corner(s, b.pubis, 1), corner(s, b.pubis, 2), b.pelvisHalf));
			double[] is = b.ischium;
			Shape ischium = union(b.ischiumBlend, tri(corner(s, is, 0), corner(s, is, 1), corner(s, is, 2), b.pelvisHalf),
					tri(corner(s, is, 0), corner(s, is, 2), corner(s, is, 3), b.pelvisHalf));
			if (b.obturator != null) {
				// The ischium's plate with the obturator foramen left open in it.
				double[] ob = b.obturator;
				ischium = cut(ischium, ell(side(s, ob[0]), ob[1], ob[2], ob[3], ob[4], ob[5]));
			}

			part(pg, "ischium", bone, ischium);
		}
	}

	/**
	 * The limbs: a forelimb from the shoulder blade's socket down (humerus, the point of the elbow, the radius with the
	 * ulna fused behind it, the knee = the carpus, the cannon bone = the fused metacarpals of a cloven foot, the
	 * fetlock, two toes of three phalanges each and a claw of horn per toe), and a hindlimb from the hip (femur, the
	 * kneecap in front, the tibia, the point of the hock on the Achilles tendon, the hock = the tarsus, the cannon =
	 * the fused metatarsals, the same two toes). The dewclaws stand behind the fetlock.
	 */
	static void limbs() {
		Body b = BODY;
		if (b.fore == null) {
			return;
		}

		Mat[] tissue = {b.bone, b.joint, b.cartilage, b.horn, b.coffinBone};
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right" : "left";
			for (Limb limb : new Limb[] {b.fore, b.hind}) {
				String g = "skeleton/limbs/" + side + "_" + limb.group;
				for (Bone bone : limb.upper) {
					part(g, bone.name(), tissue[bone.tissue()], shape(s, bone));
				}

				for (int d = -1; d <= 1; d += 2) {
					String toe = d > 0 ? "lateral_toe" : "medial_toe";
					double x = side(s, limb.footX);
					for (Bone bone : limb.toes) {
						part(g, toe + "/" + bone.name(), tissue[bone.tissue()], toeShape(x, d, bone));
					}
				}

				for (Bone bone : limb.after) {
					part(g, bone.name(), tissue[bone.tissue()], shape(s, bone));
				}
			}
		}
	}

	/** A limb bone's shape on side {@code s}. */
	static Shape shape(int s, Bone bone) {
		if (bone.ellipsoid()) {
			double[] e = bone.points()[0];
			return ell(side(s, e[0]), e[1], e[2], e[3], e[4], e[5]);
		}

		Tube t = tube(s, bone.points());
		if (bone.up() != null) {
			double[] up = bone.up();
			t.up((p, q, r) -> up);
		}

		return t.flat(bone.flat(), bone.flat()).build();
	}

	/** A piece of the toe on side {@code d} (1 lateral, -1 medial) of a foot at {@code x}. */
	static Shape toeShape(double x, int d, Bone bone) {
		double[][] pts = bone.points();
		if (bone.ellipsoid()) {
			double[] e = pts[0];
			return ell(x + d * e[0], e[1], e[2] + d * e[3], e[4], e[5], e[6]);
		}

		Tube t = tube();
		for (double[] q : pts) {
			t.p(x + d * q[0], q[1], q[2] + d * q[3], q[4], q[5]);
		}

		if (bone.up() != null) {
			double[] up = bone.up();
			t.up((p, q, r) -> up);
		}

		return t.flat(bone.flat(), bone.flat()).build();
	}

	/** The ribs, each sweeping out and down from its vertebra and back as it goes, with its costal cartilage. */
	static void ribs(Mat bone, Mat cartilage) {
		Body b = BODY;
		int count = b.withers.length;
		double yc = b.yc;
		double[][] cart = new double[count + 1][];
		double[] zs = b.sternalRibs;
		double[] cr = b.cartilageR;
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right" : "left";
			int sign = s;
			for (int i = 1; i <= count; i++) {
				double zh = b.thoracicZ + b.thoracicStep * (i - 1);
				double slope = (i == 1 ? b.firstRibSlope : b.ribSlope + b.ribSlopePerRib * i);
				double yJ = i <= zs.length ? (i < 3 ? b.ribJunctionFirst : b.ribJunctionSternal)
						: i < count ? b.ribJunctionAsternal + (i - (zs.length + 1)) * b.ribJunctionPerRib : b.ribJunctionFloating;
				double hb = yc - sternumY(zh + slope * 0.75) - b.ribArcInset;
				double phiEnd = Math.asin(clamp((yJ - yc) / hb, -1, 1));
				double top = b.ribArcTop;
				Tube rib = tube().up((x, y, z) -> norm(new double[] {x / sq(halfWidth(z)), (y - yc) / sq(y >= yc ? top : hb), 0}));
				rib.p(sign * b.ribHead[0], spineY(zh) + b.ribHead[1], zh, b.ribHead[2], b.ribHead[3]);
				double[] last = null;
				int steps = 14;
				double phi0 = Math.toRadians(78);
				for (int k = 0; k <= steps; k++) {
					double phi = phi0 + (phiEnd - phi0) * k / steps;
					double sp = Math.sin(phi);
					double zz = zh + slope * (1 - sp) / 2;
					double y = yc + (sp >= 0 ? top : hb) * sp;
					double x = sign * Math.max(b.ribMinX, (halfWidth(zz) - b.ribInset) * Math.cos(phi));
					double width = b.ribWidth[0] + b.ribWidth[1] * Math.sin(Math.PI * k / steps) + b.ribWidth[2] * i * 10;
					rib.p(x, y, zz, width, b.ribThickness);
					last = new double[] {x, y, zz};
				}

				String g = String.format(Locale.ROOT, "skeleton/ribcage/%s_rib_%02d", side, i);
				// Coarse grains keep only every second or fourth rib, so a gap still shows between them.
				boolean kept = (i - 1) % (LOD >= 2 * b.ribLod ? 4 : LOD >= b.ribLod ? 2 : 1) == 0;
				if (kept) {
					part(g, "rib", bone, rib.build());
				}

				Tube c = tube().up((x, y, z) -> norm(new double[] {x / sq(halfWidth(z)), (y - yc) / sq(hb), 0}));
				double[] mid;
				double[] end;
				if (i <= zs.length) {
					end = new double[] {sign * b.sternalCartilage[0], sternumY(zs[i - 1]) + b.sternalCartilage[1], zs[i - 1]};
					mid = new double[] {last[0] * 0.72, last[1] - (last[1] - end[1]) * 0.6, last[2] - (last[2] - end[2]) * 0.3};
				} else if (i < count) {
					double[] pc = cart[i - 1];
					end = new double[] {pc[0] + (pc[3] - pc[0]) * 0.45, pc[1] + (pc[4] - pc[1]) * 0.45, pc[2] + (pc[5] - pc[2]) * 0.45};
					mid = new double[] {(last[0] + end[0]) / 2 + sign * b.asternalBend[0], (last[1] + end[1]) / 2 - b.asternalBend[1], (last[2] + end[2]) / 2};
				} else {
					double[] f = b.floatingTip;
					mid = new double[] {last[0] * 0.95, last[1] - f[0], last[2] + f[1]};
					end = new double[] {last[0] * 0.88, last[1] - f[2], last[2] + f[3]};
				}

				cart[i] = new double[] {last[0], last[1], last[2], mid[0], mid[1], mid[2]};
				c.p(last[0], last[1], last[2], cr[0], cr[1]).p(mid[0], mid[1], mid[2], cr[2], cr[3]).p(end[0], end[1], end[2], cr[4], cr[5]);
				if (kept) {
					part(g, "costal_cartilage", cartilage, c.build());
				}
			}
		}
	}

	/** The cervical vertebrae: C1 the atlas with its broad wings, C2 the axis with its tall spine, then the rest alike. */
	static void neck() {
		Body b = BODY;
		double[] zs = b.cervical;
		double[] sp = b.cervicalSpinous;
		double[] w = b.cervicalWingShape;
		for (int i = 0; i < zs.length; i++) {
			double z = zs[i];
			double y = neckY(z);
			String g = String.format(Locale.ROOT, "skeleton/spine/cervical/C%02d", i + 1);
			part(g, "body", b.neckBone, tube().p(0, y, z - b.cervicalHalf, b.cervicalRx, b.cervicalRy)
					.p(0, neckY(z + b.cervicalHalf), z + b.cervicalHalf, b.cervicalRx, b.cervicalRy).flat(true, true).build());
			part(g, "spinous_process", b.neckBone, tube().up((a, c, d) -> new double[] {1, 0, 0})
					.p(0, y + sp[0], z + sp[1], sp[2], sp[3]).p(0, y + (i == 1 ? sp[4] : sp[6]), z + (i == 1 ? sp[5] : sp[7]), sp[8], sp[9]).build());
			double wing = b.cervicalWings[Math.min(i, 2)];
			double thick = b.cervicalThick[i == 0 ? 0 : 1];
			for (int s = -1; s <= 1; s += 2) {
				part(g, s > 0 ? "right_transverse_process" : "left_transverse_process", b.neckBone, tube().up((a, c, d) -> new double[] {0, 1, 0})
						.p(s * w[0], y + w[1], z, w[2], thick).p(s * wing, y + w[3], z + w[4], w[5], i == 0 ? w[6] : thick * 0.9).build());
			}
		}
	}

	/**
	 * The spinal cord in the canal above the vertebral bodies, from the neck to where it tapers out over the sacrum. The
	 * vertebral arches round it are left out at this scale, so the cord fills the canal they would leave. On a grid whose
	 * cells are taller than the cord (the game's, 2 cm a quarter pixel up) it is drawn taller, so where it steps from one
	 * row of cells to the next both rows hold it and it stays one piece.
	 */
	static void spinalCord() {
		Body b = BODY;
		Tube t = tube().up((x, y, z) -> new double[] {0, 1, 0});
		for (double[] a : b.cord) {
			t.p(0, spineY(a[0]) + b.cordLift, a[0], a[1], 1.0 * a[1] / 0.8);
		}

		Shape tube = t.build();
		Shape tall = new Shape(tube.lo, tube.hi, (x, y, z) -> {
			double stretch = Math.max(1.0, DUCT * 1.5);
			double c = spineY(z) + b.cordLift;
			return tube.f.at(x, c + (y - c) / stretch, z);
		});
		part("nervous/spinal_cord", "spinal_cord", b.cordTissue, tall);
	}

	// ------------------------------------------------------------------------------------------------ soft tissue

	/**
	 * How far (cm, roughly) a point lies outside the wall of the trunk's cavity: the ribs' arc, carried back over the
	 * belly to the pelvis. Negative inside, where the organs are.
	 */
	static double wall(double x, double y, double z) {
		Body b = BODY;
		double ex = halfWidth(z);
		double ey = y >= b.yc ? b.ribArcTop : b.yc - sternumY(z);
		double u = x / ex;
		double w = (y - b.yc) / ey;
		double r = Math.sqrt(u * u + w * w);
		if (r < 1.0E-6) {
			return -Math.min(ex, ey);
		}

		double grad = Math.sqrt(sq(u / ex) + sq(w / ey)) / r;
		return (r - 1) / grad;
	}

	/** Where the chest wall gives way to the belly's at height y: the costal arch, from the xiphoid up and back to the last rib. */
	static double archZ(double y) {
		Body b = BODY;
		double lastRib = b.thoracicZ + b.thoracicStep * (b.withers.length - 1) + 4 * b.size;
		double t = clamp((y - sternumY(b.xiphoid[0][2]) - 0.5) / (b.yc + 4 * b.size - sternumY(b.xiphoid[0][2])), 0, 1);
		return b.xiphoid[0][2] + (lastRib - b.xiphoid[0][2]) * t;
	}

	/** A landmark {@code base} (right side) moved by (dx, dy, dz) ewe centimetres, on side {@code s}. */
	static double[] at(int s, double[] base, double dx, double dy, double dz) {
		double k = BODY.size;
		return v(side(s, base[0] + dx * k), base[1] + dy * k, base[2] + dz * k);
	}

	/** The middle of a triangle given as {x, y, z} x 3 (right side). */
	static double[] centroid(double[] t) {
		return v((t[0] + t[3] + t[6]) / 3, (t[1] + t[4] + t[7]) / 3, (t[2] + t[5] + t[8]) / 3);
	}

	/** A tube through points {x, y, z, rx, ry} already placed, with ry along {@code up}; radii in ewe cm, scaled. */
	static Shape strap(double[] up, double[]... pts) {
		double k = BODY.size;
		Tube t = tube().up((x, y, z) -> up);
		for (double[] q : pts) {
			t.p(q[0], q[1], q[2], q[3] * k, q[4] * k);
		}

		return t.build();
	}

	/** {@code p} with radii (rx, ry) appended, for {@link #strap}. */
	static double[] r(double[] p, double rx, double ry) {
		return v(p[0], p[1], p[2], rx, ry);
	}

	/** A layer of the trunk's wall: outside the cavity by {@code d0} to {@code d1} cm (ewe, scaled), where {@code also} holds. */
	static Shape layer(double d0, double d1, P3 also) {
		Body b = BODY;
		double k = b.size;
		double w = halfWidth(0) + 6 * k;
		double[] lo = v(-w, sternumY(0) - 6 * k, b.thoracicZ - 6 * k);
		double[] hi = v(w, spineY(0) + 10 * k, b.tail[0]);
		return new Shape(lo, hi, (x, y, z) -> {
			double d = wall(x, y, z);
			if (!also.test(x, y, z)) {
				return 1;
			}

			double u = k * layerStretch();
			return Math.max(d0 * u - d, d - d1 * u);
		});
	}

	/**
	 * How much the layers of the trunk's wall are thickened at the grain being cut: none while a layer's thinnest (about
	 * half a centimetre in a ewe) still holds a voxel, then enough that each layer keeps one, so the flank reads as
	 * layers rather than as rings.
	 */
	static double layerStretch() {
		return Math.max(1, VOX / (0.45 * BODY.size));
	}

	/** The lean of one named muscle: the species' lean, each muscle a shade apart so the seams between them read. */
	static Mat lean(int n) {
		Mat m = BODY.lean;
		double f = 0.84 + 0.26 * hash(n, 7, 3, 911);
		return (x, y, z, face, ax, ay, az) -> mul(m.rgb(x, y, z, face, ax, ay, az), f);
	}

	/**
	 * The soft tissue round the skeleton, from the skin in: the skin and the fat under it over the whole body, the fat
	 * of the brisket and the tail head, then the named muscles (the groups a butcher's joints are made of), and what is
	 * left between them filled with lean. Each muscle is laid out from the bones it runs between, so it follows the
	 * species' own frame. The organs, made before, keep every voxel they hold; so do the bones.
	 *
	 * <p>The muscles: the longissimus (the eye of the loin and the rack), the spinalis beside the withers, the psoas
	 * under the loin (the fillet); the shoulder's supraspinatus, infraspinatus and subscapularis on the blade, the
	 * serratus ventralis under it and the long head of the triceps behind the arm; the latissimus dorsi over the ribs,
	 * the pectorals of the brisket and the intercostals between the ribs; the belly wall in its layers (transversus,
	 * internal and external oblique, the rectus down the floor, the cutaneous muscle under the skin of the flank); the
	 * gluteus medius over the hip (the chump); the thigh's biceps femoris, semitendinosus, semimembranosus, quadriceps
	 * and adductor (the leg); and the neck's extensors, brachiocephalicus and sternocephalicus.
	 */
	static void softTissue() {
		Body b = BODY;
		double k = b.size;
		double[] glenoid = b.glenoid;
		double[] blade = b.blade;
		double[] spineTop = b.scapularSpine[b.scapularSpine.length - 1];
		double[] acetabulum = b.acetabulum;
		double[] ischialTuber = v(b.ischium[3], b.ischium[4], b.ischium[5]);
		double lastLumbar = b.lumbarZ + b.lumbarStep * (b.transverse.length - 1);
		double t1 = b.thoracicZ;
		double pubisZ = b.pubis[2];

		// The skin and the fat under it: shells round an outline of the whole body (the barrel, the back over the
		// loin eyes and the withers, the shoulders, the thighs and the neck).
		Shape body = outline();
		Shape[] legs = legs();
		double fatDepth = 0.6 * k;
		double skinDepth = 0.4 * k;
		part("skin", "skin", b.skin, new Shape(body.lo, body.hi, (x, y, z) -> {
			double d = body.f.at(x, y, z);
			return Math.max(-d, d - Math.max(skinDepth, VOX));
		}));
		part("fat/subcutaneous", "subcutaneous_fat", b.fat, new Shape(body.lo, body.hi, (x, y, z) -> {
			double d = body.f.at(x, y, z);
			return wall(x, y, z) < 0 || lowerLeg(legs, x, y, z) ? 1 : Math.max(-d - Math.max(fatDepth, VOX), d);
		}));
		part("fat/depots", "brisket_fat", b.fat, ell(0, sternumY(b.manubriumZ + 10 * k) - 2.4 * k, b.manubriumZ + 10 * k, 4.0 * k, 0.9 * k, 7.0 * k));
		double tailRoot = b.tail[0];
		part("fat/depots", "tailhead_fat", b.fat, ell(0, spineY(tailRoot - 1 * k) + 2.4 * k, tailRoot - 1 * k, 4.5 * k, 1.3 * k, 5.0 * k));

		int n = 0;
		double[] across = {0, 1, 0};
		double[] outward = {1, 0, 0};
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right_" : "left_";
			// The back: the longissimus from the first thoracic vertebra to the hip, deepest and widest over the loin, as a
			// control point a vertebra; the spinalis against the thoracic spines; the psoas under the lumbar vertebrae.
			Tube eye = tube().up((x, y, z) -> across);
			for (int i = 0; i < b.withers.length; i++) {
				double z = t1 + b.thoracicStep * i;
				double f = i / (double) (b.withers.length - 1);
				eye.p(side(s, (3.2 + 0.8 * f) * k), spineY(z) + (1.6 + 0.3 * f) * k, z, (2.0 + 0.6 * f) * k, (1.0 + 0.5 * f) * k);
			}

			for (int i = 0; i < b.transverse.length; i++) {
				double z = b.lumbarZ + b.lumbarStep * i;
				double taper = i == b.transverse.length - 1 ? 0.8 : 1.0;
				eye.p(side(s, 4.2 * k), spineY(z) + 2.4 * k, z, 3.0 * k * taper, 2.0 * k * taper);
			}

			part("muscles/back/" + side + "longissimus", "longissimus", lean(n++), eye.build());
			Tube spinalis = tube().up((x, y, z) -> across);
			for (int i = 0; i < b.withers.length; i++) {
				double z = t1 + b.thoracicStep * i;
				double h = b.withers[i];
				spinalis.p(side(s, 1.0 * k), spineY(z) + 1.3 * k + 0.3 * h, z, 0.75 * k, 0.3 * h + 0.4 * k);
			}

			part("muscles/back/" + side + "spinalis", "spinalis", lean(n++), spinalis.build());
			double ry = b.lumbarRy;
			double lastRib = t1 + b.thoracicStep * (b.withers.length - 1);
			part("muscles/back/" + side + "psoas", "psoas", lean(n++), strap(across,
					v(side(s, 2.4 * k), spineY(lastRib) - ry - 0.9 * k, lastRib, 1.1, 0.8),
					v(side(s, 2.8 * k), spineY(b.lumbarZ + b.lumbarStep * 2) - ry - 1.3 * k, b.lumbarZ + b.lumbarStep * 2, 1.8, 1.3),
					v(side(s, 3.2 * k), spineY(lastLumbar) - ry - 1.6 * k, lastLumbar, 2.0, 1.6),
					r(at(s, acetabulum, -0.4, -1.5, 0.8), 1.2, 1.0)));

			// The shoulder: the blade's muscles either side of its spine, the subscapularis under it against the
			// serratus, and the long head of the triceps filling the angle behind the arm.
			double[] bladeTop = v(blade[3], blade[4], blade[5]);
			double[] bladeBack = v(blade[6], blade[7], blade[8]);
			part("muscles/shoulder/" + side + "supraspinatus", "supraspinatus", lean(n++), strap(outward,
					r(at(s, glenoid, 0.7, 2.2, -0.6), 1.5, 1.0),
					r(at(s, mid(bladeTop, spineTop), 0.9, -0.5, -0.4), 2.0, 0.7)));
			part("muscles/shoulder/" + side + "infraspinatus", "infraspinatus", lean(n++), strap(outward,
					r(at(s, glenoid, 0.8, 3.5, 2.0), 1.8, 1.0),
					r(at(s, mid(bladeBack, spineTop), 0.9, -1.5, -0.5), 2.6, 0.7)));
			part("muscles/shoulder/" + side + "subscapularis", "subscapularis", lean(n++), tri(at(s, v(blade[0], blade[1], blade[2]), -0.9, 0.5, 0.3),
					at(s, bladeTop, -0.9, -1.0, 0.6), at(s, bladeBack, -0.9, -1.0, -0.6), 0.55 * k));
			int sign = s;
			double bladeInner = Math.min(blade[0], Math.min(blade[3], blade[6])) - 1.4 * k;
			part("muscles/shoulder/" + side + "serratus_ventralis", "serratus_ventralis", lean(n++), region(
					v(Math.min(0, sign * bladeInner), b.yc - 2 * k, blade[5] - 2 * k), v(Math.max(0, sign * bladeInner), blade[4], blade[8] + 3 * k),
					(x, y, z) -> {
						double d = wall(x, y, z);
						return d > 0.1 * k && x * sign < bladeInner;
					}));
			part("muscles/shoulder/" + side + "triceps", "triceps", lean(n++), lumpy(ell(side(s, glenoid[0] + 0.6 * k), glenoid[1] - 5.5 * k, glenoid[2] + 5.0 * k,
					2.3 * k, 5.5 * k, 3.4 * k), 0.25 * k, 0.5 / k, 301 + s));

			// The chest: the pectorals under the breastbone to the arm, the latissimus fanning over the ribs behind the
			// blade, the intercostals between the ribs.
			double zp = b.manubriumZ + 7 * k;
			part("muscles/chest/" + side + "superficial_pectoral", "superficial_pectoral", lean(n++),
					ell(side(s, 2.8 * k), sternumY(zp) - 0.9 * k, zp, 3.0 * k, 1.6 * k, 6.5 * k));
			part("muscles/chest/" + side + "deep_pectoral", "deep_pectoral", lean(n++), strap(across,
					v(side(s, 1.2 * k), sternumY(zp + 3 * k) - 0.6 * k, zp + 3 * k, 1.6, 1.1),
					r(at(s, glenoid, -2.7, -3.0, 1.0), 1.6, 1.1)));
			double[] lat0 = v(blade[8] - 1 * k, spineY(blade[8]) + 0.5 * k);
			double[] lat1 = v(b.lumbarZ + b.lumbarStep * 1.5, spineY(b.lumbarZ + b.lumbarStep * 1.5) + 0.5 * k);
			double[] lat2 = v(glenoid[2] + 4 * k, glenoid[1] - 2 * k);
			part("muscles/chest/" + side + "latissimus_dorsi", "latissimus_dorsi", lean(n++), layer(0.6, 1.3,
					(x, y, z) -> x * sign > 0 && inTriangle(z, y, lat0, lat1, lat2)));
			part("muscles/chest/" + side + "intercostals", "intercostals", lean(n++), layer(-0.9, 0.25,
					(x, y, z) -> x * sign > 0 && z < archZ(y) && z > t1 - 1 * k && y > sternumY(z) + 0.5 * k));

			// The belly wall: the rectus down the floor, then the three layers of the flank, from the cavity out, and the
			// cutaneous muscle under the skin.
			part("muscles/belly/" + side + "rectus_abdominis", "rectus_abdominis", lean(n++), layer(-0.1, 1.0,
					(x, y, z) -> x * sign > 0 && Math.abs(x) < 4.5 * k && y < b.yc - 8 * k && z > b.xiphoid[0][2] && z < pubisZ));
			P3 flank = (x, y, z) -> x * sign > 0 && z >= archZ(y) && z < pubisZ && y < spineY(z) - 1 * k;
			int wallTint = n++;
			String flankWall = "muscles/belly/" + side + "flank_wall/";
			part(flankWall + "transversus_abdominis", "transversus_abdominis", lean(wallTint), layer(-0.1, 0.4, flank));
			part(flankWall + "internal_oblique", "internal_oblique", lean(wallTint), layer(0.4, 0.9, flank));
			part(flankWall + "external_oblique", "external_oblique", lean(wallTint), layer(0.9, 1.4,
					(x, y, z) -> flank.test(x, y, z) || x * sign > 0 && z < archZ(y) && z > lastRib - 12 * k && y < b.yc));
			part(flankWall + "cutaneous_trunci", "cutaneous_trunci", lean(wallTint), layer(1.4, 1.8,
					(x, y, z) -> x * sign > 0 && y > b.yc - 7 * k && y < b.yc + 10 * k && z > blade[8] - 4 * k && z < pubisZ - 4 * k));

			// The hindquarter: the gluteus medius from the loin over the hip, and the thigh round where the femur runs.
			double[] wing = centroid(b.iliumWing);
			part("muscles/hindquarter/" + side + "gluteus_medius", "gluteus_medius", lean(n++), strap(across,
					v(side(s, 4.5 * k), spineY(lastLumbar - b.lumbarStep) + 2.6 * k, lastLumbar - b.lumbarStep, 2.2, 1.4),
					r(at(s, wing, 0, 2.3, 0), 3.2, 2.2),
					r(at(s, acetabulum, 1.6, 3.0, 1.0), 2.2, 1.6)));
			part("muscles/hindquarter/" + side + "biceps_femoris", "biceps_femoris", lean(n++), strap(outward,
					r(at(s, ischialTuber, 2.0, -0.5, -1.0), 2.6, 1.5),
					r(at(s, acetabulum, 4.1, -6.0, 5.2), 3.4, 1.8),
					r(at(s, acetabulum, 4.6, -13.5, 1.2), 2.4, 1.2)));
			part("muscles/hindquarter/" + side + "semitendinosus", "semitendinosus", lean(n++), strap(outward,
					r(at(s, ischialTuber, 0, 0, 0.8), 1.4, 1.4),
					r(at(s, ischialTuber, 1.4, -9.0, 0), 1.6, 1.6),
					r(at(s, ischialTuber, 2.2, -17.0, -3.0), 1.2, 1.2)));
			part("muscles/hindquarter/" + side + "semimembranosus", "semimembranosus", lean(n++), strap(outward,
					r(at(s, ischialTuber, -2.2, -3.0, -1.0), 1.8, 1.8),
					r(at(s, ischialTuber, -1.0, -10.0, -3.0), 2.0, 2.0),
					r(at(s, ischialTuber, 0, -16.5, -6.0), 1.4, 1.4)));
			part("muscles/hindquarter/" + side + "quadriceps", "quadriceps", lean(n++), lumpy(ell(side(s, acetabulum[0] + 2.4 * k), acetabulum[1] - 6.5 * k,
					acetabulum[2] - 3.0 * k, 2.2 * k, 5.0 * k, 2.4 * k), 0.25 * k, 0.5 / k, 311 + s));
			part("muscles/hindquarter/" + side + "adductor", "adductor", lean(n++), ell(side(s, 3.4 * k), acetabulum[1] - 6.5 * k, acetabulum[2] + 2.7 * k,
					1.4 * k, 3.6 * k, 2.6 * k));

			// The neck: the extensors over the vertebrae to the withers, the brachiocephalicus from behind the skull down
			// to the arm, the sternocephalicus from the breastbone up the throat to the jaw.
			Tube extensors = tube().up((x, y, z) -> across);
			for (double z : b.cervical) {
				extensors.p(side(s, 1.7 * k), neckY(z) + 2.2 * k, z, 1.6 * k, 1.8 * k);
			}

			extensors.p(side(s, 1.4 * k), spineY(t1) + 2.6 * k, t1, 1.2 * k, 1.6 * k);
			part("muscles/neck/" + side + "neck_extensors", "neck_extensors", lean(n++), extensors.build());
			double c0 = b.cervical[0];
			double cm = b.cervical[b.cervical.length / 2];
			part("muscles/neck/" + side + "brachiocephalicus", "brachiocephalicus", lean(n++), strap(outward,
					v(side(s, 2.6 * k), neckY(c0 - 1.5 * k) - 1.4 * k, c0 - 1.5 * k, 1.1, 1.3),
					v(side(s, 4.8 * k), neckY(cm) - 2.4 * k, cm, 1.4, 1.6),
					r(at(s, glenoid, -1.4, 2.5, -1.5), 1.4, 1.6)));
			part("muscles/neck/" + side + "sternocephalicus", "sternocephalicus", lean(n++), strap(outward,
					v(side(s, 1.0 * k), sternumY(b.manubriumZ) + 0.4 * k, b.manubriumZ - 0.5 * k, 0.6, 0.6),
					v(side(s, 1.8 * k), neckY(cm) - 3.6 * k, cm, 0.6, 0.6),
					v(side(s, 2.6 * k), neckY(c0) - 4.4 * k, c0 - 4.5 * k, 0.6, 0.6)));

			// The legs: in the forearm the extensors in front of the radius and the flexors behind it; in the gaskin the
			// gastrocnemius behind the tibia, running into the Achilles tendon, and the extensors in front. Below the knee
			// and the hock there is no muscle, only bone, tendon and skin.
			if (b.fore != null) {
				double[] r0 = point(b.fore, "radius", 0);
				double[] r1 = point(b.fore, "radius", 1);
				double[] r2 = point(b.fore, "radius", -1);
				part("muscles/foreleg/" + side + "forearm_extensors", "forearm_extensors", lean(n++), strap(outward,
						r(at(s, r0, 0.3, 0.4, -1.0), 1.1, 1.0), r(at(s, r1, 0.2, 0, -0.85), 0.85, 0.8), r(at(s, r2, 0, 1.0, -0.6), 0.35, 0.35)));
				part("muscles/foreleg/" + side + "forearm_flexors", "forearm_flexors", lean(n++), strap(outward,
						r(at(s, r0, 0, -1.2, 1.9), 1.2, 1.1), r(at(s, r1, 0, 0, 1.45), 0.9, 0.85), r(at(s, r2, 0, 1.2, 1.0), 0.35, 0.35)));
				double[] f2 = point(b.hind, "femur", -1);
				double[] t0 = point(b.hind, "tibia", 0);
				double[] t1b = point(b.hind, "tibia", 1);
				double[] t2 = point(b.hind, "tibia", -1);
				part("muscles/hindleg/" + side + "gastrocnemius", "gastrocnemius", lean(n++), strap(outward,
						r(at(s, f2, 0, -0.8, 2.4), 1.5, 1.3), r(at(s, t1b, 0, 2.5, 2.2), 1.1, 1.0), r(at(s, t1b, 0, -3.5, 2.6), 0.45, 0.45)));
				part("muscles/hindleg/" + side + "shank_extensors", "shank_extensors", lean(n++), strap(outward,
						r(at(s, t0, 0.3, -1.2, -1.1), 0.95, 0.95), r(at(s, t1b, 0.2, 0, -0.95), 0.75, 0.75), r(at(s, t2, 0, 1.8, -0.6), 0.35, 0.35)));
			}
		}

		// What the named muscles leave between them under the fat: the many small muscles too fine to name here.
		part("muscles/other", "other_muscle", b.lean, new Shape(body.lo, body.hi, (x, y, z) -> {
			double d = body.f.at(x, y, z);
			return wall(x, y, z) < 0 || lowerLeg(legs, x, y, z) ? 1 : d + Math.max(fatDepth, VOX);
		}));
	}

	/** Halfway between two points. */
	static double[] mid(double[] a, double[] b) {
		return v((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2);
	}

	/** Whether (z, y) lies in the triangle of three (z, y) points. */
	static boolean inTriangle(double z, double y, double[] a, double[] b, double[] c) {
		double d1 = (z - b[0]) * (a[1] - b[1]) - (a[0] - b[0]) * (y - b[1]);
		double d2 = (z - c[0]) * (b[1] - c[1]) - (b[0] - c[0]) * (y - c[1]);
		double d3 = (z - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (y - a[1]);
		boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
		boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
		return !(neg && pos);
	}

	/**
	 * The outline of the whole body under its skin, as a field (negative inside): the barrel outside the cavity's wall
	 * and its muscles, the back over the loin eyes and the tops of the spines, the shoulders and the arms' tops, the
	 * thighs, the legs down to the coronets of the hooves, and the neck. Simple shapes, cheap to test at every voxel.
	 */
	static Shape outline() {
		Body b = BODY;
		double k = b.size;
		double t1 = b.thoracicZ;
		double tail = b.tail[0];
		double w = halfWidth(0) + 8 * k;
		Shape[] legs = legs();
		double ground = legs == null ? sternumY(0) - 8 * k : b.fore.toes[3].points()[1][1] - 1 * k;
		double[] lo = v(-w, ground, b.cervical[0] - 6 * k);
		double[] hi = v(w, spineY(0) + 14 * k, tail + 12 * k);
		double[] g = b.glenoid;
		double[] ac = b.acetabulum;
		Shape neck = tube().up((x, y, z) -> new double[] {0, 1, 0})
				.p(0, neckY(b.cervical[0] - 3 * k) - 0.8 * k, b.cervical[0] - 3 * k, 3.2 * k, 3.8 * k)
				.p(0, neckY(b.cervical[b.cervical.length / 2]) - 1.2 * k, b.cervical[b.cervical.length / 2], 4.4 * k, 5.6 * k)
				.p(0, spineY(t1) - 4.5 * k, t1 - 2 * k, 6.0 * k, 9.5 * k).build();
		return new Shape(lo, hi, (x, y, z) -> {
			double cover = 1.8 * k * layerStretch() + 1.0 * k;
			double d = wall(x, y, z) - cover;
			if (z < t1 - 1 * k || z > tail) {
				d = Math.max(d, z < t1 ? t1 - 1 * k - z : z - tail);
			}

			// The back, from the withers to the tail head: over the loin eyes and the tips of the spines.
			if (z > t1 - 2 * k && z < tail + 2 * k) {
				double top = spineTop(z) + 0.6 * k - (spineY(z) + 1.0 * k);
				double rx = 8.2 * k;
				double u = x / rx;
				double v = (y - spineY(z) - 1.0 * k) / Math.max(top, 2 * k);
				d = smin(d, (Math.sqrt(u * u + v * v) - 1) * Math.min(rx, Math.max(top, 2 * k)), 1.5 * k);
			}

			double ax = Math.abs(x);
			d = smin(d, ellipsoid(ax, y, z, g[0] + 0.3 * k, g[1] - 1.5 * k, g[2] + 3.5 * k, 3.4 * k, 14.0 * k, 7.5 * k), 2.0 * k);
			d = smin(d, ellipsoid(ax, y, z, ac[0] + 2.4 * k, ac[1] - 5.5 * k, ac[2] + 3.6 * k, 5.6 * k, 11.0 * k, 9.0 * k), 2.0 * k);
			if (z >= neck.lo[2] && z <= t1 + 4 * k) {
				d = smin(d, neck.f.at(x, y, z), 2.0 * k);
			}

			if (legs != null) {
				for (Shape leg : legs) {
					if (ax >= leg.lo[0] && ax <= leg.hi[0] && y >= leg.lo[1] && y <= leg.hi[1] && z >= leg.lo[2] && z <= leg.hi[2]) {
						d = smin(d, leg.f.at(ax, y, z), 1.0 * k);
					}
				}
			}

			return d;
		});
	}


	/** The right legs under their skin, as tubes from the forearm and the gaskin down to the coronet of the hooves; null without legs. */
	static Shape[] legs() {
		Body b = BODY;
		if (b.fore == null) {
			return null;
		}

		double k = b.size;
		Shape[] out = new Shape[2];
		Limb[] limbs = {b.fore, b.hind};
		String[] top = {"radius", "tibia"};
		String[] joint = {"knee_carpus", "hock_tarsus"};
		for (int i = 0; i < 2; i++) {
			Limb l = limbs[i];
			double[] a = point(l, top[i], 0);
			double[] m = point(l, top[i], 1);
			double[] kn = point(l, joint[i], 0);
			double[] c = point(l, "cannon_bone", 1);
			double[] f = point(l, "fetlock", 0);
			double[] hoof = l.toes[3].points()[0];
			double[] pastern = l.toes[1].points()[1];
			double back = i == 1 ? 0.9 * k : 0.3 * k;
			out[i] = tube().up((x, y, z) -> new double[] {1, 0, 0})
					.p(a[0], a[1], a[2] + back, 2.4 * k, 2.7 * k).p(m[0], m[1], m[2] + back * 0.6, 1.9 * k, 2.2 * k)
					.p(kn[0], kn[1], kn[2], 1.45 * k, 1.6 * k).p(c[0], c[1], c[2], 1.05 * k, 1.15 * k).p(f[0], f[1], f[2], 1.3 * k, 1.3 * k)
					.p(l.footX, hoof[1] + 0.7 * k, pastern[2], 1.2 * k, 1.0 * k).build();
		}

		return out;
	}

	/** Whether (x, y, z) is in a leg below the elbow or the stifle, where there is no fat and below the knee no muscle. */
	static boolean lowerLeg(Shape[] legs, double x, double y, double z) {
		if (legs == null) {
			return false;
		}

		double ax = Math.abs(x);
		for (int i = 0; i < 2; i++) {
			Limb l = i == 0 ? BODY.fore : BODY.hind;
			double top = point(l, i == 0 ? "radius" : "tibia", 0)[1];
			if (y < top && legs[i].in(ax, y, z)) {
				return true;
			}
		}

		return false;
	}

	/** Point {@code i} of the bone named {@code name} in limb {@code l} (from the end if negative). */
	static double[] point(Limb l, String name, int i) {
		for (Bone bone : l.upper) {
			if (bone.name().equals(name)) {
				double[][] p = bone.points();
				return p[i < 0 ? p.length + i : i];
			}
		}

		throw new IllegalArgumentException("No " + name + " in the " + l.group);
	}
	/** The highest bone or loin eye over the spine at z: the tip of the nearest spinous process, or the top of the loin. */
	static double spineTop(double z) {
		Body b = BODY;
		double k = b.size;
		double t13 = b.thoracicZ + b.thoracicStep * (b.withers.length - 1);
		if (z <= t13) {
			double f = clamp((z - b.thoracicZ) / b.thoracicStep, 0, b.withers.length - 1);
			int i = (int) Math.floor(f);
			int j = Math.min(i + 1, b.withers.length - 1);
			double h = b.withers[i] + (b.withers[j] - b.withers[i]) * (f - i);
			return spineY(z) + b.thoracicSpinous[3] + h;
		}

		return spineY(z) + Math.max(b.lumbarSpinous[b.lumbarSpinous.length - 1][1], 4.4 * k);
	}

	/** A rough signed distance to an axis-aligned ellipsoid. */
	static double ellipsoid(double x, double y, double z, double cx, double cy, double cz, double rx, double ry, double rz) {
		double u = (x - cx) / rx;
		double v = (y - cy) / ry;
		double w = (z - cz) / rz;
		return (Math.sqrt(u * u + v * v + w * w) - 1) * Math.min(rx, Math.min(ry, rz));
	}
}
