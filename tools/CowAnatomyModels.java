import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import javax.imageio.ImageIO;

/**
 * Builds Blockbench models (.bbmodel) of a cow's insides, organ by organ and all together in place, plus game-scale
 * copies fitted to the vanilla adult cow. Run from the project root with:
 * <pre>java tools/CowAnatomyModels.java art/cow_anatomy</pre>
 * Output is deterministic (fixed seeds and name-based uuids), so re-running produces identical files. Nothing under
 * {@code src/} is touched: this tool owns only {@code art/cow_anatomy*}.
 *
 * <p>Every organ is sculpted once, in centimetres, where it lies in a standing adult cow (non-pregnant female, about
 * 550 kg; veterinary topography: the rumen fills the left of the belly and crosses the midline behind, the liver is
 * pushed wholly to the right against the diaphragm, the guts hang on the right behind it, the heart lies low between
 * the forelegs two thirds to the left, the kidneys are lobulated and lie under the loin). The body frame: +x the cow's
 * right, +y up from the floor of the belly, -z towards the head, so in Blockbench the cow faces north. The shapes are
 * then cut into voxels, the voxels merged into as few cubes as will hold them, and every visible face painted from a
 * 3D texture of its tissue, one texel per voxel face (the vanilla "silver age" rule of one texel per model pixel).
 * Organs that press on each other leave impressions: where two overlap the one earlier in {@link #PARTS} keeps the
 * voxel, so the liver carries the dents of the reticulum, omasum and kidney.
 *
 * <p>Each organ is one group in the outliner; its lobes, chambers, loops and vessels are subgroups, so an organ can
 * be shown, hidden, moved or broken apart in Blockbench as one anatomical object, its pieces as its parts. Damaged
 * art reuses the same geometry: the intact form is every organ's default, an opened form is a separate export whose
 * wall is cut away cleanly to show the defining interior, a torn form is a representative ragged rupture, and the
 * heated form is an alternate texture (the same cubes repainted) rather than more geometry.
 *
 * <p>The anatomy follows Cornell's bovine website and standard veterinary sources (see the generated README);
 * proportions are modelled, not measured, and the file records which features are sourced and which are simplified.
 */
public final class CowAnatomyModels {
	/** The voxel edge being cut, in cm. Thin parts are thickened to at least about one voxel so they never vanish. */
	private static double VOX = 1.0;
	/** The least radius of a thin part, in voxels: enough to light one voxel centre, more at the mod's coarse grain. */
	private static double THIN = 0.55;
	/** How far past a shape its box reaches: past the thickening of thin parts at the coarsest grain. */
	private static final double PAD = 4.0;
	/** The height of the middle of the trunk, in cm: between the breastbone and the back. */
	private static final double YC = 38.0;
	private static final List<Part> PARTS = new ArrayList<>();

	private CowAnatomyModels() {
	}

	public static void main(String[] args) throws IOException {
		Path out = Path.of(args.length > 0 ? args[0] : "art/cow_anatomy");
		Files.createDirectories(out.resolve("textures"));
		Files.createDirectories(out.resolve("previews"));
		build();
		List<String> rows = new ArrayList<>();
		rows.add(export(out, "cow_liver", "Cow liver", 1.0, p -> p.under("abdomen/liver")));
		rows.add(export(out, "cow_heart", "Cow heart", 1.0, p -> p.under("thorax/heart")));
		rows.add(export(out, "cow_lungs", "Cow lungs and windpipe", 1.0, p -> p.under("thorax/lungs") || p.under("thorax/airway")));
		rows.add(export(out, "cow_pluck", "Cow pluck (heart, lungs, liver)", 1.0,
				p -> p.under("thorax/heart") || p.under("thorax/lungs") || p.under("thorax/airway") || p.under("abdomen/liver")));
		rows.add(export(out, "cow_kidneys", "Cow kidneys in suet", 1.0, p -> p.under("abdomen/kidneys")));
		rows.add(export(out, "cow_spleen", "Cow spleen", 1.0, p -> p.under("abdomen/spleen")));
		rows.add(export(out, "cow_stomachs", "Cow stomachs (rumen, reticulum, omasum, abomasum)", 1.0, p -> p.under("abdomen/stomach")));
		rows.add(export(out, "cow_omasum", "Cow omasum (the manyplies)", 1.0, p -> p.under("abdomen/stomach/omasum")));
		rows.add(export(out, "cow_reticulum", "Cow reticulum (the honeycomb)", 1.0, p -> p.under("abdomen/stomach/reticulum")));
		rows.add(export(out, "cow_intestines", "Cow intestines", 1.0, p -> p.under("abdomen/intestines") || p.under("abdomen/urinary")));
		rows.add(export(out, "cow_skull", "Cow skull, jaw, teeth and horns", 1.0, p -> p.under("skeleton/skull")));
		rows.add(export(out, "cow_brain", "Cow brain", 1.0, p -> p.under("head/brain")));
		rows.add(export(out, "cow_head", "Cow head (skull, jaw, teeth, horns, brain)", 1.0,
				p -> p.under("skeleton/skull") || p.under("head/brain")));
		rows.add(export(out, "cow_limbs", "Cow forelimb and hindlimb bones with cloven hooves", 1.0, p -> p.under("skeleton/limbs")));
		rows.add(export(out, "cow_skeleton", "Cow skeleton", 1.0, p -> p.under("skeleton")));
		rows.add(export(out, "cow_body_layers", "Cow body wall and muscle groups (supporting tissue)", 1.0, p -> p.under("body_wall")));
		rows.add(export(out, "cow_in_situ", "Cow anatomy in place", 1.0, p -> !p.under("body_wall")));
		List<String> stateRows = new ArrayList<>();
		for (int state : new int[] {1, 2}) {
			STATE = state;
			PARTS.clear();
			build();
			String tag = state == 1 ? "opened" : "torn";
			stateRows.add(export(out, "cow_stomachs_" + tag, "Cow stomachs " + tag, 1.0, p -> p.under("abdomen/stomach")));
			if (state == 1) {
				stateRows.add(export(out, "cow_heart_opened", "Cow heart opened (chambers in section)", 1.0, p -> p.under("thorax/heart")));
				stateRows.add(export(out, "cow_lungs_opened", "Cow lungs opened (cut surface and bronchi)", 1.0,
						p -> p.under("thorax/lungs") || p.under("thorax/airway")));
				stateRows.add(export(out, "cow_liver_opened", "Cow liver opened (cut surface)", 1.0, p -> p.under("abdomen/liver")));
				stateRows.add(export(out, "cow_kidneys_opened", "Cow kidneys opened (cortex, medulla, pelvis)", 1.0, p -> p.under("abdomen/kidneys")));
				stateRows.add(export(out, "cow_intestines_opened", "Cow intestines opened (wall, lumen, contents)", 1.0,
						p -> p.under("abdomen/intestines") || p.under("abdomen/urinary")));
			} else {
				stateRows.add(export(out, "cow_damage", "Cow damaged organs (torn and ruptured, with clean cuts beside them)", 1.0,
						p -> p.under("thorax/heart") || p.under("thorax/lungs") || p.under("abdomen/liver") || p.under("abdomen/kidneys")
								|| p.under("abdomen/intestines")));
			}
		}

		STATE = 0;
		PARTS.clear();
		build();
		Files.writeString(out.resolve("README.md"), readme(rows, stateRows), StandardCharsets.UTF_8);
		manifest(out, rows, stateRows);
		System.out.println("Wrote " + out.toAbsolutePath());
		Path art = out.toAbsolutePath().getParent();
		for (int res : new int[] {16, 32}) {
			mob(art.resolve("cow_anatomy_" + res + "x"), res);
		}
	}

	/** The smallest centimetres per output unit in force, for thickening thin parts at the coarsest grain. */
	static double minScale() {
		if (FITS == null) {
			return 1;
		}

		double s = 1e9;
		for (Fit f : FITS) {
			for (int a = 0; a < 3; a++) {
				s = Math.min(s, Math.abs(f.cm1[a] - f.cm0[a]) / (f.px1[a] - f.px0[a]));
			}
		}

		return s;
	}

	/**
	 * The grain the mod is built from: 16 would be a whole pixel per voxel, but the trunk inside its fat and muscle is only
	 * a dozen pixels across, too few to keep the organs apart from the bones; 32 cuts half pixels (the mod has always
	 * placed anatomy finer than whole pixels) and still draws with the 16x tiles, a texel a pixel.
	 */
	static final int MOD_RES = 32;

	/**
	 * One region of the vanilla cow that anatomy is fitted to: a box of model pixels and the box of centimetres it
	 * holds. The game frame is the vanilla adult cow's own ({@code CowModel.createBodyLayer}, 64x64 texture): model
	 * pixels with y measured up from the ground (so {@code y_up = 24 - y_model}), the cow's right at -X and the head
	 * towards -Z, exactly as the sheep assets are laid out. Each vanilla part is fitted on its own, the way the mod's
	 * {@code Anatomy.fit} does, so parts may step at their joints; the pixel box is the interior of the vanilla cube
	 * after the cow's fat and muscle ({@code Beast.COW}'s 0.3 px of hide and 0.6 px of cover), which is the clearance
	 * the organs keep from the skin.
	 */
	static final class Fit {
		final String name;
		/** The region's box in model pixels: {@code px0} is its lowest corner, {@code px1} its highest. */
		final double[] px0;
		final double[] px1;
		/** The centimetre point under {@link #px0} and under {@link #px1}: the game's -X is the cow's right (+x in cm). */
		final double[] cm0;
		final double[] cm1;

		Fit(String name, double[] px0, double[] px1, double[] cm0, double[] cm1) {
			this.name = name;
			this.px0 = px0;
			this.px1 = px1;
			this.cm0 = cm0;
			this.cm1 = cm1;
		}

		boolean holds(double x, double y, double z) {
			return x >= px0[0] && x <= px1[0] && y >= px0[1] && y <= px1[1] && z >= px0[2] && z <= px1[2];
		}

		double[] at(double x, double y, double z) {
			return new double[] {axis(x, 0), axis(y, 1), axis(z, 2)};
		}

		/** The model pixel of a centimetre point: the inverse of {@link #at}. */
		double[] inv(double x, double y, double z) {
			return new double[] {un(x, 0), un(y, 1), un(z, 2)};
		}

		private double axis(double v, int a) {
			return cm0[a] + (v - px0[a]) * (cm1[a] - cm0[a]) / (px1[a] - px0[a]);
		}

		private double un(double v, int a) {
			return px0[a] + (v - cm0[a]) * (px1[a] - px0[a]) / (cm1[a] - cm0[a]);
		}
	}

	/**
	 * The vanilla cow's parts and what each holds: the body cube (12x18x10 and the udder box under it), the head cube
	 * with the muzzle and horns, and the four 4x12x4 legs. Centimetres are the cow's own, so the trunk is squashed into
	 * the body about two to one across (the vanilla cow is a wide, blocky animal) and the head more so; that is fitting
	 * real anatomy to the game's silhouette and is said so in the README.
	 */
	static final Fit[] GAME_FITS = {
			new Fit("body", new double[] {-5.1, 12.9, -8}, new double[] {5.1, 21.1, 10}, new double[] {25.5, 2, -80}, new double[] {-25.5, 76, 80}),
			new Fit("head", new double[] {-3.4, 16.6, -15}, new double[] {3.4, 23.4, -8}, new double[] {13, 44, -152}, new double[] {-13, 84, -80}),
			new Fit("right_front_leg", new double[] {-5.15, 0, -6.15}, new double[] {-2.85, 11.15, -3.85}, new double[] {22, 2, -42}, new double[] {4, -70, -62}),
			new Fit("left_front_leg", new double[] {2.85, 0, -6.15}, new double[] {5.15, 11.15, -3.85}, new double[] {-22, 2, -42}, new double[] {-4, -70, -62}),
			new Fit("right_hind_leg", new double[] {-5.15, 0, 5.85}, new double[] {-2.85, 11.15, 8.15}, new double[] {22, 2, 42}, new double[] {4, -70, 62}),
			new Fit("left_hind_leg", new double[] {2.85, 0, 5.85}, new double[] {5.15, 11.15, 8.15}, new double[] {-22, 2, 42}, new double[] {-4, -70, 62})};

	/** The fits in force: null in the full-size files, where one voxel is one centimetre and nothing is fitted. */
	static Fit[] FITS;

	/** The body's centimetres of an output point, or null where no region of the vanilla cow holds it. */
	static double[] cmAt(double x, double y, double z) {
		if (FITS == null) {
			return new double[] {x, y, z};
		}

		for (Fit f : FITS) {
			if (f.holds(x, y, z)) {
				return f.at(x, y, z);
			}
		}

		return null;
	}

	/**
	 * The same anatomy at the scale of the game: squashed into the vanilla adult cow's own cubes, inside its fat and
	 * muscle (as the mod's {@code Region.BEAST_TRUNK} and the leg regions lay them), cut into voxels of 16 /
	 * {@code res} of a pixel and drawn with the mod's own anatomy.png, so Blockbench shows what the game draws. At
	 * {@link #MOD_RES} it also writes every cube's canonical coordinates into the piece table: this tool never writes
	 * into {@code src/}; {@code tools/ExportCowAnatomy.ps1} generates the mod's {@code CowTrunk} from the piece tables.
	 */
	static void mob(Path out, int res) throws IOException {
		Files.createDirectories(out.resolve("previews"));
		double vox = 16.0 / res;
		FITS = GAME_FITS;
		LOD = vox * 8.9;
		THIN = res <= 16 ? 1.15 : 0.75;
		TILES = ImageIO.read(Path.of("src/main/resources/assets/fluidgore/textures/misc/anatomy.png").toFile());
		PARTS.clear();
		build();
		List<String> rows = new ArrayList<>();
		String r = res + "x";
		rows.add(export(out, "cow_trunk_" + r, "Cow trunk at " + r + " (as the mod draws it)", vox,
				p -> !p.under("skeleton/skull") && !p.under("head/brain") && !p.under("skeleton/limbs") && !p.under("skeleton/spine/cervical")
						&& !p.under("body_wall")));
		rows.add(export(out, "cow_organs_" + r, "Cow organs at " + r + " (skeleton and diaphragm left out)", vox,
				p -> !p.under("skeleton") && !p.under("thorax/diaphragm") && !p.under("body_wall")));
		rows.add(export(out, "cow_liver_" + r, "Cow liver at " + r, vox, p -> p.under("abdomen/liver")));
		rows.add(export(out, "cow_heart_lungs_" + r, "Cow heart and lungs at " + r, vox,
				p -> p.under("thorax/heart") || p.under("thorax/lungs") || p.under("thorax/airway")));
		rows.add(export(out, "cow_stomachs_" + r, "Cow stomachs at " + r, vox, p -> p.under("abdomen/stomach")));
		rows.add(export(out, "cow_guts_" + r, "Cow intestines, kidneys, spleen, bladder at " + r, vox,
				p -> p.under("abdomen/intestines") || p.under("abdomen/kidneys") || p.under("abdomen/spleen") || p.under("abdomen/urinary")));
		rows.add(export(out, "cow_head_" + r, "Cow head and neck at " + r, vox,
				p -> p.under("skeleton/skull") || p.under("head/brain") || p.under("skeleton/spine/cervical")));
		rows.add(export(out, "cow_limbs_" + r, "Cow limb bones at " + r, vox, p -> p.under("skeleton/limbs")));
		rows.add(export(out, "cow_skeleton_" + r, "Cow skeleton at " + r, vox, p -> p.under("skeleton")));
		rows.add(export(out, "cow_whole_" + r, "Cow whole animal at " + r, vox, p -> !p.under("body_wall")));
		Files.writeString(out.resolve("README.md"), mobReadme(r, rows), StandardCharsets.UTF_8);
		// Both resolutions are inputs to the runtime exporter: 32x in the body, 16x for oversized loose organs.
		Files.writeString(out.resolve("piece_table.json"), pieceTable(vox), StandardCharsets.UTF_8);
		System.out.println("Wrote " + out.resolve("piece_table.json").toAbsolutePath());
		if (res == MOD_RES) {
			Files.writeString(out.resolve("voxel_report.json"), voxelReport(vox), StandardCharsets.UTF_8);
			System.out.println("Wrote " + out.resolve("voxel_report.json").toAbsolutePath());
		}

		FITS = null;
		TILES = null;
		THIN = 0.55;
		LOD = 0.5;
	}

	/**
	 * The geometric overlap check at the mod's own grain: for every organ, how many voxel centres lie inside its shapes
	 * at all ({@code inside}) and how many it kept after the rule that the first part in the build order wins a shared
	 * voxel ({@code won}). Contact between organs is anatomical and intentional; what this catches is an organ that some
	 * other shape has squeezed out of existence, which the art must never ship.
	 */
	static String voxelReport(double vox) {
		Grid g = voxelize(vox, p -> !p.under("body_wall"));
		Map<String, long[]> counts = new java.util.TreeMap<>();
		for (int i = 0; i < g.nx; i++) {
			for (int j = 0; j < g.ny; j++) {
				for (int k = 0; k < g.nz; k++) {
					double[] c = g.cm(i, j, k);
					if (c == null) {
						continue;
					}

					List<String> in = new ArrayList<>();
					String winner = null;
					for (Part p : PARTS) {
						if (p.under("body_wall")) {
							continue;
						}

						if (p.shape.in(c[0], c[1], c[2])) {
							in.add(modPiece(p)[1]);
							if (winner == null) {
								winner = modPiece(p)[1];
							}
						}
					}

					for (String s : new java.util.LinkedHashSet<>(in)) {
						counts.computeIfAbsent(s, q -> new long[2])[0]++;
					}

					if (winner != null) {
						counts.get(winner)[1]++;
					}
				}
			}
		}

		StringBuilder sb = new StringBuilder("{\n");
		boolean first = true;
		for (Map.Entry<String, long[]> e : counts.entrySet()) {
			if (!first) {
				sb.append(",\n");
			}

			first = false;
			sb.append("  \"").append(e.getKey()).append("\": {\"inside\": ").append(e.getValue()[0]).append(", \"won\": ")
					.append(e.getValue()[1]).append(", \"lost_to_priority\": ").append(e.getValue()[0] - e.getValue()[1]).append('}');
		}

		return sb.append("\n}\n").toString();
	}

	/**
	 * Every cube of the game-scale animal in the mod's own canonical pixels, as JSON for the piece table: the
	 * trunk in {@code Anatomy.Region#BEAST_TRUNK}'s frame (10 across for the body's 12, 8 down for its 10, 16 along for
	 * its 18; the body's right at -x, the back at -y, the chest at -z) and the head and limbs in their regions' frames
	 * the same way. Geometry and anatomical metadata only: no thresholds, densities or burn rates live here.
	 */
	static String pieceTable(double vox) {
		Grid g = voxelize(vox, p -> !p.under("body_wall"));
		List<Box> boxes = boxes(g);
		StringBuilder sb = new StringBuilder();
		sb.append("[\n");
		boolean first = true;
		for (Box b : boxes) {
			Part p = PARTS.get(b.key - 1);
			String[] mp = modPiece(p);
			double x0 = g.ox + b.x0 * g.vox;
			double x1 = g.ox + b.x1 * g.vox;
			double y0 = g.oy + b.y0 * g.vox;
			double y1 = g.oy + b.y1 * g.vox;
			double z0 = g.oz + b.z0 * g.vox;
			double z1 = g.oz + b.z1 * g.vox;
			if (!first) {
				sb.append(",\n");
			}

			first = false;
			sb.append("  {\"kind\":\"").append(mp[0]).append("\",\"name\":\"").append(mp[1]).append("\",\"group\":\"").append(p.path)
					.append("\",\"px\":[").append(num(x0)).append(',').append(num(y0)).append(',').append(num(z0)).append(',')
					.append(num(x1)).append(',').append(num(y1)).append(',').append(num(z1)).append("],\"canonical\":[")
					.append(num(x0 * 5.0 / 6.0)).append(',').append(num(-(y1 - 17) * 0.8)).append(',').append(num((z0 - 1) * 8.0 / 9.0)).append(',')
					.append(num(x1 * 5.0 / 6.0)).append(',').append(num(-(y0 - 17) * 0.8)).append(',').append(num((z1 - 1) * 8.0 / 9.0)).append("]}");
		}

		sb.append("\n]\n");
		return sb.toString();
	}

	// ------------------------------------------------------------------------------------------------ the body frame

	/**
	 * The frame every organ is sculpted in: centimetres, +x the cow's right, +y up from the floor of the belly, -z towards
	 * the head, so in Blockbench the cow faces north. The animal is an adult, non-pregnant dairy-type cow of about 550 kg:
	 * the trunk is about 160 cm from the chest's entrance to the pelvis, 72 cm deep from the breastbone to the back, and
	 * 50 cm wide at the barrel (veterinary proportions; nothing here is measured from a particular animal). The legs are
	 * sculpted below the belly floor (y &lt; 0) and the neck and skull in front of the chest (z &lt; -80).
	 *
	 * <p>Where a feature is uncertain at this resolution it is simplified deliberately and said so in the generated
	 * README; the shapes are meant to read correctly at a glance, not to be measured.
	 */

	/** The half width of the trunk: narrow at the chest's entrance between the forelegs, a deep barrel behind, narrowing again at the pelvis. */
	static double halfWidth(double z) {
		return curve(z, -80, 7.0, -74, 11.5, -66, 16.5, -56, 21.0, -44, 23.8, -30, 25.2, -12, 25.8, 6, 25.9, 24, 25.4, 42, 24.0, 56, 21.5, 68, 16.5,
				78, 10.0);
	}

	/** The height of the breastbone, rising steeply towards the entrance of the chest. */
	static double sternumY(double z) {
		return curve(z, -80, 26.0, -74, 19.5, -66, 12.0, -56, 7.5, -44, 4.6, -30, 3.0, -12, 2.3, 8, 2.0, 30, 2.2, 52, 3.0, 68, 5.0);
	}

	/** The height of the middle of the vertebral bodies: the withers high over the forelegs, the loin level, the sacrum falling away to the tail. */
	static double spineY(double z) {
		return curve(z, -80, 66.5, -72, 71.0, -60, 73.5, -44, 75.0, -24, 74.2, -4, 74.8, 18, 75.8, 38, 75.6, 52, 73.8, 66, 70.5, 80, 65.0, 90, 57.0);
	}

	/** The height of the middle of the cervical vertebral bodies: the neck carried forward, level with the withers at its root and a little lower at the skull. */
	static double neckY(double z) {
		return curve(z, -140, 58.0, -128, 62.0, -114, 66.5, -100, 70.0, -88, 73.0, -80, 74.5);
	}

	/**
	 * Where the diaphragm crosses (x, y): a dome bulging forward into the chest, its top under the 8th-9th rib, its edge
	 * sloping back so it meets the spine far behind where it meets the breastbone. The right cupula stands a little
	 * further forward than the left, which the rumen leans on.
	 */
	static double diaphragmZ(double x, double y) {
		double ry = y < YC ? sq((y - YC) / 24.0) * 8.0 : sq((y - YC) / 30.0) * 9.0;
		return -30.0 + 0.45 * (y - YC) + ry + 16.0 * sq(x / 26.0) - 2.5 * (x / 26.0);
	}

	/** Piecewise linear through (x, y) pairs, flat beyond the ends. */
	static double curve(double x, double... xy) {
		if (x <= xy[0]) {
			return xy[1];
		}

		for (int i = 2; i < xy.length; i += 2) {
			if (x <= xy[i]) {
				double t = (x - xy[i - 2]) / (xy[i] - xy[i - 2]);
				return xy[i - 1] + (xy[i + 1] - xy[i - 1]) * t;
			}
		}

		return xy[xy.length - 1];
	}

	// --------------------------------------------------------------------------------------------------- the anatomy

	/** The art state being sculpted: 0 intact (every organ closed), 1 opened by a clean cut, 2 torn by a ragged rupture. */
	static int STATE = 0;

	private static void build() {
		Shape heart = heart();
		airways();
		thoracicVessels();
		kidneys();
		stomachs();
		liver();
		spleen();
		intestines();
		urinary();
		udder();
		diaphragm();
		lungs(heart);
		head();
		skeleton();
		limbs();
		bodyLayers();
		// The skeleton was added last but must win every overlap: move it to the front.
		List<Part> bones = new ArrayList<>();
		List<Part> rest = new ArrayList<>();
		for (Part p : PARTS) {
			(p.path.startsWith("skeleton") ? bones : rest).add(p);
		}

		PARTS.clear();
		PARTS.addAll(bones);
		PARTS.addAll(rest);
	}

	/**
	 * The heart: a blunt cone about 30 cm long, its base up and forward under the windpipe, its apex down, back and to
	 * the left, lying between the 3rd and 6th ribs with two thirds of it left of the midline. A cow's heart wears a thick
	 * white collar of fat in the coronary groove where the atria meet the ventricles, and fat down the paraconal groove
	 * over the septum on the left and the subsinuosal groove on the right. The ear-shaped auricles fold forward round the
	 * roots of the aorta and the pulmonary trunk. Returns the heart's outline, which the lungs keep clear of.
	 */
	private static Shape heart() {
		double[] apex = {-6.5, 11.5, -46.5};
		double[] base = {-3.0, 33.5, -60.5};
		double[] axis = norm(sub(base, apex));
		double len = dist(base, apex);
		Tube vent = tube().up((x, y, z) -> new double[] {0, 0, 1});
		double[][] prof = {{0, 1.8}, {0.1, 5.4}, {0.25, 8.6}, {0.45, 10.8}, {0.62, 11.6}, {0.8, 10.8}};
		for (double[] pr : prof) {
			double[] p = add(apex, mul(axis, pr[0] * len));
			vent.p(p[0], p[1], p[2], pr[1], pr[1] * 0.85);
		}

		Shape ventricles = vent.build();
		double[] groove = add(apex, mul(axis, 0.77 * len));
		Shape collar = lumpy(torus(groove, axis, 10.2, 2.7), 0.9, 0.75, 11);
		double[] atria = add(apex, mul(axis, 0.86 * len));
		Shape rightAtrium = lumpy(ell(atria[0] + 4.6, atria[1] + 0.5, atria[2] - 1.6, 6.2, 5.2, 6.2), 0.35, 0.85, 12);
		Shape leftAtrium = lumpy(ell(atria[0] - 3.2, atria[1] + 1.7, atria[2] + 4.0, 6.2, 4.6, 5.4), 0.35, 0.85, 13);
		Shape rightAuricle = lumpy(ell(atria[0] + 4.3, atria[1] - 3.2, atria[2] - 7.0, 4.6, 3.2, 3.0), 0.7, 1.25, 14);
		Shape leftAuricle = lumpy(ell(atria[0] - 8.1, atria[1] - 2.4, atria[2] - 2.2, 2.7, 3.5, 5.2), 0.7, 1.25, 15);
		// Fat down the paraconal groove (left, over the septum) and the subsinuosal groove (right, behind).
		Shape paraconal = lumpy(grooveTube(apex, axis, len, new double[] {-1, 0, -0.5}, 0.76, 0.18, prof), 0.4, 1.15, 16);
		Shape subsinuosal = lumpy(grooveTube(apex, axis, len, new double[] {1, 0, 0.6}, 0.76, 0.35, prof), 0.4, 1.15, 17);
		Mat fat = organic(0xECDCAD, 0xD6C188, 0xF7EED2, 0.75, 21);
		part("thorax/heart/coronary_fat", "coronary_groove", fat, collar);
		part("thorax/heart/coronary_fat", "paraconal_groove", fat, paraconal);
		part("thorax/heart/coronary_fat", "subsinuosal_groove", fat, subsinuosal);
		// The great vessels, cut where the heart is taken out: the aorta at the top of its arch, the brachiocephalic
		// trunk (long in a cow) past its fork, the pulmonary trunk past its fork, the venae cavae a hand's width out.
		Mat artery = organic(0xD8A99D, 0xC08F84, 0xE8C4B8, 0.55, 22);
		Mat vein = organic(0x4D2E4B, 0x3A2038, 0x62405E, 0.55, 23);
		part("thorax/heart/aorta", "aortic_root_and_arch", artery, tube().hollow(0.55).flat(false, true)
				.p(-2.4, 31.5, -59.5, 3.1).p(-2.2, 39.5, -57.5, 3.1).p(-2.4, 47.5, -53.5, 3.0).p(-3.2, 53.5, -47.5, 2.9).build());
		part("thorax/heart/aorta", "brachiocephalic_trunk", artery, tube().hollow(0.5).flat(false, true)
				.p(-2.4, 45.5, -55.5, 1.9).p(-1.6, 51.5, -63.5, 1.9).p(-1.4, 52.5, -68.5, 1.8).build());
		part("thorax/heart/pulmonary_trunk", "pulmonary_trunk", artery, tube().hollow(0.55)
				.p(-6.6, 29.5, -63.5, 2.8).p(-7.9, 38.5, -59.5, 2.8).p(-6.4, 43.5, -53.0, 2.7).p(-3.8, 43.0, -49.0, 2.5).build());
		part("thorax/heart/pulmonary_trunk", "left_pulmonary_artery", artery, tube().hollow(0.5).flat(false, true)
				.p(-3.8, 43.0, -49.0, 2.1).p(-10.5, 42.0, -47.0, 2.0).build());
		part("thorax/heart/pulmonary_trunk", "right_pulmonary_artery", artery, tube().hollow(0.5).flat(false, true)
				.p(-3.8, 43.0, -49.0, 2.1).p(4.2, 43.5, -48.0, 2.0).build());
		part("thorax/heart/venae_cavae", "cranial_vena_cava", vein, tube().hollow(0.6).flat(false, true)
				.p(3.8, 36.5, -63.5, 2.7).p(4.3, 42.5, -73.0, 2.7).p(4.3, 43.5, -77.5, 2.6).build());
		part("thorax/heart/venae_cavae", "caudal_vena_cava", vein, tube().hollow(0.6).flat(false, true)
				.p(4.9, 30.5, -57.5, 2.8).p(6.5, 31.5, -51.0, 2.8).build());
		Mat auricle = organic(0x5A1616, 0x431010, 0x702222, 1.0, 24);
		part("thorax/heart/right_auricle", "right_auricle", auricle, rightAuricle);
		part("thorax/heart/left_auricle", "left_auricle", auricle, leftAuricle);
		Mat atrium = organic(0x641B1B, 0x4E1414, 0x7A2724, 0.65, 25);
		part("thorax/heart/right_atrium", "right_atrium", atrium, rightAtrium);
		part("thorax/heart/left_atrium", "left_atrium", atrium, leftAtrium);
		Shape hole = STATE == 2 ? hole(-9.5, 13.5, -47.5, 5.5, 6.0, 5.5, 195) : null;
		part("thorax/heart/ventricles", "ventricles", heartMuscle(apex, axis),
				STATE == 1 ? cut(ventricles, heartCut()) : hole == null ? ventricles : cut(ventricles, hole));
		if (STATE == 1) {
			heartOpened(apex, axis, len, ventricles);
		} else if (STATE == 2) {
			Mat raw = organic(0x8E322C, 0x6E201C, 0xB0554C, 1.0, 198);
			Mat blood = organic(0x6E1A16, 0x4E100E, 0x8E2A22, 1.1, 199);
			torn("thorax/heart", ventricles, hole, raw, blood);
		}

		return union(0.5, ventricles, collar, rightAtrium, leftAtrium, rightAuricle, leftAuricle);
	}

	/** The clean cut that opens the heart: everything on the right of this plane is removed and rebuilt as a section. */
	static Shape heartCut() {
		return region(v(-30, 0, -80), v(20, 50, -30), (x, y, z) -> x > -4.2);
	}

	/**
	 * The heart opened by a clean cut down the left border: the thick muscle wall of the left ventricle beside the
	 * thinner right, the septum between them, the two atria above and the papillary muscles with their chordae in the
	 * left ventricle (Cornell's cow-heart dissection names these; at this grain they survive as a few columns).
	 */
	private static void heartOpened(double[] apex, double[] axis, double len, Shape ventricles) {
		Mat cutFlesh = organic(0x8A3A32, 0x6E2A24, 0xA85A4E, 0.9, 26);
		Mat chamber = organic(0x4A1614, 0x37100F, 0x62221E, 1.1, 27);
		Mat valve = organic(0xE6DCCB, 0xD2C6B0, 0xF4EEE2, 1.2, 28);
		Shape half = heartCut();
		Shape wall = cut(ventricles, ell(apex[0] + 0.6, apex[1] + 0.6, apex[2] + 0.6, 8.0, 9.0, 8.0));
		part("thorax/heart/state_opened/section", "cut_muscle", cutFlesh, both(wall, half));
		Shape right = lumpy(ell(apex[0] + 4.6, apex[1] + 12, apex[2] + 1.0, 4.2, 6.5, 4.2), 0.3, 1.0, 29);
		Shape left = lumpy(ell(apex[0] - 3.2, apex[1] + 13, apex[2] + 2.2, 3.2, 7.0, 3.4), 0.3, 1.0, 30);
		part("thorax/heart/state_opened/chambers", "right_ventricle", chamber, both(right, half));
		part("thorax/heart/state_opened/chambers", "left_ventricle", chamber, both(left, half));
		part("thorax/heart/state_opened/chambers", "septum", cutFlesh, both(ell(apex[0] + 0.6, apex[1] + 12.5, apex[2] + 1.6, 1.6, 7.0, 3.2), half));
		for (int i = 0; i < 3; i++) {
			double s = i - 1;
			part("thorax/heart/state_opened/chambers", "papillary_muscle", cutFlesh,
					both(ell(apex[0] - 3.0 + s * 1.6, apex[1] + 7 + Math.abs(s), apex[2] + 2.4 + s * 1.2, 1.0, 2.6, 1.0), half));
			part("thorax/heart/state_opened/chambers", "chordae_tendineae", valve,
					both(ell(apex[0] - 3.0 + s * 1.6, apex[1] + 11, apex[2] + 2.4 + s * 1.2, 1.2, 1.6, 1.2), half));
		}

		part("thorax/heart/state_opened/chambers", "atrioventricular_valve", valve,
				both(torus(add(apex, mul(axis, 0.72 * len)), axis, 4.2, 1.1), half));
	}

	/** A fat-filled groove running down the heart's surface from the coronary groove towards the apex. */
	private static Shape grooveTube(double[] apex, double[] axis, double len, double[] side, double t0, double t1, double[][] prof) {
		double[] s = norm(sub(side, mul(axis, dot(side, axis))));
		Tube t = tube();
		for (int i = 0; i <= 6; i++) {
			double f = t0 + (t1 - t0) * i / 6.0;
			double r = curve(f, prof[0][0], prof[0][1], prof[1][0], prof[1][1], prof[2][0], prof[2][1], prof[3][0], prof[3][1],
					prof[4][0], prof[4][1], prof[5][0], prof[5][1]);
			double[] p = add(add(apex, mul(axis, f * len)), mul(s, r * 0.95));
			t.p(p[0], p[1], p[2], 1.35 + 0.6 * (f - t1) / (t0 - t1));
		}

		return t.build();
	}

	/**
	 * The windpipe: about 25 rings to the inch in life, drawn as open rings here, with the extra bronchus a ruminant
	 * sends to the right cranial lobe of the lung; the gullet lies above it in the neck and the chest.
	 */
	private static void airways() {
		Mat trachea = (x, y, z, f, ax, ay, az) -> {
			boolean ring = frac(z * 0.42 + 0.15 * noise(x, y, z * 0.2, 31)) < 0.62;
			double top = curve(z, -132, 62.5, -110, 66.5, -90, 71.0, -72, 73.5, -58, 72.0) + 1.9;
			int c = y > top && Math.abs(x - 0.7) < 1.5 ? 0xC48682 : ring ? 0xE8E1D6 : 0xD39C98;
			return mix(c, 0xB98A86, noise(x * 1.4, y * 1.4, z * 1.4, 32) * 0.25);
		};
		part("thorax/airway/trachea", "trachea", trachea, tube().flat(true, false)
				.p(0.7, 62.5, -132, 2.7).p(0.7, 66.5, -110, 2.7).p(0.6, 71.0, -90, 2.6).p(0.0, 72.5, -72, 2.6).p(-0.2, 72.0, -58, 2.5).build());
		part("thorax/airway/trachea", "tracheal_bronchus", trachea, tube()
				.p(0.4, 71.5, -78, 1.5).p(4.6, 69.5, -80, 1.5).p(8.6, 66.5, -82, 1.4).build());
		part("thorax/airway/bronchi", "left_principal_bronchus", trachea, tube()
				.p(-0.2, 72.0, -58, 1.9).p(-5.2, 70.5, -53, 1.8).p(-9.6, 69.0, -46, 1.7).build());
		part("thorax/airway/bronchi", "right_principal_bronchus", trachea, tube()
				.p(-0.2, 72.0, -58, 1.9).p(5.2, 70.5, -53, 1.8).p(9.6, 69.0, -46, 1.7).build());
		Mat gullet = streaked(0xC99089, 0xB0766F, 0xDCA9A2, 33);
		part("thorax/esophagus", "thoracic_esophagus", gullet, tube().flat(true, true)
				.p(-1.8, 70.5, -132, 1.9).p(-1.8, 71.5, -110, 1.9).p(-2.0, 73.5, -88, 1.9).p(-2.6, 73.0, -66, 1.9)
				.p(-3.6, 69.5, -48, 1.9).p(-4.6, 62.0, -36, 1.9).build());
	}

	/** The aorta along under the spine and the venae cavae, between the organs that keep their own stubs. */
	private static void thoracicVessels() {
		Mat artery = organic(0xD8A99D, 0xC08F84, 0xE8C4B8, 0.55, 22);
		Mat vein = organic(0x4D2E4B, 0x3A2038, 0x62405E, 0.55, 23);
		part("vessels/aorta", "descending_aorta", artery, tube().hollow(0.55).flat(true, false)
				.p(-3.2, 53.5, -47.5, 2.9).p(-3.6, 63.5, -38, 2.9).p(-3.8, 66.5, -26, 2.9).p(-3.8, 67.5, -4, 2.8)
				.p(-3.6, 67.5, 18, 2.7).p(-3.2, 66.5, 38, 2.5).p(-2.6, 64.5, 54, 2.2).build());
		part("vessels/aorta", "brachiocephalic_trunk", artery, tube().hollow(0.5).flat(true, true)
				.p(-1.4, 52.5, -68.5, 1.8).p(-1.2, 51.5, -78, 1.8).p(-1.0, 51.0, -92, 1.7).p(-0.6, 51.5, -108, 1.6).build());
		part("vessels/venae_cavae", "cranial_vena_cava", vein, tube().hollow(0.6).flat(true, true)
				.p(4.3, 43.5, -77.5, 2.6).p(4.2, 47.5, -92, 2.6).p(3.8, 50.5, -108, 2.5).build());
		part("vessels/venae_cavae", "thoracic_caudal_vena_cava", vein, tube().flat(true, true)
				.p(6.5, 31.5, -51.0, 2.8).p(8.2, 32.5, -40, 2.8).p(9.2, 34.5, -32, 2.8).build());
		part("vessels/venae_cavae", "abdominal_caudal_vena_cava", vein, tube().flat(true, false)
				.p(13.2, 59.5, -4, 2.7).p(7.2, 62.5, 10, 2.7).p(6.4, 62.5, 32, 2.6).p(5.0, 60.5, 52, 2.3).build());
	}

	/**
	 * The kidneys: a cow's are lobulated (the fetal lobes stay visible on the surface, unlike a sheep's smooth beans),
	 * dark red-brown, the hilus on the inner border. The right one is round to oval and lies far forward under the last
	 * ribs against the liver's caudate process; the left is heart-shaped (cordiform) and, pushed over by the rumen, lies
	 * further back just right of the midline where it can be felt from the right flank only. Both are packed in suet.
	 */
	private static Shape[] kidneys() {
		Mat kidney = glossy(0x5F2420, 0x4A1916, 0x733129, 0x8E4C46, 0.7, 41);
		Mat lobe = glossy(0x6B2A24, 0x521E1A, 0x82392F, 0x9C5B52, 0.9, 42);
		Shape right = lobulated(new double[] {13.5, 58.5, 6.0}, new double[] {6.5, 5.5, 8.5}, 7, 45);
		Shape left = lobulated(new double[] {4.5, 57.5, 26.0}, new double[] {7.5, 5.2, 7.5}, 8, 46);
		// The surface lobes: shallow grooves between the fused lobes, the cow's own kidney texture (a sheep has none).
		Shape hole = STATE == 2 ? hole(13.5, 55.0, -1.5, 5.5, 5.5, 5.5, 190) : null;
		for (int s = -1; s <= 1; s += 2) {
			double[] c = s > 0 ? new double[] {13.5, 58.5, 6.0} : new double[] {4.5, 57.5, 26.0};
			double[] r = s > 0 ? new double[] {6.5, 5.5, 8.5} : new double[] {7.5, 5.2, 7.5};
			for (int i = 0; i < 6; i++) {
				double a = i * Math.PI / 3 + (s > 0 ? 0.3 : 0.8);
				Shape l = lumpy(ell(c[0] + r[0] * 0.42 * Math.cos(a), c[1] + r[1] * 0.5 * Math.sin(a), c[2] + r[2] * 0.55 * Math.sin(a + 1.1),
						r[0] * 0.42, r[1] * 0.36, r[2] * 0.42), 0.35, 1.1, 47 + i);
				part("abdomen/kidneys/" + (s > 0 ? "right" : "left") + "_kidney", "surface_lobe", lobe, hole == null ? l : cut(l, hole));
			}
		}

		part("abdomen/kidneys/right_kidney", "kidney", kidney, STATE == 1 ? both(right, region(right.lo, right.hi, (x, y, z) -> x > 13.1))
				: hole == null ? right : cut(right, hole));
		part("abdomen/kidneys/left_kidney", "kidney", kidney, STATE == 1 ? both(left, region(left.lo, left.hi, (x, y, z) -> x > 4.1)) : left);
		Mat artery = organic(0xD8A99D, 0xC08F84, 0xE8C4B8, 0.55, 22);
		Mat vein = organic(0x4D2E4B, 0x3A2038, 0x62405E, 0.55, 23);
		Mat ureter = organic(0xE2C8B4, 0xCDB09A, 0xF0DDCD, 0.7, 52);
		part("abdomen/kidneys/right_kidney", "renal_vein", vein, tube().flat(false, true).p(8.6, 59.5, 5.2, 1.1).p(5.2, 61.5, 3.8, 1.1).build());
		part("abdomen/kidneys/right_kidney", "renal_artery", artery, tube().flat(false, true).p(8.6, 58.0, 6.8, 0.75).p(3.8, 61.5, 7.2, 0.75).build());
		part("abdomen/kidneys/right_kidney", "ureter", ureter, tube().flat(false, true).p(8.8, 57.5, 6.4, 0.75).p(7.2, 56.5, 14, 0.75).build());
		part("abdomen/kidneys/left_kidney", "renal_vein", vein, tube().flat(false, true).p(0.4, 58.5, 25.2, 1.05).p(3.8, 63.5, 22.6, 1.05).build());
		part("abdomen/kidneys/left_kidney", "renal_artery", artery, tube().flat(false, true).p(0.2, 57.5, 26.4, 0.75).p(-2.8, 63.5, 25.6, 0.75).build());
		part("abdomen/kidneys/left_kidney", "ureter", ureter, tube().flat(false, true).p(0.4, 57.0, 26.2, 0.75).p(0.0, 55.5, 34, 0.75).build());
		Mat suet = crumbly(0xF1E9D4, 0xDDD0B0, 0xFBF7EC, 53);
		Shape rightSuet = lumpy(ell(13.2, 58.5, 6.0, 9.5, 7.5, 12.5), 0.9, 0.5, 54);
		// The right kidney's suet is peeled back from its lateral and ventral face, the way a butcher shows a kidney in
		// its fat: the lobulated surface is the point, so it must be seen.
		Shape peeled = region(v(0, 40, -12), v(30, 72, 26), (x, y, z) -> x > 11.6 || y < 55.6);
		part("abdomen/kidneys/right_kidney", "suet", suet, cut(rightSuet, peeled));
		part("abdomen/kidneys/left_kidney", "suet", suet, lumpy(ell(4.2, 57.8, 26.0, 8.8, 7.0, 11.5), 0.9, 0.5, 55));
		if (STATE == 1) {
			kidneysOpened(right, left);
		} else if (STATE == 2) {
			Mat raw = organic(0x8E322C, 0x6E201C, 0xB0554C, 1.0, 191);
			Mat blood = organic(0x6E1A16, 0x4E100E, 0x8E2A22, 1.1, 192);
			torn("abdomen/kidneys/right_kidney", right, hole, raw, blood);
		}

		return new Shape[] {right, left};
	}

	/** A cluster of fused lobes: the bovine kidney's own shape, grooved where the lobes meet. */
	static Shape lobulated(double[] c, double[] r, int n, int seed) {
		Shape[] s = new Shape[n + 1];
		s[0] = ell(c[0], c[1], c[2], r[0] * 0.62, r[1] * 0.62, r[2] * 0.62);
		for (int i = 0; i < n; i++) {
			double a = i * 2 * Math.PI / n + 0.4;
			s[i + 1] = lumpy(ell(c[0] + r[0] * 0.46 * Math.cos(a), c[1] + r[1] * 0.52 * Math.sin(a), c[2] + r[2] * 0.52 * Math.sin(a + 0.7),
					r[0] * 0.42, r[1] * 0.38, r[2] * 0.44), 0.5, 0.9, seed + i);
		}

		return union(2.2, s);
	}

	/** A kidney cut lengthways: the cortex's dark rim, the medullary pyramids with their papillae, and the wide pelvis. */
	private static void kidneysOpened(Shape right, Shape left) {
		Mat cortex = glossy(0x6B241C, 0x521812, 0x87362A, 0x9C5B52, 0.8, 56);
		Mat medulla = organic(0xB06A52, 0x93523E, 0xC68872, 1.0, 57);
		Mat pelvis = organic(0xE4D6C2, 0xCFBFA6, 0xF2E9D8, 1.1, 58);
		for (int s = -1; s <= 1; s += 2) {
			Shape k = s > 0 ? right : left;
			String g = "abdomen/kidneys/" + (s > 0 ? "right" : "left") + "_kidney";
			double[] c = s > 0 ? new double[] {13.5, 58.5, 6.0} : new double[] {4.5, 57.5, 26.0};
			// The cut face: cortex round the rim, pyramids pointing at the pelvis on the inner border. The pyramids and
			// the pelvis are laid down first, so the cortex fills what is left of the section around them.
			for (int i = 0; i < 5; i++) {
				double a = -1.1 + i * 0.55;
				part(g + "/state_opened/section", "renal_pyramid", medulla,
						both(tri(v(c[0] - 0.2, c[1] + 4.4 * Math.sin(a), c[2] + 5.2 * Math.cos(a)),
								v(c[0] - 0.2, c[1] + 1.2 * Math.sin(a), c[2] + 1.4 * Math.cos(a)),
								v(c[0] - 0.2, c[1] - 0.4, c[2] - 1.0), 1.1),
								region(v(c[0] - 14, c[1] - 10, c[2] - 14), v(c[0] + 14, c[1] + 10, c[2] + 14), (x, y, z) -> x < c[0] - 0.4)));
			}

			part(g + "/state_opened/section", "renal_pelvis", pelvis,
					both(lumpy(ell(c[0] + 3.2, c[1] - 0.6, c[2] - 0.4, 2.2, 2.6, 3.2), 0.4, 1.2, 59),
							region(v(c[0] - 14, c[1] - 10, c[2] - 14), v(c[0] + 14, c[1] + 10, c[2] + 14), (x, y, z) -> x < c[0] - 0.4)));
			part(g + "/state_opened/section", "cut_cortex", cortex, both(k, region(v(c[0] - 14, c[1] - 10, c[2] - 14), v(c[0] + 14, c[1] + 10, c[2] + 14),
					(x, y, z) -> x < c[0] - 0.4)));
		}
	}

	/**
	 * The four stomachs. The rumen fills the left of the belly from the diaphragm to the pelvis and crosses the midline
	 * behind: a dorsal and a ventral sac parted by the longitudinal grooves, two blind sacs behind (the ventral reaching
	 * further back), the atrium in front; fat and the ruminal arteries run in the grooves. The honeycombed reticulum
	 * lies in front of it against the diaphragm and the liver; the small round omasum (a softball, "the manyplies") sits
	 * on the right against the liver; the abomasum lies along the floor of the belly on the right and runs back to the
	 * pylorus. Opened, the rumen shows its papillae and the fibrous mat of cud, the reticulum its honeycomb, the omasum
	 * its laminae and the abomasum its folds; intact every one of them is a closed, smooth organ.
	 */
	private static void stomachs() {
		Shape[] sacs = {
				ell(-12.5, 42.0, 14.0, 17.5, 15.5, 31.0), // dorsal sac
				ell(-7.0, 15.0, 20.0, 21.0, 13.5, 31.0), // ventral sac
				ell(-9.0, 31.0, -8.0, 13.5, 14.5, 11.0), // atrium
				ell(-14.0, 40.0, 48.0, 15.0, 14.5, 15.0), // caudodorsal blind sac
				ell(-8.0, 15.0, 52.0, 17.0, 13.5, 16.0) // caudoventral blind sac
		};
		Shape[] inner = new Shape[sacs.length];
		double[][] c = {{-12.5, 42.0, 14.0, 17.5, 15.5, 31.0}, {-7.0, 15.0, 20.0, 21.0, 13.5, 31.0}, {-9.0, 31.0, -8.0, 13.5, 14.5, 11.0},
				{-14.0, 40.0, 48.0, 15.0, 14.5, 15.0}, {-8.0, 15.0, 52.0, 17.0, 13.5, 16.0}};
		for (int i = 0; i < c.length; i++) {
			inner[i] = ell(c[i][0], c[i][1], c[i][2], c[i][3] - 2.4, c[i][4] - 2.4, c[i][5] - 2.4);
		}

		double k = 7.5;
		Shape rumenOut = union(k, sacs);
		Shape rumenIn = union(k, inner);
		Shape retOut = lumpy(ell(-4.0, 17.0, -19.0, 12.5, 11.5, 9.5), 0.4, 0.5, 61);
		Shape retIn = lumpy(ell(-4.0, 17.0, -19.0, 9.7, 8.7, 6.9), 0.4, 0.5, 61);
		// The window each state opens: intact none, opened a clean oval in the left wall, torn a ragged rupture.
		Shape rumenWindow = STATE == 0 ? null : STATE == 1 ? ell(-21.5, 28.0, 14.0, 11.0, 13.0, 15.5)
				: lumpy(ell(-19.0, 26.0, 12.0, 8.5, 10.0, 12.5), 3.4, 0.55, 61);
		Shape retWindow = STATE == 0 ? null : STATE == 1 ? ell(-15.0, 17.0, -19.0, 7.5, 7.0, 6.5)
				: lumpy(ell(-14.0, 16.5, -19.0, 7.5, 7.0, 6.5), 2.8, 0.65, 62);
		Shape cavity = union(3.5, rumenIn, retIn);
		Mat rumen = (x, y, z, f, ax, ay, az) -> {
			if (cavity.f.at(ax, ay, az) < 0) {
				// The lining: shaggy with papillae (Cornell), green-brown where cud presses on it.
				double n = noise(x * 1.8, y * 1.8, z * 1.8, 51);
				return n > 0.72 ? 0x3B3220 : mix(0x6B5A3A, 0x86744C, noise(x * 0.6, y * 0.6, z * 0.6, 52));
			}

			if (rumenWindow != null && (rumenWindow.f.at(ax, ay, az) < 0 || rumenOut.f.at(ax, ay, az) < 0)) {
				// The cut margin of the opened wall.
				return STATE == 1 ? mix(0xC08A7E, 0xA97A70, noise(x, y, z, 53)) : mix(0x8E322C, 0xB0554C, noise(x, y, z, 54));
			}

			// The grooves are where the sacs' smooth union adds most: fat along them, the ruminal artery down the middle.
			double crease = crease(sacs, k, x, y, z);
			int serosa = mix(mix(0xCDB8B0, 0xB9B3B8, noise(x * 0.22, y * 0.22, z * 0.22, 54)), 0xDCCBC4, noise(x * 1.1, y * 1.1, z * 1.1, 55) * 0.5);
			if (crease > 0.66) {
				return mix(0xA8443E, 0x8E3430, noise(x * 0.9, y * 0.9, z * 0.9, 56));
			}

			if (crease > 0.3) {
				return mix(0xEADAAA, 0xD8C590, noise(x * 0.9, y * 0.9, z * 0.9, 57));
			}

			return ridge(x * 0.14, y * 0.14, z * 0.14, 58) > 0.93 ? mix(serosa, 0xB06A66, 0.45) : serosa;
		};
		Mat reticulum = (x, y, z, f, ax, ay, az) -> {
			if (cavity.f.at(ax, ay, az) < 0) {
				// The honeycomb: the reticulum's own mucosa, only ever seen when the organ is opened.
				double[] cell = cells(x * 0.55, y * 0.55, z * 0.55, 61);
				return cell[1] - cell[0] < 0.3 ? 0xAC9A74 : mix(0x433820, 0x5E4D30, cell[2]);
			}

			if (retWindow != null && (retWindow.f.at(ax, ay, az) < 0 || retOut.f.at(ax, ay, az) < 0)) {
				return STATE == 1 ? mix(0xC08A7E, 0xA97A70, noise(x, y, z, 63)) : mix(0x8E322C, 0xB0554C, noise(x, y, z, 64));
			}

			return mix(0xC2ABA5, 0xD6C4BE, noise(x * 0.7, y * 0.7, z * 0.7, 62));
		};
		Shape rumenWall = rumenWindow == null ? cut(rumenOut, rumenIn) : cut(cut(rumenOut, rumenIn), region(rumenOut.lo, rumenOut.hi, (x, y, z) -> rumenWindow.in(x, y, z)));
		Shape retWall = retWindow == null ? cut(retOut, retIn) : cut(cut(retOut, retIn), region(retOut.lo, retOut.hi, (x, y, z) -> retWindow.in(x, y, z)));
		part("abdomen/stomach/rumen", "rumen", rumen, rumenWall);
		part("abdomen/stomach/reticulum", "reticulum", reticulum, retWall);
		omasum();
		Mat abomasum = veined(0xD3A398, 0xBE8A80, 0xE2BBB0, 0xA54A48, 64);
		part("abdomen/stomach/abomasum", "abomasum", abomasum, tube()
				.p(2.0, 11.0, -10.0, 8.5).p(8.0, 10.5, 0.0, 9.0).p(13.0, 10.5, 12.0, 8.0).p(18.0, 11.5, 24.0, 6.5)
				.p(21.5, 15.5, 33.0, 4.8).p(23.0, 22.5, 39.0, 3.3).build());
		Mat gullet = streaked(0xC99089, 0xB0766F, 0xDCA9A2, 33);
		part("abdomen/stomach/esophagus", "abdominal_esophagus", gullet, tube().flat(true, false)
				.p(-4.6, 62.0, -36, 1.9).p(-6.0, 57.0, -28, 1.9).p(-7.2, 50.0, -22, 1.9).build());
		if (STATE != 0) {
			stomachsOpened(rumenIn, retIn, cavity, rumenWindow, retWindow);
		} else {
			// The contents still exist as their own group in the intact file: hidden inside the closed organ, they show
			// as soon as the wall group is hidden in Blockbench. They never share a voxel with the wall.
			contents(rumenIn, retIn, cavity, rumenWindow);
		}
	}

	/**
	 * The opened stomachs. The rumen's window is a clean cut through the wall (its thickness, the papillae on the
	 * lining, the fibrous mat of cud inside); the reticulum's shows the honeycomb; the abomasum's shows its folds.
	 * Contents are their own groups and fill the cavity only, never the wall.
	 */
	private static void stomachsOpened(Shape rumenIn, Shape retIn, Shape cavity, Shape rumenWindow, Shape retWindow) {
		Mat cutFlesh = organic(0xC08A7E, 0xA97A70, 0xD5A99C, 0.8, 65);
		Mat lining = (x, y, z, f, ax, ay, az) -> {
			double n = noise(x * 1.8, y * 1.8, z * 1.8, 66);
			return n > 0.72 ? 0x3B3220 : mix(0x6B5A3A, 0x86744C, noise(x * 0.6, y * 0.6, z * 0.6, 67));
		};
		// The wall's cut margin: a hand's width of raw tissue round the window (clean in the opened state, ragged and
		// bloodied in the torn one).
		if (rumenWindow != null) {
			Shape w = cut(union(7.5, ell(-12.5, 42.0, 14.0, 17.5, 15.5, 31.0), ell(-7.0, 15.0, 20.0, 21.0, 13.5, 31.0),
					ell(-9.0, 31.0, -8.0, 13.5, 14.5, 11.0), ell(-14.0, 40.0, 48.0, 15.0, 14.5, 15.0), ell(-8.0, 15.0, 52.0, 17.0, 13.5, 16.0)), rumenIn);
			part("abdomen/stomach/rumen/state_opened/cut_margin", STATE == 1 ? "cut_margin" : "torn_margin",
					STATE == 1 ? cutFlesh : organic(0x8E322C, 0x6E201C, 0xB0554C, 1.0, 68),
					region(w.lo, w.hi, (x, y, z) -> w.in(x, y, z) && rumenWindow.f.at(x, y, z) < (STATE == 1 ? 2.2 : 3.6)));
		}

		contents(rumenIn, retIn, cavity, rumenWindow);
		// The reticulum's honeycomb, seen only through its own window.
		if (retWindow != null) {
			part("abdomen/stomach/reticulum/state_opened/honeycomb", "honeycomb", lining,
					region(retIn.lo, retIn.hi, (x, y, z) -> retIn.in(x, y, z) && retWindow.f.at(x, y, z) > -2.0));
		}

		// The abomasum's folds: rugae in the cranial part, plain mucosa behind, with its fluid in a group of its own.
		Mat fold = organic(0xD9A99C, 0xC4907F, 0xE8C4B6, 1.0, 69);
		for (int i = 0; i < 6; i++) {
			double t = i / 5.0;
			double[] c = {2.0 + 11 * t, 11.0 + 1.2 * t - 1.2 * Math.sin(t * Math.PI), -10.0 + 21 * t};
			double r = 6.4 - 3.2 * t;
			part("abdomen/stomach/abomasum/state_opened/inner", "abomasal_fold", fold,
					torus(c, norm(new double[] {0.22, 0.12, 0.97}), r, 1.15));
		}

		Mat fluid = (x, y, z, f, ax, ay, az) -> mix(0x6E7A3E, 0x8A9654, noise(x * 0.5, y * 0.5, z * 0.5, 70));
		part("abdomen/stomach/abomasum/state_opened/contents", "abomasal_fluid", fluid, ell(11.0, 10.5, 10.0, 5.5, 4.5, 12.0));
	}

	/**
	 * A ragged rupture in an organ: the margin round a torn hole (raw, bloodied tissue, ragged where a clean cut is
	 * flat) and the blood welling in it. Only ever built in the torn state; the clean cuts of the opened state are the
	 * flat windows these sit beside in {@code cow_damage}.
	 */
	static void torn(String path, Shape bulk, Shape hole, Mat raw, Mat blood) {
		part(path + "/state_torn", "torn_margin", raw, region(bulk.lo, bulk.hi, (x, y, z) -> bulk.in(x, y, z) && hole.f.at(x, y, z) < 3.2));
		part(path + "/state_torn", "blood_in_wound", blood, region(bulk.lo, bulk.hi, (x, y, z) -> hole.in(x, y, z) && noise(x * 0.55, y * 0.55, z * 0.55, 199) > 0.5));
	}

	/** A ragged hole torn in an organ's surface. */
	static Shape hole(double cx, double cy, double cz, double rx, double ry, double rz, int seed) {
		return lumpy(ell(cx, cy, cz, rx, ry, rz), 3.0, 0.55, seed);
	}

	/**
	 * The rumen's and reticulum's contents: the fibrous mat of cud, its own group inside the cavity, never in the wall.
	 * Where an organ is opened the cud is drawn back from the window a hand's width, so the cut shows the papillae of the
	 * lining first and the cud behind them.
	 */
	private static void contents(Shape rumenIn, Shape retIn, Shape cavity, Shape window) {
		Mat cud = (x, y, z, f, ax, ay, az) -> {
			if (y < 21.0) {
				return mix(0x4C4826, 0x5E5A30, noise(x * 0.6, y * 0.6, z * 0.6, 71));
			}

			double fib = noise(x * 1.1, y * 0.3, z * 1.1, 72);
			double straw = ridge(x * 0.5 + y * 0.15, y * 0.18, z * 0.5, 73);
			return straw > 0.9 ? 0xA09A5A : mix(0x55532C, 0x7A7644, fib);
		};
		Shape carve = window == null ? region(v(-40, 2, -6), v(4, 46, 46), (x, y, z) -> false)
				: region(v(-40, 2, -6), v(4, 46, 46), (x, y, z) -> window.f.at(x, y, z) < -5.0);
		part("abdomen/stomach/ingesta", "rumen_contents", cud, cut(both(cavity, region(cavity.lo, cavity.hi, (x, y, z) -> y < 44.0)), carve));
	}

	/**
	 * The omasum: the "manyplies", a softball-sized bag on the right against the liver and the diaphragm. Intact it is
	 * a closed, smooth organ. Opened, the laminae are seen: thin sheets hung from the sides of the greater curvature
	 * and projecting inwards, of three sizes (long, intermediate and short), the passage along the lesser curvature left
	 * clear where the reticular groove comes in. Sheets, not a spoked wheel: the leaves spring from two sides and vary
	 * in length, and the game-scale organ is simply a small round body at the right of the rumen.
	 */
	private static void omasum() {
		Mat omasum = organic(0xBBA29A, 0xA58A82, 0xCDB8B0, 0.65, 63);
		double[] oc = {10.5, 29.0, -13.0};
		double[] orad = {8.6, 9.2, 9.4};
		// The wall is thick and of even thickness (outer and inner share their noise), so the organ is watertight and the
		// laminae inside can never be seen from outside it.
		Shape out = lumpy(ell(oc[0], oc[1], oc[2], orad[0], orad[1], orad[2]), 0.5, 0.55, 81);
		Shape in = lumpy(ell(oc[0], oc[1], oc[2], orad[0] - 2.8, orad[1] - 2.8, orad[2] - 2.8), 0.5, 0.55, 81);
		Shape window = STATE == 0 ? null : STATE == 1 ? ell(oc[0] + 7.5, oc[1] + 1.5, oc[2] + 2.0, 7.0, 7.5, 7.5)
				: lumpy(ell(oc[0] + 6.5, oc[1] + 1.0, oc[2] + 1.5, 6.5, 7.0, 7.0), 2.6, 0.7, 83);
		Shape wall = window == null ? cut(out, in) : cut(cut(out, in), region(out.lo, out.hi, (x, y, z) -> window.in(x, y, z)));
		part("abdomen/stomach/omasum", "omasum", omasum, wall);
		if (STATE == 0) {
			// The laminae are built anyway, tucked inside the closed organ: hiding the wall in Blockbench opens it.
			laminae(oc, orad, in, false);
		} else {
			laminae(oc, orad, in, true);
			part("abdomen/stomach/omasum/state_opened/cut_margin", STATE == 1 ? "cut_margin" : "torn_margin",
					STATE == 1 ? organic(0xC08A7E, 0xA97A70, 0xD5A99C, 0.8, 84) : organic(0x8E322C, 0x6E201C, 0xB0554C, 1.0, 85),
					region(wall.lo, wall.hi, (x, y, z) -> wall.in(x, y, z) && window.f.at(x, y, z) < (STATE == 1 ? 1.8 : 3.0)));
		}
	}

	/** The omasal laminae: sheets hung from the sides of the greater curvature, long, intermediate and short, in rows. */
	private static void laminae(double[] oc, double[] orad, Shape cavity, boolean opened) {
		Mat leaf = organic(0xD8BCAE, 0xC2A294, 0xE8D2C6, 1.1, 86);
		Mat leafEdge = organic(0xE6CFC2, 0xD2B4A6, 0xF2E2D8, 1.3, 87);
		String g = "abdomen/stomach/omasum/" + (opened ? "state_opened/" : "") + "laminae";
		// Two rows spring from the two sides of the organ (the greater curvature's walls), the leaves pointing inwards
		// towards the lumen; each row is long, intermediate and short leaves alternating, as in the butcher's bible.
		// Every leaf is clipped to the organ's cavity, so the closed organ is smooth outside and only the opened one
		// shows the sheets.
		for (int side = -1; side <= 1; side += 2) {
			for (int i = 0; i < 7; i++) {
				double t = (i - 3) / 3.0;
				double len = orad[2] * (i % 3 == 0 ? 1.45 : i % 3 == 1 ? 1.15 : 0.8);
				double y = oc[1] + t * orad[1] * 0.82;
				double z = oc[2] + (i % 2 == 0 ? 1.2 : -1.2) * (1 - Math.abs(t) * 0.5);
				part(g, i % 3 == 0 ? "long_lamina" : i % 3 == 1 ? "intermediate_lamina" : "short_lamina", i % 2 == 0 ? leaf : leafEdge,
						both(tri(v(oc[0] + side * orad[0] * 0.86, y, z - len * 0.55), v(oc[0] + side * orad[0] * 0.86, y, z + len * 0.55),
								v(oc[0] + side * orad[0] * 0.12, y + 0.5 * side, z), 0.85), cavity));
			}
		}
	}

	/**
	 * The liver: a cow's is a thick, deeply fissured organ driven wholly to the right of the belly by the rumen, moulded
	 * against the right side of the diaphragm and lying obliquely. Four lobes by the standard veterinary account (left,
	 * quadrate, right, caudate with its caudate and papillary processes), the green gallbladder in its fossa between the
	 * quadrate and right lobes reaching the ventral border, the caudal vena cava sunk in the dorsal border, the portal
	 * vein entering at the porta. (The sheep's liver is one undivided plate: this is one of the shapes that makes the
	 * cow a cow.)
	 */
	private static void liver() {
		Mat liver = (x, y, z, f, ax, ay, az) -> {
			double[] cell = cells(x * 0.55, y * 0.55, z * 0.55, 81);
			int col = mix(0x431310, 0x5F1D18, noise(x * 0.18, y * 0.18, z * 0.18, 82));
			if (cell[1] - cell[0] < 0.12) {
				col = mix(col, 0x6C2A22, 0.55);
			}

			return noise(x * 1.3, y * 1.3, z * 1.3, 83) > 0.86 ? mix(col, 0x9A5A50, 0.6) : col;
		};
		String[] lobes = {"left_lobe", "quadrate_lobe", "right_lobe", "caudate_lobe"};
		Shape hole = STATE == 2 ? hole(-2.0, 18.0, -20.0, 8.5, 9.0, 8.5, 188) : null;
		// Opened by a clean section through the right lobe: the organ is cut back to the plane and the plane is painted.
		Shape cutBack = STATE == 1 ? region(v(-16, 2, -36), v(34, 66, 12), (x, y, z) -> z > liverFront(x, y) + 6.2) : null;
		for (int i = 0; i < lobes.length; i++) {
			int lobe = i;
			Shape s = region(v(-16, 2, -36), v(34, 66, 12), (x, y, z) -> liverAt(x, y, z) == lobe);
			part("abdomen/liver/" + lobes[i], lobes[i], liver, hole != null ? cut(s, hole) : cutBack != null ? cut(s, cutBack) : s);
		}

		Mat gall = glossy(0x3F5D30, 0x2E4824, 0x55713F, 0x7E9C83, 0.65, 84);
		double gz = liverFront(17.0, 13.0) + liverThickness(17.0, 13.0) + 0.6;
		part("abdomen/liver/gallbladder", "gallbladder", gall, ell(17.0, 12.0, gz, 3.6, 7.2, 3.6));
		Mat vein = organic(0x4D2E4B, 0x3A2038, 0x62405E, 0.55, 23);
		part("abdomen/liver/caudal_vena_cava", "hepatic_caudal_vena_cava", vein, tube().hollow(0.6).flat(true, true)
				.p(9.2, 34.5, -32, 2.8).p(11.2, 41.5, -25, 2.8).p(13.8, 49.5, -17, 2.8).p(14.4, 57.5, -8, 2.7).p(13.2, 59.5, -4, 2.7).build());
		double pz = liverFront(14.5, 37.5) + liverThickness(14.5, 37.5);
		part("abdomen/liver/portal_vein", "portal_vein", organic(0x5A3550, 0x452841, 0x6E4864, 0.55, 85), tube().hollow(0.55).flat(false, true)
				.p(14.5, 37.5, pz - 3.6, 2.3).p(14.0, 36.5, pz + 2.4, 2.3).p(13.2, 35.0, pz + 9.0, 2.1).build());
		if (STATE == 1) {
			liverOpened();
		} else if (STATE == 2) {
			Mat raw = organic(0x8E322C, 0x6E201C, 0xB0554C, 1.0, 186);
			Mat blood = organic(0x6E1A16, 0x4E100E, 0x8E2A22, 1.1, 187);
			torn("abdomen/liver", region(v(-16, 2, -36), v(34, 66, 12), (x, y, z) -> liverAt(x, y, z) >= 0), hole, raw, blood);
		}
	}

	private static final double[][] LIVER_LOBES = {
			{2.0, 18.0, 11.0, 11.5}, {13.5, 14.5, 6.5, 8.5}, {23.0, 32.0, 11.5, 20.0}, {21.0, 52.5, 10.0, 9.5}, {12.0, 30.0, 11.0, 14.5}};

	/** The liver's outline seen from in front: above 0 inside, the lobes' ellipses blended into one plate. */
	static double liverOutline(double x, double y) {
		double s = 0;
		double k = 0.12;
		for (double[] l : LIVER_LOBES) {
			s += Math.exp((1 - sq((x - l[0]) / l[2]) - sq((y - l[1]) / l[3])) / k);
		}

		return k * Math.log(s);
	}

	/**
	 * The liver's diaphragmatic face: against the diaphragm at its borders, standing a little off the top of the dome,
	 * so the plate is less curled than the dome itself (a liver keeps its own shape out of the body).
	 */
	static double liverFront(double x, double y) {
		return diaphragmZ(x, y) + 1.5 + 3.6 * Math.max(0, 1 - sq((y - 34) / 24.0));
	}

	static double liverThickness(double x, double y) {
		double t = clamp(5.2 + 0.38 * (y - 8), 4.6, 12.5);
		t += 8.2 * Math.exp(-(sq(x - 21.5) + sq(y - 52.0)) / (2 * 5.0 * 5.0));
		t += 4.6 * Math.exp(-(sq(x - 12.5) + sq(y - 43.0)) / (2 * 3.4 * 3.4));
		double g = liverOutline(x, y);
		return t * Math.sqrt(clamp(g / 0.45, 0, 1));
	}

	/** Which lobe of the liver holds (x, y, z), or -1. */
	static int liverAt(double x, double y, double z) {
		double g = liverOutline(x, y);
		if (g <= 0) {
			return -1;
		}

		// The deep fissure for the round ligament, between the left and quadrate lobes.
		if (y < 21.5 && Math.abs(x - 6.6) < 0.8 + 0.4 * (21.5 - y)) {
			return -1;
		}

		double d = z - liverFront(x, y);
		if (d < 0 || d > liverThickness(x, y)) {
			return -1;
		}

		int best = 0;
		double bestG = -1e9;
		for (int i = 0; i < 4; i++) {
			double[] l = LIVER_LOBES[i];
			double gi = 1 - sq((x - l[0]) / l[2]) - sq((y - l[1]) / l[3]);
			if (gi > bestG) {
				bestG = gi;
				best = i;
			}
		}

		return best;
	}

	/** The liver opened by a clean section through the right lobe: its dark parenchyma, the vessels at the porta, bile. */
	private static void liverOpened() {
		Mat parenchyma = organic(0x6B241C, 0x521812, 0x87362A, 0.8, 86);
		Mat vessel = organic(0x5A3550, 0x452841, 0x6E4864, 0.8, 87);
		Mat bile = glossy(0x3F5D30, 0x2E4824, 0x55713F, 0x7E9C83, 0.9, 88);
		Shape section = region(v(10, 12, -30), v(34, 66, 12), (x, y, z) -> z > liverFront(x, y) + 5.5 && z < liverFront(x, y) + 6.3);
		// The vessels and the bile duct sit in the section before it is filled, so they read as themselves in it.
		for (int i = 0; i < 4; i++) {
			part("abdomen/liver/state_opened/section", "hepatic_vessel", vessel,
					both(ell(13 + (i % 2) * 5.5, 30 + i * 5.5, liverFront(13 + (i % 2) * 5.5, 30 + i * 5.5) + 5.9, 1.3, 1.3, 1.3), section));
		}

		part("abdomen/liver/state_opened/section", "bile_duct", bile,
				both(ell(16.5, 20.0, liverFront(16.5, 20.0) + 5.9, 1.2, 1.2, 1.2), section));
		part("abdomen/liver/state_opened/section", "cut_parenchyma", parenchyma, section);
	}

	/**
	 * The spleen: in the cow a long, flat, blade-shaped organ hung on the left of the rumen's dorsal sac against the
	 * last ribs and the diaphragm, its base up and its apex down and back (a sheep's is a rounded triangle).
	 */
	private static void spleen() {
		double[] c = {-23.5, 46.0, -6.0};
		double[] n = norm(new double[] {-0.90, 0.18, -0.40});
		double[] g = {0, -0.45, 0.9};
		double[] u = norm(sub(g, mul(n, dot(g, n))));
		double[] w = cross(n, u);
		Field f = (x, y, z) -> {
			double[] p = {x - c[0], y - c[1], z - c[2]};
			double a = dot(p, u);
			double b = dot(p, w);
			double h = dot(p, n);
			double rv = 8.6 * (0.72 + 0.28 * a / 12.5);
			double gg = 1 - Math.pow(Math.abs(a) / 12.5, 2.2) - Math.pow(Math.abs(b) / rv, 2.2);
			if (gg <= 0) {
				return 1;
			}

			double t = Math.max(4.2 * Math.sqrt(gg), VOX * 1.1);
			return h > -0.35 * t && h < 0.65 * t ? -1 : 1;
		};
		Mat spleen = (x, y, z, fc, ax, ay, az) -> {
			int col = mix(0x4A2038, 0x6A3754, noise(x * 0.35, y * 0.35, z * 0.35, 91));
			return noise(x * 1.3, y * 1.3, z * 1.3, 92) > 0.8 ? mix(col, 0x7C6C86, 0.5) : col;
		};
		part("abdomen/spleen/spleen", "spleen", spleen, new Shape(v(c[0] - 17, c[1] - 17, c[2] - 17), v(c[0] + 17, c[1] + 17, c[2] + 17), f));
	}

	/**
	 * The guts, hung on the right in their mesentery behind the liver and the omasum. The duodenum climbs from the
	 * pylorus to the liver and runs back high on the right flank. The spiral colon is a flat disc in the mesentery, coils
	 * in towards the centre and back out (Cornell: the ascending colon spirals, proximal loop, spiral loop, distal
	 * loop), the outgoing ones already beading into pellets. Round its rim the jejunum hangs in a garland of short loops
	 * (in Blockbench each loop is its own group, so the chain can drape and part a loop at a time). The ileum enters the
	 * caecum, which points back into the pelvis; the colon leaves it in the proximal loop, goes round the spiral, and
	 * out by the distal loop to the descending colon and the rectum.
	 */
	private static void intestines() {
		Mat small = veined(0xE3B6A4, 0xCF9D8D, 0xEFCAB9, 0xB45C52, 101);
		Mat colon = organic(0xCBB0A2, 0xB39888, 0xDBC4B6, 0.6, 102);
		Mat pellets = organic(0xA28E78, 0x86745F, 0xBCA894, 0.75, 103);
		Mat mesentery = veined(0xECDCB2, 0xDCC791, 0xF5EBCD, 0xB3544C, 104);
		Mat caecum = organic(0xC7A59A, 0xA8907A, 0xD8BCB0, 0.5, 105);
		part("abdomen/intestines/duodenum", "duodenum", small, tube()
				.p(23.0, 22.5, 39.0, 2.9).p(26.5, 31.5, 31.0, 2.9).p(28.5, 44.5, 15.0, 2.8).p(28.5, 52.5, 5.0, 2.8)
				.p(29.5, 57.5, 14.0, 2.8).p(29.5, 58.5, 31.0, 2.8).p(28.5, 57.5, 48.0, 2.8).p(25.0, 55.5, 58.0, 2.8)
				.p(18.5, 56.5, 51.0, 2.7).p(15.5, 56.5, 36.0, 2.6).p(17.0, 53.5, 26.0, 2.5).build());
		double cx = 16.5;
		double cy = 39.5;
		double cz = 31.0;
		double a0 = Math.toRadians(150);
		Tube inward = tube();
		Tube outward = tube().beads(0.5, 3.4);
		for (int i = 0; i <= 48; i++) {
			double th = i * (4 * Math.PI / 48);
			double r = 16.5 - 6.2 * th / (2 * Math.PI);
			inward.p(cx, cy + r * Math.sin(a0 - th), cz + r * Math.cos(a0 - th), 1.4);
		}

		for (int i = 48; i >= 0; i--) {
			double th = i * (4 * Math.PI / 48);
			double r = 16.5 - 3.1 - 6.2 * th / (2 * Math.PI);
			outward.p(cx, cy + Math.max(r, 1.1) * Math.sin(a0 - th), cz + Math.max(r, 1.1) * Math.cos(a0 - th), 1.3);
		}

		part("abdomen/intestines/spiral_colon", "centripetal_coils", colon, inward.build());
		part("abdomen/intestines/spiral_colon", "centrifugal_coils", pellets, outward.build());
		part("abdomen/intestines/caecum", "caecum", caecum, tube()
				.p(18.5, 28.5, 48.0, 5.2).p(19.5, 32.5, 57.0, 5.2).p(18.5, 37.5, 65.0, 4.7).p(17.0, 41.5, 71.0, 4.0).build());
		part("abdomen/intestines/proximal_colon", "proximal_loop", colon, tube()
				.p(18.5, 29.5, 46.0, 4.0).p(23.0, 39.5, 51.0, 3.8).p(23.5, 51.5, 45.0, 3.5).p(23.0, 56.5, 31.0, 3.2)
				.p(21.5, 55.5, 19.0, 3.0).p(19.0, 51.5, 15.5, 2.5)
				.p(cx, cy + 16.5 * Math.sin(a0), cz + 16.5 * Math.cos(a0), 1.5).build());
		double r1 = 16.5 - 3.1;
		part("abdomen/intestines/distal_colon", "distal_loop", pellets, tube().beads(0.45, 3.6)
				.p(cx, cy + r1 * Math.sin(a0), cz + r1 * Math.cos(a0), 1.5).p(13.5, 53.5, 23.5, 2.2).p(10.5, 59.5, 33.0, 2.4)
				.p(9.2, 61.5, 47.0, 2.4).p(7.2, 62.5, 58.0, 2.4).p(4.2, 62.5, 40.0, 2.4).p(1.2, 62.5, 15.0, 2.4)
				.p(-2.0, 61.5, 25.0, 2.5).p(-0.8, 60.5, 46.0, 2.6).p(0.0, 59.5, 62.0, 2.8).build());
		part("abdomen/intestines/rectum", "rectum", pellets, tube().beads(0.4, 4.0).flat(false, true)
				.p(0.0, 59.5, 62.0, 2.8).p(0.0, 57.5, 78.0, 3.2).p(0.0, 55.0, 92.0, 3.0).build());
		// The jejunum's garland, loop by loop, from the duodenojejunal flexure round the rim of the disc to the ileum.
		int loops = 16;
		double f0 = Math.toRadians(160);
		double f1 = Math.toRadians(360);
		double[] prev = {17.0, 53.5, 26.0};
		for (int l = 0; l < loops; l++) {
			Tube t = tube();
			t.p(prev[0], prev[1], prev[2], 2.1);
			for (int s = 1; s <= 8; s++) {
				double q = (l + s / 8.0) / loops;
				double ph = (l + s / 8.0) * 2 * Math.PI;
				double ang = f0 + (f1 - f0) * q;
				double rho = 20.5 + 4.5 * (0.5 - 0.5 * Math.cos(ph));
				double x = cx + 5.5 * Math.sin(ph * 0.5) + 1.0;
				double[] p = {x, cy + rho * Math.sin(ang), cz + rho * Math.cos(ang)};
				t.p(p[0], p[1], p[2], 2.1);
				prev = p;
			}

			part(String.format(Locale.ROOT, "abdomen/intestines/jejunum/loop_%02d", l + 1), "jejunum", small, t.build());
		}

		part("abdomen/intestines/ileum", "ileum", small, tube().p(prev[0], prev[1], prev[2], 2.0).p(18.5, 32.5, 54.5, 2.0).p(18.8, 28.5, 50.5, 2.0).build());
		Field disc = (x, y, z) -> {
			double rho = Math.hypot(y - cy, z - cz);
			return Math.abs(x - cx) < Math.max(0.75, VOX * THIN) && rho < 22.5 ? -1 : 1;
		};
		part("abdomen/intestines/mesentery", "mesentery", mesentery, new Shape(v(cx - 3, cy - 24, cz - 24), v(cx + 3, cy + 24, cz + 24), disc));
		if (STATE != 0) {
			intestinesOpened();
		}
	}

	/** The intestines opened: a clean cut through a loop of the jejunum showing wall, lumen and the digesta inside. */
	private static void intestinesOpened() {
		Mat wall = veined(0xE3B6A4, 0xCF9D8D, 0xEFCAB9, 0xB45C52, 111);
		Mat inner = organic(0xD9A08E, 0xC08878, 0xEBC0B0, 1.2, 112);
		Mat digesta = organic(0x8A7A54, 0x6E6040, 0xA2926C, 1.2, 113);
		Mat blood = organic(0x8E322C, 0x6E201C, 0xB0554C, 1.0, 114);
		// A loop cut cleanly across (the ring of wall, its lining, the plug of digesta) and one torn open beside it.
		double[] a = {20.5, 30.5, 62.0};
		double[] b = {24.5, 35.5, 66.0};
		part("abdomen/intestines/state_opened/clean_cut", "wall_ring", wall, torus(a, norm(sub(b, a)), 2.6, 0.85));
		part("abdomen/intestines/state_opened/clean_cut", "mucosa", inner, torus(a, norm(sub(b, a)), 1.9, 0.55));
		part("abdomen/intestines/state_opened/clean_cut", "digesta", digesta, ell(a[0], a[1], a[2], 1.5, 1.5, 1.5));
		if (STATE == 2) {
			Shape tear = lumpy(ell(22.5, 33.5, 64.0, 3.2, 3.2, 3.2), 1.6, 0.8, 115);
			part("abdomen/intestines/state_opened/torn", "torn_wall", blood, region(v(18, 28, 58), v(28, 40, 70), (x, y, z) -> tear.in(x, y, z)));
			part("abdomen/intestines/state_opened/torn", "spilled_digesta", digesta,
					region(v(16, 20, 56), v(30, 36, 72), (x, y, z) -> !tear.in(x, y, z) && sq((x - 22.5) / 5.5) + sq((y - 30.5) / 4.0) + sq((z - 64.0) / 5.5) < 1));
		}
	}

	/** The bladder on the floor of the belly in front of the pelvis, the ureters coming down to it. */
	private static void urinary() {
		Mat ureter = organic(0xE2C8B4, 0xCDB09A, 0xF0DDCD, 0.7, 52);
		part("abdomen/urinary/ureters", "right_ureter", ureter, tube().flat(true, false)
				.p(7.2, 56.5, 14, 0.75).p(6.2, 55.5, 28, 0.75).p(3.8, 43.5, 46, 0.75).p(1.9, 24.5, 55.0, 0.75).build());
		part("abdomen/urinary/ureters", "left_ureter", ureter, tube().flat(true, false)
				.p(0.0, 55.5, 34, 0.75).p(-1.0, 47.5, 46, 0.75).p(-1.7, 24.5, 55.5, 0.75).build());
		Mat bladder = veined(0xE2B4AA, 0xD09C92, 0xEFCFC6, 0xB55A55, 111);
		part("abdomen/urinary/bladder", "bladder", bladder, ell(0.0, 21.0, 62.0, 7.8, 7.2, 9.2));
		part("abdomen/urinary/bladder", "urethra", bladder, tube().p(0.0, 24.5, 70.0, 2.8).p(0.0, 32.5, 66.5, 2.2).p(0.0, 40.5, 71.5, 1.7)
				.p(0.0, 42.0, 84.0, 1.5).build());
	}

	/**
	 * The udder of a dry, non-pregnant cow: a small gland under the belly between the hindlegs (the vanilla cow model
	 * has its own box for it), four quarters with a teat each and a median suspensory ligament between them. Drawn with
	 * the fat tile, which is the closest the mod's palette has to glandular tissue (see the README).
	 */
	private static void udder() {
		Mat gland = crumbly(0xE8D9C2, 0xD2C0A4, 0xF6EDE0, 121);
		Mat teat = organic(0xD8B8A8, 0xC2A08E, 0xE8CCBE, 1.2, 122);
		for (int s = -1; s <= 1; s += 2) {
			part("abdomen/udder/" + (s > 0 ? "right" : "left") + "_half", "mammary_gland", gland,
					lumpy(ell(s * 3.2, 2.0, 52.0, 3.6, 4.2, 5.4), 0.5, 0.8, 123));
		}

		part("abdomen/udder", "median_suspensory_ligament", gland, tube().up((x, y, z) -> new double[] {1, 0, 0})
				.p(0, -1.5, 47.0, 0.7, 4.2).p(0, -1.5, 52.0, 0.7, 4.6).p(0, -1.0, 57.0, 0.7, 4.2).build());
		for (int s = -1; s <= 1; s += 2) {
			for (int q = -1; q <= 1; q += 2) {
				part("abdomen/udder/teats", "teat", teat, tube().p(s * 3.4, -2.5, 52.0 + q * 2.6, 1.0, 1.2).p(s * 3.6, -5.5, 52.0 + q * 2.6, 0.8, 1.0)
						.flat(false, true).build());
			}
		}
	}

	/** The diaphragm: a red muscular rim round a white tendinous centre, the right cupula standing a little forward. */
	private static void diaphragm() {
		Mat m = (x, y, z, f, ax, ay, az) -> {
			boolean tendon = sq(x / 14.5) + sq((y - 38) / 14.5) < 1;
			return tendon ? mix(0xDCD6CB, 0xC9C2B6, noise(x * 0.9, y * 0.9, z * 0.9, 121))
					: mix(0x8A2C27, 0x6E201C, frac(Math.atan2(y - 38, x) * 6) < 0.5 ? 0.3 : 0.0);
		};
		part("thorax/diaphragm", "diaphragm", m, region(v(-30, 2, -46), v(30, 78, 12), (x, y, z) -> {
			if (Math.abs(z - diaphragmZ(x, y)) > Math.max(0.85, VOX * THIN)) {
				return false;
			}

			double hy = y >= YC ? 27.0 : YC - Math.min(sternumY(z), 5.4) - 1.3;
			return sq(x / (halfWidth(z) - 1.6)) + sq((y - YC) / hy) < 1;
		}));
	}

	/**
	 * The lungs fill the chest round the heart, against the ribs, back to the dome of the diaphragm. By the bovine
	 * account (Cornell's respiratory page): the right lung has an apical (cranial) lobe in two parts, aired by its own
	 * tracheal bronchus off the windpipe, a cardiac (middle) lobe, a large diaphragmatic (caudal) lobe and the
	 * accessory (intermediate) lobe tucked in the mediastinum behind the heart; the left has a cranial lobe in two
	 * parts and a caudal lobe. Notches over the heart low on either side leave it bare against the chest wall.
	 */
	private static void lungs(Shape heart) {
		Mat lung = (x, y, z, f, ax, ay, az) -> {
			double[] cell = cells(x * 0.25, y * 0.25, z * 0.25, 131);
			int c = mix(0xE3989A, 0xF4BDB8, noise(x * 0.28, y * 0.28, z * 0.28, 132));
			if (cell[1] - cell[0] < 0.1) {
				c = mix(c, 0xC97E7C, 0.6);
			}

			return noise(x * 1.1, y * 1.1, z * 1.1, 133) > 0.85 ? mix(c, 0xB86664, 0.5) : c;
		};
		String[] names = {"right_cranial_lobe/cranial_part", "right_cranial_lobe/caudal_part", "right_cardiac_lobe", "right_diaphragmatic_lobe",
				"accessory_lobe", "left_cranial_lobe/cranial_part", "left_cranial_lobe/caudal_part", "left_diaphragmatic_lobe"};
		Shape hole = STATE == 2 ? hole(14.0, 34.0, -36.0, 7.0, 7.5, 7.5, 194) : null;
		Shape section = STATE == 1 ? lungSection() : null;
		for (int i = 0; i < names.length; i++) {
			int lobe = i;
			String leaf = names[i].substring(names[i].lastIndexOf('/') + 1);
			Shape s = region(v(-30, 4, -78), v(30, 74, 8), (x, y, z) -> lungAt(x, y, z) == lobe && heart.f.at(x, y, z) > 1.5);
			part("thorax/lungs/" + names[i], leaf, lung, section != null ? cut(s, section) : hole != null && i == 3 ? cut(s, hole) : s);
		}

		if (STATE == 1) {
			lungsOpened();
		} else if (STATE == 2) {
			Mat raw = organic(0x8E322C, 0x6E201C, 0xB0554C, 1.0, 196);
			Mat blood = organic(0x6E1A16, 0x4E100E, 0x8E2A22, 1.1, 197);
			torn("thorax/lungs/right_diaphragmatic_lobe", region(v(-30, 4, -78), v(30, 74, 8), (x, y, z) -> lungAt(x, y, z) == 3), hole, raw, blood);
		}
	}

	static int lungAt(double x, double y, double z) {
		double w = halfWidth(z) - 3.0;
		if (w <= 1) {
			return -1;
		}

		// The roof drops towards the narrow entrance of the chest, so the cranial lobes end in a blunt point.
		double top = curve(z, -77.5, 48.0, -70, 58.0, -58, 68.5, -24, 71.5, 0, 69.5);
		double hy = y >= YC ? top - YC : YC - sternumY(z) - 3.2;
		double ex = sq(x / w) + sq((y - YC) / hy);
		if (ex >= 1 || z < -77.5) {
			return -1;
		}

		// The base: against the dome of the diaphragm in the middle, and at the side a sharp basal border running from
		// the last ribs high up to the costal arch, short of where the diaphragm meets the wall.
		double basal = -50.0 + (y - 12) * 1.7 + 1.8 * (noise(y * 0.14, z * 0.14, 0, 135) - 0.5);
		if (z > diaphragmZ(x, y) - 1.8 || z > basal + 18.0 * (1 - sq(x / w))) {
			return -1;
		}

		double gap = 0.7 + VOX * 0.35;
		if (Math.abs(x + 0.7) < gap) {
			return -1;
		}

		// The cardiac notches, low on either side, where the heart lies bare against the chest wall.
		if (sq((x + 17.5) / 17.5) + sq((y - 13.5) / 10.5) + sq((z + 54.0) / 9.5) < 1 || sq((x - 15.5) / 16.5) + sq((y - 13.5) / 9.5) + sq((z + 58.0) / 7.5) < 1) {
			return -1;
		}

		double obl = -58 + (55 - y) * 1.6 + 2.8 * (noise(x * 0.12, y * 0.12, 0, 136) - 0.5);
		if (Math.abs(z - obl) < gap) {
			return -1;
		}

		if (x > -0.7) {
			// The right lung's accessory lobe, in the mediastinum behind the heart.
			if (x < 10.5 + gap && z > -47 - gap && y < 34 + gap && z > obl - 18) {
				if (x > 10.5 - gap || z < -47 + gap || y > 34 - gap) {
					return -1;
				}

				return 4;
			}

			if (z > obl) {
				return 3;
			}

			double f1 = -64.0 + 0.55 * (37 - y);
			if (z > f1 - gap && y < 39.5 + gap) {
				if (Math.abs(z - f1) < gap || Math.abs(y - 39.5) < gap) {
					return -1;
				}

				return 2;
			}

			if (y < 50 && Math.abs(z + 72.5) < gap) {
				return -1;
			}

			return z < -72.5 ? 0 : 1;
		}

		if (z > obl) {
			return 7;
		}

		if (y < 50 && Math.abs(z + 67.5) < gap) {
			return -1;
		}

		return z < -67.5 ? 5 : 6;
	}

	/** The transverse slot cut through both lungs to show their cut surface: the spongy flesh and the bronchi in it. */
	static Shape lungSection() {
		return region(v(-30, 4, -78), v(30, 74, 8), (x, y, z) -> z > -45.5 && z < -42.5 && sq(x / 22.0) + sq((y - 38) / 26.0) < 1);
	}

	/** A lung cut open: the spongy pink cut surface, the bronchi and their branches inside it, a little blood at the cut. */
	private static void lungsOpened() {
		Mat flesh = organic(0xE8A79E, 0xD18B82, 0xF6C4BC, 1.1, 137);
		Mat bronchus = organic(0xD9C7B4, 0xC2AC98, 0xEADCCC, 1.2, 138);
		Mat blood = organic(0x8E322C, 0x6E201C, 0xB0554C, 1.0, 139);
		Shape section = lungSection();
		// The airways are laid in the slot before it is filled, so the cut shows the bronchi standing in the flesh.
		for (int s = -1; s <= 1; s += 2) {
			for (int i = 0; i < 3; i++) {
				part("thorax/lungs/state_opened/section", "bronchus_branch", bronchus,
						both(tube().p(s * (2.5 + i * 4.5), 62 - i * 7, -44.0, 1.3).p(s * (4.5 + i * 5.5), 52 - i * 8, -44.0, 1.0).build(), section));
			}
		}

		part("thorax/lungs/state_opened/section", "blood_at_cut", blood,
				region(v(-24, 12, -70), v(24, 62, -20), (x, y, z) -> noise(x * 0.4, y * 0.4, 0, 140) > 0.72 && section.in(x, y, z)));
		part("thorax/lungs/state_opened/section", "cut_surface", flesh, section);
	}

	/**
	 * The trunk's bones: seven cervical vertebrae, thirteen thoracic with the tall spines of the withers, six lumbar
	 * with their long flat transverse processes, the five fused sacral and the root of the tail (a cow's tail has
	 * eighteen to twenty caudal vertebrae; the first eight are drawn and the rest simplified away). Thirteen pairs of
	 * ribs, the first eight joined to the breastbone by costal cartilage, the next four by the cartilage of the costal
	 * arch, the last floating. Seven sternebrae and the xiphoid; the shoulder blades on the chest wall; the pelvis with
	 * its obturator foramen. (Cow vertebral formula C7 T13 L6 S5 Cd18-20.)
	 */
	private static void skeleton() {
		Mat bone = bone();
		Mat joint = organic(0xEEEAE0, 0xDAD3C4, 0xF8F6F0, 0.7, 143);
		Mat cartilage = organic(0xC9D3CF, 0xAAB8B4, 0xE1E8E4, 0.65, 144);
		Mat disc = organic(0xD8D0C0, 0xC4B9A4, 0xE6E0D2, 0.65, 145);
		double[] spine = {13.5, 18.5, 21.0, 20.5, 18.5, 16.0, 13.5, 11.5, 10.0, 9.0, 8.2, 7.6, 7.2};
		for (int i = 1; i <= 13; i++) {
			double z = -78 + 6.0 * (i - 1);
			double y = spineY(z);
			String g = String.format(Locale.ROOT, "skeleton/spine/thoracic/T%02d", i);
			part(g, "body", bone, tube().p(0, y, z - 2.6, 3.0, 2.8).p(0, spineY(z + 2.6), z + 2.6, 3.0, 2.8).flat(true, true).build());
			part(g, "disc", disc, tube().p(0, spineY(z + 2.6), z + 2.6, 2.8).p(0, spineY(z + 3.6), z + 3.6, 2.8).flat(true, true).build());
			double h = spine[i - 1];
			part(g, "spinous_process", bone, tube().up((a, b, c) -> new double[] {1, 0, 0})
					.p(0, y + 2.2, z, 1.7, 0.65).p(0, y + 2.6 + h * 0.6, z + 0.3 * h * 0.6, 1.4, 0.6).p(0, y + 2.6 + h, z + 0.3 * h, 1.3, 0.85).build());
		}

		double[] transverse = {13.0, 15.5, 17.5, 18.0, 16.5, 14.5};
		for (int i = 1; i <= 6; i++) {
			double z = 6.0 + 8.5 * (i - 1);
			double y = spineY(z);
			String g = String.format(Locale.ROOT, "skeleton/spine/lumbar/L%02d", i);
			part(g, "body", bone, tube().p(0, spineY(z - 3.6), z - 3.6, 3.4, 3.0).p(0, spineY(z + 3.6), z + 3.6, 3.4, 3.0).flat(true, true).build());
			if (i < 6) {
				part(g, "disc", disc, tube().p(0, spineY(z + 3.6), z + 3.6, 3.1).p(0, spineY(z + 4.8), z + 4.8, 3.1).flat(true, true).build());
			}

			part(g, "spinous_process", bone, tube().up((a, b, c) -> new double[] {1, 0, 0})
					.p(0, y + 2.6, z, 2.6, 0.65).p(0, y + 10.5, z - 0.9, 2.2, 0.75).build());
			double reach = transverse[i - 1];
			for (int s = -1; s <= 1; s += 2) {
				part(g, s > 0 ? "right_transverse_process" : "left_transverse_process", bone, tube().up((a, b, c) -> new double[] {0, 1, 0})
						.p(s * 3.0, y - 0.5, z, 2.0, 0.65).p(s * reach * 0.55, y - 0.9, z - 0.5, 1.6, 0.55).p(s * reach, y - 2.2, z - 1.8, 1.5, 0.65).build());
			}
		}

		part("skeleton/spine/sacrum", "sacrum", bone, tube().p(0, 74.4, 48.0, 3.8, 2.7).p(0, 72.5, 60, 3.1, 2.3).p(0, 69.5, 72, 2.4, 1.8)
				.up((a, b, c) -> new double[] {0, 1, 0}).build());
		part("skeleton/spine/sacrum", "median_sacral_crest", bone, tube().p(0, 77.2, 50.0, 0.8).p(0, 79.0, 58, 0.8).p(0, 75.5, 68, 0.8).build());
		for (int s = -1; s <= 1; s += 2) {
			part("skeleton/spine/sacrum", s > 0 ? "right_wing" : "left_wing", bone, tri(v(s * 1.2, 75.0, 47.5), v(s * 9.6, 75.5, 49.5), v(s * 2.8, 73.5, 57.5), 1.3));
		}

		double[] tailZ = {74.0, 78.6, 82.8, 86.6, 90.0, 93.0, 95.6, 97.8};
		for (int i = 0; i < tailZ.length; i++) {
			double r = 2.1 - 0.18 * i;
			part(String.format(Locale.ROOT, "skeleton/spine/tail/Cd%02d", i + 1), "body", bone, tube()
					.p(0, spineY(tailZ[i] - 1.8), tailZ[i] - 1.8, r).p(0, spineY(tailZ[i] + 1.8), tailZ[i] + 1.8, r).build());
		}

		ribs(bone, cartilage);
		double[] zs = {-75.5, -69.5, -63.5, -57.5, -51.5, -45.5, -39.5, -33.5};
		for (int k = 0; k < 7; k++) {
			double a = (k == 0 ? -78.0 : zs[k]) + 0.9;
			double b = zs[k + 1] - 0.9;
			part(String.format(Locale.ROOT, "skeleton/sternum/sternebra_%d", k + 1), "sternebra", bone, tube().up((p, q, r) -> new double[] {0, 1, 0})
					.p(0, sternumY(a) + 1.8, a, 2.9, 1.8).p(0, sternumY((a + b) / 2) + 1.8, (a + b) / 2, 3.0, 1.9).p(0, sternumY(b) + 1.8, b, 2.9, 1.8)
					.build());
		}

		part("skeleton/sternum/xiphoid", "xiphoid_cartilage", cartilage, tube().up((p, q, r) -> new double[] {0, 1, 0})
				.p(0, sternumY(-33.2) + 1.3, -33.2, 2.5, 0.7).p(0, 5.2, -26, 3.2, 0.6).p(0, 4.8, -17, 3.6, 0.6).build());
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right" : "left";
			String g = "skeleton/shoulder_blades/" + side + "_scapula";
			part(g, "glenoid", joint, ell(s * 22.5, 36.0, -68.5, 2.8, 3.2, 3.4));
			part(g, "blade", bone, tri(v(s * 22.5, 39.0, -67.0), v(s * 20.0, 70.5, -59.5), v(s * 20.5, 68.0, -41.5), 0.85));
			part(g, "spine_of_scapula", bone, tube().p(s * 24.2, 42.5, -65.0, 1.1).p(s * 24.6, 58.0, -56.0, 0.95).p(s * 23.5, 69.5, -50.5, 0.7).build());
			part(g, "scapular_cartilage", cartilage, tube().up((p, q, r) -> new double[] {1, 0, 0})
					.p(s * 19.6, 72.5, -61.0, 2.5, 0.7).p(s * 19.8, 73.0, -51.5, 3.0, 0.7).p(s * 20.0, 70.5, -41.0, 2.3, 0.7).build());
			String pg = "skeleton/pelvis/" + side + "_hip_bone";
			part(pg, "ilium_wing", bone, tri(v(s * 25.5, 65.5, 50.5), v(s * 6.5, 70.0, 57.0), v(s * 13.5, 58.5, 65.5), 1.1));
			part(pg, "ilium_shaft", bone, tube().p(s * 13.5, 58.5, 65.5, 2.4).p(s * 14.5, 45.5, 73.5, 2.0).build());
			part(pg, "acetabulum", joint, cut(ell(s * 16.5, 43.5, 76.5, 3.5, 3.8, 3.8), ell(s * 20.5, 43.5, 76.5, 2.8, 2.8, 2.8)));
			part(pg, "pubis", bone, tri(v(s * 14.5, 41.5, 74.5), v(0, 38.5, 75.0), v(0, 38.5, 81.5), 1.1));
			// The ischium's plate with the obturator foramen left open in it.
			part(pg, "ischium", bone, cut(union(0.5, tri(v(s * 15.0, 42.5, 80.0), v(s * 13.0, 51.5, 101.0), v(0, 39.0, 96.5), 1.1),
					tri(v(s * 15.0, 42.5, 80.0), v(0, 39.0, 96.5), v(0, 38.5, 88.0), 1.1)),
					ell(s * 7.5, 44.5, 88.5, 4.2, 2.6, 6.5)));
		}
	}

	/** Bone: ivory with a fine grain; a cut face shows the hard cortex round the soft interior (see {@link #cutBone}). */
	static Mat bone() {
		return (x, y, z, f, ax, ay, az) -> {
			int c = mix(0xCFC09F, 0xEDE4CF, noise(x * 0.55, y * 0.55, z * 0.55, 141));
			return noise(x * 1.8, y * 1.8, z * 1.8, 142) > 0.83 ? mix(c, 0xB8A784, 0.5) : c;
		};
	}

	/** The ribs, each sweeping out and down from its vertebra and back as it goes, with its costal cartilage. */
	private static void ribs(Mat bone, Mat cartilage) {
		double[][] cart = new double[14][];
		double[] zs = {-75.5, -69.5, -63.5, -57.5, -51.5, -45.5, -39.5, -33.5};
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right" : "left";
			int sign = s;
			for (int i = 1; i <= 13; i++) {
				double zh = -78 + 6.0 * (i - 1);
				double slope = (i == 1 ? 3.2 : 6.6 + 0.9 * i);
				double yJ = i <= 8 ? (i < 3 ? 21.0 : 11.5) : i < 13 ? 13.5 + (i - 9) * 3.2 : 34.0;
				double hb = YC - sternumY(zh + slope * 0.75) - 0.7;
				double phiEnd = Math.asin(clamp((yJ - YC) / hb, -1, 1));
				Tube rib = tube().up((x, y, z) -> norm(new double[] {x / sq(halfWidth(z)), (y - YC) / sq(y >= YC ? 28.5 : hb), 0}));
				rib.p(sign * 3.2, spineY(zh) - 0.7, zh, 1.15, 1.0);
				double[] last = null;
				int steps = 14;
				double phi0 = Math.toRadians(78);
				for (int k = 0; k <= steps; k++) {
					double phi = phi0 + (phiEnd - phi0) * k / steps;
					double sp = Math.sin(phi);
					double zz = zh + slope * (1 - sp) / 2;
					double y = YC + (sp >= 0 ? 28.5 : hb) * sp;
					double x = sign * Math.max(4.8, (halfWidth(zz) - 1.3) * Math.cos(phi));
					double width = 1.25 + 0.55 * Math.sin(Math.PI * k / steps) + 0.01 * i * 10;
					rib.p(x, y, zz, width, 0.95);
					last = new double[] {x, y, zz};
				}

				String g = String.format(Locale.ROOT, "skeleton/ribcage/%s_rib_%02d", side, i);
				// Coarse grains keep only every second or fourth rib, so a gap still shows between them.
				boolean kept = (i - 1) % (LOD >= 8 ? 4 : LOD >= 4 ? 2 : 1) == 0;
				if (kept) {
					part(g, "rib", bone, rib.build());
				}

				Tube c = tube().up((x, y, z) -> norm(new double[] {x / sq(halfWidth(z)), (y - YC) / sq(hb), 0}));
				double[] mid;
				double[] end;
				if (i <= 8) {
					end = new double[] {sign * 2.2, sternumY(zs[i - 1]) + 1.5, zs[i - 1]};
					mid = new double[] {last[0] * 0.72, last[1] - (last[1] - end[1]) * 0.6, last[2] - (last[2] - end[2]) * 0.3};
				} else if (i < 13) {
					double[] pc = cart[i - 1];
					end = new double[] {pc[0] + (pc[3] - pc[0]) * 0.45, pc[1] + (pc[4] - pc[1]) * 0.45, pc[2] + (pc[5] - pc[2]) * 0.45};
					mid = new double[] {(last[0] + end[0]) / 2 + sign * 1.3, (last[1] + end[1]) / 2 - 1.1, (last[2] + end[2]) / 2};
				} else {
					mid = new double[] {last[0] * 0.95, last[1] - 3.1, last[2] + 0.7};
					end = new double[] {last[0] * 0.88, last[1] - 5.7, last[2] + 0.9};
				}

				cart[i] = new double[] {last[0], last[1], last[2], mid[0], mid[1], mid[2]};
				c.p(last[0], last[1], last[2], 1.15, 0.85).p(mid[0], mid[1], mid[2], 1.05, 0.8).p(end[0], end[1], end[2], 0.95, 0.75);
				if (kept) {
					part(g, "costal_cartilage", cartilage, c.build());
				}
			}
		}
	}

	/**
	 * The limbs: a forelimb from the shoulder blade's socket down (humerus, the point of the elbow, the radius with the
	 * ulna fused behind it, the knee = the carpus, the cannon bone = the fused metacarpals of a cloven foot, the
	 * fetlock, two toes of three phalanges each and a claw of horn per toe), and a hindlimb from the hip (femur, the
	 * kneecap in front, the tibia, the point of the hock on the Achilles tendon, the hock = the tarsus, the cannon =
	 * the fused metatarsals, the same two toes). The dewclaws stand behind the fetlock.
	 */
	private static void limbs() {
		Mat bone = bone();
		Mat joint = organic(0xEEEAE0, 0xDAD3C4, 0xF8F6F0, 0.7, 143);
		Mat cartilage = organic(0xC9D3CF, 0xAAB8B4, 0xE1E8E4, 0.65, 144);
		Mat horn = organic(0x3A3229, 0x2A241D, 0x55493C, 0.9, 146);
		Mat marrow = organic(0xB98A76, 0x9C6E5E, 0xD2AC9A, 1.2, 147);
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right" : "left";
			// The forelimb: the humerus down and back from the shoulder, the radius and ulna down to the knee.
			String f = "skeleton/limbs/" + side + "_forelimb";
			part(f, "humerus", bone, tube().p(s * 21.5, 35.0, -67.5, 2.5, 2.2).p(s * 19.5, 22.0, -58.5, 2.6, 2.3).p(s * 18.5, 12.5, -52.5, 2.3, 2.0).build());
			part(f, "point_of_elbow", bone, tube().up((a, b, c) -> new double[] {0, 0, 1}).p(s * 18.5, 12.5, -52.5, 2.2, 1.5).p(s * 18.0, 5.0, -50.5, 2.0, 1.4).build());
			// The ulna lies behind the radius and is laid down first: a ruminant's two forearm bones fuse but the ulna's
			// point of the elbow still has to survive the coarse game grain beside its bigger neighbour.
			part(f, "ulna", bone, tube().p(s * 18.2, 6.0, -49.2, 1.6, 1.3).p(s * 17.2, -18.0, -48.9, 1.35, 1.1).p(s * 16.8, -34.0, -48.9, 1.15, 0.95).build());
			part(f, "radius", bone, tube().p(s * 18.0, 8.0, -51.5, 2.4, 2.0).p(s * 16.5, -22.0, -51.0, 2.2, 1.8).p(s * 16.0, -38.5, -51.0, 2.0, 1.6).build());
			part(f, "knee_carpus", joint, ell(s * 16.0, -42.5, -51.0, 2.6, 2.9, 2.4));
			part(f, "cannon_bone", bone, tube().up((a, b, c) -> new double[] {0, 0, 1})
					.p(s * 15.6, -45.0, -51.2, 1.75, 1.45).p(s * 15.2, -58.0, -51.4, 1.6, 1.35).p(s * 15.0, -62.5, -51.5, 1.7, 1.4).build());
			part(f, "flexor_tendon", cartilage, tube().p(s * 15.0, -46.5, -49.4, 0.85).p(s * 14.8, -59.5, -49.2, 0.8).build());
			part(f, "fetlock", joint, ell(s * 15.0, -64.5, -51.5, 2.2, 1.9, 2.0));
			for (int d = -1; d <= 1; d += 2) {
				String toe = d > 0 ? "lateral_toe" : "medial_toe";
				part(f, toe + "/proximal_phalanx", bone, tube().p(s * 15.0 + d * 1.35, -66.5, -51.5 - d * 0.5, 1.15, 1.0)
						.p(s * 15.0 + d * 1.5, -70.5, -52.5 - d * 0.7, 1.05, 0.95).build());
				part(f, toe + "/middle_phalanx", bone, tube().p(s * 15.0 + d * 1.5, -70.5, -52.5 - d * 0.7, 1.05, 0.95)
						.p(s * 15.0 + d * 1.6, -73.5, -53.5 - d * 0.8, 1.0, 0.9).build());
				part(f, toe + "/distal_phalanx", cutBone, ell(s * 15.0 + d * 1.65, -75.0, -54.0 - d * 0.9, 1.0, 0.95, 1.25));
				part(f, toe + "/hoof", horn, tube().up((a, b, c) -> new double[] {0, 0, 1})
						.p(s * 15.0 + d * 1.7, -74.5, -53.8 - d * 0.9, 1.6, 1.5).p(s * 15.0 + d * 1.8, -78.5, -54.6 - d * 1.0, 1.85, 1.7).flat(true, true).build());
				part(f, toe + "/sesamoid", bone, ell(s * 15.0 + d * 1.15, -63.5, -49.6, 0.75, 0.75, 0.75));
			}

			part(f, "dewclaws", bone, ell(s * 15.1, -61.0, -48.6, 0.85, 1.35, 0.85));
			// The hindlimb: the femur down and back from the hip, the kneecap in front of the stifle, the tibia to the hock.
			String h = "skeleton/limbs/" + side + "_hindlimb";
			part(h, "femur", bone, tube().p(s * 16.5, 43.5, 76.5, 2.7, 2.4).p(s * 15.0, 27.0, 66.5, 2.7, 2.4).p(s * 13.5, 14.5, 58.5, 2.5, 2.2).build());
			part(h, "kneecap", bone, ell(s * 12.5, 12.5, 54.5, 1.7, 2.3, 1.3));
			part(h, "tibia", bone, tube().p(s * 13.5, 10.5, 57.5, 2.5, 2.1).p(s * 13.5, -18.0, 55.5, 2.3, 1.9).p(s * 13.2, -34.5, 54.5, 2.1, 1.7).build());
			part(h, "achilles_tendon", cartilage, tube().p(s * 13.2, -12.0, 57.5, 1.0).p(s * 13.0, -38.0, 58.0, 0.9).build());
			part(h, "point_of_hock", bone, tube().p(s * 13.0, -38.0, 58.5, 1.9, 1.5).p(s * 13.0, -46.5, 60.5, 1.7, 1.35).build());
			part(h, "hock_tarsus", joint, ell(s * 13.0, -44.0, 55.0, 2.6, 2.9, 2.4));
			part(h, "cannon_bone", bone, tube().up((a, b, c) -> new double[] {0, 0, 1})
					.p(s * 12.8, -46.5, 55.2, 1.75, 1.45).p(s * 12.5, -58.0, 55.0, 1.6, 1.35).p(s * 12.4, -62.5, 55.0, 1.7, 1.4).build());
			part(h, "flexor_tendon", cartilage, tube().p(s * 12.4, -48.0, 57.0, 0.85).p(s * 12.2, -59.5, 57.2, 0.8).build());
			part(h, "fetlock", joint, ell(s * 12.4, -64.5, 55.0, 2.2, 1.9, 2.0));
			for (int d = -1; d <= 1; d += 2) {
				String toe = d > 0 ? "lateral_toe" : "medial_toe";
				part(h, toe + "/proximal_phalanx", bone, tube().p(s * 12.4 + d * 1.35, -66.5, 55.0 + d * 0.5, 1.15, 1.0)
						.p(s * 12.4 + d * 1.5, -70.5, 54.0 + d * 0.7, 1.05, 0.95).build());
				part(h, toe + "/middle_phalanx", bone, tube().p(s * 12.4 + d * 1.5, -70.5, 54.0 + d * 0.7, 1.05, 0.95)
						.p(s * 12.4 + d * 1.6, -73.5, 53.0 + d * 0.8, 1.0, 0.9).build());
				part(h, toe + "/distal_phalanx", cutBone, ell(s * 12.4 + d * 1.65, -75.0, 52.5 + d * 0.9, 1.0, 0.95, 1.25));
				part(h, toe + "/hoof", horn, tube().up((a, b, c) -> new double[] {0, 0, 1})
						.p(s * 12.4 + d * 1.7, -74.5, 52.8 + d * 0.9, 1.6, 1.5).p(s * 12.4 + d * 1.8, -78.5, 52.0 + d * 1.0, 1.85, 1.7).flat(true, true).build());
				part(h, toe + "/sesamoid", bone, ell(s * 12.4 + d * 1.15, -63.5, 57.0, 0.75, 0.75, 0.75));
			}

			part(h, "dewclaws", bone, ell(s * 12.5, -61.0, 58.0, 0.85, 1.35, 0.85));
		}
	}

	/** A cut bone face: the hard pale cortex round the darker marrow cavity (a sheep's file had no cut faces at all). */
	static Mat cutBone = (x, y, z, f, ax, ay, az) -> {
		double d = Math.sqrt(sq(x - Math.rint(x / 12) * 12) + sq(y + 75) + sq(z + 51));
		return d < 0.9 ? mix(0x8E5A46, 0xB98A76, noise(x, y, z, 148)) : mix(0xE4D8BC, 0xCFC09F, noise(x * 2, y * 2, z * 2, 149));
	};

	/**
	 * The head: the neck's seven cervical vertebrae (the atlas with its broad wings, the axis with its tall spine, then
	 * five alike), the skull with its horn cores and horn sheaths, the mandible and the teeth, and the brain in the
	 * hollow braincase. A cow's grazing skull: broad between the eyes, long in the face, no upper front teeth (a hard
	 * dental pad the eight lower ones press against), six cheek teeth a side, the orbits complete bony rings.
	 */
	private static void head() {
		neck();
		skull();
		brain();
	}

	/** The seven cervical vertebrae: C1 the atlas with its broad wings, C2 the axis with its tall spine, then five alike. */
	private static void neck() {
		Mat bone = bone();
		double[] zs = {-134.0, -126.5, -118.5, -110.5, -102.5, -94.5, -86.5};
		for (int i = 0; i < zs.length; i++) {
			double z = zs[i];
			double y = neckY(z);
			String g = String.format(Locale.ROOT, "skeleton/spine/cervical/C%02d", i + 1);
			part(g, "body", bone, tube().p(0, y, z - 3.4, 3.1, 2.9).p(0, neckY(z + 3.4), z + 3.4, 3.1, 2.9).flat(true, true).build());
			part(g, "spinous_process", bone, tube().up((a, b, c) -> new double[] {1, 0, 0})
					.p(0, y + 2.3, z + 1.0, 1.4, 0.6).p(0, y + (i == 1 ? 7.2 : 4.4), z + (i == 1 ? 3.6 : 2.1), 1.1, 0.6).build());
			double wing = i == 0 ? 9.0 : i == 1 ? 5.6 : 3.5;
			double thick = i == 0 ? 2.1 : 0.85;
			for (int s = -1; s <= 1; s += 2) {
				part(g, s > 0 ? "right_transverse_process" : "left_transverse_process", bone, tube().up((a, b, c) -> new double[] {0, 1, 0})
						.p(s * 2.5, y - 0.2, z, 1.6, thick).p(s * wing, y - 0.4, z - 0.6, 1.1, i == 0 ? 2.4 : thick * 0.9).build());
			}
		}
	}

	/**
	 * The skull. The braincase is an ellipsoid shell (cut hollow so the brain fills it) with the nuchal crest and
	 * occiput round the back and the foramen magnum under them for the cord. The face is a long boxy muzzle: maxilla
	 * plates either side, the nasal bridge on top, the hard palate and dental pad below. The orbit is a complete ring of
	 * bone left open as the socket (a cow's is a closed ring); a zygomatic arch bars the cheek. The horn cores grow out
	 * of the frontal bone's cornual processes and carry the horn sheaths. The mandible hinges under the braincase and
	 * meets at the symphysis.
	 */
	private static void skull() {
		Mat bone = bone();
		Mat tooth = glossy(0xF0EAD8, 0xD9D0BA, 0xFCF9EE, 0xFFFFFF, 1.1, 171);
		Mat gum = organic(0xC7A69C, 0xAE8B82, 0xDCC0B7, 0.9, 172);
		Mat horn = organic(0x3A3229, 0x2A241D, 0x55493C, 0.9, 146);
		// Braincase: a solid lump of bone, then hollowed to the cranial cavity the brain fills.
		Shape solid = union(1.4, lumpy(ell(0, 66.5, -112.5, 6.2, 7.0, 9.8), 0.6, 0.95, 163), lumpy(ell(0, 72.5, -105.0, 3.0, 2.3, 3.7), 0.45, 1.3, 164),
				ell(0, 63.5, -103.5, 5.5, 6.0, 3.9));
		Shape cavity = ell(0, 66.5, -112.0, 4.7, 5.4, 8.3);
		Shape cranium = cut(cut(solid, cavity), ell(0, 61.5, -102.3, 2.3, 2.5, 3.5));
		part("skeleton/skull/cranium", "braincase", bone, cranium);
		for (int s = -1; s <= 1; s += 2) {
			part("skeleton/skull/cranium", s > 0 ? "right_occipital_condyle" : "left_occipital_condyle", bone, ell(s * 2.3, 61.0, -103.0, 1.6, 1.4, 2.1));
			String side = s > 0 ? "right" : "left";
			// The horn core: an outgrowth of the frontal bone, and the horn sheath of keratin over it.
			part("skeleton/skull/horns", side + "_horn_core", bone, tube()
					.p(s * 4.2, 70.5, -110.0, 2.2, 2.0).p(s * 9.5, 75.5, -109.0, 1.7, 1.5).p(s * 13.5, 79.5, -110.5, 1.15, 1.05).build());
			part("skeleton/skull/horns", side + "_horn_sheath", horn, tube()
					.p(s * 4.4, 70.5, -110.0, 3.1, 2.9).p(s * 9.8, 75.8, -109.0, 2.5, 2.3).p(s * 13.8, 79.8, -110.5, 1.8, 1.7).flat(false, true).build());
			// Maxilla: the cheek plate either side of the nasal bridge, tapering towards the nose, holding the upper teeth.
			part("skeleton/skull/face", side + "_maxilla", bone, region(v(Math.min(s * 5.3, s * 10.0), 54.0, -142.0), v(Math.max(s * 5.3, s * 10.0), 63.5, -116.0),
					(x, y, z) -> {
						double t = clamp((-116.0 - z) / 26.0, 0, 1);
						return Math.abs(x) >= 5.3 && Math.abs(x) <= 10.0 - 2.0 * t && y >= 54.0 + 1.1 * t && y <= 63.5 - 2.6 * t;
					}));
			// Zygomatic arch: a bar of bone below the eye, sweeping back to the temporal.
			part("skeleton/skull/face", side + "_zygomatic_arch", bone, tube()
					.p(s * 9.6, 61.5, -117.5, 1.25).p(s * 12.2, 62.5, -108.5, 1.25).p(s * 10.3, 63.5, -99.5, 1.15).build());
			// The orbital rim: a complete ring of bone left open as the socket, high and to the side.
			part("skeleton/skull/orbit", side + "_orbital_rim", bone,
					lumpy(torus(new double[] {s * 8.3, 63.5, -111.5}, new double[] {1, 0, 0}, 5.3, 1.25), 0.35, 1.3, 165));
		}

		// Nasal bridge: a flat wide bar along the top of the muzzle.
		part("skeleton/skull/face", "nasal_bones", bone, tube().up((a, b, c) -> new double[] {0, 1, 0})
				.p(0, 64.5, -116.0, 3.9, 1.4).p(0, 61.5, -131.0, 3.5, 1.4).p(0, 58.0, -144.0, 2.8, 1.3).p(0, 55.5, -149.0, 2.1, 1.15).build());
		// The hard palate and, at its front, the dental pad a cow's lower incisors press on.
		part("skeleton/skull/face", "hard_palate", bone, region(v(-4.4, 53.4, -143.0), v(4.4, 54.5, -118.0), (x, y, z) -> true));
		part("skeleton/skull/face", "dental_pad", gum, region(v(-4.1, 53.4, -149.5), v(4.1, 55.8, -143.0), (x, y, z) -> true));
		part("skeleton/skull/face", "incisive_bone", bone, tube().up((a, b, c) -> new double[] {0, 1, 0})
				.p(-4.4, 55.0, -144.0, 1.25).p(0, 55.9, -150.5, 1.35).p(4.4, 55.0, -144.0, 1.25).build());
		mandible(bone, tooth);
	}

	/** The mandible: two halves rising to a hinge under the braincase, carrying the cheek teeth forward to the symphysis. */
	private static void mandible(Mat bone, Mat tooth) {
		for (int s = -1; s <= 1; s += 2) {
			String g = "skeleton/skull/mandible/" + (s > 0 ? "right" : "left");
			part(g, "ramus_and_body", bone, tube().up((a, b, c) -> new double[] {0, 1, 0})
					.p(s * 4.8, 60.5, -104.5, 1.7, 2.1).p(s * 6.9, 54.0, -113.0, 1.8, 2.5).p(s * 7.6, 50.5, -123.0, 1.9, 2.3)
					.p(s * 6.9, 51.5, -134.0, 1.8, 2.2).p(s * 5.1, 52.5, -144.5, 1.6, 2.0).p(s * 0.9, 52.5, -151.5, 1.25, 1.6).build());
			part(g, "coronoid_process", bone, tube().up((a, b, c) -> new double[] {0, 1, 0})
					.p(s * 5.5, 61.5, -110.5, 1.25, 1.15).p(s * 5.3, 65.0, -114.0, 0.9, 0.8).build());
			part(g, "condyle", bone, ell(s * 4.8, 60.5, -104.0, 1.6, 1.25, 1.4));
		}

		part("skeleton/skull/mandible/symphysis", "symphysis", bone, ell(0, 52.5, -151.8, 1.6, 1.4, 1.85));
		// Teeth: six selenodont cheek teeth a side, upper and lower, and the eight lower incisors at the front.
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right" : "left";
			part("skeleton/skull/teeth", side + "_upper_cheek_teeth", tooth, tube().up((a, b, c) -> new double[] {0, 1, 0})
					.p(s * 6.9, 54.0, -117.5, 1.8, 2.1).p(s * 6.6, 53.5, -128.5, 1.95, 2.2).p(s * 6.1, 53.0, -139.0, 1.8, 2.1).beads(0.6, 3.4).build());
			part("skeleton/skull/teeth", side + "_lower_cheek_teeth", tooth, tube().up((a, b, c) -> new double[] {0, 1, 0})
					.p(s * 6.6, 51.5, -117.5, 1.7, 2.0).p(s * 6.3, 51.2, -128.5, 1.85, 2.1).p(s * 5.8, 51.0, -139.0, 1.7, 2.0).beads(0.6, 3.4).build());
		}

		part("skeleton/skull/teeth", "lower_incisors", tooth, tube().up((a, b, c) -> new double[] {0, 1, 0})
				.p(-3.9, 52.5, -148.5, 1.15, 2.1).p(0, 52.9, -151.5, 1.15, 2.3).p(3.9, 52.5, -148.5, 1.15, 2.1).beads(0.7, 2.5).build());
	}

	/** The brain, filling the hollow braincase: the folded cerebrum, the wrinkled cerebellum, the stem to the cord. */
	private static void brain() {
		Mat cerebrum = organic(0xC9A3A0, 0xA87E7C, 0xDDC0BC, 1.15, 181);
		Mat folds = organic(0xB98C89, 0x9A6E6C, 0xCDAAA6, 1.5, 182);
		part("head/brain/cerebrum", "cerebrum", cerebrum, lumpy(ell(0, 66.8, -113.5, 4.2, 4.8, 6.6), 0.8, 2.4, 183));
		part("head/brain/cerebellum", "cerebellum", folds, lumpy(ell(0, 63.5, -106.5, 3.4, 2.8, 2.6), 0.7, 3.2, 184));
		part("head/brain/brainstem", "brainstem", folds, tube().p(0, 65.0, -107.5, 1.7).p(0, 62.5, -104.5, 1.5).p(0, 60.5, -102.5, 1.4).build());
	}

	/**
	 * Enough of the body wall to sit the organs in: the layers of a representative cutaway at the left flank (skin,
	 * fat, muscle in section), the great fat deposits (the suet over the kidneys and in the mesentery is already with
	 * them), and broad muscle masses over the trunk and limbs that an exposed-body scene can show. Sheets and grouped
	 * blocks at this scale, never microscopic detail.
	 */
	private static void bodyLayers() {
		Mat hide = organic(0x9A7A5E, 0x7E6248, 0xB29276, 1.1, 191);
		Mat fat = crumbly(0xE8D6AE, 0xD4BF92, 0xF6EBCE, 192);
		Mat beef = streaked(0x9B4A3E, 0x7C342A, 0xB96A58, 193);
		// A section of the flank: the three layers the mod burns through, from the hide inwards.
		part("body_wall/flank_cutaway", "skin", hide, region(v(-30, 2, -20), v(-18, 70, 30), (x, y, z) -> {
			double d = (x + 26.0) * (x + 26.0) + sq((y - 38) / 1.6) + sq((z - 5) / 1.6);
			return d > 210 && d < 260 && z > -6 && z < 16;
		}));
		part("body_wall/flank_cutaway", "fat", fat, region(v(-30, 2, -20), v(-18, 70, 30), (x, y, z) -> {
			double d = (x + 26.0) * (x + 26.0) + sq((y - 38) / 1.6) + sq((z - 5) / 1.6);
			return d > 150 && d <= 210 && z > -6 && z < 16;
		}));
		part("body_wall/flank_cutaway", "muscle", beef, region(v(-30, 2, -20), v(-18, 70, 30), (x, y, z) -> {
			double d = (x + 26.0) * (x + 26.0) + sq((y - 38) / 1.6) + sq((z - 5) / 1.6);
			return d > 84 && d <= 150 && z > -6 && z < 16;
		}));
		// The broad muscle groups: the longissimus over the back, the shoulder and haunch masses, the neck's.
		part("body_wall/muscle_groups", "longissimus_dorsi", beef, region(v(-16, 56, -46), v(16, 76, 34),
				(x, y, z) -> sq(x / 15.5) + sq((y - 68) / 9.5) < 1 && y < 71.5 - 0.04 * Math.abs(z + 6)));
		for (int s = -1; s <= 1; s += 2) {
			int sign = s;
			part("body_wall/muscle_groups", s > 0 ? "right_triceps" : "left_triceps", beef,
					region(v(s * 6, 2, -74), v(s * 27, 40, -40), (x, y, z) -> sq((x - sign * 17) / 10.5) + sq((y - 22) / 16.5) + sq((z + 57) / 13.5) < 1));
			part("body_wall/muscle_groups", s > 0 ? "right_quadriceps" : "left_quadriceps", beef,
					region(v(s * 6, 2, 40), v(s * 27, 46, 82), (x, y, z) -> sq((x - sign * 15) / 10.5) + sq((y - 24) / 20.5) + sq((z - 62) / 17.5) < 1));
			part("body_wall/muscle_groups", s > 0 ? "right_brachiocephalus" : "left_brachiocephalus", beef,
					region(v(s * 2, 30, -132), v(s * 24, 72, -70), (x, y, z) -> sq((x - sign * 11) / 9.5) + sq((y - 54) / 16.5) + sq((z + 100) / 26.5) < 1));
		}

		// The fat of the trunk: the suet slab over the kidneys and the omentum's sheet over the rumen.
		Mat suet = crumbly(0xF1E9D4, 0xDDD0B0, 0xFBF7EC, 53);
		part("body_wall/fat_deposits", "suet_over_loin", suet, region(v(-6, 60, 0), v(20, 74, 40),
				(x, y, z) -> sq((x - 7) / 12.5) + sq((y - 68) / 5.5) + sq((z - 20) / 18.5) < 1));
		part("body_wall/fat_deposits", "omental_fat", suet, region(v(-30, 26, -6), v(16, 62, 44),
				(x, y, z) -> sq((x + 8) / 18.5) + sq((y - 44) / 15.5) + sq((z - 18) / 21.5) > 1 && sq((x + 8) / 19.5) + sq((y - 44) / 16.5) + sq((z - 18) / 22.5) < 1));
	}
	// --------------------------------------------------------------------------------------------------- shapes

	interface Field {
		/** Below 0 inside; for the simple shapes roughly the distance to the surface in cm. */
		double at(double x, double y, double z);
	}

	interface P3 {
		boolean test(double x, double y, double z);
	}

	interface Up {
		double[] at(double x, double y, double z);
	}

	/** A field with the box outside which it is certainly outside. */
	static final class Shape {
		final double[] lo;
		final double[] hi;
		final Field f;

		Shape(double[] lo, double[] hi, Field f) {
			this.lo = lo;
			this.hi = hi;
			this.f = f;
		}

		boolean in(double x, double y, double z) {
			return x >= lo[0] && x <= hi[0] && y >= lo[1] && y <= hi[1] && z >= lo[2] && z <= hi[2] && f.at(x, y, z) < 0;
		}
	}

	static Shape ell(double cx, double cy, double cz, double rx, double ry, double rz) {
		double p = PAD;
		return new Shape(v(cx - rx - p, cy - ry - p, cz - rz - p), v(cx + rx + p, cy + ry + p, cz + rz + p), (x, y, z) -> {
			double ax = Math.max(rx, VOX * THIN);
			double ay = Math.max(ry, VOX * THIN);
			double az = Math.max(rz, VOX * THIN);
			double dx = (x - cx) / ax;
			double dy = (y - cy) / ay;
			double dz = (z - cz) / az;
			return (Math.sqrt(dx * dx + dy * dy + dz * dz) - 1) * Math.min(ax, Math.min(ay, az));
		});
	}

	static Shape torus(double[] c, double[] axis, double big, double small) {
		double e = big + small + PAD;
		return new Shape(v(c[0] - e, c[1] - e, c[2] - e), v(c[0] + e, c[1] + e, c[2] + e), (x, y, z) -> {
			double[] q = {x - c[0], y - c[1], z - c[2]};
			double h = dot(q, axis);
			double rho = len(sub(q, mul(axis, h)));
			return Math.hypot(rho - big, h) - Math.max(small, VOX * THIN);
		});
	}

	/** A thick triangle with rounded edges: the flat bones. */
	static Shape tri(double[] a, double[] b, double[] c, double half) {
		double[] lo = new double[3];
		double[] hi = new double[3];
		for (int i = 0; i < 3; i++) {
			lo[i] = Math.min(a[i], Math.min(b[i], c[i])) - half - PAD;
			hi[i] = Math.max(a[i], Math.max(b[i], c[i])) + half + PAD;
		}

		return new Shape(lo, hi, (x, y, z) -> distTriangle(new double[] {x, y, z}, a, b, c) - Math.max(half, VOX * THIN));
	}

	static Shape region(double[] lo, double[] hi, P3 inside) {
		return new Shape(lo, hi, (x, y, z) -> x >= lo[0] && x <= hi[0] && y >= lo[1] && y <= hi[1] && z >= lo[2] && z <= hi[2] && inside.test(x, y, z) ? -1 : 1);
	}

	static Shape union(double k, Shape... s) {
		double[] lo = s[0].lo.clone();
		double[] hi = s[0].hi.clone();
		for (Shape t : s) {
			for (int i = 0; i < 3; i++) {
				lo[i] = Math.min(lo[i], t.lo[i] - k * 0.25);
				hi[i] = Math.max(hi[i], t.hi[i] + k * 0.25);
			}
		}

		return new Shape(lo, hi, (x, y, z) -> {
			double d = s[0].f.at(x, y, z);
			for (int i = 1; i < s.length; i++) {
				d = smin(d, s[i].f.at(x, y, z), k);
			}

			return d;
		});
	}

	/** How much the smooth union of {@code s} adds at a point beyond the plain union: above 0 in the grooves between them. */
	static double crease(Shape[] s, double k, double x, double y, double z) {
		double plain = Double.MAX_VALUE;
		double smooth = 0;
		for (int i = 0; i < s.length; i++) {
			double d = s[i].f.at(x, y, z);
			plain = Math.min(plain, d);
			smooth = i == 0 ? d : smin(smooth, d, k);
		}

		return plain - smooth;
	}

	static Shape cut(Shape a, Shape b) {
		return new Shape(a.lo, a.hi, (x, y, z) -> {
			double d = a.f.at(x, y, z);
			return d >= 0 ? d : Math.max(d, -b.f.at(x, y, z));
		});
	}

	static Shape both(Shape a, Shape b) {
		double[] lo = new double[3];
		double[] hi = new double[3];
		for (int i = 0; i < 3; i++) {
			lo[i] = Math.max(a.lo[i], b.lo[i]);
			hi[i] = Math.min(a.hi[i], b.hi[i]);
		}

		return new Shape(lo, hi, (x, y, z) -> Math.max(a.f.at(x, y, z), b.f.at(x, y, z)));
	}

	/** An organic, lumpy surface: the field pushed in and out by noise. */
	static Shape lumpy(Shape a, double amp, double freq, int seed) {
		return new Shape(v(a.lo[0] - amp, a.lo[1] - amp, a.lo[2] - amp), v(a.hi[0] + amp, a.hi[1] + amp, a.hi[2] + amp),
				(x, y, z) -> a.f.at(x, y, z) + amp * (2 * noise(x * freq, y * freq, z * freq, seed) - 1));
	}

	static Tube tube() {
		return new Tube();
	}

	/**
	 * A tube along a smooth curve through control points, each with its own radii. With {@link #up} its cross-section
	 * is an ellipse whose second radius lies along the given direction (a rib's thickness along the body's outward
	 * normal); otherwise round. Ends are rounded unless {@link #flat}: a cut vessel or gut, which with {@link #hollow}
	 * shows its lumen.
	 */
	static final class Tube {
		private final List<double[]> ctrl = new ArrayList<>();
		private Up up;
		private boolean flat0;
		private boolean flat1;
		private double hollow;
		private double beadAmp;
		private double beadLen = 1;

		Tube p(double x, double y, double z, double r) {
			return p(x, y, z, r, r);
		}

		Tube p(double x, double y, double z, double rx, double ry) {
			ctrl.add(new double[] {x, y, z, rx, ry});
			return this;
		}

		Tube up(Up u) {
			up = u;
			return this;
		}

		Tube flat(boolean start, boolean end) {
			flat0 = start;
			flat1 = end;
			return this;
		}

		Tube hollow(double ratio) {
			hollow = ratio;
			return this;
		}

		/** Pellets forming inside: the radius swells and narrows every {@code len} cm. */
		Tube beads(double amp, double len) {
			beadAmp = amp;
			beadLen = len;
			return this;
		}

		Shape build() {
			List<double[]> s = new ArrayList<>();
			int n = ctrl.size();
			for (int i = 0; i < n - 1; i++) {
				double[] p0 = i > 0 ? ctrl.get(i - 1) : extrapolate(ctrl.get(0), ctrl.get(1));
				double[] p1 = ctrl.get(i);
				double[] p2 = ctrl.get(i + 1);
				double[] p3 = i + 2 < n ? ctrl.get(i + 2) : extrapolate(ctrl.get(n - 1), ctrl.get(n - 2));
				int steps = Math.max(2, (int) Math.ceil(dist(p1, p2) / 0.3));
				for (int k = 0; k < steps; k++) {
					double t = k / (double) steps;
					double[] q = new double[5];
					for (int a = 0; a < 3; a++) {
						q[a] = catmull(p0[a], p1[a], p2[a], p3[a], t);
					}

					q[3] = p1[3] + (p2[3] - p1[3]) * t;
					q[4] = p1[4] + (p2[4] - p1[4]) * t;
					s.add(q);
				}
			}

			s.add(ctrl.get(n - 1).clone());
			int m = s.size();
			double[][] pt = s.toArray(double[][]::new);
			double[] cum = new double[m];
			for (int i = 1; i < m; i++) {
				cum[i] = cum[i - 1] + dist(pt[i - 1], pt[i]);
			}

			double[][] tan = new double[m - 1][];
			double[][] nrm = new double[m - 1][];
			double[][] bin = new double[m - 1][];
			double[] lo = {1e9, 1e9, 1e9};
			double[] hi = {-1e9, -1e9, -1e9};
			for (int i = 0; i < m - 1; i++) {
				tan[i] = norm(sub(pt[i + 1], pt[i]));
				if (up != null) {
					double[] mid = mul(add(pt[i], pt[i + 1]), 0.5);
					double[] u = up.at(mid[0], mid[1], mid[2]);
					double[] nn = sub(u, mul(tan[i], dot(u, tan[i])));
					if (len(nn) < 1e-6) {
						nn = Math.abs(tan[i][1]) < 0.9 ? new double[] {0, 1, 0} : new double[] {1, 0, 0};
						nn = sub(nn, mul(tan[i], dot(nn, tan[i])));
					}

					nrm[i] = norm(nn);
					bin[i] = cross(tan[i], nrm[i]);
				}
			}

			for (double[] q : pt) {
				double r = Math.max(q[3], q[4]) * (1 + beadAmp) + PAD;
				for (int a = 0; a < 3; a++) {
					lo[a] = Math.min(lo[a], q[a] - r);
					hi[a] = Math.max(hi[a], q[a] + r);
				}
			}

			boolean f0 = flat0;
			boolean f1 = flat1;
			double hol = hollow;
			double ba = beadAmp;
			double bl = beadLen;
			Up hasUp = up;
			return new Shape(lo, hi, (x, y, z) -> {
				int best = 0;
				double bestD = Double.MAX_VALUE;
				double bestT = 0;
				for (int i = 0; i < m - 1; i++) {
					double[] a = pt[i];
					double dx = pt[i + 1][0] - a[0];
					double dy = pt[i + 1][1] - a[1];
					double dz = pt[i + 1][2] - a[2];
					double l2 = dx * dx + dy * dy + dz * dz;
					double t = l2 == 0 ? 0 : ((x - a[0]) * dx + (y - a[1]) * dy + (z - a[2]) * dz) / l2;
					t = t < 0 ? 0 : t > 1 ? 1 : t;
					double ex = x - a[0] - dx * t;
					double ey = y - a[1] - dy * t;
					double ez = z - a[2] - dz * t;
					double d = ex * ex + ey * ey + ez * ez;
					if (d < bestD) {
						bestD = d;
						best = i;
						bestT = t;
					}
				}

				double[] a = pt[best];
				double[] b = pt[best + 1];
				double[] q = {a[0] + (b[0] - a[0]) * bestT, a[1] + (b[1] - a[1]) * bestT, a[2] + (b[2] - a[2]) * bestT};
				double[] o = {x - q[0], y - q[1], z - q[2]};
				double[] t = tan[best];
				double along = dot(o, t);
				double ot = 0;
				if (best == 0 && bestT <= 0 && along < 0) {
					if (f0) {
						return 1;
					}

					ot = along;
				} else if (best == m - 2 && bestT >= 1 && along > 0) {
					if (f1) {
						return 1;
					}

					ot = along;
				}

				double[] op = sub(o, mul(t, along));
				double scale = 1;
				if (ba > 0) {
					double c = cum[best] + (cum[best + 1] - cum[best]) * bestT;
					scale = 1 + ba * Math.sin(2 * Math.PI * c / bl);
				}

				double rx = Math.max((a[3] + (b[3] - a[3]) * bestT) * scale, VOX * THIN);
				double ry = Math.max((a[4] + (b[4] - a[4]) * bestT) * scale, VOX * THIN);
				double e;
				if (hasUp != null) {
					double cb = dot(op, bin[best]);
					double cn = dot(op, nrm[best]);
					e = Math.sqrt(sq(cb / rx) + sq(cn / ry) + sq(ot / rx));
				} else {
					e = Math.sqrt(dot(op, op) + ot * ot) / rx;
				}

				double d = (e - 1) * Math.min(rx, ry);
				if (hol > 0 && d < 0 && e < hol && Math.min(rx, ry) * (1 - hol) >= VOX * 0.5) {
					return (hol - e) * Math.min(rx, ry);
				}

				return d;
			});
		}

		private static double[] extrapolate(double[] end, double[] next) {
			return new double[] {2 * end[0] - next[0], 2 * end[1] - next[1], 2 * end[2] - next[2], end[3], end[4]};
		}
	}

	static double catmull(double p0, double p1, double p2, double p3, double t) {
		double t2 = t * t;
		double t3 = t2 * t;
		return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
	}

	static double distTriangle(double[] p, double[] a, double[] b, double[] c) {
		double[] ab = sub(b, a);
		double[] ac = sub(c, a);
		double[] ap = sub(p, a);
		double d1 = dot(ab, ap);
		double d2 = dot(ac, ap);
		if (d1 <= 0 && d2 <= 0) {
			return len(ap);
		}

		double[] bp = sub(p, b);
		double d3 = dot(ab, bp);
		double d4 = dot(ac, bp);
		if (d3 >= 0 && d4 <= d3) {
			return len(bp);
		}

		double vc = d1 * d4 - d3 * d2;
		if (vc <= 0 && d1 >= 0 && d3 <= 0) {
			return len(sub(p, add(a, mul(ab, d1 / (d1 - d3)))));
		}

		double[] cp = sub(p, c);
		double d5 = dot(ab, cp);
		double d6 = dot(ac, cp);
		if (d6 >= 0 && d5 <= d6) {
			return len(cp);
		}

		double vb = d5 * d2 - d1 * d6;
		if (vb <= 0 && d2 >= 0 && d6 <= 0) {
			return len(sub(p, add(a, mul(ac, d2 / (d2 - d6)))));
		}

		double va = d3 * d6 - d5 * d4;
		if (va <= 0 && d4 - d3 >= 0 && d5 - d6 >= 0) {
			return len(sub(p, add(b, mul(sub(c, b), (d4 - d3) / ((d4 - d3) + (d5 - d6))))));
		}

		double den = 1 / (va + vb + vc);
		return len(sub(p, add(a, add(mul(ab, vb * den), mul(ac, vc * den)))));
	}

	// ------------------------------------------------------------------------------------------------ tissues

	/** A tissue's colour at a voxel's centre, looking out of {@code face} into the empty voxel centred at (ax, ay, az). */
	interface Mat {
		int rgb(double x, double y, double z, int face, double ax, double ay, double az);
	}

	static Mat organic(int base, int dark, int light, double scale, int seed) {
		return (x, y, z, f, ax, ay, az) -> {
			double n = noise(x * scale, y * scale, z * scale, seed) * 0.7 + noise(x * scale * 3, y * scale * 3, z * scale * 3, seed + 1) * 0.3;
			return n < 0.5 ? mix(dark, base, n * 2) : mix(base, light, (n - 0.5) * 2);
		};
	}

	static Mat glossy(int base, int dark, int light, int sheen, double scale, int seed) {
		Mat m = organic(base, dark, light, scale, seed);
		return (x, y, z, f, ax, ay, az) -> {
			int c = m.rgb(x, y, z, f, ax, ay, az);
			return f == 2 && noise(x * 2.5, y * 2.5, z * 2.5, seed + 7) > 0.8 ? mix(c, sheen, 0.6) : c;
		};
	}

	static Mat veined(int base, int dark, int light, int vessel, int seed) {
		Mat m = organic(base, dark, light, 1.2, seed);
		return (x, y, z, f, ax, ay, az) -> {
			int c = m.rgb(x, y, z, f, ax, ay, az);
			return ridge(x * 0.35, y * 0.35, z * 0.35, seed + 5) > 0.9 ? mix(c, vessel, 0.55) : c;
		};
	}

	static Mat streaked(int base, int dark, int light, int seed) {
		return (x, y, z, f, ax, ay, az) -> mix(mix(dark, light, noise(x * 3, y * 3, z * 0.4, seed)), base, 0.4);
	}

	static Mat crumbly(int base, int dark, int light, int seed) {
		return (x, y, z, f, ax, ay, az) -> {
			double[] c = cells(x * 1.4, y * 1.4, z * 1.4, seed);
			return c[1] - c[0] < 0.1 ? dark : mix(base, light, c[2]);
		};
	}

	static Mat heartMuscle(double[] apex, double[] axis) {
		return (x, y, z, f, ax, ay, az) -> {
			double[] p = {x - apex[0], y - apex[1], z - apex[2]};
			double h = dot(p, axis);
			double around = Math.atan2(p[2], p[0]);
			double streak = frac((around * 2.2 + h * 0.35) + 0.3 * noise(x, y, z, 26));
			int c = mix(0x5A1717, 0x7A2623, noise(x * 1.2, y * 1.2, z * 1.2, 27));
			c = streak < 0.25 ? mix(c, 0x4C1212, 0.4) : c;
			return noise(x * 3, y * 3, z * 3, 28) > 0.88 ? mix(c, 0x9A4E4A, 0.5) : c;
		};
	}

	// ------------------------------------------------------------------------------------------------ parts

	/** One named piece of an organ, at a path of groups ("abdomen/liver/caudate_lobe"). */
	static final class Part {
		final String path;
		final String name;
		final Mat mat;
		final Shape shape;
		int index;

		Part(String path, String name, Mat mat, Shape shape) {
			this.path = path;
			this.name = name;
			this.mat = mat;
			this.shape = shape;
		}

		boolean under(String prefix) {
			return path.equals(prefix) || path.startsWith(prefix + "/");
		}
	}

	/** Vessels, ducts and small processes thinner than a pixel: left out at the mod's coarse grain, where they would fatten to a pixel. */
	private static final java.util.Set<String> FINE = java.util.Set.of("disc", "median_sacral_crest", "spine_of_scapula", "renal_vein", "renal_artery",
			"ureter", "right_ureter", "left_ureter", "urethra", "brachiocephalic_trunk", "left_pulmonary_artery", "right_pulmonary_artery",
			"cranial_vena_cava", "caudal_vena_cava", "thoracic_caudal_vena_cava", "abdominal_caudal_vena_cava", "portal_vein",
			"hepatic_caudal_vena_cava", "tracheal_bronchus", "left_principal_bronchus", "right_principal_bronchus", "scapular_cartilage",
			"flexor_tendon", "achilles_tendon", "sesamoid", "dewclaws", "bile_duct", "hepatic_vessel", "chordae_tendineae", "papillary_muscle",
			"bronchus_branch", "teat", "median_suspensory_ligament");

	static void part(String path, String name, Mat mat, Shape shape) {
		if (LOD >= 2.5 && FINE.contains(name)) {
			return;
		}

		PARTS.add(new Part(path, name, mat, shape));
	}

	/**
	 * What the mod calls a part: its {@code Anatomy.Kind} (the tile it is drawn with and how it cooks) and the organ it
	 * belongs to (a translation id, {@code fluidgore.organ.<name>}). Every piece of one organ shares the name, so the mod
	 * can keep the organ one object: the gallbladder and the liver's veins are the liver's, the coronary fat the heart's.
	 */
	static String[] modPiece(Part p) {
		String path = p.path;
		String n = p.name;
		if (p.under("skeleton/spine/thoracic")) {
			int t = Integer.parseInt(path.substring(path.lastIndexOf('T') + 1));
			return n.equals("body") ? new String[] {"VERTEBRA", "thoracic_spine"} : new String[] {"BONE", t <= 7 && n.equals("spinous_process") ? "withers" : "thoracic_spine"};
		}

		if (p.under("skeleton/spine/lumbar")) {
			return new String[] {n.equals("body") ? "VERTEBRA" : "BONE", "lumbar_spine"};
		}

		if (p.under("skeleton/spine/sacrum")) {
			return new String[] {"BONE", "sacrum"};
		}

		if (p.under("skeleton/spine/tail")) {
			return new String[] {"VERTEBRA", "tail_vertebrae"};
		}

		if (p.under("skeleton/spine/cervical")) {
			return new String[] {n.equals("body") ? "VERTEBRA" : "BONE", "neck_spine"};
		}

		if (p.under("skeleton/skull/teeth")) {
			return new String[] {"TEETH", n.equals("lower_incisors") ? "incisors" : "teeth"};
		}

		if (p.under("skeleton/skull/mandible")) {
			return new String[] {"SKULL", "jawbone"};
		}

		if (p.under("skeleton/skull/horns")) {
			return new String[] {n.contains("sheath") ? "HORN" : "BONE", "horn"};
		}

		if (p.under("skeleton/skull")) {
			return new String[] {"SKULL", n.contains("dental_pad") ? "dental_pad" : n.contains("condyle") ? "cranium" : "skull"};
		}

		if (p.under("head/brain")) {
			return new String[] {"BRAIN", "brain"};
		}

		if (p.under("skeleton/ribcage")) {
			return new String[] {n.equals("rib") ? "BONE" : "CARTILAGE", "rib"};
		}

		if (p.under("skeleton/sternum")) {
			return n.equals("xiphoid_cartilage") ? new String[] {"CARTILAGE", "xiphoid"} : new String[] {"BONE", "breastbone"};
		}

		if (p.under("skeleton/shoulder_blades")) {
			return n.equals("glenoid") ? new String[] {"BONE_END", "shoulder_joint"} : new String[] {n.equals("scapular_cartilage") ? "CARTILAGE" : "BONE", "shoulder_blade"};
		}

		if (p.under("skeleton/pelvis")) {
			return switch (n) {
				case "acetabulum" -> new String[] {"BONE_END", "hip_socket"};
				case "pubis" -> new String[] {"BONE", "pubic_bone"};
				case "ischium" -> new String[] {"BONE", "pin_bone"};
				default -> new String[] {"BONE", "hip_bone"};
			};
		}

		if (p.under("skeleton/limbs")) {
			if (n.contains("hoof")) {
				return new String[] {"HOOF", "hoof"};
			}

			if (n.contains("phalanx") || n.contains("phalanges") || n.equals("dewclaws")) {
				return new String[] {n.contains("distal") ? "HOOF" : "PHALANGES", "toe_bones"};
			}

			if (n.contains("tendon")) {
				return new String[] {"CARTILAGE", n.contains("achilles") ? "achilles_tendon" : "flexor_tendon"};
			}

			if (n.contains("carpus") || n.contains("tarsus") || n.equals("fetlock")) {
				return new String[] {"BONE_END", n.contains("carpus") ? "knee_carpus" : n.contains("tarsus") ? "hock" : "fetlock"};
			}

			return new String[] {"BONE", n.contains("humerus") ? "humerus" : n.contains("radius") ? "radius" : n.contains("ulna") ? "ulna"
					: n.contains("femur") ? "femur" : n.contains("tibia") ? "tibia" : n.contains("cannon") ? "cannon_bone" : n.contains("kneecap") ? "kneecap"
							: n.contains("sesamoid") ? "sesamoid" : n.contains("point_of_elbow") ? "point_of_elbow" : n.contains("point_of_hock") ? "point_of_hock"
									: "toe_bones"};
		}

		if (p.under("thorax/heart")) {
			String kind = p.under("thorax/heart/coronary_fat") ? "SUET" : p.under("thorax/heart/aorta") || p.under("thorax/heart/pulmonary_trunk") ? "AORTA"
					: p.under("thorax/heart/venae_cavae") ? "VEIN" : n.contains("valve") || n.contains("chordae") ? "CARTILAGE" : "HEART";
			return new String[] {kind, "heart"};
		}

		if (p.under("thorax/airway")) {
			return new String[] {"TRACHEA", "windpipe"};
		}

		if (p.under("thorax/esophagus") || p.under("abdomen/stomach/esophagus")) {
			return new String[] {"STOMACH", "esophagus"};
		}

		if (p.under("thorax/lungs")) {
			return new String[] {n.contains("bronchus") ? "TRACHEA" : n.contains("blood") ? "VEIN" : "LUNG", n.contains("bronchus") ? "windpipe" : "lung"};
		}

		if (p.under("thorax/diaphragm")) {
			return new String[] {"MUSCLE", "diaphragm"};
		}

		if (p.under("vessels/aorta")) {
			return new String[] {"AORTA", "aorta"};
		}

		if (p.under("vessels/venae_cavae")) {
			return new String[] {"VEIN", "vena_cava"};
		}

		if (p.under("abdomen/liver")) {
			return new String[] {p.under("abdomen/liver/gallbladder") || n.contains("bile") ? "GALLBLADDER" : n.contains("vessel") || n.contains("portal") || n.contains("vena")
					? "VEIN" : "LIVER", p.under("abdomen/liver/gallbladder") || n.contains("bile") ? "gallbladder" : "liver"};
		}

		if (p.under("abdomen/spleen")) {
			return new String[] {"SPLEEN", "spleen"};
		}

		if (p.under("abdomen/kidneys")) {
			return switch (n) {
				case "suet" -> new String[] {"SUET", "kidney_fat"};
				case "renal_vein" -> new String[] {"VEIN", "kidney"};
				case "ureter" -> new String[] {"BLADDER", "bladder"};
				case "renal_artery" -> new String[] {"AORTA", "kidney"};
				default -> new String[] {"KIDNEY", "kidney"};
			};
		}

		if (p.under("abdomen/stomach/rumen") || n.equals("rumen_contents")) {
			return new String[] {"RUMEN", n.equals("rumen_contents") ? "cud" : "rumen"};
		}

		if (p.under("abdomen/stomach/reticulum")) {
			return new String[] {"RETICULUM", "reticulum"};
		}

		if (p.under("abdomen/stomach/omasum")) {
			return new String[] {"STOMACH", "omasum"};
		}

		if (p.under("abdomen/stomach/abomasum")) {
			return new String[] {n.contains("fluid") ? "STOMACH" : "STOMACH", "abomasum"};
		}

		if (p.under("abdomen/intestines/spiral_colon")) {
			return new String[] {"COLON", "spiral_colon"};
		}

		if (p.under("abdomen/intestines/caecum")) {
			return new String[] {"COLON", "caecum"};
		}

		if (p.under("abdomen/intestines/proximal_colon") || p.under("abdomen/intestines/distal_colon") || p.under("abdomen/intestines/rectum")) {
			return new String[] {"COLON", "colon"};
		}

		if (p.under("abdomen/intestines/mesentery")) {
			return new String[] {"SUET", "mesentery"};
		}

		if (p.under("abdomen/intestines")) {
			return new String[] {n.contains("digesta") ? "COLON" : "INTESTINE", n.contains("digesta") ? "gut" : "small_intestine"};
		}

		if (p.under("abdomen/urinary")) {
			return new String[] {"BLADDER", "bladder"};
		}

		if (p.under("abdomen/udder")) {
			return new String[] {n.equals("teat") ? "BLADDER" : "FAT", "udder"};
		}

		if (p.under("body_wall")) {
			if (n.equals("skin")) {
				return new String[] {"DERMIS", "skin"};
			}

			if (n.equals("fat") || n.contains("fat") || n.contains("suet") || n.contains("omental")) {
				return new String[] {"SUET", n.contains("omental") ? "fat" : "suet"};
			}

			return new String[] {"BEEF", "muscle"};
		}

		throw new IllegalStateException("No tissue for " + path + "/" + n);
	}

	/** Each Kind's tile in anatomy.png (the order of {@code Anatomy.Kind}). */
	private static final Map<String, Integer> TILE = Map.ofEntries(Map.entry("BONE", 0), Map.entry("BONE_END", 1), Map.entry("CARTILAGE", 6),
			Map.entry("VERTEBRA", 7), Map.entry("MUSCLE", 8), Map.entry("DERMIS", 14), Map.entry("FAT", 13), Map.entry("LUNG", 16), Map.entry("HEART", 17),
			Map.entry("AORTA", 18), Map.entry("LIVER", 19), Map.entry("STOMACH", 20), Map.entry("INTESTINE", 21), Map.entry("COLON", 22),
			Map.entry("KIDNEY", 23), Map.entry("SPLEEN", 24), Map.entry("BLADDER", 25), Map.entry("TRACHEA", 26), Map.entry("BEEF", 42),
			Map.entry("SUET", 38), Map.entry("RUMEN", 45), Map.entry("GALLBLADDER", 49), Map.entry("RETICULUM", 50), Map.entry("VEIN", 51),
			Map.entry("SKULL", 2), Map.entry("TEETH", 3), Map.entry("SOCKET", 4), Map.entry("BRAIN", 31), Map.entry("PHALANGES", 32),
			Map.entry("HOOF", 43), Map.entry("HORN", 44));

	static int tile(Part p) {
		return TILE.get(modPiece(p)[0]);
	}

	// ------------------------------------------------------------------------------------------------ export

	/** Faces: 0 east +x, 1 west -x, 2 up +y, 3 down -y, 4 south +z, 5 north -z. */
	private static final String[] FACE = {"east", "west", "up", "down", "south", "north"};
	private static final int[][] NORMAL = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	static final class Grid {
		final int nx;
		final int ny;
		final int nz;
		final double vox;
		final double ox;
		final double oy;
		final double oz;
		final int[] cell;

		Grid(int nx, int ny, int nz, double vox, double ox, double oy, double oz) {
			this.nx = nx;
			this.ny = ny;
			this.nz = nz;
			this.vox = vox;
			this.ox = ox;
			this.oy = oy;
			this.oz = oz;
			cell = new int[nx * ny * nz];
		}

		int at(int x, int y, int z) {
			return x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz ? 0 : cell[(x * ny + y) * nz + z];
		}

		void set(int x, int y, int z, int v) {
			cell[(x * ny + y) * nz + z] = v;
		}

		double wx(int i) {
			return ox + (i + 0.5) * vox;
		}

		double wy(int j) {
			return oy + (j + 0.5) * vox;
		}

		double wz(int k) {
			return oz + (k + 0.5) * vox;
		}

		/** A voxel centre in the body's centimetres, where the shapes and tissues are defined; null outside every fit. */
		double[] cm(int i, int j, int k) {
			return cmAt(wx(i), wy(j), wz(k));
		}
	}

	/** Centimetres per voxel, for choosing detail: every rib, or only some with a gap between them. */
	static double LOD = 0.5;
	/** Mod mode: anatomy.png, each part drawn with its tissue's 16px tile as the mod draws it. */
	static BufferedImage TILES;

	static Grid voxelize(double vox, Predicate<Part> keep) {
		for (int i = 0; i < PARTS.size(); i++) {
			PARTS.get(i).index = i;
		}

		// The output box of the kept shapes: their own centimetres in the full-size files, and at game scale the image
		// of each shape's box through the fit whose region holds it (a part that fits nowhere is not drawn at all).
		double[] lo = {1e9, 1e9, 1e9};
		double[] hi = {-1e9, -1e9, -1e9};
		for (Part p : PARTS) {
			if (!keep.test(p)) {
				continue;
			}

			if (FITS == null) {
				for (int a = 0; a < 3; a++) {
					lo[a] = Math.min(lo[a], p.shape.lo[a]);
					hi[a] = Math.max(hi[a], p.shape.hi[a]);
				}

				continue;
			}

			for (Fit f : FITS) {
				double[] clo = {Math.min(f.cm0[0], f.cm1[0]), Math.min(f.cm0[1], f.cm1[1]), Math.min(f.cm0[2], f.cm1[2])};
				double[] chi = {Math.max(f.cm0[0], f.cm1[0]), Math.max(f.cm0[1], f.cm1[1]), Math.max(f.cm0[2], f.cm1[2])};
				double[] u = {Math.max(p.shape.lo[0], clo[0]), Math.max(p.shape.lo[1], clo[1]), Math.max(p.shape.lo[2], clo[2])};
				double[] w = {Math.min(p.shape.hi[0], chi[0]), Math.min(p.shape.hi[1], chi[1]), Math.min(p.shape.hi[2], chi[2])};
				if (u[0] >= w[0] || u[1] >= w[1] || u[2] >= w[2]) {
					continue;
				}

				for (int corner = 0; corner < 8; corner++) {
					double[] q = f.inv((corner & 1) == 0 ? u[0] : w[0], (corner & 2) == 0 ? u[1] : w[1], (corner & 4) == 0 ? u[2] : w[2]);
					for (int a = 0; a < 3; a++) {
						lo[a] = Math.min(lo[a], q[a]);
						hi[a] = Math.max(hi[a], q[a]);
					}
				}
			}
		}

		int[] n = new int[3];
		double[] o = new double[3];
		for (int a = 0; a < 3; a++) {
			o[a] = Math.floor(lo[a] / vox) * vox;
			n[a] = Math.max(1, (int) Math.ceil((hi[a] - o[a]) / vox));
		}

		Grid raw = new Grid(n[0], n[1], n[2], vox, o[0], o[1], o[2]);
		int[] min = {n[0], n[1], n[2]};
		int[] max = {-1, -1, -1};
		Part[] parts = PARTS.toArray(Part[]::new);
		for (int i = 0; i < n[0]; i++) {
			double x = raw.wx(i);
			for (int j = 0; j < n[1]; j++) {
				double y = raw.wy(j);
				for (int k = 0; k < n[2]; k++) {
					double z = raw.wz(k);
					double[] c = cmAt(x, y, z);
					if (c == null) {
						continue;
					}

					for (Part p : parts) {
						if (p.shape.in(c[0], c[1], c[2])) {
							if (keep.test(p)) {
								raw.set(i, j, k, p.index + 1);
								min[0] = Math.min(min[0], i);
								min[1] = Math.min(min[1], j);
								min[2] = Math.min(min[2], k);
								max[0] = Math.max(max[0], i);
								max[1] = Math.max(max[1], j);
								max[2] = Math.max(max[2], k);
							}

							break;
						}
					}
				}
			}
		}

		Grid g = new Grid(max[0] - min[0] + 1, max[1] - min[1] + 1, max[2] - min[2] + 1, vox, raw.ox + min[0] * vox, raw.oy + min[1] * vox,
				raw.oz + min[2] * vox);
		for (int i = 0; i < g.nx; i++) {
			for (int j = 0; j < g.ny; j++) {
				for (int k = 0; k < g.nz; k++) {
					g.set(i, j, k, raw.at(i + min[0], j + min[1], k + min[2]));
				}
			}
		}

		return g;
	}

	record Box(int x0, int y0, int z0, int x1, int y1, int z1, int key) {
	}

	/** Greedy merge: each run of one part's voxels grown along x, then z, then y into the biggest box it fills. */
	static List<Box> boxes(Grid g) {
		boolean[] used = new boolean[g.cell.length];
		List<Box> out = new ArrayList<>();
		for (int j = 0; j < g.ny; j++) {
			for (int k = 0; k < g.nz; k++) {
				for (int i = 0; i < g.nx; i++) {
					int key = g.at(i, j, k);
					if (key == 0 || used[(i * g.ny + j) * g.nz + k]) {
						continue;
					}

					int i1 = i + 1;
					while (i1 < g.nx && g.at(i1, j, k) == key && !used[(i1 * g.ny + j) * g.nz + k]) {
						i1++;
					}

					int k1 = k + 1;
					grow:
					while (k1 < g.nz) {
						for (int a = i; a < i1; a++) {
							if (g.at(a, j, k1) != key || used[(a * g.ny + j) * g.nz + k1]) {
								break grow;
							}
						}

						k1++;
					}

					int j1 = j + 1;
					grow:
					while (j1 < g.ny) {
						for (int a = i; a < i1; a++) {
							for (int c = k; c < k1; c++) {
								if (g.at(a, j1, c) != key || used[(a * g.ny + j1) * g.nz + c]) {
									break grow;
								}
							}
						}

						j1++;
					}

					for (int a = i; a < i1; a++) {
						for (int b = j; b < j1; b++) {
							for (int c = k; c < k1; c++) {
								used[(a * g.ny + b) * g.nz + c] = true;
							}
						}
					}

					out.add(new Box(i, j, k, i1, j1, k1, key));
				}
			}
		}

		return out;
	}

	/** The voxel at column c, row r of a box's face as Blockbench maps its UV (seen from outside, top row up). */
	static int[] texel(Box b, int face, int c, int r) {
		return switch (face) {
			case 0 -> new int[] {b.x1 - 1, b.y1 - 1 - r, b.z1 - 1 - c};
			case 1 -> new int[] {b.x0, b.y1 - 1 - r, b.z0 + c};
			case 2 -> new int[] {b.x0 + c, b.y1 - 1, b.z0 + r};
			case 3 -> new int[] {b.x0 + c, b.y0, b.z1 - 1 - r};
			case 4 -> new int[] {b.x0 + c, b.y1 - 1 - r, b.z1 - 1};
			default -> new int[] {b.x1 - 1 - c, b.y1 - 1 - r, b.z0};
		};
	}

	static int faceW(Box b, int face) {
		return face < 2 ? b.z1 - b.z0 : b.x1 - b.x0;
	}

	static int faceH(Box b, int face) {
		return face == 2 || face == 3 ? b.z1 - b.z0 : b.y1 - b.y0;
	}

	static boolean faceVisible(Grid g, Box b, int face) {
		int[] nn = NORMAL[face];
		int w = faceW(b, face);
		int h = faceH(b, face);
		for (int c = 0; c < w; c++) {
			for (int r = 0; r < h; r++) {
				int[] t = texel(b, face, c, r);
				if (g.at(t[0] + nn[0], t[1] + nn[1], t[2] + nn[2]) == 0) {
					return true;
				}
			}
		}

		return false;
	}

	/** A voxel face's colour: its tissue, darkened where the space in front of it is closed in (crevices, folds). */
	static int shade(Grid g, int i, int j, int k, int face) {
		Part p = PARTS.get(g.at(i, j, k) - 1);
		int[] nn = NORMAL[face];
		int ai = i + nn[0];
		int aj = j + nn[1];
		int ak = k + nn[2];
		int c;
		if (TILES != null) {
			// As the mod draws it: the tissue's tile, one texel per pixel across the face.
			int tile = tile(p);
			int u = Math.floorMod(nn[0] != 0 ? k : i, 16);
			int v = Math.floorMod(nn[1] != 0 ? k : -j, 16);
			c = TILES.getRGB(tile % 8 * 16 + u, tile / 8 * 16 + v) & 0xFFFFFF;
		} else {
			double[] c0 = g.cm(i, j, k);
			double[] c1 = g.cm(ai, aj, ak);
			if (c1 == null) {
				c1 = c0;
			}

			c = p.mat.rgb(c0[0], c0[1], c0[2], face, c1[0], c1[1], c1[2]);
		}

		int solid = 0;
		for (int du = -1; du <= 1; du++) {
			for (int dv = -1; dv <= 1; dv++) {
				if (du == 0 && dv == 0) {
					continue;
				}

				int x = ai;
				int y = aj;
				int z = ak;
				if (nn[0] != 0) {
					y += du;
					z += dv;
				} else if (nn[1] != 0) {
					x += du;
					z += dv;
				} else {
					x += du;
					y += dv;
				}

				if (g.at(x, y, z) != 0) {
					solid++;
				}
			}
		}

		if (g.at(ai + nn[0], aj + nn[1], ak + nn[2]) != 0) {
			solid += 2;
		}

		return mul(c, Math.max(0.7, 1 - 0.035 * solid));
	}

	record Rect(Box box, int face, int w, int h, int[] at) {
	}

	static String export(Path out, String file, String title, double vox, Predicate<Part> keep) throws IOException {
		long t0 = System.nanoTime();
		VOX = vox * minScale();
		Grid g = voxelize(vox, keep);
		List<Box> boxes = boxes(g);
		Map<String, Integer> byGroup = new LinkedHashMap<>();
		for (Box b : boxes) {
			byGroup.merge(PARTS.get(b.key - 1).path, 1, Integer::sum);
		}

		CUBES.put(file + ".bbmodel", byGroup);
		List<Rect> rects = new ArrayList<>();
		long area = 0;
		int widest = 0;
		for (Box b : boxes) {
			for (int f = 0; f < 6; f++) {
				if (faceVisible(g, b, f)) {
					Rect r = new Rect(b, f, faceW(b, f), faceH(b, f), new int[2]);
					rects.add(r);
					area += (long) r.w * r.h;
					widest = Math.max(widest, r.w);
				}
			}
		}

		if (TILES != null) {
			// Every face takes its tissue's tile from anatomy.png, as the mod draws it.
			Map<Long, int[]> uv = new LinkedHashMap<>();
			for (Rect r : rects) {
				int tile = tile(PARTS.get(r.box.key - 1));
				int u = tile % 8 * 16;
				int v = tile / 8 * 16;
				uv.put(System.identityHashCode(r.box) * 8L + r.face, new int[] {u, v, u + Math.min(r.w, 16), v + Math.min(r.h, 16)});
			}

			ByteArrayOutputStream png = new ByteArrayOutputStream();
			ImageIO.write(TILES, "png", png);
			String json = bbmodel(file, g, boxes, uv, TILES.getWidth(), TILES.getHeight(), Base64.getEncoder().encodeToString(png.toByteArray()), null);
			Files.writeString(out.resolve(file + ".bbmodel"), json, StandardCharsets.UTF_8);
			preview(out.resolve("previews/" + file + ".png"), title, g);
			System.out.printf(Locale.ROOT, "%-24s %5d cubes  %3dx%3dx%3d voxels of %.2f px%n", file, boxes.size(), g.nx, g.ny, g.nz, vox);
			return String.format(Locale.ROOT, "| `%s.bbmodel` | %s | %d |", file, title, boxes.size());
		}

		int texW = Integer.highestOneBit((int) Math.ceil(Math.sqrt(area * 1.15)) - 1) << 1;
		texW = Math.max(Math.max(texW, 64), Integer.highestOneBit(Math.max(widest, 1) - 1) << 1);
		rects.sort((a, b) -> a.h != b.h ? b.h - a.h : b.w - a.w);
		int x = 0;
		int y = 0;
		int shelf = 0;
		for (Rect r : rects) {
			if (x + r.w > texW) {
				x = 0;
				y += shelf;
				shelf = 0;
			}

			r.at[0] = x;
			r.at[1] = y;
			x += r.w;
			shelf = Math.max(shelf, r.h);
		}

		int texH = Math.max(16, (y + shelf + 15) / 16 * 16);
		BufferedImage tex = new BufferedImage(texW, texH, BufferedImage.TYPE_INT_ARGB);
		BufferedImage charredTex = new BufferedImage(texW, texH, BufferedImage.TYPE_INT_ARGB);
		Map<Long, int[]> uv = new LinkedHashMap<>();
		for (Rect r : rects) {
			Part p = PARTS.get(r.box.key - 1);
			String kind = modPiece(p)[0];
			for (int c = 0; c < r.w; c++) {
				for (int row = 0; row < r.h; row++) {
					int[] t = texel(r.box, r.face, c, row);
					int col = shade(g, t[0], t[1], t[2], r.face);
					tex.setRGB(r.at[0] + c, r.at[1] + row, 0xFF000000 | col);
					charredTex.setRGB(r.at[0] + c, r.at[1] + row, 0xFF000000 | charred(kind, col, g.cm(t[0], t[1], t[2])));
				}
			}

			uv.put(System.identityHashCode(r.box) * 8L + r.face, new int[] {r.at[0], r.at[1], r.at[0] + r.w, r.at[1] + r.h});
		}

		ImageIO.write(tex, "png", out.resolve("textures/" + file + ".png").toFile());
		ImageIO.write(charredTex, "png", out.resolve("textures/" + file + "_charred.png").toFile());
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(tex, "png", png);
		ByteArrayOutputStream charPng = new ByteArrayOutputStream();
		ImageIO.write(charredTex, "png", charPng);
		String json = bbmodel(file, g, boxes, uv, texW, texH, Base64.getEncoder().encodeToString(png.toByteArray()),
				Base64.getEncoder().encodeToString(charPng.toByteArray()));
		Files.writeString(out.resolve(file + ".bbmodel"), json, StandardCharsets.UTF_8);
		preview(out.resolve("previews/" + file + ".png"), title, g);
		if (boxes.stream().anyMatch(b -> PARTS.get(b.key - 1).under("skeleton")) && boxes.stream().anyMatch(b -> !PARTS.get(b.key - 1).under("skeleton"))) {
			// The organs without the bones (and the diaphragm) in front of them, as when those groups are hidden.
			Grid soft = new Grid(g.nx, g.ny, g.nz, g.vox, g.ox, g.oy, g.oz);
			for (int i = 0; i < g.cell.length; i++) {
				int key = g.cell[i];
				soft.cell[i] = key == 0 || PARTS.get(key - 1).under("skeleton") || PARTS.get(key - 1).under("thorax/diaphragm") ? 0 : key;
			}

			preview(out.resolve("previews/" + file + "_organs.png"), title + " (skeleton and diaphragm hidden)", soft);
		}
		System.out.printf(Locale.ROOT, "%-18s %5d cubes  %4dx%-4d texture  %3dx%3dx%3d voxels of %.1f cm  %.1fs%n", file, boxes.size(), texW, texH, g.nx,
				g.ny, g.nz, vox, (System.nanoTime() - t0) / 1e9);
		return String.format(Locale.ROOT, "| `%s.bbmodel` | %s | %d | %.1f cm | %.0f x %.0f x %.0f cm |", file, title, boxes.size(), vox, g.nx * vox,
				g.ny * vox, g.nz * vox);
	}

	/** One group of the outliner, with its subgroups and cubes. */
	static final class Node {
		final String name;
		final String path;
		final Map<String, Node> kids = new LinkedHashMap<>();
		final List<Integer> cubes = new ArrayList<>();
		double sx;
		double sy;
		double sz;
		double sw;

		Node(String name, String path) {
			this.name = name;
			this.path = path;
		}
	}

	/**
	 * The same tissue cooked and charred, for the alternate heated texture: bone scorches then whitens, keratin goes
	 * black and stays, fat renders brown, any organ browns then chars. One palette per tissue, no new geometry.
	 */
	static int charred(String kind, int c, double[] at) {
		double n = at == null ? 0.5 : noise(at[0] * 0.55, at[1] * 0.55, at[2] * 0.55, 201);
		return switch (kind) {
			case "BONE", "BONE_END", "VERTEBRA", "SKULL", "TEETH", "PHALANGES" -> mix(mix(c, 0xA89478, 0.55), 0x8A8074, n * 0.4);
			case "HORN", "HOOF" -> mix(mix(c, 0x241D18, 0.72), 0x0E0B09, n * 0.3);
			case "SUET", "FAT", "LARD" -> mix(mix(c, 0xC0A066, 0.55), 0x4A3A22, n * 0.45);
			default -> mix(mix(c, 0x6B4A2E, 0.5), 0x1E1814, 0.35 + n * 0.35);
		};
	}

	static String bbmodel(String name, Grid g, List<Box> boxes, Map<Long, int[]> uv, int texW, int texH, String png, String charPng) {
		// Blockbench units: the organ files one per voxel, centred on x and z, standing on y = 0; the game-scale ones in
		// the vanilla cow's own pixels, where the vanilla model puts its body.
		boolean absolute = TILES != null;
		double unit = absolute ? g.vox : 1;
		double cx = absolute ? -g.ox / g.vox : g.nx / 2.0;
		double cy = absolute ? -g.oy / g.vox : 0;
		double cz = absolute ? -g.oz / g.vox : g.nz / 2.0;
		Node root = new Node(name, "");
		StringBuilder el = new StringBuilder();
		Map<String, Integer> colours = new LinkedHashMap<>();
		for (int n = 0; n < boxes.size(); n++) {
			Box b = boxes.get(n);
			Part p = PARTS.get(b.key - 1);
			String[] segs = p.path.split("/");
			Node node = root;
			String acc = "";
			for (String s : segs) {
				acc = acc.isEmpty() ? s : acc + "/" + s;
				String path = acc;
				node = node.kids.computeIfAbsent(s, k -> new Node(k, path));
			}

			node.cubes.add(n);
			double vol = (double) (b.x1 - b.x0) * (b.y1 - b.y0) * (b.z1 - b.z0);
			double mx = ((b.x0 + b.x1) / 2.0 - cx) * unit;
			double my = ((b.y0 + b.y1) / 2.0 - cy) * unit;
			double mz = ((b.z0 + b.z1) / 2.0 - cz) * unit;
			Node walk = root;
			for (String s : segs) {
				walk = walk.kids.get(s);
				walk.sx += mx * vol;
				walk.sy += my * vol;
				walk.sz += mz * vol;
				walk.sw += vol;
			}

			String organ = segs.length > 1 ? segs[0] + "/" + segs[1] : segs[0];
			int colour = colours.computeIfAbsent(organ, k -> colours.size() % 8);
			if (n > 0) {
				el.append(",\n");
			}

			el.append("{\"name\":\"").append(p.name).append("\",\"box_uv\":false,\"rescale\":false,\"locked\":false,\"light_emission\":0,")
					.append("\"render_order\":\"default\",\"allow_mirror_modeling\":true,")
					.append("\"from\":[").append(num((b.x0 - cx) * unit)).append(',').append(num((b.y0 - cy) * unit)).append(',').append(num((b.z0 - cz) * unit)).append("],")
					.append("\"to\":[").append(num((b.x1 - cx) * unit)).append(',').append(num((b.y1 - cy) * unit)).append(',').append(num((b.z1 - cz) * unit)).append("],")
					.append("\"autouv\":0,\"color\":").append(colour).append(",\"origin\":[").append(num(mx)).append(',').append(num(my)).append(',')
					.append(num(mz)).append("],\"faces\":{");
			for (int f = 0; f < 6; f++) {
				int[] r = uv.get(System.identityHashCode(b) * 8L + f);
				if (f > 0) {
					el.append(',');
				}

				el.append('"').append(FACE[f]).append("\":");
				if (r == null) {
					el.append("{\"uv\":[0,0,0,0],\"texture\":null}");
				} else {
					el.append("{\"uv\":[").append(r[0]).append(',').append(r[1]).append(',').append(r[2]).append(',').append(r[3]).append("],\"texture\":0}");
				}
			}

			el.append("},\"type\":\"cube\",\"uuid\":\"").append(uuid(name + "/cube/" + n)).append("\"}");
		}

		StringBuilder ol = new StringBuilder();
		boolean first = true;
		for (Node k : root.kids.values()) {
			if (!first) {
				ol.append(",\n");
			}

			first = false;
			outline(ol, name, k);
		}

		if (absolute) {
			// The vanilla adult cow's own parts, hidden: show the group to see where the organs sit in it. These are
			// CowModel's exact cubes in the same frame as the anatomy (y up from the ground, the cow's right at -x).
			double[][] ref = {{-6, 12, -8, 6, 22, 10}, {-2, 11, 4, 2, 12, 10}, {-4, 16, -14, 4, 24, -8}, {-3, 16, -15, 3, 19, -14},
					{-5, 22, -13, -4, 25, -12}, {4, 22, -13, 5, 25, -12}, {-6, 0, -7, -2, 12, -3}, {2, 0, -7, 6, 12, -3},
					{-6, 0, 5, -2, 12, 9}, {2, 0, 5, 6, 12, 9}};
			String[] refNames = {"body", "udder", "head", "muzzle", "right_horn", "left_horn", "right_front_leg", "left_front_leg", "right_hind_leg",
					"left_hind_leg"};
			StringBuilder kids = new StringBuilder();
			for (int r = 0; r < ref.length; r++) {
				String id = uuid(name + "/reference/" + r);
				el.append(",\n{\"name\":\"").append(refNames[r]).append("\",\"box_uv\":false,\"rescale\":false,\"locked\":true,\"light_emission\":0,")
						.append("\"render_order\":\"default\",\"allow_mirror_modeling\":true,\"from\":[").append(num(ref[r][0])).append(',').append(num(ref[r][1]))
						.append(',').append(num(ref[r][2])).append("],\"to\":[").append(num(ref[r][3])).append(',').append(num(ref[r][4])).append(',')
						.append(num(ref[r][5])).append("],\"autouv\":0,\"color\":7,\"origin\":[0,0,0],\"faces\":{");
				for (int f = 0; f < 6; f++) {
					el.append(f > 0 ? "," : "").append('"').append(FACE[f]).append("\":{\"uv\":[96,16,112,32],\"texture\":0}");
				}

				el.append("},\"type\":\"cube\",\"uuid\":\"").append(id).append("\"}");
				kids.append(r > 0 ? "," : "").append('"').append(id).append('"');
			}

			ol.append(",\n{\"name\":\"vanilla_cow_reference\",\"origin\":[0,0,0],\"color\":7,\"uuid\":\"").append(uuid(name + "/reference"))
					.append("\",\"export\":false,\"mirror_uv\":false,\"isOpen\":false,\"locked\":true,\"visibility\":false,\"autouv\":0,\"children\":[")
					.append(kids).append("]}");
		}

		String textures = "{\"path\":\"\",\"name\":\"" + name + ".png\",\"folder\":\"\",\"namespace\":\"\","
				+ "\"id\":\"0\",\"group\":\"\",\"width\":" + texW + ",\"height\":" + texH + ",\"uv_width\":" + texW + ",\"uv_height\":" + texH
				+ ",\"particle\":false,\"use_as_default\":false,\"layers_enabled\":false,\"sync_to_project\":\"\",\"render_mode\":\"default\","
				+ "\"render_sides\":\"auto\",\"pbr_channel\":\"color\",\"frame_time\":1,\"frame_order_type\":\"loop\",\"frame_order\":\"\","
				+ "\"frame_interpolate\":false,\"visible\":true,\"internal\":true,\"saved\":false,\"uuid\":\"" + uuid(name + "/texture")
				+ "\",\"relative_path\":\"textures/" + name + ".png\",\"source\":\"data:image/png;base64," + png + "\"}";
		if (charPng != null) {
			// The heated state of the same cubes, kept in the file but hidden: swap the visible texture to cook it.
			textures += ",{\"path\":\"\",\"name\":\"" + name + "_charred.png\",\"folder\":\"\",\"namespace\":\"\","
					+ "\"id\":\"1\",\"group\":\"\",\"width\":" + texW + ",\"height\":" + texH + ",\"uv_width\":" + texW + ",\"uv_height\":" + texH
					+ ",\"particle\":false,\"use_as_default\":false,\"layers_enabled\":false,\"sync_to_project\":\"\",\"render_mode\":\"default\","
					+ "\"render_sides\":\"auto\",\"pbr_channel\":\"color\",\"frame_time\":1,\"frame_order_type\":\"loop\",\"frame_order\":\"\","
					+ "\"frame_interpolate\":false,\"visible\":false,\"internal\":true,\"saved\":false,\"uuid\":\"" + uuid(name + "/texture_charred")
					+ "\",\"relative_path\":\"textures/" + name + "_charred.png\",\"source\":\"data:image/png;base64," + charPng + "\"}";
		}

		return "{\"meta\":{\"format_version\":\"4.10\",\"model_format\":\"free\",\"box_uv\":false},\n\"name\":\"" + name + "\",\"model_identifier\":\"\","
				+ "\"visible_box\":[1,1,0],\"variable_placeholders\":\"\",\"variable_placeholder_buttons\":[],\"timeline_setups\":[],"
				+ "\"unhandled_root_fields\":{},\n\"resolution\":{\"width\":" + texW + ",\"height\":" + texH + "},\n\"elements\":[\n" + el + "\n],\n"
				+ "\"outliner\":[\n" + ol + "\n],\n\"textures\":[" + textures + "]\n}\n";
	}

	static void outline(StringBuilder sb, String model, Node n) {
		double w = Math.max(n.sw, 1e-9);
		boolean visible = STATE != 0 || !interior(n.path);
		sb.append("{\"name\":\"").append(n.name).append("\",\"origin\":[").append(num(n.sx / w)).append(',').append(num(n.sy / w)).append(',')
				.append(num(n.sz / w)).append("],\"color\":0,\"uuid\":\"").append(uuid(model + "/group/" + n.path))
				.append("\",\"export\":true,\"mirror_uv\":false,\"isOpen\":false,\"locked\":false,\"visibility\":").append(visible).append(",\"autouv\":0,\"children\":[");
		boolean first = true;
		for (Node k : n.kids.values()) {
			if (!first) {
				sb.append(',');
			}

			first = false;
			outline(sb, model, k);
		}

		for (int c : n.cubes) {
			if (!first) {
				sb.append(',');
			}

			first = false;
			sb.append('"').append(uuid(model + "/cube/" + c)).append('"');
		}

		sb.append("]}");
	}

	/**
	 * Groups hidden by default in an intact model: the interior of a closed organ (the rumen's lining and cud, the
	 * omasum's laminae, the reticulum's honeycomb). Hiding the organ's wall group reveals them; the opened and torn
	 * exports show them outright.
	 */
	static boolean interior(String path) {
		return path.contains("interior") || path.endsWith("laminae") || path.contains("ingesta") || path.contains("contents")
				|| path.contains("honeycomb");
	}

	static String uuid(String s) {
		return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)).toString();
	}

	static String num(double d) {
		if (d == Math.rint(d)) {
			return Long.toString((long) d);
		}

		return String.format(Locale.ROOT, "%.3f", d).replaceAll("0+$", "");
	}

	// ------------------------------------------------------------------------------------------------ previews

	/** Four orthographic views (left, right, from above, three-quarter from the front left), ray cast through the voxels. */
	static void preview(Path file, String title, Grid g) throws IOException {
		int size = 320;
		BufferedImage img = new BufferedImage(size * 3, size * 2 + 28, BufferedImage.TYPE_INT_RGB);
		Graphics2D gr = img.createGraphics();
		gr.setColor(new Color(0x1E1E22));
		gr.fillRect(0, 0, img.getWidth(), img.getHeight());
		double[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0.0001}, norm(new double[] {0.75, -0.45, 0.55})};
		// The mod's frame has the sheep's right at -x, the anatomy's at +x: the same views see the other side.
		boolean flipped = FITS != null;
		String[] names = {flipped ? "right side (head to the left)" : "left side (head to the left)",
				flipped ? "left side (head to the right)" : "right side (head to the right)", "from in front", "from behind", "from above (head up)",
				flipped ? "front right, from above" : "front left, from above"};
		for (int v = 0; v < 6; v++) {
			render(img, g, dirs[v], (v % 3) * size, 28 + (v / 3) * size, size, v == 4);
			gr.setColor(new Color(0x9A9AA6));
			gr.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
			gr.drawString(names[v], (v % 3) * size + 8, 28 + (v / 3) * size + 16);
		}

		gr.setColor(Color.WHITE);
		gr.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
		gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		gr.drawString(title, 8, 19);
		gr.dispose();
		ImageIO.write(img, "png", file.toFile());
	}

	static void render(BufferedImage img, Grid g, double[] d, int ox, int oy, int size, boolean top) {
		double[] upRef = top ? new double[] {0, 0, -1} : new double[] {0, 1, 0};
		double[] right = norm(cross(d, upRef));
		double[] up = cross(right, d);
		double[] c = {g.nx / 2.0, g.ny / 2.0, g.nz / 2.0};
		double ext = 0;
		for (int corner = 0; corner < 8; corner++) {
			double[] p = {(corner & 1) * g.nx - c[0], ((corner >> 1) & 1) * g.ny - c[1], ((corner >> 2) & 1) * g.nz - c[2]};
			ext = Math.max(ext, Math.max(Math.abs(dot(p, right)), Math.abs(dot(p, up))));
		}

		double scale = (size - 24) / (2 * ext);
		double[] light = norm(new double[] {-0.35, 0.85, -0.4});
		double far = g.nx + g.ny + g.nz;
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				double u = (px - size / 2.0) / scale;
				double w = -(py - size / 2.0) / scale;
				double[] o = add(add(c, add(mul(right, u), mul(up, w))), mul(d, -far));
				int[] hit = cast(g, o, d);
				if (hit != null) {
					int col = shade(g, hit[0], hit[1], hit[2], hit[3]);
					int[] nn = NORMAL[hit[3]];
					double lam = 0.55 + 0.45 * Math.max(0, nn[0] * light[0] + nn[1] * light[1] + nn[2] * light[2]);
					img.setRGB(ox + px, oy + py, mul(col, lam));
				}
			}
		}
	}

	/** Amanatides-Woo through the grid: the first solid voxel and the face the ray came in by, or null. */
	static int[] cast(Grid g, double[] o, double[] d) {
		double t0 = 0;
		double t1 = Double.MAX_VALUE;
		int[] n = {g.nx, g.ny, g.nz};
		int enter = 0;
		for (int a = 0; a < 3; a++) {
			if (Math.abs(d[a]) < 1e-9) {
				if (o[a] < 0 || o[a] > n[a]) {
					return null;
				}

				continue;
			}

			double ta = (0 - o[a]) / d[a];
			double tb = (n[a] - o[a]) / d[a];
			if (ta > tb) {
				double s = ta;
				ta = tb;
				tb = s;
			}

			if (ta > t0) {
				t0 = ta;
				enter = a;
			}

			t1 = Math.min(t1, tb);
		}

		if (t0 > t1) {
			return null;
		}

		double[] p = add(o, mul(d, t0 + 1e-7));
		int[] cell = new int[3];
		int[] step = new int[3];
		double[] tMax = new double[3];
		double[] tDelta = new double[3];
		for (int a = 0; a < 3; a++) {
			cell[a] = Math.min(n[a] - 1, Math.max(0, (int) Math.floor(p[a])));
			step[a] = d[a] > 0 ? 1 : -1;
			double next = d[a] > 0 ? cell[a] + 1 : cell[a];
			tMax[a] = Math.abs(d[a]) < 1e-9 ? Double.MAX_VALUE : t0 + (next - p[a]) / d[a];
			tDelta[a] = Math.abs(d[a]) < 1e-9 ? Double.MAX_VALUE : 1 / Math.abs(d[a]);
		}

		int axis = enter;
		while (true) {
			if (g.at(cell[0], cell[1], cell[2]) != 0) {
				int face = axis * 2 + (step[axis] > 0 ? 1 : 0);
				return new int[] {cell[0], cell[1], cell[2], face};
			}

			axis = tMax[0] < tMax[1] ? (tMax[0] < tMax[2] ? 0 : 2) : (tMax[1] < tMax[2] ? 1 : 2);
			cell[axis] += step[axis];
			if (cell[axis] < 0 || cell[axis] >= n[axis]) {
				return null;
			}

			tMax[axis] += tDelta[axis];
		}
	}

	static String mobReadme(String res, List<String> rows) {
		return """
				# Cow anatomy at %1$s

				The same veterinary model of a cow as `../cow_anatomy`, at the scale of the game: squashed into the vanilla adult
				cow's own cubes, inside its fat and muscle (the mod's `Region.BEAST_TRUNK` and leg regions), cut into voxels of
				16/%2$s of a pixel and drawn with the mod's own `anatomy.png` tiles, exactly as the game draws them.

				| File | What | Cubes |
				|---|---|---|
				%3$s

				- Blockbench units are the vanilla cow's own model pixels with y up from the ground: the body is x -6..6,
				  12..22 up, z -8..10, the head x -4..4, 16..24 up, z -15..-8, the legs x -6..-2 or 2..6, 0..12 up. The cow faces
				  **north** (-Z) and its right is **-X**, exactly as in `CowModel`.
				- Every part is fitted on its own (as the mod's `Anatomy.fit` does), so the head and limbs are squashed more
				  than the trunk: the vanilla cow is a wide, blocky animal and real anatomy is fitted to its silhouette.
				- The `vanilla_cow_reference` group (hidden) is the vanilla body, udder, head, muzzle, horns and legs: show it
				  to see where the organs sit.
				- Every cube is named by its tissue; groups are organs and their parts, as in the full-size files. States
				  (opened, torn) are not exported at game scale: the mod makes its own damage from these shapes.
				- At this grain the thinnest vessels and ducts are left out and only every %4$s rib is kept, so a gap shows
				  between them. Nothing here is dropped for being unimportant: see the "Sources and simplifications" section
				  of `../cow_anatomy/README.md` for what had to be simplified and why.
				- %5$s
				""".formatted(res, res.replace("x", ""), String.join("\n", rows), res.equals("16x") ? "fourth" : "second",
				res.equals("32x")
						? """
						The mod uses this table: `tools/ExportCowAnatomy.ps1` turns this folder's `piece_table.json` into the cow's
						  trunk (`src/main/java/dev/fluidgore/anatomy/CowTrunk.java`, `pieces()`). The head and limbs still use the
						  shared ungulate tables. Run this tool first, then that script; never edit `CowTrunk.java` by hand."""
						: """
						The mod uses this table for loose organs: `tools/ExportCowAnatomy.ps1` reads this folder's `piece_table.json`
						  into `CowTrunk.compact()`: the coarser shape a loose organ takes when its 32x shape has too many cubes
						  to carry and sync. Run this tool first, then that script; never edit `CowTrunk.java` by hand.""");
	}

	static String readme(List<String> rows, List<String> stateRows) {
		return """
				# Cow anatomy models

				Blockbench models of a cow's insides, generated by `tools/CowAnatomyModels.java` (re-run it to rebuild
				everything: `java tools/CowAnatomyModels.java art/cow_anatomy`). Open any `.bbmodel` in Blockbench (File >
				Open Model, or drag it onto the window). The texture is embedded in each file; a copy is in `textures/`, and
				`textures/<name>_charred.png` is the same model cooked and charred (swap the texture source to see it).
				`previews/` has a multi-view render of each model from both sides, in front, behind, above and a three-quarter
				view. `manifest.json` is the machine-readable asset list for integration.

				| File | What | Cubes | Voxel | Size |
				|---|---|---|---|---|
				%s

				## Damaged art (states, one axis of reuse)

				| File | What | Cubes | Voxel | Size |
				|---|---|---|---|---|
				%s

				Every organ is complete and closed in the files above (its **intact** form). The **opened** files cut a clean
				window in one wall to show the organ's defining interior (the rumen's papillae and cud, the reticulum's
				honeycomb, the omasum's laminae, the abomasum's folds, the heart's chambers, the kidneys' cortex and pyramids,
				the liver's cut surface, a gut's wall, lumen and contents). The **torn** files rupture one wall raggedly with a
				raw, bloodied margin and spilled contents. The **heated** form is the alternate `_charred` texture on the same
				cubes: cooked brown, then charred, without a second copy of the geometry. Blood, stomach contents, bile and
				urine are separate groups and never share a voxel with a wall; no blood spray, smoke or flame is baked in.

				## Scale and orientation

				- One Blockbench unit is one voxel of 1 cm, in every file including `cow_in_situ`. A cow is about 2.3 times
				  a ewe's length, so this is slightly finer relative to the animal than the sheep organ files' 0.5 cm, and
				  the models stay small enough to edit. The cow faces **north** (-Z), +X is the cow's right, +Y is up.
				- Every organ sits exactly where it lies in a standing cow, so the organ files are the same pieces as the
				  whole body, just cut finer.
				- Each organ is a group; its lobes, chambers, loops and vessels are subgroups, and every cube is named after
				  its tissue. Hide a group with its eye icon to look inside (hide `skeleton` in `cow_in_situ` first).
				- `cow_anatomy_16x` and `cow_anatomy_32x` hold the same anatomy fitted to the vanilla adult cow's model.

				## What to look for (cow, not sheep)

				- **Liver**: thick, deeply fissured and driven wholly to the right by the rumen, moulded on the diaphragm;
				  left, quadrate, right and caudate lobes, the green gallbladder in its fossa reaching the ventral border,
				  the caudal vena cava in the dorsal border. (A sheep's liver is one plate: this is not that.)
				- **Kidneys**: **lobulated** on the surface (a sheep's are smooth beans), the right round to oval far
				  forward under the last ribs, the left heart-shaped and further back, pushed over by the rumen.
				- **Stomachs**: the rumen fills the left of the belly and crosses the midline behind, with dorsal and ventral
				  sacs, two blind sacs and fat-filled grooves; the reticulum is smooth outside (its honeycomb is an opened
				  view only); the **omasum** is a closed, softball-sized bag on the right whose opened form shows sheets of
				  three sizes hung from the sides of the greater curvature, never a spoked wheel; the abomasum lies on the
				  floor of the belly to the right and opens to show its folds.
				- **Heart**: between the 3rd and 6th ribs, two thirds to the left; blunt apex, thick fat collar, paraconal
				  and subsinuosal grooves; opened it shows both ventricles, the septum and the papillary muscles.
				- **Lungs**: bovine lobation (Cornell): right apical in two parts with its own tracheal bronchus, cardiac,
				  diaphragmatic, accessory; left cranial in two parts and diaphragmatic. Cardiac notches leave the heart bare.
				- **Intestines**: the spiral colon as a flat double coil (proximal, spiral and distal loops), the jejunum as a
				  garland of loops (one subgroup each), the caecum pointing back into the pelvis, the rectum on the midline.
				- **Skeleton**: C7 T13 L6 S5 Cd18-20 (the first eight caudal drawn), 13 pairs of ribs, 7 sternebrae and the
				  xiphoid, scapulae, the pelvis with its obturator foramen, and limbs with fused cannon bones, two toes of
				  three phalanges and a claw of horn each.

				## Sources and simplifications

				Proportions are modelled for an adult, non-pregnant, dairy-type cow of about 550 kg and simplified to blocks:
				they are meant to read correctly at a glance, not to be measured. No photograph or illustration was copied into
				a texture; every texture is procedural from fixed seeds.

				Sources:

				- Cornell veterinary students' [bovine anatomy teaching site](https://sites.google.com/cornell.edu/bovine-website/)
				  (no endorsement implied): the stomachs, lungs, kidneys, liver ducts, large intestine and heart.
				- Standard veterinary anatomy texts: Dyce, Sack & Wensing, *Textbook of Veterinary Anatomy*; Popesko, *Atlas of
				  Topographical Anatomy of the Domestic Animals*.
				- University of Minnesota, [Large Animal Anatomy](https://open.lib.umn.edu/largeanimalanatomy/chapter/abdomen-2/)
				  (open textbook): lobulated kidneys, the spleen on the rumen, the omasum's size and place, the liver's lobes.
				- University of Minnesota, [Ungulate Anatomy Lab Guide: bovine abdomen](https://pressbooks.umn.edu/ungulateanatomylabguide/chapter/part-3-bovine-abdomen/):
				  the inside of the kidney.
				- Colorado State University, [Pathophysiology of the Digestive System](https://vivo.colostate.edu/hbooks/pathphys/digestion/herbivores/rumen_anat.html):
				  rumen papillae and the reticulum's honeycomb.
				- University of Calgary veterinary outreach notes: the vertebral formula.
				- Mississippi State University Extension, [Understanding the Ruminant Animal Digestive System](https://extension.msstate.edu/publications/understanding-the-ruminant-animal-digestive-system)
				  (extension leaflet): how much of the belly the stomachs fill.
				- Reference websites: IMAIOS vet-Anatomy (kidney lobulation), veterinaryanatomyguide.com (spleen shape) and
				  Wikipedia's [Omasum](https://en.wikipedia.org/wiki/Omasum) (where the leaves attach).
				- Minecraft 26.3's `CowModel`: every game-scale coordinate.

				Simplified on purpose:

				- **Ribs**: all 13 pairs in the full-size files; the game-scale files keep every fourth (16x) or every second
				  (32x). The smallest vessels, ducts, sesamoids, teats and discs are dropped at game scale.
				- **Tail**: 8 of the 18-20 caudal vertebrae, in the full-size files only (the vanilla cow has no tail part).
				- **Omasum**: seven leaves a side in three sizes, not the whole book.
				- **Heart chambers and the reticulum's honeycomb**: in the opened files only.
				- **Intestines**: the jejunum is 16 loops; the spiral colon is two coils in and two out.
				- **Head**: the frontal sinuses are not hollowed; horns are short cores with keratin sheaths.
				- **Udder**: dry and small, drawn with the fat tile (`anatomy.png` has no gland tile).
				- **Body wall**: a representative flank cutaway and four broad muscle masses, not a muscle atlas. It is left
				  out of the game-scale files because the mod draws its own skin, fat and muscle.
				- **Opened kidneys**: they show a renal pelvis, but cattle kidneys have calyces and no renal pelvis (see the
				  Minnesota lab guide above). Treat the opened kidney files as inaccurate; the mod does not use them.
				""".formatted(String.join("\n", rows), String.join("\n", stateRows));
	}

	// ------------------------------------------------------------------------------------------------ manifest

	/** One detachable organ or major bone of the manifest. */
	record Asset(String id, String name, String group, String region, String side, String parent, String attachment, String limits) {
	}

	/**
	 * Every organ and major bone the mod may throw, cut, burn or identify: the table the manifest is built from.
	 * Anatomical and geometric metadata only (what it is, where it sits, how it is drawn): no injury thresholds, blast
	 * constants, densities or burn rates, which are gameplay's to decide.
	 */
	static final Asset[] ASSETS = {
			new Asset("cow.heart", "Heart", "thorax/heart", "thorax", "left of the midline", "thorax", "between the 3rd and 6th ribs, under the windpipe; great vessels cut at the base",
					"chambers are modelled in the opened state only; the valves survive as a ring and columns at best"),
			new Asset("cow.lungs", "Lungs", "thorax/lungs", "thorax", "both", "thorax", "bronchi at the hilus; base on the diaphragm", "lobes are separated by gaps of a voxel, not by dissection"),
			new Asset("cow.trachea", "Windpipe and bronchi", "thorax/airway", "thorax", "median", "thorax", "larynx end cut at the neck; bronchi enter the lungs", "rings are drawn at about one per two voxels"),
			new Asset("cow.diaphragm", "Diaphragm", "thorax/diaphragm", "thorax", "median", "thorax", "rim on the ribs, sternum and lumbar spine", "a shell one voxel thick"),
			new Asset("cow.esophagus", "Esophagus", "thorax/esophagus", "thorax", "median", "thorax", "neck to the rumen's atrium", "cut at both ends"),
			new Asset("cow.liver", "Liver", "abdomen/liver", "abdomen", "right", "abdomen", "against the right cupula of the diaphragm; porta on the visceral face", "the four lobes are read from their fissures at voxel grain"),
			new Asset("cow.gallbladder", "Gallbladder", "abdomen/liver/gallbladder", "abdomen", "right", "cow.liver", "fossa between the quadrate and right lobes", "bile ducts beyond the porta are not drawn"),
			new Asset("cow.rumen", "Rumen", "abdomen/stomach/rumen", "abdomen", "left", "abdomen", "esophagus at the atrium; reticulum in front; abomasum on the floor", "papillae are texture in the intact form, geometry only in the opened one"),
			new Asset("cow.reticulum", "Reticulum", "abdomen/stomach/reticulum", "abdomen", "left of the midline", "abdomen", "cranial to the rumen against the diaphragm", "the honeycomb exists only in the opened state"),
			new Asset("cow.omasum", "Omasum", "abdomen/stomach/omasum", "abdomen", "right", "abdomen", "short tunnel to the reticulum; abomasum out at its base", "laminae are seven a side in three sizes, not the whole book"),
			new Asset("cow.abomasum", "Abomasum", "abdomen/stomach/abomasum", "abdomen", "right", "abdomen", "omasum to the pylorus along the belly's floor", "rugae are drawn as six folds in the opened form"),
			new Asset("cow.spleen", "Spleen", "abdomen/spleen", "abdomen", "left", "abdomen", "hung on the left of the rumen's dorsal sac against the last ribs", "blade-shaped, smooth: no notches drawn"),
			new Asset("cow.kidney.right", "Right kidney", "abdomen/kidneys/right_kidney", "abdomen", "right", "abdomen", "under the last ribs against the liver's caudate process", "surface lobes are six; the cut face shows cortex, five pyramids and the pelvis"),
			new Asset("cow.kidney.left", "Left kidney", "abdomen/kidneys/left_kidney", "abdomen", "left of the midline", "abdomen", "under the first lumbar vertebrae, movable behind the rumen", "cordiform and lobulated; its suet is unpeeled"),
			new Asset("cow.small_intestine", "Small intestine", "abdomen/intestines", "abdomen", "right", "abdomen", "duodenum from the pylorus; ileum into the caecum", "the jejunum is 16 loops, not the full metre count"),
			new Asset("cow.spiral_colon", "Spiral colon", "abdomen/intestines/spiral_colon", "abdomen", "right", "abdomen", "in the mesentery's disc", "two coils in and two out, as in the ruminant plan"),
			new Asset("cow.caecum", "Caecum", "abdomen/intestines/caecum", "abdomen", "right", "abdomen", "between ileum and ascending colon, pointing into the pelvis", "a blunt comma, no haustra drawn"),
			new Asset("cow.colon", "Colon and rectum", "abdomen/intestines/proximal_colon", "abdomen", "median", "abdomen", "proximal loop, spiral, distal loop, descending, rectum to the anus", "pellets are bead texture on the distal loop only"),
			new Asset("cow.mesentery", "Mesentery", "abdomen/intestines/mesentery", "abdomen", "right", "abdomen", "root along the lumbar spine; the disc hangs the spiral colon", "a sheet one voxel thick"),
			new Asset("cow.bladder", "Bladder and ureters", "abdomen/urinary", "abdomen", "median", "abdomen", "ureters from the kidneys' pelvis; urethra to the pelvis", "urethra ends at the pelvic floor"),
			new Asset("cow.udder", "Udder", "abdomen/udder", "abdomen", "median", "abdomen", "under the belly between the hindlegs (the vanilla udder box)", "dry and small; four quarters and teats, drawn with the fat tile (no gland tile exists)"),
			new Asset("cow.skull", "Skull with horns", "skeleton/skull/cranium", "head", "median", "skeleton", "occipital condyles on the atlas", "horn cores and sheaths are short cones; the sinuses are not hollowed"),
			new Asset("cow.mandible", "Mandible", "skeleton/skull/mandible", "head", "median", "skeleton", "condyle under the braincase; symphysis in front", "the jaw is not hinged in the model: move the group"),
			new Asset("cow.teeth", "Teeth", "skeleton/skull/teeth", "head", "median", "skeleton", "in the maxilla and the mandible", "eight lower incisors and six cheek teeth a side; no upper incisors (the dental pad)"),
			new Asset("cow.brain", "Brain", "head/brain", "head", "median", "head", "in the cranial cavity", "cerebrum, cerebellum and stem only"),
			new Asset("cow.spine.cervical", "Neck vertebrae", "skeleton/spine/cervical", "neck", "median", "skeleton", "atlas to C7", "transverse processes shortened to fit the head cube at game scale"),
			new Asset("cow.spine.thoracic", "Thoracic vertebrae", "skeleton/spine/thoracic", "thorax", "median", "skeleton", "T1-T13, with the withers' tall spines", "discs are one voxel"),
			new Asset("cow.spine.lumbar", "Lumbar vertebrae", "skeleton/spine/lumbar", "abdomen", "median", "skeleton", "L1-L6 with long transverse processes", ""),
			new Asset("cow.spine.sacrum", "Sacrum and tail", "skeleton/spine/sacrum", "pelvis", "median", "skeleton", "sacrum to the first caudal vertebrae", "the tail's last ten caudal vertebrae are not drawn"),
			new Asset("cow.ribs", "Ribs with costal cartilage", "skeleton/ribcage", "thorax", "both", "skeleton", "13 pairs; 8 sternal, 4 on the costal arch, 1 floating", "only every second or fourth rib survives the coarse game grain"),
			new Asset("cow.sternum", "Breastbone", "skeleton/sternum", "thorax", "median", "skeleton", "7 sternebrae and the xiphoid", ""),
			new Asset("cow.shoulder_blades", "Shoulder blades", "skeleton/shoulder_blades", "thorax", "both", "skeleton", "glenoid on the humerus; blade on the chest wall", ""),
			new Asset("cow.pelvis", "Pelvis", "skeleton/pelvis", "pelvis", "both", "skeleton", "ilium to the lumbar spine; acetabulum on the femur", "the obturator foramen is left open in the ischium's plate"),
			new Asset("cow.forelimb.right", "Right forelimb", "skeleton/limbs/right_forelimb", "forelimb", "right", "skeleton", "humerus in the shoulder's glenoid", "two toes of three phalanges each with a claw; dewclaws behind the fetlock"),
			new Asset("cow.forelimb.left", "Left forelimb", "skeleton/limbs/left_forelimb", "forelimb", "left", "skeleton", "humerus in the shoulder's glenoid", "mirror of the right"),
			new Asset("cow.hindlimb.right", "Right hindlimb", "skeleton/limbs/right_hindlimb", "hindlimb", "right", "skeleton", "femur in the hip's acetabulum", "mirror of the left"),
			new Asset("cow.hindlimb.left", "Left hindlimb", "skeleton/limbs/left_hindlimb", "hindlimb", "left", "skeleton", "femur in the hip's acetabulum", "as the right, with the kneecap in front of the stifle"),
			new Asset("cow.body_wall", "Body wall and muscle groups", "body_wall", "trunk", "both", "body", "outside the organs", "a representative flank cutaway and broad masses, not a muscle atlas")};

	/** Cubes per group of each export, for the manifest's counts. */
	static final Map<String, Map<String, Integer>> CUBES = new LinkedHashMap<>();

	/**
	 * The machine-readable manifest: every asset's identifiers, materials, states, bounds and pivot in the anatomical
	 * frame and in the vanilla cow's pixels, and what was simplified. Written next to the models so integration code can
	 * read it instead of parsing .bbmodel files.
	 */
	static void manifest(Path out, List<String> rows, List<String> stateRows) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("{\n  \"schema\": \"fluidgore.cow_anatomy/1\",\n");
		sb.append("  \"species\": \"cow (Bos taurus), adult non-pregnant female, dairy type, about 550 kg\",\n");
		sb.append("  \"generator\": \"tools/CowAnatomyModels.java\",\n");
		sb.append("  \"regenerate\": \"java tools/CowAnatomyModels.java art/cow_anatomy\",\n");
		sb.append("  \"frames\": {\n");
		sb.append("    \"anatomy_cm\": \"centimetres; +x the cow's right, +y up from the floor of the belly, -z towards the head (Blockbench: the cow faces north)\",\n");
		sb.append("    \"game_px\": \"vanilla adult cow model pixels (CowModel, 64x64), y up from the ground (y_up = 24 - y_model), the cow's right at -x, the head towards -z; one Blockbench unit is one model pixel\",\n");
		sb.append("    \"canonical\": \"the mod's Anatomy.Region.BEAST_TRUNK pixels: x -5..5 for the body's 12 (right at -x), y -4..4 for its 10 (back at -y, belly at +y), z -8..8 for its 18 (chest at -z); head and limbs in their regions' frames\",\n");
		sb.append("    \"transforms\": {\n");
		sb.append("      \"anatomy_cm_to_game_px\": \"per vanilla part, as Anatomy.fit: body cm x +25.5..-25.5 -> px -5.1..5.1, cm y 2..76 -> y_up 12.9..21.1, cm z -80..80 -> px -8..10; head cm x +13..-13 -> px -3.4..3.4, cm y 44..84 -> y_up 16.6..23.4, cm z -152..-80 -> px -15..-8; each leg cm -> its own 2.3x11.15x2.3 px box\",\n");
		sb.append("      \"game_px_to_canonical\": \"trunk only: canonical x = px x * 5/6, y = -(y_up - 17) * 0.8, z = (px z - 1) * 8/9 (the body's own centre sits at pixel z 1); head and limbs are fitted to their own regions (BEAST_HEAD, FORELEG, HINDLEG), whose canonical frames the px bounds determine directly\"\n");
		sb.append("    },\n");
		sb.append("    \"vanilla_cow\": [\n");
		sb.append("      {\"part\": \"body\", \"px\": [-6, 12, -8, 6, 22, 10]},\n");
		sb.append("      {\"part\": \"udder\", \"px\": [-2, 11, 4, 2, 12, 10]},\n");
		sb.append("      {\"part\": \"head\", \"px\": [-4, 16, -14, 4, 24, -8]},\n");
		sb.append("      {\"part\": \"muzzle\", \"px\": [-3, 16, -15, 3, 19, -14]},\n");
		sb.append("      {\"part\": \"right_horn\", \"px\": [-5, 22, -13, -4, 25, -12]},\n");
		sb.append("      {\"part\": \"left_horn\", \"px\": [4, 22, -13, 5, 25, -12]},\n");
		sb.append("      {\"part\": \"right_front_leg\", \"px\": [-6, 0, -7, -2, 12, -3]},\n");
		sb.append("      {\"part\": \"left_front_leg\", \"px\": [2, 0, -7, 6, 12, -3]},\n");
		sb.append("      {\"part\": \"right_hind_leg\", \"px\": [-6, 0, 5, -2, 12, 9]},\n");
		sb.append("      {\"part\": \"left_hind_leg\", \"px\": [2, 0, 5, 6, 12, 9]}\n");
		sb.append("    ]\n  },\n");
		sb.append("  \"assets\": [\n");
		boolean first = true;
		for (Asset a : ASSETS) {
			Bounds b = boundsOf(a.group());
			if (b == null) {
				continue;
			}

			if (!first) {
				sb.append(",\n");
			}

			first = false;
			StringBuilder materials = new StringBuilder();
			java.util.Set<String> kinds = new java.util.TreeSet<>();
			for (Part p : PARTS) {
				if (p.under(a.group())) {
					kinds.add(modPiece(p)[0]);
				}
			}

			for (String k : kinds) {
				if (materials.length() > 0) {
					materials.append("\", \"");
				}

				materials.append(k);
			}

			sb.append("    {\"id\":\"").append(a.id()).append("\",\"name\":\"").append(a.name()).append("\",\"species\":\"cow\",\"side\":\"")
					.append(a.side()).append("\",\"region\":\"").append(a.region()).append("\",\"parent\":\"").append(a.parent())
					.append("\",\"group\":\"").append(a.group()).append("\",\"materials\":[\"").append(materials).append("\"],\n");
			sb.append("     \"bounds_cm\":[").append(num(b.lo[0])).append(',').append(num(b.lo[1])).append(',').append(num(b.lo[2])).append(',')
					.append(num(b.hi[0])).append(',').append(num(b.hi[1])).append(',').append(num(b.hi[2])).append("],\"pivot_cm\":[")
					.append(num(b.cx)).append(',').append(num(b.cy)).append(',').append(num(b.cz)).append("],\n");
			double[] gp = gameOf(b);
			Fit gf = fitOf(b.cx, b.cy, b.cz);
			String canonical = "null";
			if (gp != null && gf != null && gf.name.equals("body")) {
				// The mod's BEAST_TRUNK frame: 10 across for the body's 12 (the right at -x), 8 down for its 10 (the back
				// at -y), 16 along for its 18 (the chest at -z, the body's own centre at pixel z 1).
				canonical = "[" + num(gp[0] * 5.0 / 6.0) + "," + num(-(gp[4] - 17) * 0.8) + "," + num((gp[2] - 1) * 8.0 / 9.0) + ","
						+ num(gp[3] * 5.0 / 6.0) + "," + num(-(gp[1] - 17) * 0.8) + "," + num((gp[5] - 1) * 8.0 / 9.0) + "]";
			}

			sb.append("     \"game\":").append(gp == null ? "null" : "{\"fit\":\"" + gf.name + "\",\"group\":\"" + a.group().replace('/', '_')
					+ "\",\"bounds_px\":[" + num(gp[0]) + "," + num(gp[1]) + "," + num(gp[2]) + "," + num(gp[3]) + "," + num(gp[4]) + "," + num(gp[5])
					+ "],\"pivot_px\":[" + num(gp[6]) + "," + num(gp[7]) + "," + num(gp[8]) + "],\"bounds_canonical\":" + canonical + "}").append(",\n");
			sb.append("     \"attachment\":\"").append(a.attachment()).append("\",\"limits\":\"").append(a.limits()).append("\",\n");
			sb.append("     \"states\":{\"intact\":").append(filesFor(a.group(), "intact")).append(",\"opened\":").append(filesFor(a.group(), "opened"))
					.append(",\"torn\":").append(filesFor(a.group(), "torn")).append(",\"charred\":").append(charredFor(a.group())).append("},\n");
			sb.append("     \"cubes\":").append(countsFor(a.group())).append("}");
		}

		sb.append("\n  ],\n");
		sb.append("  \"files\": {\n");
		first = true;
		for (Map.Entry<String, Map<String, Integer>> e : CUBES.entrySet()) {
			if (!first) {
				sb.append(",\n");
			}

			first = false;
			sb.append("    \"").append(e.getKey()).append("\": ").append(e.getValue().values().stream().mapToInt(Integer::intValue).sum());
		}

		sb.append("\n  }\n}\n");
		Files.writeString(out.resolve("manifest.json"), sb.toString(), StandardCharsets.UTF_8);
		System.out.println("Wrote " + out.resolve("manifest.json").toAbsolutePath());
	}

	/** A group's extent in centimetres and the volume centre of its shapes, for a manifest entry. */
	record Bounds(double[] lo, double[] hi, double cx, double cy, double cz) {
	}

	static Bounds boundsOf(String group) {
		double[] lo = {1e9, 1e9, 1e9};
		double[] hi = {-1e9, -1e9, -1e9};
		double w = 0;
		double sx = 0;
		double sy = 0;
		double sz = 0;
		for (Part p : PARTS) {
			if (!p.under(group)) {
				continue;
			}

			for (int a = 0; a < 3; a++) {
				lo[a] = Math.min(lo[a], p.shape.lo[a]);
				hi[a] = Math.max(hi[a], p.shape.hi[a]);
			}

			double v = Math.max(1e-6, (p.shape.hi[0] - p.shape.lo[0]) * (p.shape.hi[1] - p.shape.lo[1]) * (p.shape.hi[2] - p.shape.lo[2]));
			sx += (p.shape.lo[0] + p.shape.hi[0]) / 2 * v;
			sy += (p.shape.lo[1] + p.shape.hi[1]) / 2 * v;
			sz += (p.shape.lo[2] + p.shape.hi[2]) / 2 * v;
			w += v;
		}

		if (w == 0) {
			return null;
		}

		return new Bounds(lo, hi, sx / w, sy / w, sz / w);
	}

	/** The vanilla part a centimetre point is fitted to, or null where no region holds it. */
	static Fit fitOf(double cx, double cy, double cz) {
		for (Fit g : GAME_FITS) {
			double[] q = g.inv(cx, cy, cz);
			if (g.holds(q[0], q[1], q[2])) {
				return g;
			}
		}

		return null;
	}

	/** A group's bounds and pivot in the vanilla cow's pixels (y up), or null where it does not fit. */
	static double[] gameOf(Bounds b) {
		Fit f = fitOf(b.cx, b.cy, b.cz);
		if (f == null) {
			return null;
		}

		double[] out = new double[9];
		for (int corner = 0; corner < 8; corner++) {
			double[] q = f.inv((corner & 1) == 0 ? b.lo[0] : b.hi[0], (corner & 2) == 0 ? b.lo[1] : b.hi[1], (corner & 4) == 0 ? b.lo[2] : b.hi[2]);
			for (int a = 0; a < 3; a++) {
				// Inside the region's own box: a shape that reaches past the vanilla cube is clipped there, exactly as
				// the game-scale export clips it.
				q[a] = clamp(q[a], Math.min(f.px0[a], f.px1[a]), Math.max(f.px0[a], f.px1[a]));
				if (corner == 0) {
					out[a] = q[a];
					out[3 + a] = q[a];
				} else {
					out[a] = Math.min(out[a], q[a]);
					out[3 + a] = Math.max(out[3 + a], q[a]);
				}
			}
		}

		double[] centre = f.inv(b.cx, b.cy, b.cz);
		out[6] = clamp(centre[0], Math.min(f.px0[0], f.px1[0]), Math.max(f.px0[0], f.px1[0]));
		out[7] = clamp(centre[1], Math.min(f.px0[1], f.px1[1]), Math.max(f.px0[1], f.px1[1]));
		out[8] = clamp(centre[2], Math.min(f.px0[2], f.px1[2]), Math.max(f.px0[2], f.px1[2]));
		return out;
	}

	/** The export files that hold a group in one state. */
	static String filesFor(String group, String state) {
		List<String> files = new ArrayList<>();
		for (Map.Entry<String, Map<String, Integer>> e : CUBES.entrySet()) {
			boolean has = e.getValue().keySet().stream().anyMatch(g -> g.equals(group) || g.startsWith(group + "/"));
			boolean inState = state.equals("intact") ? !e.getKey().contains("opened") && !e.getKey().contains("torn")
					: e.getKey().contains(state.equals("opened") ? "opened" : "torn");
			if (has && inState) {
				files.add(e.getKey());
			}
		}

		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < files.size(); i++) {
			sb.append(i > 0 ? ", " : "").append('"').append(files.get(i)).append('"');
		}

		return sb.append(']').toString();
	}

	/** The alternate heated texture of the first full-size file a group appears in. */
	static String charredFor(String group) {
		for (Map.Entry<String, Map<String, Integer>> e : CUBES.entrySet()) {
			if (!e.getKey().contains("16x") && !e.getKey().contains("32x") && e.getValue().keySet().stream().anyMatch(g -> g.equals(group) || g.startsWith(group + "/"))) {
				return "\"textures/" + e.getKey().replace(".bbmodel", "") + "_charred.png\"";
			}
		}

		return "null";
	}

	/** Cube counts per export file that holds a group, by state. */
	static String countsFor(String group) {
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, Map<String, Integer>> e : CUBES.entrySet()) {
			int n = 0;
			for (Map.Entry<String, Integer> g : e.getValue().entrySet()) {
				if (g.getKey().equals(group) || g.getKey().startsWith(group + "/")) {
					n += g.getValue();
				}
			}

			if (n == 0) {
				continue;
			}

			if (!first) {
				sb.append(", ");
			}

			first = false;
			sb.append('"').append(e.getKey()).append("\": ").append(n);
		}

		return sb.append('}').toString();
	}

	// ------------------------------------------------------------------------------------------------ maths

	static double[] v(double... a) {
		return a;
	}

	static double sq(double a) {
		return a * a;
	}

	static double clamp(double a, double lo, double hi) {
		return a < lo ? lo : a > hi ? hi : a;
	}

	static double frac(double a) {
		return a - Math.floor(a);
	}

	static double smin(double a, double b, double k) {
		double h = Math.max(k - Math.abs(a - b), 0) / k;
		return Math.min(a, b) - h * h * k * 0.25;
	}

	static double[] add(double[] a, double[] b) {
		return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
	}

	static double[] sub(double[] a, double[] b) {
		return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	static double[] mul(double[] a, double s) {
		return new double[] {a[0] * s, a[1] * s, a[2] * s};
	}

	static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	static double len(double[] a) {
		return Math.sqrt(dot(a, a));
	}

	static double dist(double[] a, double[] b) {
		return Math.sqrt(sq(a[0] - b[0]) + sq(a[1] - b[1]) + sq(a[2] - b[2]));
	}

	static double[] norm(double[] a) {
		double l = len(a);
		return l == 0 ? a : mul(a, 1 / l);
	}

	static double hash(int x, int y, int z, int seed) {
		long h = x * 374761393L + y * 668265263L + z * 2147483647L + seed * 144665L;
		h = (h ^ (h >>> 13)) * 1274126177L;
		h ^= h >>> 16;
		return (h & 0xFFFFFF) / (double) 0x1000000;
	}

	/** Smooth value noise in [0, 1]. */
	static double noise(double x, double y, double z, int seed) {
		int xi = (int) Math.floor(x);
		int yi = (int) Math.floor(y);
		int zi = (int) Math.floor(z);
		double fx = x - xi;
		double fy = y - yi;
		double fz = z - zi;
		fx = fx * fx * (3 - 2 * fx);
		fy = fy * fy * (3 - 2 * fy);
		fz = fz * fz * (3 - 2 * fz);
		double r = 0;
		for (int c = 0; c < 8; c++) {
			int dx = c & 1;
			int dy = (c >> 1) & 1;
			int dz = (c >> 2) & 1;
			r += hash(xi + dx, yi + dy, zi + dz, seed) * (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz);
		}

		return r;
	}

	/** Thin winding lines: near 1 along the zero set of a noise, for blood vessels and straw. */
	static double ridge(double x, double y, double z, int seed) {
		return 1 - Math.abs(2 * noise(x, y, z, seed) - 1);
	}

	/** Worley cells: the distances to the nearest and second nearest feature point, and the nearest one's random id. */
	static double[] cells(double x, double y, double z, int seed) {
		int xi = (int) Math.floor(x);
		int yi = (int) Math.floor(y);
		int zi = (int) Math.floor(z);
		double f1 = 9;
		double f2 = 9;
		double id = 0;
		for (int a = -1; a <= 1; a++) {
			for (int b = -1; b <= 1; b++) {
				for (int c = -1; c <= 1; c++) {
					int cx = xi + a;
					int cy = yi + b;
					int cz = zi + c;
					double px = cx + hash(cx, cy, cz, seed);
					double py = cy + hash(cx, cy, cz, seed + 1);
					double pz = cz + hash(cx, cy, cz, seed + 2);
					double d = Math.sqrt(sq(x - px) + sq(y - py) + sq(z - pz));
					if (d < f1) {
						f2 = f1;
						f1 = d;
						id = hash(cx, cy, cz, seed + 3);
					} else if (d < f2) {
						f2 = d;
					}
				}
			}
		}

		return new double[] {f1, f2, id};
	}

	static int mix(int a, int b, double t) {
		t = clamp(t, 0, 1);
		int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return r << 16 | g << 8 | bl;
	}

	static int mul(int c, double f) {
		int r = (int) clamp(((c >> 16) & 255) * f, 0, 255);
		int g = (int) clamp(((c >> 8) & 255) * f, 0, 255);
		int b = (int) clamp((c & 255) * f, 0, 255);
		return r << 16 | g << 8 | b;
	}
}
