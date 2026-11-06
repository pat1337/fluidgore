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
 * Builds Blockbench models (.bbmodel) of a sheep's insides, organ by organ and all together in place. Run from the
 * project root with:
 * <pre>java tools/SheepAnatomyModels.java art/sheep_anatomy</pre>
 * Output is deterministic (fixed seeds and name-based uuids), so re-running produces identical files.
 *
 * <p>Every organ is sculpted once, in centimetres, where it lies in a standing ewe of about 65 kg (veterinary
 * topography: the rumen fills the left of the belly, the liver is pushed wholly to the right against the diaphragm,
 * the guts hang in a curtain on the right behind it, the heart lies low between the forelegs a little to the left).
 * The body frame: +x the sheep's right, +y up from the floor of the belly, -z towards the head, so in Blockbench the
 * sheep faces north. The shapes are then cut into voxels, the voxels merged into as few cubes as will hold them, and
 * every visible face painted from a 3D texture of its tissue, one texel per voxel face (the vanilla "silver age" rule
 * of one texel per model pixel). Organs that press on each other leave impressions: where two overlap the one
 * earlier in {@link #PARTS} keeps the voxel, so the liver carries the dents of the reticulum, omasum and kidney.
 *
 * <p>Each organ is one group in the outliner; its lobes, chambers, loops and vessels are subgroups, so an organ can
 * be shown, hidden, moved or broken apart in Blockbench as one anatomical object, its pieces as its parts.
 */
public final class SheepAnatomyModels {
	/** The voxel edge being cut, in cm. Thin parts are thickened to at least about one voxel so they never vanish. */
	private static double VOX = 1.0;
	/** The radius a {@link Tube#duct} keeps at least: a little over half the tallest side of a voxel, so a thin duct running down across the grid stays one piece. */
	private static double DUCT = 0;
	/** The least radius of a thin part, in voxels: enough to light one voxel centre, more at the mod's coarse grain. */
	private static double THIN = 0.55;
	/** How far past a shape its box reaches: past the thickening of thin parts at the coarsest grain (0.8 of a 4.7 cm voxel). */
	private static final double PAD = 4.0;
	private static final double YC = 17.0;
	private static final List<Part> PARTS = new ArrayList<>();

	private SheepAnatomyModels() {
	}

	public static void main(String[] args) throws IOException {
		Path out = Path.of(args.length > 0 ? args[0] : "art/sheep_anatomy");
		Files.createDirectories(out.resolve("textures"));
		Files.createDirectories(out.resolve("previews"));
		build();
		List<String> rows = new ArrayList<>();
		rows.add(export(out, "sheep_liver", "Sheep liver", 0.5, p -> p.under("abdomen/liver")));
		rows.add(export(out, "sheep_heart", "Sheep heart", 0.5, p -> p.under("thorax/heart")));
		rows.add(export(out, "sheep_lungs", "Sheep lungs and windpipe", 0.5, p -> p.under("thorax/lungs") || p.under("thorax/airway")));
		rows.add(export(out, "sheep_pluck", "Sheep pluck (heart, lungs, liver)", 0.5,
				p -> p.under("thorax/heart") || p.under("thorax/lungs") || p.under("thorax/airway") || p.under("abdomen/liver")));
		rows.add(export(out, "sheep_kidneys", "Sheep kidneys in suet", 0.5, p -> p.under("abdomen/kidneys")));
		rows.add(export(out, "sheep_glands", "Sheep glands, ureters and spinal cord (thymus, adrenals, pancreas, thyroid, salivary glands)", 0.5,
				p -> p.under("thorax/thymus") || p.under("abdomen/adrenals") || p.under("abdomen/pancreas") || p.under("head/glands") || p.under("nervous")
						|| p.name.contains("ureter") || p.name.equals("urethra") || p.under("abdomen/urinary/bladder")));
		rows.add(export(out, "sheep_omentum", "Sheep greater omentum over the intestines", 0.5, p -> p.under("abdomen/omentum") || p.under("abdomen/intestines")));
		rows.add(export(out, "sheep_spleen", "Sheep spleen", 0.5, p -> p.under("abdomen/spleen")));
		rows.add(export(out, "sheep_stomachs", "Sheep stomachs (rumen, reticulum, omasum, abomasum)", 0.5, p -> p.under("abdomen/stomach")));
		rows.add(export(out, "sheep_omasum", "Sheep omasum (the manyplies)", 0.5, p -> p.under("abdomen/stomach/omasum")));
		rows.add(export(out, "sheep_intestines", "Sheep intestines", 0.5, p -> p.under("abdomen/intestines")));
		rows.add(export(out, "sheep_skeleton", "Sheep trunk skeleton", 0.5, p -> p.under("skeleton")));
		rows.add(export(out, "sheep_skull", "Sheep skull, jaw and teeth", 0.5, p -> p.under("skeleton/skull")));
		rows.add(export(out, "sheep_brain", "Sheep brain", 0.5, p -> p.under("head/brain")));
		rows.add(export(out, "sheep_head", "Sheep head (skull, jaw, teeth, brain)", 0.5, p -> p.under("skeleton/skull") || p.under("head/brain")));
		rows.add(export(out, "sheep_in_situ", "Sheep anatomy in place", 1.0, p -> true));
		Predicate<Part> tract = p -> p.under("reproductive") || p.under("abdomen/urinary") || p.under("skeleton/pelvis");
		rows.add(export(out, "sheep_ewe_reproductive", "Ewe reproductive tract and udder, with the bladder and pelvis", 0.5, tract));
		MALE = true;
		PARTS.clear();
		build();
		rows.add(export(out, "sheep_ram_reproductive", "Ram reproductive organs, with the bladder and pelvis", 0.5, tract));
		MALE = false;
		LAMB = true;
		PARTS.clear();
		build();
		rows.add(export(out, "sheep_lamb_stomachs", "Lamb stomachs (small rumen, big abomasum) and thymus", 0.5,
				p -> p.under("abdomen/stomach") || p.under("thorax/thymus") || p.under("thorax/heart")));
		rows.add(export(out, "sheep_lamb_skull", "Lamb skull and milk teeth", 0.5, p -> p.under("skeleton/skull")));
		LAMB = false;
		PARTS.clear();
		build();
		rows.add(export(out, "sheep_grown_stomachs", "Grown sheep stomachs and thymus, to compare with the lamb's", 0.5,
				p -> p.under("abdomen/stomach") || p.under("thorax/thymus") || p.under("thorax/heart")));
		Files.writeString(out.resolve("README.md"), readme(rows), StandardCharsets.UTF_8);
		System.out.println("Wrote " + out.toAbsolutePath());
		Path art = out.toAbsolutePath().getParent();
		Map<Integer, List<String>> tables = new java.util.LinkedHashMap<>();
		for (int res : MOD_GRAINS) {
			tables.put(res, mob(art.resolve("sheep_anatomy_" + res + "x"), res));
		}

		List<String> mixed = mixed();
		// The ram: the same body with his own reproductive organs, at the two grains the mixed table is cut from.
		MALE = true;
		for (int res : new int[] {MOD_RES, MOD_RES * 2}) {
			mob(art.resolve("sheep_anatomy_ram_" + res + "x"), res);
		}

		List<String> ram = mixed();
		// The lambs, ewe and ram (the vanilla baby sheep).
		LAMB = true;
		List<List<String>> lambs = new ArrayList<>();
		for (boolean male : new boolean[] {false, true}) {
			MALE = male;
			for (int res : new int[] {MOD_RES, MOD_RES * 2}) {
				mob(art.resolve("sheep_anatomy_" + (male ? "ram_lamb_" : "ewe_lamb_") + res + "x"), res);
			}

			lambs.add(mixed());
		}

		LAMB = false;
		MALE = false;

		Path java = Path.of("src/main/java/dev/fluidgore/anatomy/SheepTrunk.java");
		Files.writeString(java, trunkJava(tables, mixed, ram, lambs.get(0), lambs.get(1)), StandardCharsets.UTF_8);
		System.out.println("Wrote " + java.toAbsolutePath());
		Path headJava = Path.of("src/main/java/dev/fluidgore/anatomy/SheepHead.java");
		Files.writeString(headJava, headJava(), StandardCharsets.UTF_8);
		System.out.println("Wrote " + headJava.toAbsolutePath());
	}

	/**
	 * The grain the mod is built from: 16 would be a whole pixel per voxel, but the trunk inside its fat and muscle is only
	 * 6x4 pixels across, too few to keep the organs apart from the bones; 32 cuts half pixels (the mod has always placed
	 * anatomy finer than whole pixels) and still draws with the 16x tiles, a texel a pixel.
	 */
	static final int MOD_RES = 32;

	/**
	 * Every grain the mod carries a trunk table for: {@link #MOD_RES} is the one it draws; 16 (a voxel a pixel) and 64 (a
	 * quarter pixel) are for comparing detail side by side (the {@code resolution} demo), and 64 also gives the finer
	 * organs a mixed table borrows (see {@code Anatomy.Detail}).
	 */
	static final int[] MOD_GRAINS = {16, 32, 64};

	/**
	 * The same anatomy at the scale of the game: squashed into the vanilla sheep's 8x6x16 body cube, inside its fat and
	 * muscle (as the mod's {@code Region.BEAST_TRUNK} lays them), cut into voxels of 16 / {@code res} of a pixel and drawn
	 * with the mod's own anatomy.png, so Blockbench shows what the game draws. At {@link #MOD_RES} it also writes the mod's piece table.
	 */
	static List<String> mob(Path out, int res) throws IOException {
		Files.createDirectories(out.resolve("previews"));
		double vox = 16.0 / res;
		// Pixels to centimetres (the model has the right at -x, the anatomy at +x): the body's interior, +-3.32 px across, 12.64 to 17.36 px up, -8 to 8 along, holds the
		// ewe's +-15.5 cm, -3 to 35 cm (so the four rows of whole pixels fall on the breastbone, the belly, the middle
		// of the chest and the spine) and -44 to 44 cm.
		SCALE = new double[] {-15.5 / 3.32, 38.0 / 4.72, 5.5};
		SHIFT = new double[] {0, -3.0 - 38.0 / 4.72 * 12.64, 0};
		CLIP_LO = new double[] {-3.32, 12.64, -8};
		CLIP_HI = new double[] {3.32, 17.36, 8};
		LOD = vox * 5.5;
		THIN = res <= 16 ? 0.8 : 0.55;
		TILES = ImageIO.read(Path.of("src/main/resources/assets/fluidgore/textures/misc/anatomy.png").toFile());
		PARTS.clear();
		build();
		List<String> rows = new ArrayList<>();
		String r = res + "x";
		rows.add(export(out, "sheep_trunk_" + r, "Sheep trunk at " + r + " (as the mod draws it)", vox, p -> true));
		rows.add(export(out, "sheep_organs_" + r, "Sheep organs at " + r + " (skeleton and diaphragm left out)", vox,
				p -> !p.under("skeleton") && !p.under("thorax/diaphragm")));
		rows.add(export(out, "sheep_liver_" + r, "Sheep liver at " + r, vox, p -> p.under("abdomen/liver")));
		rows.add(export(out, "sheep_heart_lungs_" + r, "Sheep heart and lungs at " + r, vox,
				p -> p.under("thorax/heart") || p.under("thorax/lungs") || p.under("thorax/airway")));
		rows.add(export(out, "sheep_stomachs_" + r, "Sheep stomachs at " + r, vox, p -> p.under("abdomen/stomach")));
		rows.add(export(out, "sheep_guts_" + r, "Sheep intestines, kidneys, spleen at " + r, vox,
				p -> p.under("abdomen/intestines") || p.under("abdomen/kidneys") || p.under("abdomen/spleen") || p.under("abdomen/urinary")));
		rows.add(export(out, "sheep_skeleton_" + r, "Sheep trunk skeleton at " + r, vox, p -> p.under("skeleton")));
		Grid g = voxelize(vox, p -> true);
		if (System.getProperty("debug") != null) {
			Map<String, int[]> who = new java.util.TreeMap<>();
			for (int i = 0; i < g.nx; i++) {
				for (int j = 0; j < g.ny; j++) {
					for (int k = 0; k < g.nz; k++) {
						List<String> in = new ArrayList<>();
						for (Part p : PARTS) {
							if (p.shape.in(g.cx(i), g.cy(j), g.cz(k))) {
								in.add(modPiece(p)[1]);
							}
						}

						if (!in.isEmpty()) {
							for (String s : new java.util.LinkedHashSet<>(in)) {
								who.computeIfAbsent(s, q -> new int[2])[0]++;
							}

							who.get(in.get(0))[1]++;
						}
					}
				}
			}

			who.forEach((k, v2) -> System.out.println(res + " " + k + " inside " + v2[0] + " won " + v2[1]));
		}

		List<String> table = pieces(g, boxes(g));
		String[][] keys = new String[PARTS.size()][];
		for (int i = 0; i < keys.length; i++) {
			keys[i] = modPiece(PARTS.get(i));
		}

		GRIDS.put(res, g);
		KEYS.put(res, keys);
		Files.writeString(out.resolve("README.md"), mobReadme(r, rows), StandardCharsets.UTF_8);
		SCALE = new double[] {1, 1, 1};
		SHIFT = new double[] {0, 0, 0};
		CLIP_LO = null;
		CLIP_HI = null;
		TILES = null;
		THIN = 0.55;
		LOD = 0.5;
		return table;
	}

	/**
	 * One grain's piece table, a {@code p(...)} line per cube: every cube of the trunk in {@code Region.BEAST_TRUNK}'s canonical
	 * pixels as the beast stands (x as the model has it, the right at -x, 10 across for the body's 8; y down, the back at -4,
	 * 8 for its 6; z the same, the chest at -8).
	 */
	static List<String> pieces(Grid g, List<Box> boxes) {
		List<String> lines = new ArrayList<>();
		for (Box b : boxes) {
			String[] mp = modPiece(PARTS.get(b.key - 1));
			double x0 = g.ox + b.x0 * g.vox;
			double x1 = g.ox + b.x1 * g.vox;
			double y0 = g.oy + b.y0 * g.vox;
			double y1 = g.oy + b.y1 * g.vox;
			double z0 = g.oz + b.z0 * g.vox;
			double z1 = g.oz + b.z1 * g.vox;
			lines.add(line(mp, x0, x1, y0, y1, z0, z1));
		}

		return lines;
	}

	/** Each grain's grid and the (kind, name) of every key in it, kept by {@link #mob} for {@link #mixed}. */
	static final Map<Integer, Grid> GRIDS = new LinkedHashMap<>();
	static final Map<Integer, String[][]> KEYS = new LinkedHashMap<>();

	/**
	 * The organs the mixed grain cuts at 64 (quarter pixels) inside a body cut at 32 (half pixels): the small ones
	 * whose shape is lost at half pixels. Must match {@code Anatomy.Detail.HERO}.
	 */
	static final java.util.Set<String> HERO = java.util.Set.of("heart", "aorta", "vena_cava", "windpipe", "esophagus", "kidney", "kidney_fat",
			"pancreas", "thymus", "adrenal_gland", "ureter", "urethra", "spinal_cord", "vulva", "vagina", "cervix", "uterus", "oviduct", "ovary", "udder", "teat",
			"testis", "epididymis", "vas_deferens", "spermatic_cord", "vesicular_gland", "scrotum", "penis", "prepuce");

	/**
	 * The mixed table: the half-pixel grain's cubes, except that each {@link #HERO} organ is recut at quarter pixels inside
	 * the half-pixel cells it filled (its quarter cells outside them, and the half cells it does not fill, left open). The
	 * fine organ keeps its cavity, its edges on the half-pixel cubes round it, and every other organ is untouched.
	 */
	static List<String> mixed() {
		return mixed(GRIDS.get(MOD_RES), KEYS.get(MOD_RES), GRIDS.get(MOD_RES * 2), KEYS.get(MOD_RES * 2), HERO, SheepAnatomyModels::line);
	}

	/** Fewer quarter-pixel cells than this, cut off from the rest of their organ, are a speck and left out of a mixed table. */
	static final int SPECK = 4;

	/** One cube of a table as its line of Java: (kind, name), then x0, x1, y0, y1, z0, z1 in output units. */
	interface Line {
		String of(String[] mp, double x0, double x1, double y0, double y1, double z0, double z1);
	}

	/** {@link #mixed()} for any pair of grids a grain apart, {@code hero} the organs taken fine. */
	static List<String> mixed(Grid c, String[][] ck, Grid f, String[][] fk, java.util.Set<String> hero, Line line) {
		double v = f.vox;
		double ox = Math.min(c.ox, f.ox);
		double oy = Math.min(c.oy, f.oy);
		double oz = Math.min(c.oz, f.oz);
		int nx = (int) Math.round((Math.max(c.ox + c.nx * c.vox, f.ox + f.nx * v) - ox) / v);
		int ny = (int) Math.round((Math.max(c.oy + c.ny * c.vox, f.oy + f.ny * v) - oy) / v);
		int nz = (int) Math.round((Math.max(c.oz + c.nz * c.vox, f.oz + f.nz * v) - oz) / v);
		Grid m = new Grid(nx, ny, nz, v, ox, oy, oz);
		// One key per (kind, name) across both grains.
		Map<String, Integer> ids = new LinkedHashMap<>();
		List<String[]> names = new ArrayList<>();
		// The half-pixel cells of the fine organs: only these are cut to quarter pixels, and only the fine organ's own quarter
		// cells in them are kept. So the fine organ sits in the very cavity its half-pixel self filled, and every other organ
		// is exactly as the half-pixel table has it.
		boolean[] split = new boolean[c.cell.length];
		// Organs half pixels lost entirely (a thin row of teeth, the brainstem): they take what cells they need, of whatever is there.
		// An organ half pixels keep only scraps of (under two thirds of the fine organ's volume: a thin cord, a flat gland)
		// counts as lost too, or it would be kept as those scraps.
		Map<String, Integer> coarseCells = new java.util.HashMap<>();
		Map<String, Integer> fineCells = new java.util.HashMap<>();
		for (int id : c.cell) {
			if (id != 0) {
				coarseCells.merge(ck[id - 1][1], 1, Integer::sum);
			}
		}

		for (int id : f.cell) {
			if (id != 0) {
				fineCells.merge(fk[id - 1][1], 1, Integer::sum);
			}
		}

		double ratio = Math.pow(c.vox / f.vox, 3);
		java.util.Set<String> coarseHas = new java.util.HashSet<>();
		coarseCells.forEach((name, n) -> {
			if (!hero.contains(name) || n * ratio * 1.5 >= fineCells.getOrDefault(name, 0)) {
				coarseHas.add(name);
			}
		});
		System.out.println("mixed: half pixels lose " + hero.stream().filter(h -> !coarseHas.contains(h)).sorted().toList());

		for (int i = 0; i < nx; i++) {
			for (int j = 0; j < ny; j++) {
				for (int k = 0; k < nz; k++) {
					String[] fine = key(f, fk, m.wx(i), m.wy(j), m.wz(k));
					String[] coarse = key(c, ck, m.wx(i), m.wy(j), m.wz(k));
					int parent = parent(c, m.wx(i), m.wy(j), m.wz(k));
					boolean fineHero = fine != null && hero.contains(fine[1]);
					if (parent >= 0 && (coarse != null && hero.contains(coarse[1]) || fineHero && (coarse == null || !coarseHas.contains(fine[1])))) {
						split[parent] = true;
					}
				}
			}
		}

		for (int i = 0; i < nx; i++) {
			for (int j = 0; j < ny; j++) {
				for (int k = 0; k < nz; k++) {
					int parent = parent(c, m.wx(i), m.wy(j), m.wz(k));
					String[] fine = key(f, fk, m.wx(i), m.wy(j), m.wz(k));
					if (parent < 0 || !split[parent]) {
						continue;
					}

					String[] coarse = key(c, ck, m.wx(i), m.wy(j), m.wz(k));
					String[] own = fine != null && hero.contains(fine[1]) ? fine : coarse != null && !hero.contains(coarse[1]) ? coarse : null;
					if (own != null) {
						int id = ids.computeIfAbsent(own[0] + "/" + own[1], s -> {
							names.add(own);
							return names.size();
						});
						m.set(i, j, k, id);
					}
				}
			}
		}

		// A fine organ's specks (a few quarter-pixel cells cut off from the rest) are left out: alone they would be organs of
		// their own, a loose cube of heart beside the heart. An organ that is all small pieces (an adrenal) keeps them.
		boolean[] seen = new boolean[m.cell.length];
		List<List<Integer>> comps = new ArrayList<>();
		Map<Integer, Integer> largest = new java.util.HashMap<>();
		// Cells touching at a face, an edge or a corner are one piece, as the mod joins them ({@code Anatomy.objects}).
		List<int[]> near = new ArrayList<>();
		for (int a = -1; a <= 1; a++) {
			for (int b = -1; b <= 1; b++) {
				for (int d = -1; d <= 1; d++) {
					if (a != 0 || b != 0 || d != 0) {
						near.add(new int[] {a, b, d});
					}
				}
			}
		}

		int[][] steps = near.toArray(int[][]::new);
		for (int start = 0; start < m.cell.length; start++) {
			int id = m.cell[start];
			if (id == 0 || seen[start] || !hero.contains(names.get(id - 1)[1])) {
				continue;
			}

			List<Integer> comp = new ArrayList<>();
			java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>(List.of(start));
			seen[start] = true;
			while (!queue.isEmpty()) {
				int at = queue.poll();
				comp.add(at);
				int i = at / (ny * nz);
				int j = at / nz % ny;
				int k = at % nz;
				for (int[] s : steps) {
					int a = i + s[0];
					int b = j + s[1];
					int c2 = k + s[2];
					if (m.at(a, b, c2) == id) {
						int next = (a * ny + b) * nz + c2;
						if (!seen[next]) {
							seen[next] = true;
							queue.add(next);
						}
					}
				}
			}

			comps.add(comp);
			largest.merge(id, comp.size(), Math::max);
		}

		// A speck is a small piece close beside a bigger one of the same organ (within two cells); a small organ on its own, such as
		// the far ovary of a pair, is kept.
		int[] compOf = new int[m.cell.length];
		java.util.Arrays.fill(compOf, -1);
		for (int ci = 0; ci < comps.size(); ci++) {
			for (int at : comps.get(ci)) {
				compOf[at] = ci;
			}
		}

		for (int ci = 0; ci < comps.size(); ci++) {
			List<Integer> comp = comps.get(ci);
			int id = m.cell[comp.get(0)];
			if (comp.size() >= SPECK || largest.get(id) < SPECK) {
				continue;
			}

			boolean beside = false;
			for (int at : comp) {
				int i = at / (ny * nz);
				int j = at / nz % ny;
				int k = at % nz;
				for (int a = i - 2; a <= i + 2 && !beside; a++) {
					for (int b = j - 2; b <= j + 2 && !beside; b++) {
						for (int d = k - 2; d <= k + 2 && !beside; d++) {
							if (m.at(a, b, d) == id) {
								int other = compOf[(a * ny + b) * nz + d];
								beside = other != ci && other >= 0 && comps.get(other).size() > comp.size();
							}
						}
					}
				}
			}

			if (beside) {
				comp.forEach(at -> m.cell[at] = 0);
			}
		}

		List<String> lines = new ArrayList<>();
		// The half pixels that were not split, merged as the half-pixel table merges them.
		Grid kept = new Grid(c.nx, c.ny, c.nz, c.vox, c.ox, c.oy, c.oz);
		for (int n = 0; n < c.cell.length; n++) {
			kept.cell[n] = split[n] || c.cell[n] == 0 || hero.contains(ck[c.cell[n] - 1][1]) ? 0 : c.cell[n];
		}

		for (Box b : boxes(kept)) {
			lines.add(line.of(ck[b.key - 1], c.ox + b.x0 * c.vox, c.ox + b.x1 * c.vox, c.oy + b.y0 * c.vox, c.oy + b.y1 * c.vox, c.oz + b.z0 * c.vox,
					c.oz + b.z1 * c.vox));
		}

		for (Box b : boxes(m)) {
			String[] mp = names.get(b.key - 1);
			lines.add(line.of(mp, m.ox + b.x0 * v, m.ox + b.x1 * v, m.oy + b.y0 * v, m.oy + b.y1 * v, m.oz + b.z0 * v, m.oz + b.z1 * v));
		}

		return lines;
	}

	/**
	 * The head's small parts that half pixels would lose, cut at quarter pixels in the mixed head: the three parts of the
	 * brain, the eyes, the teeth and the dental pad.
	 */
	static final java.util.Set<String> HEAD_HERO = java.util.Set.of("cerebrum", "cerebellum", "brainstem", "eye", "incisors", "upper_molars",
			"lower_molars", "dental_pad", "thyroid", "salivary_gland", "thymus");

	/**
	 * The skull and what it holds, squashed into the vanilla sheep's head box like the trunk is into its body, at
	 * {@code res}. Output units are the head's own model pixels: x across (the right at -x), y up from the bottom of the box
	 * (6 high), z along it (8 long, the nose at -8). The head's anatomy is not one scale: the long muzzle is squashed far
	 * more than the braincase, so the eyes and the jaw's hinge come where the game draws the eyes and swings the jaw.
	 * Only inside the skin, fat and muscle the mod lays over it.
	 */
	static Grid headGrid(int res, Map<Integer, String[][]> keysOut) {
		double vox = 16.0 / res;
		WARP = new java.util.function.DoubleUnaryOperator[] {x -> -1.963 * x, y -> stretch(y, 0.3, 18.4, 4.39, 26.3, 5.7, 29.9),
				z -> stretch(z, -7.8, -99.3, -4.25, -81.5, -0.2, -73.3)};
		CLIP_LO = new double[] {-2.85, 0.3, -7.8};
		CLIP_HI = new double[] {2.85, 5.7, -0.2};
		VOX = vox * 1.9;
		DUCT = 0;
		LOD = vox * 1.9;
		THIN = res <= 16 ? 0.8 : 0.55;
		PARTS.clear();
		build();
		Grid g = voxelize(vox, p -> headPiece(p) != null);
		String[][] keys = new String[PARTS.size()][];
		for (int i = 0; i < keys.length; i++) {
			keys[i] = modPiece(PARTS.get(i));
		}

		keysOut.put(res, keys);
		WARP = null;
		CLIP_LO = null;
		CLIP_HI = null;
		VOX = 1.0;
		LOD = 0.5;
		THIN = 0.55;
		return g;
	}

	/** Piecewise linear through (x, y) pairs, carried on straight past the ends. */
	static double stretch(double x, double... xy) {
		int i = x <= xy[2] ? 0 : xy.length - 4;
		for (int k = 2; k < xy.length - 2; k += 2) {
			if (x > xy[k]) {
				i = k;
			}
		}

		return xy[i + 1] + (xy[i + 3] - xy[i + 1]) * (x - xy[i]) / (xy[i + 2] - xy[i]);
	}

	/** A head cube in {@code Region.BEAST_HEAD}'s canonical pixels: 8 across, 8 down (the poll at -4), 8 along (the nose at -8). */
	static String headLine(String[] mp, double x0, double x1, double y0, double y1, double z0, double z1) {
		String name = mp[1];
		boolean jaw = java.util.Set.of("jawbone", "lower_molars", "incisors", "tongue").contains(name);
		float texels = name.equals("eye") ? 4.0F : mp[0].equals("TEETH") ? 2.0F : 1.0F;
		return "p(Kind." + mp[0] + ", " + fl(x0 * 4 / 3) + ", " + fl(4 - y1 * 4 / 3) + ", " + fl(z0) + ", " + fl(x1 * 4 / 3) + ", " + fl(4 - y0 * 4 / 3) + ", "
				+ fl(z1) + ", " + jaw + ", " + fl(texels) + ", \"" + name + "\")";
	}

	/** {@code anatomy/SheepHead.java}: the mixed head, half pixels with {@link #HEAD_HERO} at quarter pixels. */
	static String headJava() {
		Map<Integer, String[][]> keys = new LinkedHashMap<>();
		Grid c = headGrid(MOD_RES, keys);
		Grid f = headGrid(MOD_RES * 2, keys);
		List<String> lines = mixed(c, keys.get(MOD_RES), f, keys.get(MOD_RES * 2), HEAD_HERO, SheepAnatomyModels::headLine);
		LAMB = true;
		Map<Integer, String[][]> lambKeys = new LinkedHashMap<>();
		Grid lc = headGrid(MOD_RES, lambKeys);
		Grid lf = headGrid(MOD_RES * 2, lambKeys);
		List<String> lamb = mixed(lc, lambKeys.get(MOD_RES), lf, lambKeys.get(MOD_RES * 2), HEAD_HERO, SheepAnatomyModels::headLine);
		LAMB = false;
		StringBuilder sb = new StringBuilder();
		sb.append("""
				package dev.fluidgore.anatomy;

				import dev.fluidgore.anatomy.Anatomy.Kind;
				import dev.fluidgore.anatomy.Anatomy.Piece;

				/**
				 * A sheep's head, generated by {@code tools/SheepAnatomyModels.java} from its veterinary model of a ewe: do not edit,
				 * change the tool and run it again. The skull (braincase, nasal bones, upper jaw, cheekbones, orbits, the jaw), the
				 * teeth (the lower incisors biting on the dental pad, the cheek teeth), the brain in its three parts, the eyes, the
				 * tongue, the larynx and pharynx and the nasal cavity, squashed into the vanilla head box: half pixels, the brain, eyes
				 * and teeth quarter pixels. In {@link Anatomy.Region#BEAST_HEAD}'s canonical pixels: the right at -x, the poll at -y,
				 * the nose at -z. What hangs on the jaw swings open with it.
				 */
				final class SheepHead {
					private SheepHead() {
					}

				""");
		sb.append("\t/** The head: ").append(lines.size()).append(" cubes. */\n\tstatic Piece[] pieces() {\n\t\treturn new Piece[] {\n");
		lines.forEach(l -> sb.append("\t\t\t").append(l).append(",\n"));
		sb.append("\t\t};\n\t}\n\n\t/** A lamb's head: its milk teeth, and its thymus up the throat. ").append(lamb.size())
				.append(" cubes. */\n\tstatic Piece[] lamb() {\n\t\treturn new Piece[] {\n");
		lamb.forEach(l -> sb.append("\t\t\t").append(l).append(",\n"));
		sb.append("""
						};
					}

					private static Piece p(Kind kind, float x0, float y0, float z0, float x1, float y1, float z1, boolean jaw, float texels, String name) {
						return new Piece(kind, x0, y0, z0, x1, y1, z1, jaw, texels, name);
					}
				}
				""");
		return sb.toString();
	}

	/** The index of the cell of {@code g} that holds the point, or -1 if it lies outside {@code g}. */
	static int parent(Grid g, double x, double y, double z) {
		int i = (int) Math.floor((x - g.ox) / g.vox);
		int j = (int) Math.floor((y - g.oy) / g.vox);
		int k = (int) Math.floor((z - g.oz) / g.vox);
		return i < 0 || j < 0 || k < 0 || i >= g.nx || j >= g.ny || k >= g.nz ? -1 : (i * g.ny + j) * g.nz + k;
	}

	/** The (kind, name) of the cell of {@code g} that holds the point, or null if none does. */
	static String[] key(Grid g, String[][] keys, double x, double y, double z) {
		int key = g.at((int) Math.floor((x - g.ox) / g.vox), (int) Math.floor((y - g.oy) / g.vox), (int) Math.floor((z - g.oz) / g.vox));
		return key == 0 ? null : keys[key - 1];
	}

	static String line(String[] mp, double x0, double x1, double y0, double y1, double z0, double z1) {
		return "p(Kind." + mp[0] + ", " + fl(x0 * 1.25) + ", " + fl(-(y1 - 15) / 0.75) + ", " + fl(z0) + ", " + fl(x1 * 1.25) + ", "
				+ fl(-(y0 - 15) / 0.75) + ", " + fl(z1) + ", \"" + mp[1] + "\")";
	}

	/** A Java method holds at most 64 KB of code: tables longer than this are split into parts joined at load. */
	static final int CHUNK = 900;

	/** {@code anatomy/SheepTrunk.java}: one method per grain ({@link #MOD_GRAINS}), and {@code pieces()}, the mixed table the mod draws. */
	static String trunkJava(Map<Integer, List<String>> tables, List<String> mixed, List<String> ram, List<String> eweLamb, List<String> ramLamb) {
		Map<String, List<String>> named = new LinkedHashMap<>();
		tables.forEach((res, lines) -> named.put(res == MOD_RES ? "standard" : res < MOD_RES ? "coarse" : "fine", lines));
		named.put("pieces", mixed);
		named.put("ram", ram);
		named.put("eweLamb", eweLamb);
		named.put("ramLamb", ramLamb);
		StringBuilder sb = new StringBuilder();
		sb.append("""
				package dev.fluidgore.anatomy;

				import dev.fluidgore.anatomy.Anatomy.Kind;
				import dev.fluidgore.anatomy.Anatomy.Piece;

				/**
				 * A ruminant's trunk (a sheep's, which a cow shares), generated by {@code tools/SheepAnatomyModels.java} from its
				 * veterinary model of a ewe: do not edit, change the tool and run it again. The organs lie where they do in the
				 * animal, squashed into the vanilla body, cut to half pixels (the smallest organs to quarter pixels) and drawn with the
				 * 16x tiles; every cube is named by the organ it belongs to, so the pieces of one organ (the liver's lobes, gallbladder
				 * and veins; the heart and its fat) can be kept together as one object. In {@link Anatomy.Region#BEAST_TRUNK}'s
				 * canonical pixels as the beast stands: the body's right at -x, the back at -y, the chest at -z. Blockbench files of
				 * the even grains are in {@code art/sheep_anatomy_16x}, {@code _32x} and {@code _64x}. {@link #coarse()},
				 * {@link #standard()} and {@link #fine()} are the same anatomy cut evenly to whole, half and quarter pixels, for
				 * comparing detail (see {@link Anatomy.Detail}).
				 */
				final class SheepTrunk {
					private SheepTrunk() {
					}
				""");
		for (Map.Entry<String, List<String>> e : named.entrySet()) {
			String name = e.getKey();
			List<String> lines = e.getValue();
			String what = switch (name) {
				case "coarse" -> "cut to whole pixels";
				case "standard" -> "cut to half pixels";
				case "fine" -> "cut to quarter pixels";
				case "ram" -> "of a ram, his own reproductive organs in place of the ewe's, as the mod draws it";
				case "eweLamb" -> "of a ewe lamb, as the mod draws it: a small rumen and a big abomasum, a big thymus, her tract immature";
				case "ramLamb" -> "of a ram lamb, as the mod draws it: a small rumen and a big abomasum, a big thymus, his organs immature";
				default -> "as the mod draws it: half pixels, with the small organs that need it (" + String.join(", ", new java.util.TreeSet<>(HERO))
						+ ") at quarter pixels";
			};
			sb.append("\n\t/** The trunk ").append(what).append(": ").append(lines.size()).append(" cubes. */\n");
			if (lines.size() <= CHUNK) {
				sb.append("\tstatic Piece[] ").append(name).append("() {\n\t\treturn new Piece[] {\n");
				lines.forEach(l -> sb.append("\t\t\t").append(l).append(",\n"));
				sb.append("\t\t};\n\t}\n");
				continue;
			}

			int parts = (lines.size() + CHUNK - 1) / CHUNK;
			sb.append("\tstatic Piece[] ").append(name).append("() {\n\t\treturn join(");
			for (int c = 0; c < parts; c++) {
				sb.append(c == 0 ? "" : ", ").append(name).append(c).append("()");
			}

			sb.append(");\n\t}\n");
			for (int c = 0; c < parts; c++) {
				sb.append("\n\tprivate static Piece[] ").append(name).append(c).append("() {\n\t\treturn new Piece[] {\n");
				lines.subList(c * CHUNK, Math.min(lines.size(), (c + 1) * CHUNK)).forEach(l -> sb.append("\t\t\t").append(l).append(",\n"));
				sb.append("\t\t};\n\t}\n");
			}
		}

		sb.append("""

					private static Piece[] join(Piece[]... parts) {
						int n = 0;
						for (Piece[] part : parts) {
							n += part.length;
						}

						Piece[] all = new Piece[n];
						int at = 0;
						for (Piece[] part : parts) {
							System.arraycopy(part, 0, all, at, part.length);
							at += part.length;
						}

						return all;
					}

					private static Piece p(Kind kind, float x0, float y0, float z0, float x1, float y1, float z1, String name) {
						return new Piece(kind, x0, y0, z0, x1, y1, z1, false, 1.0F, name);
					}
				}
				""");
		return sb.toString();
	}

	static String fl(double d) {
		String s = String.format(Locale.ROOT, "%.4f", d).replaceAll("0+$", "");
		return (s.endsWith(".") ? s + "0" : s) + "F";
	}

	// ------------------------------------------------------------------------------------------------ the body frame

	/** The half width of the trunk at the ribs: narrow at the chest's entrance between the forelegs, a barrel behind. */
	static double halfWidth(double z) {
		return curve(z, -41, 4.0, -38, 5.5, -34, 7.5, -30, 9.3, -26, 10.8, -20, 12.4, -14, 13.6, -8, 14.4, -2, 14.9, 4, 15.1,
				14, 15.0, 22, 14.2, 28, 12.8, 34, 10.5, 40, 8.0);
	}

	/** The height of the breastbone, rising towards the entrance of the chest. */
	static double sternumY(double z) {
		return curve(z, -41, 14.0, -38, 10.5, -34, 7.0, -30, 4.8, -25, 3.3, -20, 2.6, -14, 2.2, -9, 2.0, 0, 1.5);
	}

	/** The height of the middle of the vertebral bodies. */
	static double spineY(double z) {
		return curve(z, -48, 26.5, -40, 28.5, -36, 29.5, -28, 30.2, -10, 30.4, 0, 30.5, 20, 30.6, 26, 30.0, 36, 28.0, 46, 26.0);
	}

	/**
	 * Where the diaphragm crosses (x, y): a dome bulging forward into the chest, its top under the 8th-9th rib, its
	 * edge sloping back so it meets the spine far behind where it meets the breastbone.
	 */
	static double diaphragmZ(double x, double y) {
		double ry = y < 16 ? sq((y - 16) / 11.0) * 4.5 : sq((y - 16) / 14.0) * 5.0;
		return -14.0 + 0.42 * (y - 16) + ry + 5.0 * sq(x / 14.0);
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

	private static void build() {
		Shape heart = heart();
		airways();
		thoracicVessels();
		thymus();
		adrenals();
		kidneys();
		stomachs();
		liver();
		spleen();
		intestines();
		pancreas();
		omentum();
		if (MALE) {
			ram();
		} else {
			ewe();
		}

		urinary();

		diaphragm();
		lungs(heart);
		skeleton();
		spinalCord();
		head();
		// The skeleton was added last but must win every overlap: move it to the front, with the cord ahead of it, which
		// fills the canal the vertebral arches would leave.
		List<Part> cord = new ArrayList<>();
		List<Part> bones = new ArrayList<>();
		List<Part> rest = new ArrayList<>();
		for (Part p : PARTS) {
			(p.path.startsWith("nervous") ? cord : p.path.startsWith("skeleton") ? bones : rest).add(p);
		}

		PARTS.clear();
		PARTS.addAll(cord);
		PARTS.addAll(bones);
		PARTS.addAll(rest);
	}

	/**
	 * The heart: a blunt cone, its base up and forward under the windpipe, its apex down and back towards the
	 * breastbone at the 5th-6th rib, a little to the left. A sheep's heart wears a thick white collar of fat in the
	 * coronary groove where the atria meet the ventricles, and fat down the grooves over the septum on either side.
	 * The ear-shaped auricles fold forward round the roots of the aorta and the pulmonary trunk. Returns the heart's
	 * outline, which the lungs keep clear of.
	 */
	private static Shape heart() {
		double[] apex = {-2.6, 5.0, -19.5};
		double[] base = {-1.2, 15.5, -26.5};
		double[] axis = norm(sub(base, apex));
		double len = dist(base, apex);
		Tube vent = tube().up((x, y, z) -> new double[] {0, 0, 1});
		double[][] prof = {{0, 0.6}, {0.1, 2.0}, {0.25, 3.2}, {0.45, 3.9}, {0.62, 4.1}, {0.8, 3.8}};
		for (double[] pr : prof) {
			double[] p = add(apex, mul(axis, pr[0] * len));
			vent.p(p[0], p[1], p[2], pr[1], pr[1] * 0.85);
		}

		Shape ventricles = vent.build();
		double[] groove = add(apex, mul(axis, 0.77 * len));
		Shape collar = lumpy(torus(groove, axis, 3.7, 1.15), 0.45, 1.3, 11);
		double[] atria = add(apex, mul(axis, 0.86 * len));
		Shape rightAtrium = lumpy(ell(atria[0] + 1.7, atria[1] + 0.2, atria[2] - 0.6, 2.3, 1.9, 2.3), 0.15, 1.5, 12);
		Shape leftAtrium = lumpy(ell(atria[0] - 1.2, atria[1] + 0.6, atria[2] + 1.5, 2.3, 1.7, 2.0), 0.15, 1.5, 13);
		Shape rightAuricle = lumpy(ell(atria[0] + 1.6, atria[1] - 1.2, atria[2] - 2.6, 1.7, 1.2, 1.1), 0.3, 2.2, 14);
		Shape leftAuricle = lumpy(ell(atria[0] - 3.0, atria[1] - 0.9, atria[2] - 0.8, 1.0, 1.3, 1.9), 0.3, 2.2, 15);
		// Fat down the paraconal groove (left, over the septum) and the subsinuosal groove (right, behind).
		Shape paraconal = lumpy(grooveTube(apex, axis, len, new double[] {-1, 0, -0.5}, 0.76, 0.18, prof), 0.2, 2.0, 16);
		Shape subsinuosal = lumpy(grooveTube(apex, axis, len, new double[] {1, 0, 0.6}, 0.76, 0.35, prof), 0.2, 2.0, 17);
		Mat fat = organic(0xECDCAD, 0xD6C188, 0xF7EED2, 1.6, 21);
		part("thorax/heart/coronary_fat", "coronary_groove", fat, collar);
		part("thorax/heart/coronary_fat", "paraconal_groove", fat, paraconal);
		part("thorax/heart/coronary_fat", "subsinuosal_groove", fat, subsinuosal);
		// The great vessels, cut where the heart is taken out: the aorta at the top of its arch, the pulmonary trunk
		// past its fork, the venae cavae a hand's width out.
		Mat artery = organic(0xD8A99D, 0xC08F84, 0xE8C4B8, 1.2, 22);
		Mat vein = organic(0x4D2E4B, 0x3A2038, 0x62405E, 1.2, 23);
		part("thorax/heart/aorta", "aortic_root_and_arch", artery, tube().hollow(0.55).flat(false, true)
				.p(-0.9, 14.5, -25.8, 1.15).p(-0.8, 18.0, -25.0, 1.15).p(-0.9, 21.5, -23.5, 1.15).p(-1.2, 24.0, -21.0, 1.1).build());
		part("thorax/heart/aorta", "brachiocephalic_trunk", artery, tube().hollow(0.5).flat(false, true)
				.p(-0.9, 20.8, -23.8, 0.7).p(-0.6, 19.8, -27.0, 0.7).p(-0.5, 19.5, -28.5, 0.7).build());
		part("thorax/heart/pulmonary_trunk", "pulmonary_trunk", artery, tube().hollow(0.55)
				.p(-2.4, 13.8, -27.5, 1.05).p(-2.9, 17.0, -26.0, 1.05).p(-2.4, 18.8, -23.5, 1.0).p(-1.4, 18.6, -22.0, 0.95).build());
		part("thorax/heart/pulmonary_trunk", "left_pulmonary_artery", artery, tube().hollow(0.5).flat(false, true)
				.p(-1.4, 18.6, -22.0, 0.8).p(-3.9, 18.2, -21.2, 0.75).build());
		part("thorax/heart/pulmonary_trunk", "right_pulmonary_artery", artery, tube().hollow(0.5).flat(false, true)
				.p(-1.4, 18.6, -22.0, 0.8).p(1.6, 18.8, -21.4, 0.75).build());
		part("thorax/heart/venae_cavae", "cranial_vena_cava", vein, tube().hollow(0.6).flat(false, true)
				.p(1.4, 15.8, -27.5, 1.0).p(1.6, 17.0, -31.0, 1.0).p(1.6, 17.2, -32.5, 1.0).build());
		part("thorax/heart/venae_cavae", "caudal_vena_cava", vein, tube().hollow(0.6).flat(false, true)
				.p(1.8, 13.6, -24.0, 1.05).p(2.4, 13.8, -21.5, 1.05).build());
		Mat auricle = organic(0x5A1616, 0x431010, 0x702222, 2.2, 24);
		part("thorax/heart/right_auricle", "right_auricle", auricle, rightAuricle);
		part("thorax/heart/left_auricle", "left_auricle", auricle, leftAuricle);
		Mat atrium = organic(0x641B1B, 0x4E1414, 0x7A2724, 1.4, 25);
		part("thorax/heart/right_atrium", "right_atrium", atrium, rightAtrium);
		part("thorax/heart/left_atrium", "left_atrium", atrium, leftAtrium);
		part("thorax/heart/ventricles", "ventricles", heartMuscle(apex, axis), ventricles);
		return union(0.5, ventricles, collar, rightAtrium, leftAtrium, rightAuricle, leftAuricle);
	}

	/** A fat-filled groove running down the heart's surface from the coronary groove towards the apex. */
	private static Shape grooveTube(double[] apex, double[] axis, double len, double[] side, double t0, double t1, double[][] prof) {
		double[] s = norm(sub(side, mul(axis, dot(side, axis))));
		Tube t = tube().up((x, y, z) -> new double[] {0, 1, 0});
		for (int i = 0; i <= 6; i++) {
			double f = t0 + (t1 - t0) * i / 6.0;
			double r = curve(f, prof[0][0], prof[0][1], prof[1][0], prof[1][1], prof[2][0], prof[2][1], prof[3][0], prof[3][1],
					prof[4][0], prof[4][1], prof[5][0], prof[5][1]);
			double[] p = add(add(apex, mul(axis, f * len)), mul(s, r * 0.95));
			t.p(p[0], p[1], p[2], 0.55 + 0.25 * (f - t1) / (t0 - t1));
		}

		return t.build();
	}

	/** The windpipe, its rings open at the top, with the extra bronchus a ruminant sends to the right cranial lobe; the gullet above it. */
	private static void airways() {
		Mat trachea = (x, y, z, f, ax, ay, az) -> {
			boolean ring = frac(z * 0.95 + 0.15 * noise(x, y, z * 0.2, 31)) < 0.62;
			double top = curve(z, -48, 23.0, -38, 22.6, -30, 22.0, -25.5, 21.2) + 0.85;
			int c = y > top && Math.abs(x - 0.3) < 0.7 ? 0xC48682 : ring ? 0xE8E1D6 : 0xD39C98;
			return mix(c, 0xB98A86, noise(x * 3, y * 3, z * 3, 32) * 0.25);
		};
		part("thorax/airway/trachea", "trachea", trachea, tube().flat(true, false)
				.p(0.3, 23.0, -48, 1.25).p(0.3, 22.6, -38, 1.25).p(0.2, 22.0, -30, 1.2).p(0.0, 21.2, -25.5, 1.2).build());
		part("thorax/airway/trachea", "tracheal_bronchus", trachea, tube()
				.p(0.3, 22.0, -31.5, 0.7).p(2.2, 21.0, -32.5, 0.7).p(4.0, 19.5, -33.5, 0.65).build());
		part("thorax/airway/bronchi", "left_principal_bronchus", trachea, tube()
				.p(0.0, 21.2, -25.5, 0.9).p(-2.4, 20.6, -23.5, 0.85).p(-4.4, 20.0, -20.5, 0.8).build());
		part("thorax/airway/bronchi", "right_principal_bronchus", trachea, tube()
				.p(0.0, 21.2, -25.5, 0.9).p(2.4, 20.6, -23.5, 0.85).p(4.4, 20.0, -20.5, 0.8).build());
		Mat gullet = streaked(0xC99089, 0xB0766F, 0xDCA9A2, 33);
		part("thorax/esophagus", "thoracic_esophagus", gullet, tube().flat(true, true)
				.p(-0.8, 25.2, -48, 0.9).p(-0.8, 24.8, -38, 0.9).p(-0.9, 24.2, -28, 0.9).p(-1.2, 23.2, -18, 0.9)
				.p(-1.7, 21.8, -10.8, 0.9).build());
	}

	/** The aorta along under the spine and the venae cavae, between the organs that keep their own stubs. */
	private static void thoracicVessels() {
		Mat artery = organic(0xD8A99D, 0xC08F84, 0xE8C4B8, 1.2, 22);
		Mat vein = organic(0x4D2E4B, 0x3A2038, 0x62405E, 1.2, 23);
		part("vessels/aorta", "descending_aorta", artery, tube().hollow(0.55).flat(true, false)
				.p(-1.2, 24.0, -21.0, 1.1).p(-1.5, 26.3, -18.0, 1.1).p(-1.6, 27.0, -14, 1.1).p(-1.6, 27.3, -4, 1.05).p(-1.5, 27.2, 8, 1.0)
				.p(-1.3, 26.8, 20, 0.95).p(-1.0, 25.8, 29, 0.85).build());
		part("vessels/aorta", "brachiocephalic_trunk", artery, tube().hollow(0.5).flat(true, true)
				.p(-0.5, 19.5, -28.5, 0.7).p(-0.4, 19.0, -31.0, 0.7).p(-0.3, 18.8, -38, 0.7).p(0.0, 18.5, -46, 0.65).build());
		part("vessels/venae_cavae", "cranial_vena_cava", vein, tube().hollow(0.6).flat(true, true)
				.p(1.6, 17.2, -32.5, 1.0).p(1.5, 17.5, -40, 1.0).p(1.2, 17.5, -46, 0.95).build());
		part("vessels/venae_cavae", "thoracic_caudal_vena_cava", vein, tube().flat(true, true)
				.p(2.4, 13.8, -21.5, 1.05).p(3.0, 14.2, -17, 1.05).p(3.4, 14.8, -14.2, 1.05).build());
		part("vessels/venae_cavae", "abdominal_caudal_vena_cava", vein, tube().flat(true, false)
				.p(4.8, 25.6, 0.0, 1.0).p(2.6, 26.6, 6, 1.0).p(2.3, 26.6, 18, 0.95).p(1.8, 25.8, 29, 0.85).build());
	}

	/**
	 * The kidneys: smooth and bean-shaped (a sheep's are not lobed like a cow's), dark red-brown, the hilus on the
	 * inner border. The right one lies under the loin against the liver's caudate process; the left one, pushed over by
	 * the rumen, hangs behind it just right of the midline. Both are packed in suet; the right one's is peeled back
	 * below, the way a butcher shows a kidney in its fat.
	 */
	private static Shape[] kidneys() {
		Mat kidney = glossy(0x5F2420, 0x4A1916, 0x733129, 0x8E4C46, 1.5, 41);
		Shape right = cut(tube().up((x, y, z) -> new double[] {0, 1, 0})
				.p(4.9, 25.7, 4.2, 1.6, 1.15).p(5.5, 25.8, 6.0, 2.2, 1.5).p(4.9, 25.7, 7.8, 1.6, 1.15).build(),
				ell(3.2, 25.6, 6.0, 1.2, 0.8, 1.5));
		Shape left = cut(tube().up((x, y, z) -> new double[] {0, 1, 0})
				.p(1.5, 22.8, 12.2, 1.5, 1.1).p(2.1, 22.8, 14.0, 2.1, 1.45).p(1.5, 22.8, 15.8, 1.5, 1.1).build(),
				ell(-0.1, 22.7, 14.0, 1.1, 0.8, 1.4));
		part("abdomen/kidneys/right_kidney", "kidney", kidney, right);
		part("abdomen/kidneys/left_kidney", "kidney", kidney, left);
		Mat artery = organic(0xD8A99D, 0xC08F84, 0xE8C4B8, 1.2, 22);
		Mat vein = organic(0x4D2E4B, 0x3A2038, 0x62405E, 1.2, 23);
		Mat ureter = organic(0xE2C8B4, 0xCDB09A, 0xF0DDCD, 1.5, 42);
		part("abdomen/kidneys/right_kidney", "renal_vein", vein, tube().flat(false, true).p(3.3, 25.9, 5.7, 0.45).p(2.2, 26.4, 5.2, 0.45).build());
		part("abdomen/kidneys/right_kidney", "renal_artery", artery, tube().flat(false, true).p(3.3, 25.4, 6.3, 0.3).p(1.6, 26.2, 6.6, 0.3).build());
		part("abdomen/kidneys/right_kidney", "ureter", ureter, tube().duct().flat(false, true).p(3.4, 25.5, 6.2, 0.3).p(2.8, 25.4, 9.5, 0.3).build());
		part("abdomen/kidneys/left_kidney", "renal_vein", vein, tube().flat(false, true).p(0.2, 22.9, 13.8, 0.42).p(1.4, 25.2, 12.8, 0.42).build());
		part("abdomen/kidneys/left_kidney", "renal_artery", artery, tube().flat(false, true).p(0.1, 22.6, 14.3, 0.3).p(-1.0, 25.4, 14.0, 0.3).build());
		part("abdomen/kidneys/left_kidney", "ureter", ureter, tube().duct().flat(false, true).p(0.2, 22.7, 14.2, 0.3).p(0.0, 22.2, 17.5, 0.3).build());
		Mat suet = crumbly(0xF1E9D4, 0xDDD0B0, 0xFBF7EC, 43);
		Shape rightSuet = lumpy(ell(5.0, 25.8, 6.0, 3.4, 2.5, 4.6), 0.5, 0.9, 44);
		Shape peeled = region(v(-2, 18, -2), v(12, 32, 14), (x, y, z) -> y < 26.3 || x > 5.9);
		part("abdomen/kidneys/right_kidney", "suet", suet, cut(rightSuet, peeled));
		part("abdomen/kidneys/left_kidney", "suet", suet, lumpy(ell(1.5, 22.9, 14.0, 3.1, 2.4, 4.2), 0.5, 0.9, 45));
		return new Shape[] {right, left};
	}

	/**
	 * The four stomachs. The rumen fills the left half of the belly from the diaphragm to the pelvis: a dorsal and a
	 * ventral sac parted by the longitudinal grooves, two blind sacs behind (the ventral reaching further back), the
	 * atrium in front; fat and the ruminal arteries run in the grooves. The honeycombed reticulum lies in front of it
	 * against the diaphragm and the liver; the small round omasum on the right; the abomasum along the floor of the
	 * belly on the right to the pylorus. A window is cut in the rumen's left wall and in the reticulum's to show the
	 * papillae, the honeycomb and the fibrous mat of cud inside.
	 */
	private static void stomachs() {
		// The dorsal sac, the ventral sac, the atrium, the caudodorsal and the caudoventral blind sacs.
		double[][] c = {{-5.5, 18.0, 7.0, 7.5, 7.0, 13.5}, {-3.0, 7.0, 9.5, 9.0, 6.2, 13.5}, {-4.0, 13.5, -3.0, 5.8, 6.5, 4.5},
				{-6.0, 17.0, 21.0, 6.2, 6.3, 6.0}, {-3.5, 7.5, 23.0, 7.0, 6.0, 6.8}};
		// A suckling lamb's rumen is small, drawn up under the loin on the left: milk bypasses it down the oesophageal groove
		// to the abomasum, which is the big stomach until the lamb grazes.
		double shrink = LAMB ? 0.62 : 1.0;
		double[] anchor = {-6.5, 19.0, 6.0};
		Shape[] sacs = new Shape[c.length];
		Shape[] inner = new Shape[c.length];
		for (int i = 0; i < c.length; i++) {
			for (int a = 0; a < 3; a++) {
				c[i][a] = anchor[a] + (c[i][a] - anchor[a]) * shrink;
				c[i][a + 3] *= shrink;
			}

			sacs[i] = ell(c[i][0], c[i][1], c[i][2], c[i][3], c[i][4], c[i][5]);
			inner[i] = ell(c[i][0], c[i][1], c[i][2], c[i][3] - 1.0, c[i][4] - 1.0, c[i][5] - 1.0);
		}

		double k = 3.2 * shrink;
		Shape rumenOut = union(k, sacs);
		Shape rumenIn = union(k, inner);
		Shape retOut = ell(-1.5, 7.5, -6.0, 5.0, 5.0, 4.0);
		Shape retIn = ell(-1.5, 7.5, -6.0, 4.0, 4.0, 3.0);
		P3 rumenWindow = (x, y, z) -> x < -6.0 && sq((y - 14.5) / 5.0) + sq((z - 8.0) / 6.5) < 1;
		P3 retWindow = (x, y, z) -> x < -3.6 && sq((y - 7.0) / 2.8) + sq((z + 6.0) / 2.6) < 1;
		Shape cavity = union(1.5, rumenIn, retIn);
		Mat rumen = (x, y, z, f, ax, ay, az) -> {
			if (cavity.f.at(ax, ay, az) < 0) {
				double n = noise(x * 4, y * 4, z * 4, 51);
				return n > 0.72 ? 0x3B3220 : mix(0x6B5A3A, 0x86744C, noise(x * 1.3, y * 1.3, z * 1.3, 52));
			}

			if (rumenWindow.test(ax, ay, az) || rumenOut.f.at(ax, ay, az) < 0) {
				return mix(0xD3A190, 0xB98577, noise(x * 2, y * 2, z * 2, 53));
			}

			// The grooves are where the sacs' smooth union adds most: fat along them, the ruminal artery down the middle.
			double crease = crease(sacs, k, x, y, z);
			int serosa = mix(mix(0xCDB8B0, 0xB9B3B8, noise(x * 0.5, y * 0.5, z * 0.5, 54)), 0xDCCBC4, noise(x * 2.5, y * 2.5, z * 2.5, 55) * 0.5);
			if (crease > 0.66) {
				return mix(0xA8443E, 0x8E3430, noise(x * 2, y * 2, z * 2, 56));
			}

			if (crease > 0.3) {
				return mix(0xEADAAA, 0xD8C590, noise(x * 2, y * 2, z * 2, 57));
			}

			return ridge(x * 0.3, y * 0.3, z * 0.3, 58) > 0.93 ? mix(serosa, 0xB06A66, 0.45) : serosa;
		};
		Mat reticulum = (x, y, z, f, ax, ay, az) -> {
			if (cavity.f.at(ax, ay, az) < 0) {
				double[] cell = cells(x * 1.15, y * 1.15, z * 1.15, 61);
				return cell[1] - cell[0] < 0.3 ? 0xAC9A74 : mix(0x433820, 0x5E4D30, cell[2]);
			}

			if (retOut.f.at(ax, ay, az) < 0 || retWindow.test(ax, ay, az)) {
				return mix(0xD3A190, 0xB98577, noise(x * 2, y * 2, z * 2, 53));
			}

			return mix(0xC2ABA5, 0xD6C4BE, noise(x * 1.5, y * 1.5, z * 1.5, 62));
		};
		Shape rumenWall = cut(cut(rumenOut, rumenIn), region(rumenOut.lo, rumenOut.hi, rumenWindow));
		Shape retWall = cut(cut(retOut, retIn), region(retOut.lo, retOut.hi, retWindow));
		Mat omasum = organic(0xBBA29A, 0xA58A82, 0xCDB8B0, 1.4, 63);
		Mat abomasum = veined(0xD3A398, 0xBE8A80, 0xE2BBB0, 0xA54A48, 64);
		// The omasum, the "manyplies": a ball of thin leaves radiating from a central mesentery, cut as angular wedges
		// around a vertical axis so it reads as a book of pages (isolate it in Blockbench and flip through the leaves).
		double[] oc = {4.0, 12.5, -6.0};
		double[] orad = {3.4, 3.8, 3.8};
		int oLeaves = 12;
		double oSector = 2 * Math.PI / oLeaves;
		Shape oBook = both(ell(oc[0], oc[1], oc[2], orad[0], orad[1], orad[2]),
				region(v(oc[0] - orad[0], oc[1] - orad[1], oc[2] - orad[2]), v(oc[0] + orad[0], oc[1] + orad[1], oc[2] + orad[2]),
						(x, y, z) -> {
							double dx = x - oc[0];
							double dz = z - oc[2];
							if (Math.hypot(dx, dz) < 1.0) {
								return true; // the central core every leaf hangs from
							}

							return Math.abs(frac(Math.atan2(dz, dx) / oSector) - 0.5) < 0.25;
						}));
		part("abdomen/stomach/omasum", "omasum", omasum, oBook);
		part("abdomen/stomach/reticulum", "reticulum", reticulum, retWall);
		// A lamb's abomasum is the big stomach: it fills the floor of the belly from the left side across to the right.
		Shape abomasumShape = LAMB ? tube().p(-5.0, 6.4, -1.0, 4.2).p(-1.0, 5.8, -2.5, 4.6).p(3.0, 5.4, 1.5, 4.8).p(5.0, 5.0, 7.0, 4.2)
				.p(6.8, 5.2, 12.0, 3.2).p(8.2, 6.4, 15.2, 2.1).p(9.0, 9.0, 16.5, 1.3).build()
				: tube().p(1.0, 5.0, -4.0, 3.4).p(3.5, 4.8, 0.5, 3.6).p(5.0, 4.6, 5.5, 3.2).p(6.5, 4.8, 11.0, 2.6).p(8.0, 6.0, 15.0, 1.9)
						.p(9.0, 9.0, 16.5, 1.3).build();
		part("abdomen/stomach/abomasum", "abomasum", abomasum, abomasumShape);
		Mat gullet = streaked(0xC99089, 0xB0766F, 0xDCA9A2, 33);
		part("abdomen/stomach/esophagus", "abdominal_esophagus", gullet, tube().flat(true, false)
				.p(-1.7, 21.8, -10.8, 0.9).p(-2.2, 20.6, -7.0, 0.9).p(-2.6, 19.2, -4.5, 0.9).build());
		part("abdomen/stomach/rumen", "rumen", rumen, rumenWall);
		Mat cud = (x, y, z, f, ax, ay, az) -> {
			if (y < 9.5) {
				return mix(0x4C4826, 0x5E5A30, noise(x, y, z, 71));
			}

			double fib = noise(x * 2.6, y * 0.7, z * 2.6, 72);
			double straw = ridge(x * 1.2 + y * 0.3, y * 0.4, z * 1.2, 73);
			return straw > 0.9 ? 0xA09A5A : mix(0x55532C, 0x7A7644, fib);
		};
		Shape carve = region(v(-15, 6, -2), v(-8.4, 23, 18), (x, y, z) -> sq((y - 14.5) / 5.6) + sq((z - 8.0) / 7.2) < 1);
		part("abdomen/stomach/ingesta", "rumen_contents", cud, cut(both(cavity, region(cavity.lo, cavity.hi, (x, y, z) -> y < (LAMB ? 16.5 : 19.5))), carve));
	}

	/**
	 * The liver: one undivided plate (a sheep's is not deeply split into lobes like a pig's or a dog's), dark red-brown,
	 * moulded against the right side of the diaphragm so it lies obliquely, its thin sharp ventral border low on the
	 * left, its thick dorsal border high on the right with the caudal vena cava sunk in it. The left lobe, the
	 * quadrate lobe across the notch of the round ligament, the green gallbladder in its fossa, the right lobe, and on
	 * the visceral face the caudate lobe, its process jutting back to cup the right kidney, the papillary process over
	 * the porta where the portal vein goes in.
	 */
	private static void liver() {
		Mat liver = (x, y, z, f, ax, ay, az) -> {
			double[] cell = cells(x * 1.25, y * 1.25, z * 1.25, 81);
			int c = mix(0x431310, 0x5F1D18, noise(x * 0.4, y * 0.4, z * 0.4, 82));
			if (cell[1] - cell[0] < 0.12) {
				c = mix(c, 0x6C2A22, 0.55);
			}

			return noise(x * 3, y * 3, z * 3, 83) > 0.86 ? mix(c, 0x9A5A50, 0.6) : c;
		};
		String[] lobes = {"left_lobe", "quadrate_lobe", "right_lobe", "caudate_lobe"};
		for (int i = 0; i < lobes.length; i++) {
			int lobe = i;
			part("abdomen/liver/" + lobes[i], lobes[i], liver, region(v(-5, 2, -15), v(15, 29, 8), (x, y, z) -> liverAt(x, y, z) == lobe));
		}

		Mat gall = glossy(0x3F5D30, 0x2E4824, 0x55713F, 0x7E9C83, 1.4, 84);
		double gz = liverFront(6.4, 6.4) + liverThickness(6.4, 6.4) + 0.3;
		part("abdomen/liver/gallbladder", "gallbladder", gall, ell(6.4, 6.0, gz, 1.5, 3.0, 1.5));
		Mat vein = organic(0x4D2E4B, 0x3A2038, 0x62405E, 1.2, 23);
		part("abdomen/liver/caudal_vena_cava", "hepatic_caudal_vena_cava", vein, tube().hollow(0.6).flat(true, true)
				.p(3.4, 14.8, -14.2, 1.05).p(4.2, 17.5, -11.5, 1.05).p(5.2, 21.0, -7.5, 1.05).p(5.4, 23.8, -3.5, 1.0).p(4.8, 25.6, 0.0, 1.0).build());
		double pz = liverFront(5.6, 16.5) + liverThickness(5.6, 16.5);
		part("abdomen/liver/portal_vein", "portal_vein", organic(0x5A3550, 0x452841, 0x6E4864, 1.2, 85), tube().hollow(0.55).flat(false, true)
				.p(5.6, 16.5, pz - 1.5, 0.9).p(5.4, 16.0, pz + 1.0, 0.9).p(5.2, 15.4, pz + 3.8, 0.85).build());
	}

	private static final double[][] LIVER_LOBES = {
			{0.8, 8.5, 4.8, 4.8}, {4.6, 6.8, 2.6, 3.4}, {8.8, 14.5, 4.8, 8.5}, {7.8, 22.8, 4.0, 3.8}, {5.0, 13.0, 4.5, 6.0}};

	/** The liver's outline seen from in front: above 0 inside, the lobes' ellipses blended into one plate. */
	static double liverOutline(double x, double y) {
		double s = 0;
		double k = 0.12;
		for (double[] l : LIVER_LOBES) {
			s += Math.exp((1 - sq((x - l[0]) / l[2]) - sq((y - l[1]) / l[3])) / k);
		}

		return k * Math.log(s);
	}

	/** How far the liver reaches back from the diaphragm at (x, y): thin and sharp below, thick above, the processes jutting. */
	/**
	 * The liver's diaphragmatic face: against the diaphragm at its borders, standing a little off the top of the dome,
	 * so the plate is less curled than the dome itself (a liver keeps its own shape out of the body).
	 */
	static double liverFront(double x, double y) {
		return diaphragmZ(x, y) + 0.7 + 1.6 * Math.max(0, 1 - sq((y - 15) / 11.0));
	}

	static double liverThickness(double x, double y) {
		double t = clamp(2.2 + 0.17 * (y - 4), 2.0, 5.6);
		t += 3.6 * Math.exp(-(sq(x - 7.6) + sq(y - 24.0)) / (2 * 2.2 * 2.2));
		t += 2.0 * Math.exp(-(sq(x - 4.2) + sq(y - 19.5)) / (2 * 1.5 * 1.5));
		double g = liverOutline(x, y);
		return t * Math.sqrt(clamp(g / 0.45, 0, 1));
	}

	/** Which lobe of the liver holds (x, y, z), or -1. */
	static int liverAt(double x, double y, double z) {
		double g = liverOutline(x, y);
		if (g <= 0) {
			return -1;
		}

		if (y < 9.5 && Math.abs(x - 2.9) < 0.35 + 0.18 * (9.5 - y)) {
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

	/** The spleen: a flat rounded triangle, blue-purple, on the left of the rumen's dorsal sac under the last ribs, against the diaphragm. */
	private static void spleen() {
		double[] c = {-11.0, 21.0, -3.0};
		double[] n = norm(new double[] {-0.88, 0.25, -0.40});
		double[] g = {0, -0.5, 0.85};
		double[] u = norm(sub(g, mul(n, dot(g, n))));
		double[] w = cross(n, u);
		Field f = (x, y, z) -> {
			double[] p = {x - c[0], y - c[1], z - c[2]};
			double a = dot(p, u);
			double b = dot(p, w);
			double h = dot(p, n);
			double rv = 4.4 * (0.75 + 0.25 * a / 5.2);
			double gg = 1 - Math.pow(Math.abs(a) / 5.2, 2.5) - Math.pow(Math.abs(b) / rv, 2.5);
			if (gg <= 0) {
				return 1;
			}

			double t = Math.max(1.9 * Math.sqrt(gg), VOX * 1.1);
			return h > -0.35 * t && h < 0.65 * t ? -1 : 1;
		};
		Mat spleen = (x, y, z, fc, ax, ay, az) -> {
			int col = mix(0x4A2038, 0x6A3754, noise(x * 0.8, y * 0.8, z * 0.8, 91));
			return noise(x * 3, y * 3, z * 3, 92) > 0.8 ? mix(col, 0x7C6C86, 0.5) : col;
		};
		part("abdomen/spleen/spleen", "spleen", spleen, new Shape(v(c[0] - 7, c[1] - 7, c[2] - 7), v(c[0] + 7, c[1] + 7, c[2] + 7), f));
	}

	/**
	 * The guts, hung on the right in their mesentery behind the liver and the omasum. The duodenum climbs from the
	 * pylorus to the liver and runs back high on the right flank. The spiral colon is a flat disc in the mesentery, two
	 * coils in towards the centre and two back out, the outgoing ones already beading into pellets. Round its rim the
	 * jejunum hangs in a garland of short loops (in Blockbench each loop is its own group, so the chain can drape and
	 * part a loop at a time). The ileum enters the caecum, which points back into the pelvis; the colon leaves it in
	 * the proximal loop, goes round the spiral, and out by the distal loop to the descending colon and the rectum.
	 */
	private static void intestines() {
		Mat small = veined(0xE3B6A4, 0xCF9D8D, 0xEFCAB9, 0xB45C52, 101);
		Mat colon = organic(0xCBB0A2, 0xB39888, 0xDBC4B6, 1.3, 102);
		Mat pellets = organic(0xA28E78, 0x86745F, 0xBCA894, 1.6, 103);
		Mat mesentery = veined(0xECDCB2, 0xDCC791, 0xF5EBCD, 0xB3544C, 104);
		Mat caecum = organic(0xC7A59A, 0xA8907A, 0xD8BCB0, 1.0, 105);
		part("abdomen/intestines/duodenum", "duodenum", small, tube()
				.p(9.0, 9.0, 16.5, 1.15).p(10.5, 13.0, 13.0, 1.15).p(11.5, 18.0, 6.0, 1.1).p(11.5, 21.0, 2.0, 1.1).p(12.0, 23.0, 6.0, 1.1)
				.p(12.0, 23.5, 14.0, 1.1).p(11.5, 23.0, 22.0, 1.1).p(10.0, 22.0, 26.0, 1.1).p(7.0, 22.5, 23.0, 1.1).p(6.0, 22.5, 16.0, 1.05)
				.p(6.5, 21.5, 11.0, 1.0).build());
		double cx = 6.8;
		double cy = 16.5;
		double cz = 13.5;
		double a0 = Math.toRadians(150);
		// The spiral colon: in along one coil, out along the other between its turns.
		Tube inward = tube();
		Tube outward = tube().beads(0.22, 1.5);
		for (int i = 0; i <= 48; i++) {
			double th = i * (4 * Math.PI / 48);
			double r = 6.9 - 2.6 * th / (2 * Math.PI);
			inward.p(cx, cy + r * Math.sin(a0 - th), cz + r * Math.cos(a0 - th), 0.58);
		}

		for (int i = 48; i >= 0; i--) {
			double th = i * (4 * Math.PI / 48);
			double r = 6.9 - 1.3 - 2.6 * th / (2 * Math.PI);
			outward.p(cx, cy + Math.max(r, 0.45) * Math.sin(a0 - th), cz + Math.max(r, 0.45) * Math.cos(a0 - th), 0.55);
		}

		part("abdomen/intestines/spiral_colon", "centripetal_coils", colon, inward.build());
		part("abdomen/intestines/spiral_colon", "centrifugal_coils", pellets, outward.build());
		part("abdomen/intestines/caecum", "caecum", caecum, tube()
				.p(7.6, 12.6, 20.5, 2.1).p(7.9, 14.2, 24.5, 2.1).p(7.6, 16.2, 28.0, 1.9).p(7.0, 17.6, 30.5, 1.6).build());
		part("abdomen/intestines/proximal_colon", "proximal_loop", colon, tube()
				.p(7.6, 12.8, 19.5, 1.6).p(9.3, 17.0, 21.5, 1.5).p(9.4, 21.5, 19.0, 1.4).p(9.2, 22.8, 13.0, 1.3).p(8.6, 22.2, 8.0, 1.2)
				.p(7.6, 20.6, 6.6, 1.0).p(cx, cy + 6.9 * Math.sin(a0), cz + 6.9 * Math.cos(a0), 0.6).build());
		double r1 = 6.9 - 1.3;
		part("abdomen/intestines/distal_colon", "distal_loop", pellets, tube().beads(0.2, 1.6)
				.p(cx, cy + r1 * Math.sin(a0), cz + r1 * Math.cos(a0), 0.6).p(5.6, 21.6, 9.8, 0.9).p(4.2, 23.6, 14.0, 0.95)
				.p(3.6, 24.0, 21.0, 0.95).p(2.8, 24.6, 24.5, 0.95).p(1.8, 25.2, 17.0, 0.95).p(0.5, 25.6, 6.0, 0.95)
				.p(-1.0, 25.3, 10.0, 0.95).p(-0.6, 24.4, 22.0, 1.0).p(0.0, 23.6, 29.0, 1.1).build());
		part("abdomen/intestines/rectum", "rectum", pellets, tube().beads(0.18, 1.8).flat(false, true)
				.p(0.0, 23.6, 29.0, 1.1).p(0.0, 22.8, 37.0, 1.3).p(0.0, 21.8, 45.5, 1.2).build());
		// The jejunum's garland, loop by loop, from the duodenojejunal flexure round the rim of the disc to the ileum.
		int loops = 14;
		double f0 = Math.toRadians(160);
		double f1 = Math.toRadians(360);
		double[] prev = {6.5, 21.5, 11.0};
		for (int l = 0; l < loops; l++) {
			Tube t = tube().up((x, y, z) -> new double[] {0, 1, 0});
			t.p(prev[0], prev[1], prev[2], 0.85);
			for (int s = 1; s <= 8; s++) {
				double q = (l + s / 8.0) / loops;
				double ph = (l + s / 8.0) * 2 * Math.PI;
				double ang = f0 + (f1 - f0) * q;
				double rho = 8.6 + 1.9 * (0.5 - 0.5 * Math.cos(ph));
				double x = cx + 2.3 * Math.sin(ph * 0.5) + 0.4;
				double[] p = {x, cy + rho * Math.sin(ang), cz + rho * Math.cos(ang)};
				t.p(p[0], p[1], p[2], 0.85);
				prev = p;
			}

			part(String.format(Locale.ROOT, "abdomen/intestines/jejunum/loop_%02d", l + 1), "jejunum", small, t.build());
		}

		part("abdomen/intestines/ileum", "ileum", small, tube().p(prev[0], prev[1], prev[2], 0.8).p(7.6, 14.5, 22.6, 0.8).p(7.7, 12.8, 21.2, 0.8).build());
		Field disc = (x, y, z) -> {
			double rho = Math.hypot(y - cy, z - cz);
			return Math.abs(x - cx) < Math.max(0.3, VOX * THIN) && rho < 9.4 ? -1 : 1;
		};
		part("abdomen/intestines/mesentery", "mesentery", mesentery, new Shape(v(cx - 1, cy - 10, cz - 10), v(cx + 1, cy + 10, cz + 10), disc));
	}

	/** Gland tissue in the art: pale pink-cream lobules. */
	static Mat gland(int seed) {
		return organic(0xD9A894, 0xBF8A76, 0xEAC4B2, 2.4, seed);
	}

	/**
	 * The thymus in the front of the chest, under the windpipe and the great vessels, ahead of the heart between the
	 * cranial lobes of the lungs. A lamb's is large; by adulthood it has shrunk to a remnant like this one.
	 */
	private static void thymus() {
		// A lamb's: two big lobes filling the front of the chest round the great vessels, reaching up the neck.
		Shape thymus = LAMB ? union(1.0, ell(1.4, 13.6, -33.0, 2.4, 2.8, 5.2), ell(-1.0, 13.2, -34.0, 2.2, 2.6, 5.0), ell(0.2, 16.0, -40.5, 2.0, 2.4, 3.6))
				: ell(0.3, 12.8, -33.5, 1.3, 1.1, 2.4);
		part("thorax/thymus", "thymus", gland(121), lumpy(thymus, 0.2, 2.0, 122));
	}

	/**
	 * The adrenals: small, flattened, buried in the suet at the front and inner edge of each kidney, the right one against
	 * the caudal vena cava, the left one by the aorta ahead of the left kidney, which the rumen has pushed back.
	 */
	private static void adrenals() {
		part("abdomen/adrenals/right", "right_adrenal", gland(123), tube().duct().p(3.6, 26.2, 2.2, 0.6).p(3.2, 26.2, 4.2, 0.6).build());
		part("abdomen/adrenals/left", "left_adrenal", gland(124), tube().duct().p(0.5, 25.8, 9.2, 0.6).p(0.2, 25.8, 11.2, 0.6).build());
	}

	/**
	 * The pancreas: flat and lobulated, lying across the roof of the belly on the right. Its body sits by the portal vein
	 * behind the liver, its right lobe runs back inside the bend of the duodenum, and its left lobe reaches towards the
	 * left kidney under the aorta.
	 */
	private static void pancreas() {
		part("abdomen/pancreas", "pancreas", gland(125), lumpy(tube().up((x, y, z) -> new double[] {0, 1, 0})
				.p(0.6, 24.2, 6.5, 1.0, 0.95).p(3.0, 23.4, 3.0, 1.4, 1.05).p(6.0, 22.4, 2.6, 1.5, 1.1).p(8.8, 21.6, 6.0, 1.2, 1.05)
				.p(9.6, 21.6, 11.5, 0.9, 0.95).build(), 0.25, 2.2, 126));
	}

	/**
	 * The greater omentum: the lacy fat that hangs from the rumen and the abomasum and slings round the intestines on the
	 * right, so a cut into the right flank meets this veil of fat before the guts behind it. A thin curved sheet just
	 * inside the belly wall, holed where the lace is thin.
	 */
	private static void omentum() {
		Mat lace = veined(0xEFE2BE, 0xDDCB9A, 0xF7EED6, 0xB3544C, 127);
		Field sheet = (x, y, z) -> {
			double wall = 13.6 - 0.02 * sq(y - 14) - 0.006 * sq(z - 10);
			double half = Math.max(0.3, VOX * THIN);
			if (Math.abs(x - wall) >= half || y < 3.5 || y > 21.5 || z < -8 || z > 28) {
				return 1;
			}

			return noise(x * 0.9, y * 0.9, z * 0.9, 128) > 0.82 ? 1 : -1;
		};
		part("abdomen/omentum", "greater_omentum", lace, new Shape(v(9, 3, -9), v(15, 22, 29), sheet));
	}

	/**
	 * The spinal cord in the canal above the vertebral bodies, from the neck to where it tapers out over the sacrum. The
	 * vertebral arches round it are left out at this scale, so the cord fills the canal they would leave. On a grid whose
	 * cells are taller than the cord (the game's, 2 cm a quarter pixel up) it is drawn taller, so where it steps from one
	 * row of cells to the next both rows hold it and it stays one piece.
	 */
	private static void spinalCord() {
		Mat cord = organic(0xEDE3D6, 0xD6C8B8, 0xF8F2EA, 1.4, 129);
		Tube t = tube().up((x, y, z) -> new double[] {0, 1, 0});
		double[][] at = {{-46, 0.8}, {-38, 0.8}, {-26, 0.78}, {-10, 0.76}, {2, 0.8}, {12, 0.78}, {20, 0.7}, {25, 0.6}};
		for (double[] a : at) {
			t.p(0, spineY(a[0]) + 2.4, a[0], a[1], 1.0 * a[1] / 0.8);
		}

		Shape tube = t.build();
		Shape tall = new Shape(tube.lo, tube.hi, (x, y, z) -> {
			double stretch = Math.max(1.0, DUCT * 1.5);
			double c = spineY(z) + 2.4;
			return tube.f.at(x, c + (y - c) / stretch, z);
		});
		part("nervous/spinal_cord", "spinal_cord", cord, tall);
	}

	/** Whether {@link #build} makes a ram (true) or a ewe. */
	static boolean MALE = false;
	/** Whether {@link #build} makes a lamb (the vanilla baby sheep): milk teeth, a small rumen and a big abomasum, a big thymus, an immature tract. */
	static boolean LAMB = false;

	/**
	 * A ewe's reproductive tract, in the pelvis and the back of the belly: the vulva under the tail; the vagina along the
	 * pelvic floor between the rectum above and the urethra below; the firm ringed cervix; the short uterine body over the
	 * bladder and the two horns curling forward and down like a ram's horns, each with its almond ovary near the tip and the
	 * thin oviduct between. The udder hangs between the thighs: two halves, a teat on each.
	 */
	private static void ewe() {
		Mat uterus = organic(0xD7A09A, 0xBE847E, 0xE6BAB4, 1.6, 211);
		Mat cervix = organic(0xEBDDD2, 0xD5C4B6, 0xF5ECE4, 1.8, 212);
		Mat skin = organic(0x7A5450, 0x5E3E3A, 0x946C66, 1.6, 213);
		// A ewe lamb's tract is the same, smaller: the vagina a little, the uterus and ovaries about half, drawn back to the
		// cervix; her udder only a small bud with its two teats.
		part("reproductive/female/vulva", "vulva", skin, ell(0, 19.4, 43.4, 0.6, 1.6, 0.7));
		if (LAMB) {
			grow(0.8, 0, 19.6, 43.2);
		}

		part("reproductive/female/vagina", "vagina", uterus, tube().flat(true, false).p(0, 19.6, 43.2, 1.0).p(0, 19.8, 38.5, 1.1).p(0, 19.8, 35.0, 1.1).build());
		if (LAMB) {
			grow(0.55, 0, 19.8, 36.6);
		}

		part("reproductive/female/cervix", "cervix", cervix, lumpy(tube().p(0, 19.8, 35.0, 1.15).p(0, 19.7, 32.0, 1.1).build(), 0.15, 3.5, 214));
		part("reproductive/female/uterus", "uterine_body", uterus, tube().p(0, 19.7, 32.0, 1.2).p(0, 19.4, 29.5, 1.3).build());
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right_" : "left_";
			// The ovary in the curl of its horn, where the bursa holds it.
			part("reproductive/female/ovaries", side + "ovary", gland(215 + s), tube().duct().p(s * 3.7, 15.4, 26.4, 0.7).p(s * 3.7, 15.4, 27.6, 0.7).build());
			part("reproductive/female/uterus", side + "uterine_horn", uterus, tube().p(0, 19.4, 29.5, 1.1).p(s * 1.6, 19.0, 27.2, 1.05).p(s * 3.4, 17.6, 25.6, 0.95)
					.p(s * 4.6, 15.6, 25.2, 0.85).p(s * 4.4, 13.8, 26.6, 0.75).p(s * 3.4, 13.4, 28.0, 0.65).build());
			part("reproductive/female/oviducts", side + "oviduct", uterus, tube().duct().p(s * 3.4, 13.4, 28.0, 0.25).p(s * 4.6, 13.0, 27.0, 0.25)
					.p(s * 5.4, 13.8, 25.4, 0.25).build());
		}

		if (LAMB) {
			grow(0.45, 0, 6.6, 37.8);
		}

		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right_" : "left_";
			part("reproductive/female/udder", side + "udder_half", gland(217 + s), lumpy(ell(s * 1.9, 3.6, 37.5, 2.1, 2.9, 3.1), 0.2, 1.4, 219 + s));
			part("reproductive/female/udder", side + "teat", skin, tube().duct().p(s * 2.0, 1.2, 38.0, 0.5).p(s * 2.2, -2.6, 38.4, 0.45).build());
		}

		grow(1, 0, 0, 0);
	}

	/**
	 * A ram's: the two big testes in the scrotum between the thighs, each with its epididymis down the back (the tail a
	 * knob at the bottom); the spermatic cords climbing from them through the groin; the vas deferens back up into the
	 * pelvis to the paired lobulated vesicular glands over the neck of the bladder; and the penis, from its roots on the
	 * pelvis down behind the scrotum through the S of its sigmoid flexure, then forward under the skin of the belly, outside its wall, to the sheath.
	 */
	private static void ram() {
		Mat testis = glossy(0xEDE3DE, 0xD6C8C2, 0xFAF4F0, 0xFFFFFF, 1.4, 221);
		Mat duct = organic(0xE2C8B4, 0xCDB09A, 0xF0DDCD, 1.5, 222);
		Mat vein = organic(0x4D2E4B, 0x3A2038, 0x62405E, 1.2, 223);
		Mat skin = organic(0x7A5450, 0x5E3E3A, 0x946C66, 1.6, 224);
		Mat penis = organic(0xD9A9A2, 0xC08C86, 0xE8C4BE, 1.6, 225);
		// A ram lamb's are his own, smaller: the testes and their sac half the size, drawn up to the neck of the scrotum;
		// the glands and the penis slighter. The cords and the vas deferens still run from the testes into the groin.
		double thin = LAMB ? 0.75 : 1.0;
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right_" : "left_";
			if (LAMB) {
				grow(0.6, s * 1.7, 19.0, 31.6);
			}

			part("reproductive/male/vesicular_glands", side + "vesicular_gland", gland(226 + s), lumpy(ell(s * 1.7, 19.0, 31.6, 0.9, 1.1, 1.5), 0.2, 2.5, 228 + s));
			if (LAMB) {
				// Each testis smaller where it hangs, so the septum still parts them.
				grow(0.6, s * 2.4, 9.0, 36.8);
			}

			part("reproductive/male/testes", side + "testis", testis, ell(s * 2.4, 4.4, 37.0, 1.65, 3.8, 2.4));
			part("reproductive/male/epididymis", side + "epididymis", duct, union(0.4, tube().duct().p(s * 2.4, 8.6, 37.6, 0.6).p(s * 2.6, 4.4, 39.6, 0.5)
					.p(s * 2.4, 0.9, 38.6, 0.6).build(), ell(s * 2.4, 0.6, 38.2, 1.0, 0.9, 1.0)));
			// Where the cord leaves the top of the testis and the vas deferens the tail of its epididymis, grown or not.
			double[] top = {grown(s * 2.0, 0), grown(8.6, 1), grown(36.6, 2)};
			double[] tail = {grown(s * 2.1, 0), grown(1.2, 1), grown(39.2, 2)};
			grow(1, 0, 0, 0);
			part("reproductive/male/spermatic_cords", side + "spermatic_cord", vein, tube().duct().p(top[0], top[1], top[2], 0.8 * thin)
					.p(s * 3.2, 11.8, 33.4, 0.7 * thin).p(s * 4.4, 14.2, 30.2, 0.6 * thin).build());
			part("reproductive/male/vas_deferens", side + "vas_deferens", duct, tube().duct().p(tail[0], tail[1], tail[2], 0.25)
					.p(s * 3.0, Math.max(9.0, tail[1] + 2.0), 36.2, 0.25).p(s * 4.0, 14.0, 31.0, 0.25).p(s * 2.0, 17.4, 30.6, 0.25).p(s * 0.7, 18.4, 33.4, 0.25)
					.build());
		}

		part("reproductive/male/penis", "penis", penis, tube().duct().p(0, 17.2, 42.6, 0.9 * thin).p(0, 12.5, 43.0, 0.85 * thin).p(0, 10.4, 41.4, 0.8 * thin)
				.p(0, 12.0, 40.0, 0.8 * thin).p(0, 10.2, 38.8, 0.8 * thin).p(0, 5.6, 32.2, 0.8 * thin).p(0, 1.5, 28.0, 0.75 * thin).p(0, -0.6, 22.0, 0.7 * thin)
				.p(0, -0.8, 16.0, 0.7 * thin).p(0, -0.8, 12.5, 0.6 * thin).build());
		if (LAMB) {
			grow(0.65, 0, 9.0, 36.8);
		}

		// Solid: what the testes, epididymides and cords before it leave of the sac is its wall and the septum between them.
		part("reproductive/male/scrotum", "scrotum", skin, ell(0, 4.1, 37.6, 5.8, 5.8, 4.3));
		grow(1, 0, 0, 0);
		part("reproductive/male/prepuce", "prepuce", skin, cut(ell(0, -0.6, 11.2, 1.3 * thin, 1.2 * thin, 2.2 * thin), ell(0, -0.6, 11.2, 0.7 * thin, 0.6 * thin,
				1.6 * thin)));
	}

	/** The bladder on the floor of the belly in front of the pelvis, the ureters coming down to it. */
	private static void urinary() {
		Mat ureter = organic(0xE2C8B4, 0xCDB09A, 0xF0DDCD, 1.5, 42);
		part("abdomen/urinary/ureters", "right_ureter", ureter, tube().duct().flat(true, false)
				.p(2.8, 25.4, 9.5, 0.3).p(2.4, 24.8, 16, 0.3).p(1.4, 19.5, 24, 0.3).p(0.8, 12.8, 28.0, 0.3).build());
		part("abdomen/urinary/ureters", "left_ureter", ureter, tube().duct().flat(true, false)
				.p(0.0, 22.2, 17.5, 0.3).p(-0.4, 19.0, 23.5, 0.3).p(-0.7, 12.8, 28.2, 0.3).build());
		Mat bladder = veined(0xE2B4AA, 0xD09C92, 0xEFCFC6, 0xB55A55, 111);
		part("abdomen/urinary/bladder", "bladder", bladder, ell(0.0, 9.5, 30.5, 3.3, 3.2, 4.0));
		part("abdomen/urinary/bladder", "urethra", bladder, tube().duct().p(0.0, 11.0, 33.8, 1.1).p(0.0, 14.8, 32.4, 0.9).p(0.0, 18.6, 34.5, 0.7)
				.p(0.0, 19.0, 40.0, 0.6).build());
	}

	/** The diaphragm: a red muscular rim round a white tendinous centre. */
	private static void diaphragm() {
		Mat m = (x, y, z, f, ax, ay, az) -> {
			boolean tendon = sq(x / 6.5) + sq((y - 15) / 6.5) < 1;
			return tendon ? mix(0xDCD6CB, 0xC9C2B6, noise(x * 2, y * 2, z * 2, 121))
					: mix(0x8A2C27, 0x6E201C, frac(Math.atan2(y - 15, x) * 6) < 0.5 ? 0.3 : 0.0);
		};
		part("thorax/diaphragm", "diaphragm", m, region(v(-16, 0, -20), v(16, 31, 4), (x, y, z) -> {
			if (Math.abs(z - diaphragmZ(x, y)) > Math.max(0.35, VOX * THIN)) {
				return false;
			}

			double hy = y >= YC ? 12.3 : YC - Math.min(sternumY(z), 2.4) - 0.6;
			return sq(x / (halfWidth(z) - 0.7)) + sq((y - YC) / hy) < 1;
		}));
	}

	/**
	 * The lungs fill the chest round the heart, against the ribs, back to the dome of the diaphragm. A ruminant's
	 * right lung has a cranial lobe in two parts (aired by its own bronchus off the windpipe), a middle lobe, a big
	 * caudal lobe and the accessory lobe tucked in the mediastinum behind the heart; the left has a two-part cranial
	 * lobe and a caudal lobe. Notches over the heart low on either side leave it bare against the chest wall.
	 */
	private static void lungs(Shape heart) {
		Mat lung = (x, y, z, f, ax, ay, az) -> {
			double[] cell = cells(x * 0.55, y * 0.55, z * 0.55, 131);
			int c = mix(0xE3989A, 0xF4BDB8, noise(x * 0.6, y * 0.6, z * 0.6, 132));
			if (cell[1] - cell[0] < 0.1) {
				c = mix(c, 0xC97E7C, 0.6);
			}

			return noise(x * 2.5, y * 2.5, z * 2.5, 133) > 0.85 ? mix(c, 0xB86664, 0.5) : c;
		};
		String[] names = {"right_cranial_lobe/cranial_part", "right_cranial_lobe/caudal_part", "right_middle_lobe", "right_caudal_lobe",
				"accessory_lobe", "left_cranial_lobe/cranial_part", "left_cranial_lobe/caudal_part", "left_caudal_lobe"};
		for (int i = 0; i < names.length; i++) {
			int lobe = i;
			String leaf = names[i].substring(names[i].lastIndexOf('/') + 1);
			part("thorax/lungs/" + names[i], leaf, lung, region(v(-16, 2, -41), v(16, 30, 4),
					(x, y, z) -> lungAt(x, y, z) == lobe && heart.f.at(x, y, z) > 0.6));
		}
	}

	static int lungAt(double x, double y, double z) {
		double w = halfWidth(z) - 1.3;
		if (w <= 1) {
			return -1;
		}

		// The roof drops towards the narrow entrance of the chest, so the cranial lobes end in a blunt point.
		double top = curve(z, -40.5, 20.5, -36, 24.5, -30, 27.8, -12, 28.6, 0, 27.6);
		double hy = y >= YC ? top - YC : YC - sternumY(z) - 1.4;
		double ex = sq(x / w) + sq((y - YC) / hy);
		if (ex >= 1 || z < -40.5) {
			return -1;
		}

		// The base: against the dome of the diaphragm in the middle, and at the side a sharp basal border running from
		// the 11th rib high up to the 6th costochondral junction, short of where the diaphragm meets the wall.
		double basal = -22.0 + (y - 5) * 0.8 + 0.8 * (noise(y * 0.3, z * 0.3, 0, 135) - 0.5);
		if (z > diaphragmZ(x, y) - 0.8 || z > basal + 8.0 * (1 - sq(x / w))) {
			return -1;
		}

		double gap = 0.3 + VOX * 0.35;
		if (Math.abs(x + 0.3) < gap) {
			return -1;
		}

		if (sq((x + 8.0) / 8.0) + sq((y - 6.0) / 4.5) + sq((z + 24.5) / 4.0) < 1 || sq((x - 6.5) / 7.0) + sq((y - 6.0) / 4.0) + sq((z + 26.5) / 3.0) < 1) {
			return -1;
		}

		double obl = -27 + (24 - y) * 0.75 + 1.2 * (noise(x * 0.25, y * 0.25, 0, 136) - 0.5);
		if (Math.abs(z - obl) < gap) {
			return -1;
		}

		if (x > -0.3) {
			if (x < 4.6 + gap && z > -21 - gap && y < 15 + gap && z > obl - 8) {
				if (x > 4.6 - gap || z < -21 + gap || y > 15 - gap) {
					return -1;
				}

				return 4;
			}

			if (z > obl) {
				return 3;
			}

			double f1 = -29.0 + 0.25 * (16 - y);
			if (z > f1 - gap && y < 17.5 + gap) {
				if (Math.abs(z - f1) < gap || Math.abs(y - 17.5) < gap) {
					return -1;
				}

				return 2;
			}

			if (y < 22 && Math.abs(z + 33.5) < gap) {
				return -1;
			}

			return z < -33.5 ? 0 : 1;
		}

		if (z > obl) {
			return 7;
		}

		if (y < 22 && Math.abs(z + 31) < gap) {
			return -1;
		}

		return z < -31 ? 5 : 6;
	}

	/**
	 * The trunk's bones: thirteen thoracic vertebrae with the tall spines of the withers, six lumbar ones with their long
	 * flat transverse processes, the sacrum and the root of the tail; thirteen pairs of ribs, the first eight joined to
	 * the breastbone by costal cartilage, the next four by the cartilage of the costal arch, the last floating; the seven
	 * sternebrae and the xiphoid; the shoulder blades on the chest wall; the pelvis.
	 */
	private static void skeleton() {
		Mat bone = (x, y, z, f, ax, ay, az) -> {
			int c = mix(0xCFC09F, 0xEDE4CF, noise(x * 1.2, y * 1.2, z * 1.2, 141));
			return noise(x * 4, y * 4, z * 4, 142) > 0.83 ? mix(c, 0xB8A784, 0.5) : c;
		};
		Mat joint = organic(0xEEEAE0, 0xDAD3C4, 0xF8F6F0, 1.5, 143);
		Mat cartilage = organic(0xC9D3CF, 0xAAB8B4, 0xE1E8E4, 1.4, 144);
		Mat disc = organic(0xD8D0C0, 0xC4B9A4, 0xE6E0D2, 1.4, 145);
		double[] spine = {6.5, 8.5, 9.0, 8.5, 7.5, 6.5, 5.5, 4.6, 4.0, 3.6, 3.3, 3.1, 3.0};
		for (int i = 1; i <= 13; i++) {
			double z = -38 + 3 * (i - 1);
			double y = spineY(z);
			String g = String.format(Locale.ROOT, "skeleton/spine/thoracic/T%02d", i);
			part(g, "body", bone, tube().p(0, y, z - 1.25, 1.4, 1.3).p(0, spineY(z + 1.25), z + 1.25, 1.4, 1.3).flat(true, true).build());
			part(g, "disc", disc, tube().p(0, spineY(z + 1.25), z + 1.25, 1.3).p(0, spineY(z + 1.75), z + 1.75, 1.3).flat(true, true).build());
			double h = spine[i - 1];
			part(g, "spinous_process", bone, tube().up((a, b, c) -> new double[] {1, 0, 0})
					.p(0, y + 1.0, z, 0.8, 0.3).p(0, y + 1.3 + h * 0.6, z + 0.35 * h * 0.6, 0.65, 0.28).p(0, y + 1.3 + h, z + 0.35 * h, 0.6, 0.4).build());
		}

		double[] transverse = {6.0, 7.2, 8.0, 8.0, 7.4, 6.4};
		for (int i = 1; i <= 6; i++) {
			double z = 1.5 + 4.0 * (i - 1);
			double y = spineY(z);
			String g = String.format(Locale.ROOT, "skeleton/spine/lumbar/L%02d", i);
			part(g, "body", bone, tube().p(0, spineY(z - 1.7), z - 1.7, 1.6, 1.4).p(0, spineY(z + 1.7), z + 1.7, 1.6, 1.4).flat(true, true).build());
			if (i < 6) {
				part(g, "disc", disc, tube().p(0, spineY(z + 1.7), z + 1.7, 1.45).p(0, spineY(z + 2.3), z + 2.3, 1.45).flat(true, true).build());
			}

			part(g, "spinous_process", bone, tube().up((a, b, c) -> new double[] {1, 0, 0})
					.p(0, y + 1.2, z, 1.2, 0.3).p(0, y + 4.8, z - 0.4, 1.0, 0.35).build());
			double reach = transverse[i - 1];
			for (int s = -1; s <= 1; s += 2) {
				part(g, s > 0 ? "right_transverse_process" : "left_transverse_process", bone, tube().up((a, b, c) -> new double[] {0, 1, 0})
						.p(s * 1.4, y - 0.2, z, 0.9, 0.3).p(s * reach * 0.55, y - 0.4, z - 0.2, 0.75, 0.25).p(s * reach, y - 1.0, z - 0.8, 0.7, 0.3).build());
			}
		}

		part("skeleton/spine/sacrum", "sacrum", bone, tube().p(0, 30.2, 24.2, 1.7, 1.2).p(0, 29.4, 30, 1.4, 1.0).p(0, 28.2, 35.8, 1.1, 0.8)
				.up((a, b, c) -> new double[] {0, 1, 0}).build());
		part("skeleton/spine/sacrum", "median_sacral_crest", bone, tube().p(0, 31.4, 24.8, 0.35).p(0, 32.2, 28, 0.35).p(0, 30.8, 34, 0.35).build());
		for (int s = -1; s <= 1; s += 2) {
			part("skeleton/spine/sacrum", s > 0 ? "right_wing" : "left_wing", bone, tri(v(s * 0.5, 30.4, 24.0), v(s * 4.2, 30.6, 24.8), v(s * 1.2, 29.8, 28.5), 0.6));
		}

		double[] tailZ = {37.0, 39.2, 41.2, 43.0};
		for (int i = 0; i < tailZ.length; i++) {
			double r = 0.95 - 0.08 * i;
			part(String.format(Locale.ROOT, "skeleton/spine/tail/Cd%02d", i + 1), "body", bone, tube()
					.p(0, spineY(tailZ[i] - 0.85), tailZ[i] - 0.85, r).p(0, spineY(tailZ[i] + 0.85), tailZ[i] + 0.85, r).build());
		}

		ribs(bone, cartilage);
		double[] zs = {-38.8, -35.5, -32.0, -28.5, -25.0, -21.5, -18.0, -14.5};
		for (int k = 0; k < 7; k++) {
			double a = (k == 0 ? -40.5 : zs[k]) + 0.4;
			double b = zs[k + 1] - 0.4;
			part(String.format(Locale.ROOT, "skeleton/sternum/sternebra_%d", k + 1), "sternebra", bone, tube().up((p, q, r) -> new double[] {0, 1, 0})
					.p(0, sternumY(a) + 0.8, a, 1.3, 0.8).p(0, sternumY((a + b) / 2) + 0.8, (a + b) / 2, 1.35, 0.85).p(0, sternumY(b) + 0.8, b, 1.3, 0.8)
					.build());
		}

		part("skeleton/sternum/xiphoid", "xiphoid_cartilage", cartilage, tube().up((p, q, r) -> new double[] {0, 1, 0})
				.p(0, sternumY(-14.3) + 0.6, -14.3, 1.1, 0.3).p(0, 2.3, -11, 1.4, 0.25).p(0, 2.1, -7.0, 1.6, 0.25).build());
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right" : "left";
			String g = "skeleton/shoulder_blades/" + side + "_scapula";
			part(g, "glenoid", joint, ell(s * 10.2, 15.5, -37.5, 1.2, 1.4, 1.5));
			part(g, "blade", bone, tri(v(s * 10.2, 17.0, -36.8), v(s * 9.0, 30.5, -33.6), v(s * 9.2, 29.5, -25.2), 0.35));
			part(g, "spine_of_scapula", bone, tube().p(s * 10.9, 18.5, -36.0, 0.45).p(s * 11.1, 25.0, -32.0, 0.4).p(s * 10.6, 29.8, -29.6, 0.3).build());
			part(g, "scapular_cartilage", cartilage, tube().up((p, q, r) -> new double[] {1, 0, 0})
					.p(s * 8.8, 31.4, -33.4, 1.1, 0.3).p(s * 8.9, 31.6, -29.2, 1.3, 0.3).p(s * 9.0, 30.6, -25.0, 1.0, 0.3).build());
			String pg = "skeleton/pelvis/" + side + "_hip_bone";
			part(pg, "ilium_wing", bone, tri(v(s * 11.0, 28.8, 22.0), v(s * 2.8, 31.0, 25.0), v(s * 6.0, 25.5, 29.0), 0.45));
			part(pg, "ilium_shaft", bone, tube().p(s * 6.0, 25.5, 29.0, 1.0).p(s * 6.5, 19.5, 32.5, 0.85).build());
			part(pg, "acetabulum", joint, cut(ell(s * 6.9, 19.0, 33.3, 1.5, 1.6, 1.6), ell(s * 8.4, 19.0, 33.3, 1.15, 1.15, 1.15)));
			part(pg, "pubis", bone, tri(v(s * 6.2, 18.0, 32.3), v(0, 16.6, 32.6), v(0, 16.6, 35.2), 0.45));
			part(pg, "ischium", bone, union(0.2, tri(v(s * 6.4, 18.3, 34.5), v(s * 5.6, 22.0, 43.0), v(0, 16.8, 41.0), 0.45),
					tri(v(s * 6.4, 18.3, 34.5), v(0, 16.8, 41.0), v(0, 16.6, 37.4), 0.45)));
		}
	}

	/** The ribs, each sweeping out and down from its vertebra and back as it goes, with its costal cartilage. */
	private static void ribs(Mat bone, Mat cartilage) {
		double[][] cart = new double[14][];
		double[] zs = {-38.8, -35.5, -32.0, -28.5, -25.0, -21.5, -18.0, -14.5};
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right" : "left";
			int sign = s;
			for (int i = 1; i <= 13; i++) {
				double zh = -38 + 3 * (i - 1);
				double slope = (i == 1 ? 1.5 : 3.0 + 0.45 * i);
				double yJ = i <= 8 ? (i < 3 ? 10.5 : 8.5) : i < 13 ? 7.5 + (i - 9) * 0.8 : 13.5;
				double hb = YC - sternumY(zh + slope * 0.75) - 0.3;
				double phiEnd = Math.asin(clamp((yJ - YC) / hb, -1, 1));
				Tube rib = tube().up((x, y, z) -> norm(new double[] {x / sq(halfWidth(z)), (y - YC) / sq(y >= YC ? 12.8 : hb), 0}));
				rib.p(sign * 1.5, spineY(zh) - 0.3, zh, 0.5, 0.45);
				double[] last = null;
				int steps = 14;
				double phi0 = Math.toRadians(78);
				for (int k = 0; k <= steps; k++) {
					double phi = phi0 + (phiEnd - phi0) * k / steps;
					double sp = Math.sin(phi);
					double zz = zh + slope * (1 - sp) / 2;
					double y = YC + (sp >= 0 ? 12.8 : hb) * sp;
					double x = sign * Math.max(2.2, (halfWidth(zz) - 0.6) * Math.cos(phi));
					double width = 0.55 + 0.25 * Math.sin(Math.PI * k / steps) + 0.004 * i * 10;
					rib.p(x, y, zz, width, 0.42);
					last = new double[] {x, y, zz};
				}

				String g = String.format(Locale.ROOT, "skeleton/ribcage/%s_rib_%02d", side, i);
				// Coarse grains keep only every second or fourth rib, so a gap still shows between them.
				boolean kept = (i - 1) % (LOD >= 4 ? 4 : LOD >= 2 ? 2 : 1) == 0;
				if (kept) {
					part(g, "rib", bone, rib.build());
				}
				Tube c = tube().up((x, y, z) -> norm(new double[] {x / sq(halfWidth(z)), (y - YC) / sq(hb), 0}));
				double[] mid;
				double[] end;
				if (i <= 8) {
					end = new double[] {sign * 1.0, sternumY(zs[i - 1]) + 0.7, zs[i - 1]};
					mid = new double[] {last[0] * 0.72, last[1] - (last[1] - end[1]) * 0.6, last[2] - (last[2] - end[2]) * 0.3};
				} else if (i < 13) {
					double[] pc = cart[i - 1];
					end = new double[] {pc[0] + (pc[3] - pc[0]) * 0.45, pc[1] + (pc[4] - pc[1]) * 0.45, pc[2] + (pc[5] - pc[2]) * 0.45};
					mid = new double[] {(last[0] + end[0]) / 2 + sign * 0.6, (last[1] + end[1]) / 2 - 0.5, (last[2] + end[2]) / 2};
				} else {
					mid = new double[] {last[0] * 0.95, last[1] - 1.4, last[2] + 0.3};
					end = new double[] {last[0] * 0.88, last[1] - 2.6, last[2] + 0.4};
				}

				cart[i] = new double[] {last[0], last[1], last[2], mid[0], mid[1], mid[2]};
				c.p(last[0], last[1], last[2], 0.5, 0.36).p(mid[0], mid[1], mid[2], 0.45, 0.34).p(end[0], end[1], end[2], 0.4, 0.32);
				if (kept) {
					part(g, "costal_cartilage", cartilage, c.build());
				}
			}
		}
	}

	/** The height of the middle of the cervical vertebral bodies: the neck carried forward and gently down to the skull. */
	static double neckY(double z) {
		return curve(z, -72, 24.6, -66, 25.3, -58, 26.1, -50, 27.0, -42, 27.8);
	}

	/**
	 * The head, forward of the trunk on the neck. A ewe's grazing skull: long and narrow, the eyes high and to the
	 * sides for a prey animal's wide field, no upper incisors (a hard dental pad the lower ones press against), six
	 * selenodont cheek teeth a side, and a small brain held in a hollow braincase. The braincase is a shell, so
	 * hiding the skeleton leaves the brain standing in its cavity: the "they are like us" moment.
	 */
	private static void head() {
		neck();
		skull();
		brain();
		softHead();
	}

	/**
	 * The soft parts of the head round the skull: the eyes in their orbits, dark and glossy, looking out to the sides; the
	 * tongue on the floor of the mouth between the lower cheek teeth, thin at the tip by the incisors and thick at its
	 * root; the larynx at the back of the throat, a box of cartilage under the braincase; the pharynx above it; and the
	 * nasal cavity, the dark space under the nasal bones above the palate (its scrolls of turbinate too fine to cut).
	 */
	private static void softHead() {
		Mat eye = glossy(0x2E2218, 0x160F0A, 0x5C4A38, 0xFFFFFF, 2.0, 191);
		Mat tongue = organic(0xC7837C, 0xA9645E, 0xDBA39C, 2.2, 192);
		Mat cartilage = organic(0xD9D2C2, 0xBFB6A2, 0xEAE5D8, 2.0, 193);
		Mat dark = organic(0x3A2420, 0x24140F, 0x4E322C, 2.0, 194);
		for (int s = -1; s <= 1; s += 2) {
			part("head/eyes", s > 0 ? "right_eye" : "left_eye", eye, ell(s * 4.4, 26.3, -81.5, 1.35, 1.35, 1.35));
		}

		part("head/tongue", "tongue", tongue, tube().up((a, b, c) -> new double[] {0, 1, 0})
				.p(0, 20.3, -95.0, 0.9, 0.45).p(0, 20.6, -89.0, 1.4, 0.55).p(0, 21.0, -83.0, 1.6, 0.75).p(0, 21.6, -78.0, 1.3, 0.8).build());
		part("head/throat", "larynx", cartilage, ell(0, 21.2, -74.6, 1.1, 1.0, 1.3));
		part("head/throat", "pharynx", dark, ell(0, 22.6, -76.6, 0.9, 0.7, 1.6));
		// The glands of the throat: the parotid flat over the back of the jaw under the ear, the mandibular tucked under
		// the angle of the jaw, and the two lobes of the thyroid either side of the windpipe just behind the larynx.
		Mat gland = gland(195);
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right_" : "left_";
			part("head/glands/salivary", side + "parotid", gland, lumpy(ell(s * 3.4, 22.6, -75.4, 0.75, 1.6, 1.0), 0.15, 3.0, 196 + s));
			part("head/glands/salivary", side + "mandibular_gland", gland, lumpy(ell(s * 2.6, 19.6, -75.4, 0.85, 0.75, 0.9), 0.12, 3.0, 198 + s));
			part("head/glands/thyroid", side + "thyroid_lobe", gland, ell(s * 1.5, 20.8, -73.9, 0.45, 0.7, 0.8));
			if (LAMB) {
				// A lamb's thymus runs all the way up the neck beside the windpipe to the throat.
				part("head/glands/thymus", side + "cervical_thymus", gland(197), lumpy(ell(s * 2.1, 20.2, -73.8, 0.8, 1.0, 0.9), 0.12, 3.0, 199));
			}
		}
		part("head/nose", "nasal_cavity", dark, region(v(-1.3, 21.8, -97.0), v(1.3, 24.0, -84.0), (x, y, z) -> true));
	}

	/** The seven cervical vertebrae: C1 the atlas with its broad wings, C2 the axis with its tall spine, then five alike. */
	private static void neck() {
		Mat bone = (x, y, z, f, ax, ay, az) -> {
			int c = mix(0xCFC09F, 0xEDE4CF, noise(x * 1.2, y * 1.2, z * 1.2, 151));
			return noise(x * 4, y * 4, z * 4, 152) > 0.83 ? mix(c, 0xB8A784, 0.5) : c;
		};
		double[] zs = {-68.5, -64.0, -59.5, -55.0, -50.5, -46.0, -42.0};
		for (int i = 0; i < zs.length; i++) {
			double z = zs[i];
			double y = neckY(z);
			String g = String.format(Locale.ROOT, "skeleton/spine/cervical/C%02d", i + 1);
			part(g, "body", bone, tube().p(0, y, z - 1.7, 1.5, 1.4).p(0, neckY(z + 1.7), z + 1.7, 1.5, 1.4).flat(true, true).build());
			part(g, "spinous_process", bone, tube().up((a, b, c) -> new double[] {1, 0, 0})
					.p(0, y + 1.1, z + 0.5, 0.7, 0.3).p(0, y + (i == 1 ? 3.4 : 2.1), z + (i == 1 ? 1.7 : 1.0), 0.55, 0.3).build());
			double wing = i == 0 ? 4.3 : i == 1 ? 2.7 : 1.7;
			double thick = i == 0 ? 1.0 : 0.42;
			for (int s = -1; s <= 1; s += 2) {
				part(g, s > 0 ? "right_transverse_process" : "left_transverse_process", bone, tube().up((a, b, c) -> new double[] {0, 1, 0})
						.p(s * 1.2, y - 0.1, z, 0.8, thick).p(s * wing, y - 0.2, z - 0.3, 0.55, i == 0 ? 1.2 : thick * 0.9).build());
			}
		}
	}

	/**
	 * The skull. The braincase is an ellipsoid shell (cut hollow so the brain fills it) with the nuchal crest and
	 * occipital round the back and the foramen magnum under them for the cord. The face is a long boxy muzzle in the
	 * silver-age way: maxilla plates either side, the nasal bridge on top, the hard palate and dental pad below, an
	 * incisive rim at the nose. The orbit is a ring of bone (a torus) left open as the socket; a zygomatic arch bars
	 * the cheek. The mandible hinges under the braincase and meets at the symphysis.
	 */
	private static void skull() {
		Mat bone = (x, y, z, f, ax, ay, az) -> {
			int c = mix(0xD6C9AC, 0xEFE7D0, noise(x * 1.1, y * 1.1, z * 1.1, 161));
			return noise(x * 4, y * 4, z * 4, 162) > 0.86 ? mix(c, 0xBEB091, 0.5) : c;
		};
		Mat tooth = glossy(0xF0EAD8, 0xD9D0BA, 0xFCF9EE, 0xFFFFFF, 2.4, 171);
		Mat gum = organic(0xC7A69C, 0xAE8B82, 0xDCC0B7, 2.0, 172);
		// Braincase: a solid lump of bone, then hollowed to the cranial cavity the brain fills.
		Shape solid = union(0.6, lumpy(ell(0, 26.3, -78.5, 2.7, 3.0, 4.3), 0.25, 2.2, 163), lumpy(ell(0, 28.9, -75.2, 1.3, 1.0, 1.6), 0.2, 3.0, 164),
				ell(0, 25.0, -74.2, 2.4, 2.6, 1.7));
		Shape cavity = ell(0, 26.3, -78.3, 2.05, 2.35, 3.6);
		Shape cranium = cut(cut(solid, cavity), ell(0, 24.1, -73.6, 1.0, 1.1, 1.5));
		part("skeleton/skull/cranium", "braincase", bone, cranium);
		for (int s = -1; s <= 1; s += 2) {
			part("skeleton/skull/cranium", s > 0 ? "right_occipital_condyle" : "left_occipital_condyle", bone, ell(s * 1.0, 23.9, -74.0, 0.7, 0.6, 0.9));
			String side = s > 0 ? "right" : "left";
			// Maxilla: the cheek plate either side of the nasal bridge, tapering towards the nose, holding the upper teeth.
			part("skeleton/skull/face", side + "_maxilla", bone, region(v(Math.min(s * 2.3, s * 4.3), 21.4, -95.0), v(Math.max(s * 2.3, s * 4.3), 25.3, -83.0),
					(x, y, z) -> {
						double t = clamp((-83.0 - z) / 12.0, 0, 1);
						return Math.abs(x) >= 2.3 && Math.abs(x) <= 4.3 - 0.9 * t && y >= 21.4 + 0.5 * t && y <= 25.3 - 1.2 * t;
					}));
			// Zygomatic arch: a bar of bone below the eye, sweeping back to the temporal.
			part("skeleton/skull/face", side + "_zygomatic_arch", bone, tube()
					.p(s * 4.2, 24.2, -84.5, 0.55).p(s * 5.3, 24.6, -80.5, 0.55).p(s * 4.5, 25.0, -76.5, 0.5).build());
			// The orbital rim: a ring of bone left open as the socket, high and to the side.
			part("skeleton/skull/orbit", side + "_orbital_rim", bone,
					lumpy(torus(new double[] {s * 3.6, 26.3, -81.5}, new double[] {1, 0, 0}, 2.3, 0.55), 0.15, 3.0, 165));
		}
		// Nasal bridge: a flat wide bar along the top of the muzzle.
		part("skeleton/skull/face", "nasal_bones", bone, tube().up((a, b, c) -> new double[] {0, 1, 0})
				.p(0, 26.6, -83.0, 1.7, 0.6).p(0, 25.4, -90.0, 1.5, 0.6).p(0, 23.8, -96.0, 1.2, 0.55).p(0, 22.8, -98.2, 0.9, 0.5).build());
		// The hard palate (roof of the mouth) and, at its front, the dental pad a sheep's lower incisors press on.
		part("skeleton/skull/face", "hard_palate", bone, region(v(-1.9, 21.2, -96.0), v(1.9, 21.7, -84.0), (x, y, z) -> true));
		part("skeleton/skull/face", "dental_pad", gum, region(v(-1.8, 21.4, -98.2), v(1.8, 22.2, -95.5), (x, y, z) -> true));
		part("skeleton/skull/face", "incisive_bone", bone, tube().up((a, b, c) -> new double[] {0, 1, 0})
				.p(-1.9, 22.0, -96.0, 0.55).p(0, 22.4, -98.8, 0.6).p(1.9, 22.0, -96.0, 0.55).build());
		mandible(bone, tooth);
	}

	/** The mandible: two halves rising to a hinge under the braincase, carrying the cheek teeth forward to the symphysis. */
	private static void mandible(Mat bone, Mat tooth) {
		for (int s = -1; s <= 1; s += 2) {
			String g = "skeleton/skull/mandible/" + (s > 0 ? "right" : "left");
			part(g, "ramus_and_body", bone, tube().up((a, b, c) -> new double[] {0, 1, 0})
					.p(s * 2.1, 23.4, -75.8, 0.75, 0.9).p(s * 3.0, 20.6, -79.5, 0.8, 1.1).p(s * 3.3, 18.9, -84.0, 0.85, 1.0)
					.p(s * 3.0, 19.4, -89.0, 0.8, 0.95).p(s * 2.2, 19.8, -93.5, 0.7, 0.85).p(s * 0.4, 19.9, -96.5, 0.55, 0.7).build());
			part(g, "coronoid_process", bone, tube().up((a, b, c) -> new double[] {0, 1, 0})
					.p(s * 2.4, 23.8, -78.5, 0.55, 0.5).p(s * 2.3, 25.4, -80.0, 0.4, 0.35).build());
			part(g, "condyle", bone, ell(s * 2.1, 23.4, -75.6, 0.7, 0.55, 0.6));
		}

		part("skeleton/skull/mandible/symphysis", "symphysis", bone, ell(0, 19.9, -96.6, 0.7, 0.6, 0.8));
		// Teeth: six selenodont cheek teeth a side, upper and lower, and the eight lower incisors at the front. A lamb has only
		// its milk teeth: three milk premolars a side at the front of the row (the molars behind them not yet come through),
		// and eight small, narrow milk incisors.
		double back = LAMB ? -88.0 : -83.5;
		double middle = LAMB ? -90.5 : -88.5;
		double shrink = LAMB ? 0.8 : 1.0;
		for (int s = -1; s <= 1; s += 2) {
			String side = s > 0 ? "right" : "left";
			part("skeleton/skull/teeth", side + "_upper_cheek_teeth", tooth, tube().up((a, b, c) -> new double[] {0, 1, 0})
					.p(s * 3.0, 21.4, back, 0.8 * shrink, 0.9 * shrink).p(s * 2.9, 21.2, middle, 0.85 * shrink, 0.95 * shrink)
					.p(s * 2.7, 21.1, -93.0, 0.8 * shrink, 0.9 * shrink).beads(0.28, 1.5).build());
			part("skeleton/skull/teeth", side + "_lower_cheek_teeth", tooth, tube().up((a, b, c) -> new double[] {0, 1, 0})
					.p(s * 2.9, 20.6, back, 0.75 * shrink, 0.85 * shrink).p(s * 2.8, 20.5, middle, 0.8 * shrink, 0.9 * shrink)
					.p(s * 2.6, 20.4, -93.0, 0.75 * shrink, 0.85 * shrink).beads(0.28, 1.5).build());
		}

		double wide = LAMB ? 1.3 : 1.7;
		double thick = LAMB ? 0.4 : 0.5;
		double front = LAMB ? -95.7 : -95.5;
		part("skeleton/skull/teeth", "lower_incisors", tooth, tube().up((a, b, c) -> new double[] {0, 1, 0})
				.p(-wide, 19.9, front, thick, 0.9 * shrink).p(0, 20.1, -96.9, thick, 1.0 * shrink).p(wide, 19.9, front, thick, 0.9 * shrink)
				.beads(0.3, 1.1).build());
	}

	/** The brain, filling the hollow braincase: the folded cerebrum, the wrinkled cerebellum, the stem to the cord. */
	private static void brain() {
		Mat cerebrum = organic(0xC9A3A0, 0xA87E7C, 0xDDC0BC, 2.6, 181);
		Mat folds = organic(0xB98C89, 0x9A6E6C, 0xCDAAA6, 3.4, 182);
		part("head/brain/cerebrum", "cerebrum", cerebrum, lumpy(ell(0, 26.4, -79.0, 1.85, 2.1, 2.9), 0.35, 5.5, 183));
		part("head/brain/cerebellum", "cerebellum", folds, lumpy(ell(0, 25.0, -75.8, 1.5, 1.25, 1.15), 0.3, 7.5, 184));
		part("head/brain/brainstem", "brainstem", folds, tube().p(0, 25.4, -76.2, 0.75).p(0, 24.4, -74.8, 0.65).p(0, 23.6, -73.9, 0.6).build());
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

	/** Shapes made while this is under 1 are smaller by it about {@link #GROW_AT}: a lamb's immature organs, made from the grown ones. */
	static double GROW = 1.0;
	static double[] GROW_AT = {0, 0, 0};

	/** Coordinate {@code a} (0 x, 1 y, 2 z) of a point, moved in to {@link #GROW_AT} by {@link #GROW}. */
	static double grown(double v, int a) {
		return GROW_AT[a] + (v - GROW_AT[a]) * GROW;
	}

	/** Grows the shapes made from now on by {@code g} about (x, y, z); {@code grow(1, 0, 0, 0)} ends it. */
	static void grow(double g, double x, double y, double z) {
		GROW = g;
		GROW_AT = new double[] {x, y, z};
	}

	static Shape ell(double cx, double cy, double cz, double rx, double ry, double rz) {
		if (GROW != 1.0) {
			return ellAt(grown(cx, 0), grown(cy, 1), grown(cz, 2), rx * GROW, ry * GROW, rz * GROW);
		}

		return ellAt(cx, cy, cz, rx, ry, rz);
	}

	static Shape ellAt(double cx, double cy, double cz, double rx, double ry, double rz) {
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
		private boolean duct;
		private double beadAmp;
		private double beadLen = 1;

		Tube p(double x, double y, double z, double r) {
			return p(x, y, z, r, r);
		}

		Tube p(double x, double y, double z, double rx, double ry) {
			ctrl.add(new double[] {grown(x, 0), grown(y, 1), grown(z, 2), rx * GROW, ry * GROW});
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

		/** A duct too thin for the grid (a ureter): kept at least {@link #DUCT} thick, so it does not break into loose cells. */
		Tube duct() {
			duct = true;
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
			boolean isDuct = duct;
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

				double least = isDuct ? Math.max(VOX * THIN, DUCT) : VOX * THIN;
				double rx = Math.max((a[3] + (b[3] - a[3]) * bestT) * scale, least);
				double ry = Math.max((a[4] + (b[4] - a[4]) * bestT) * scale, least);
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
			"hepatic_caudal_vena_cava", "tracheal_bronchus", "left_principal_bronchus", "right_principal_bronchus", "scapular_cartilage");

	static void part(String path, String name, Mat mat, Shape shape) {
		if (LOD >= 2.5 && FINE.contains(name)) {
			return;
		}

		PARTS.add(new Part(path, name, mat, shape));
	}

	/** {@link #modPiece} for the skull and the head, as "KIND name", or null for a part elsewhere. */
	static String headPiece(Part p) {
		String n = p.name;
		if (p.under("skeleton/skull/teeth")) {
			return n.endsWith("upper_cheek_teeth") ? "TEETH upper_molars" : n.endsWith("lower_cheek_teeth") ? "TEETH lower_molars" : "TEETH incisors";
		}

		if (p.under("skeleton/skull/mandible")) {
			return "SKULL jawbone";
		}

		if (p.under("skeleton/skull/orbit")) {
			return "SKULL eye_socket";
		}

		if (p.under("skeleton/skull/face")) {
			return switch (n) {
				case "nasal_bones" -> "SKULL nasal_bone";
				case "dental_pad" -> "DERMIS dental_pad";
				default -> n.endsWith("zygomatic_arch") ? "SKULL cheekbone" : "SKULL upper_jaw";
			};
		}

		if (p.under("skeleton/skull")) {
			return "SKULL cranium";
		}

		if (p.under("head/brain")) {
			return "BRAIN " + n;
		}

		if (p.under("head/eyes")) {
			return "EYE eye";
		}

		if (p.under("head/tongue")) {
			return "TONGUE tongue";
		}

		if (p.under("head/glands")) {
			return p.under("head/glands/thyroid") ? "GLAND thyroid" : p.under("head/glands/thymus") ? "GLAND thymus" : "GLAND salivary_gland";
		}

		if (p.under("head/throat")) {
			return n.equals("larynx") ? "CARTILAGE larynx" : "SOCKET pharynx";
		}

		return p.under("head/nose") ? "SOCKET nasal_cavity" : null;
	}

	/**
	 * What the mod calls a part: its {@code Anatomy.Kind} (the tile it is drawn with and how it cooks) and the organ it
	 * belongs to (a translation id, {@code fluidgore.organ.<name>}). Every piece of one organ shares the name, so the mod
	 * can keep the organ one object: the gallbladder and the liver's veins are the liver's, the coronary fat the heart's.
	 */
	static String[] modPiece(Part p) {
		String path = p.path;
		String n = p.name;
		String head = headPiece(p);
		if (head != null) {
			return new String[] {head.substring(0, head.indexOf(' ')), head.substring(head.indexOf(' ') + 1)};
		}

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
			return new String[] {"TEETH", "teeth"};
		}

		if (p.under("skeleton/skull/mandible")) {
			return new String[] {"SKULL", "mandible"};
		}

		if (p.under("skeleton/skull")) {
			return new String[] {"SKULL", "skull"};
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

		if (p.under("thorax/heart")) {
			String kind = p.under("thorax/heart/coronary_fat") ? "SUET" : p.under("thorax/heart/aorta") || p.under("thorax/heart/pulmonary_trunk") ? "AORTA"
					: p.under("thorax/heart/venae_cavae") ? "VEIN" : "HEART";
			return new String[] {kind, "heart"};
		}

		if (p.under("thorax/airway")) {
			return new String[] {"TRACHEA", "windpipe"};
		}

		if (p.under("thorax/esophagus") || p.under("abdomen/stomach/esophagus")) {
			return new String[] {"STOMACH", "esophagus"};
		}

		if (p.under("thorax/lungs")) {
			return new String[] {"LUNG", "lung"};
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
			return new String[] {p.under("abdomen/liver/gallbladder") ? "GALLBLADDER" : p.under("abdomen/liver/caudal_vena_cava") || p.under("abdomen/liver/portal_vein")
					? "VEIN" : "LIVER", "liver"};
		}

		if (p.under("abdomen/spleen")) {
			return new String[] {"SPLEEN", "spleen"};
		}

		if (p.under("reproductive")) {
			return switch (n.replaceFirst("^(right|left)_", "")) {
				case "vulva", "teat", "scrotum", "prepuce" -> new String[] {"DERMIS", n.replaceFirst("^(right|left)_", "")};
				case "cervix" -> new String[] {"CARTILAGE", "cervix"};
				case "vagina" -> new String[] {"BLADDER", "vagina"};
				case "uterine_body", "uterine_horn" -> new String[] {"BLADDER", "uterus"};
				case "oviduct" -> new String[] {"AORTA", "oviduct"};
				case "ovary" -> new String[] {"GLAND", "ovary"};
				case "udder_half" -> new String[] {"GLAND", "udder"};
				case "testis" -> new String[] {"TESTIS", "testis"};
				case "epididymis" -> new String[] {"AORTA", "epididymis"};
				case "vas_deferens" -> new String[] {"AORTA", "vas_deferens"};
				case "spermatic_cord" -> new String[] {"VEIN", "spermatic_cord"};
				case "vesicular_gland" -> new String[] {"GLAND", "vesicular_gland"};
				case "penis" -> new String[] {"BLADDER", "penis"};
				default -> throw new IllegalStateException("No tissue for " + path + "/" + n);
			};
		}

		if (p.under("nervous/spinal_cord")) {
			return new String[] {"BRAIN", "spinal_cord"};
		}

		if (p.under("thorax/thymus")) {
			return new String[] {"GLAND", "thymus"};
		}

		if (p.under("abdomen/adrenals")) {
			return new String[] {"GLAND", "adrenal_gland"};
		}

		if (p.under("abdomen/pancreas")) {
			return new String[] {"GLAND", "pancreas"};
		}

		if (p.under("abdomen/omentum")) {
			return new String[] {"SUET", "omentum"};
		}

		if (n.contains("ureter")) {
			return new String[] {"AORTA", "ureter"};
		}

		if (n.equals("urethra")) {
			return new String[] {"BLADDER", "urethra"};
		}

		if (p.under("abdomen/kidneys")) {
			return switch (n) {
				case "suet" -> new String[] {"SUET", "kidney_fat"};
				case "renal_vein" -> new String[] {"VEIN", "kidney"};
				case "kidney" -> new String[] {"KIDNEY", "kidney"};
				default -> new String[] {"AORTA", "kidney"};
			};
		}

		if (p.under("abdomen/stomach/rumen") || p.under("abdomen/stomach/ingesta")) {
			return new String[] {"RUMEN", "rumen"};
		}

		if (p.under("abdomen/stomach/reticulum")) {
			return new String[] {"RETICULUM", "reticulum"};
		}

		if (p.under("abdomen/stomach/omasum")) {
			return new String[] {"STOMACH", "omasum"};
		}

		if (p.under("abdomen/stomach/abomasum")) {
			return new String[] {"STOMACH", "abomasum"};
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
			return new String[] {"INTESTINE", "small_intestine"};
		}

		if (p.under("abdomen/urinary")) {
			return new String[] {"BLADDER", "bladder"};
		}

		throw new IllegalStateException("No tissue for " + path + "/" + n);
	}

	/** Each Kind's tile in anatomy.png (the order of {@code Anatomy.Kind}). */
	private static final Map<String, Integer> TILE = Map.ofEntries(Map.entry("BONE", 0), Map.entry("BONE_END", 1), Map.entry("CARTILAGE", 6),
			Map.entry("VERTEBRA", 7), Map.entry("MUSCLE", 8), Map.entry("LUNG", 16), Map.entry("HEART", 17), Map.entry("AORTA", 18), Map.entry("LIVER", 19),
			Map.entry("STOMACH", 20), Map.entry("INTESTINE", 21), Map.entry("COLON", 22), Map.entry("KIDNEY", 23), Map.entry("SPLEEN", 24),
			Map.entry("BLADDER", 25), Map.entry("TRACHEA", 26), Map.entry("SUET", 38), Map.entry("RUMEN", 45), Map.entry("GALLBLADDER", 49),
			Map.entry("RETICULUM", 50), Map.entry("VEIN", 51), Map.entry("SKULL", 2), Map.entry("TEETH", 3), Map.entry("SOCKET", 4), Map.entry("BRAIN", 31),
			Map.entry("EYE", 5), Map.entry("TONGUE", 12), Map.entry("DERMIS", 14), Map.entry("GLAND", 54), Map.entry("TESTIS", 55));

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

		/** A voxel centre in the body's centimetres, where the shapes and tissues are defined. */
		double cx(int i) {
			if (WARP != null) {
				return WARP[0].applyAsDouble(wx(i));
			}

			return wx(i) * SCALE[0] + SHIFT[0];
		}

		double cy(int j) {
			if (WARP != null) {
				return WARP[1].applyAsDouble(wy(j));
			}

			return wy(j) * SCALE[1] + SHIFT[1];
		}

		double cz(int k) {
			if (WARP != null) {
				return WARP[2].applyAsDouble(wz(k));
			}

			return wz(k) * SCALE[2] + SHIFT[2];
		}
	}

	/**
	 * The output frame: centimetres = output unit x {@code SCALE} + {@code SHIFT}, per axis. For the organ files the
	 * output unit is the centimetre; for the mod it is the vanilla sheep's own pixel, its body squashed into the 8x6x16
	 * cube. {@code CLIP_LO/HI}: only voxels whose centre lies inside, in output units (null: everything).
	 */
	static double[] SCALE = {1, 1, 1};
	static double[] SHIFT = {0, 0, 0};
	static double[] CLIP_LO;
	static double[] CLIP_HI;
	/**
	 * Output units to centimetres per axis when they are not one straight scale (null: {@code SCALE} and {@code SHIFT}): the head,
	 * whose box squashes the braincase and the long muzzle by different amounts so the eyes fall where the skin draws them.
	 */
	static java.util.function.DoubleUnaryOperator[] WARP;
	/** Centimetres per voxel, for choosing detail: every rib, or only some with a gap between them. */
	static double LOD = 0.5;
	/** Mod mode: anatomy.png, each part drawn with its tissue's 16px tile as the mod draws it. */
	static BufferedImage TILES;

	static Grid voxelize(double vox, Predicate<Part> keep) {
		for (int i = 0; i < PARTS.size(); i++) {
			PARTS.get(i).index = i;
		}

		double[] lo = {1e9, 1e9, 1e9};
		double[] hi = {-1e9, -1e9, -1e9};
		for (Part p : PARTS) {
			if (keep.test(p)) {
				for (int a = 0; a < 3; a++) {
					double u = (p.shape.lo[a] - SHIFT[a]) / SCALE[a];
					double w = (p.shape.hi[a] - SHIFT[a]) / SCALE[a];
					lo[a] = Math.min(lo[a], Math.min(u, w));
					hi[a] = Math.max(hi[a], Math.max(u, w));
				}
			}
		}

		if (WARP != null) {
			lo = CLIP_LO.clone();
			hi = CLIP_HI.clone();
		}

		if (CLIP_LO != null) {
			for (int a = 0; a < 3; a++) {
				lo[a] = Math.max(lo[a], CLIP_LO[a]);
				hi[a] = Math.min(hi[a], CLIP_HI[a]);
			}
		}

		int[] n = new int[3];
		double[] o = new double[3];
		for (int a = 0; a < 3; a++) {
			o[a] = Math.floor(lo[a] / vox) * vox;
			n[a] = (int) Math.ceil((hi[a] - o[a]) / vox);
		}

		Grid raw = new Grid(n[0], n[1], n[2], vox, o[0], o[1], o[2]);
		int[] min = {n[0], n[1], n[2]};
		int[] max = {-1, -1, -1};
		Part[] parts = PARTS.toArray(Part[]::new);
		for (int i = 0; i < n[0]; i++) {
			double x = raw.cx(i);
			for (int j = 0; j < n[1]; j++) {
				double y = raw.cy(j);
				for (int k = 0; k < n[2]; k++) {
					double z = raw.cz(k);
					if (CLIP_LO != null && !(raw.wx(i) > CLIP_LO[0] && raw.wx(i) < CLIP_HI[0] && raw.wy(j) > CLIP_LO[1] && raw.wy(j) < CLIP_HI[1]
							&& raw.wz(k) > CLIP_LO[2] && raw.wz(k) < CLIP_HI[2])) {
						continue;
					}

					for (Part p : parts) {
						if (p.shape.in(x, y, z)) {
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
			c = p.mat.rgb(g.cx(i), g.cy(j), g.cz(k), face, g.cx(ai), g.cy(aj), g.cz(ak));
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
		VOX = vox * Math.min(Math.abs(SCALE[0]), Math.min(Math.abs(SCALE[1]), Math.abs(SCALE[2])));
		DUCT = 0.65 * vox * Math.max(Math.abs(SCALE[0]), Math.max(Math.abs(SCALE[1]), Math.abs(SCALE[2])));
		Grid g = voxelize(vox, keep);
		List<Box> boxes = boxes(g);
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
			String json = bbmodel(file, g, boxes, uv, TILES.getWidth(), TILES.getHeight(), Base64.getEncoder().encodeToString(png.toByteArray()));
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
		Map<Long, int[]> uv = new LinkedHashMap<>();
		for (Rect r : rects) {
			for (int c = 0; c < r.w; c++) {
				for (int row = 0; row < r.h; row++) {
					int[] t = texel(r.box, r.face, c, row);
					tex.setRGB(r.at[0] + c, r.at[1] + row, 0xFF000000 | shade(g, t[0], t[1], t[2], r.face));
				}
			}

			uv.put(System.identityHashCode(r.box) * 8L + r.face, new int[] {r.at[0], r.at[1], r.at[0] + r.w, r.at[1] + r.h});
		}

		ImageIO.write(tex, "png", out.resolve("textures/" + file + ".png").toFile());
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(tex, "png", png);
		String json = bbmodel(file, g, boxes, uv, texW, texH, Base64.getEncoder().encodeToString(png.toByteArray()));
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

	static String bbmodel(String name, Grid g, List<Box> boxes, Map<Long, int[]> uv, int texW, int texH, String png) {
		// Blockbench units: the organ files one per voxel, centred on x and z, standing on y = 0; the mod's in the
		// sheep's own pixels, where the vanilla model puts its body.
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
			// The vanilla sheep's body, head and legs, hidden: show the group to see where the organs sit in it.
			double[][] ref = {{-4, 12, -8, 4, 18, 8}, {-3, 16, -14, 3, 22, -6}, {-5, 0, 5, -1, 12, 9}, {1, 0, 5, 5, 12, 9}, {-5, 0, -7, -1, 12, -3},
					{1, 0, -7, 5, 12, -3}};
			String[] refNames = {"body", "head", "right_hind_leg", "left_hind_leg", "right_front_leg", "left_front_leg"};
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

			ol.append(",\n{\"name\":\"vanilla_sheep_reference\",\"origin\":[0,0,0],\"color\":7,\"uuid\":\"").append(uuid(name + "/reference"))
					.append("\",\"export\":false,\"mirror_uv\":false,\"isOpen\":false,\"locked\":true,\"visibility\":false,\"autouv\":0,\"children\":[")
					.append(kids).append("]}");
		}

		return "{\"meta\":{\"format_version\":\"4.10\",\"model_format\":\"free\",\"box_uv\":false},\n\"name\":\"" + name + "\",\"model_identifier\":\"\","
				+ "\"visible_box\":[1,1,0],\"variable_placeholders\":\"\",\"variable_placeholder_buttons\":[],\"timeline_setups\":[],"
				+ "\"unhandled_root_fields\":{},\n\"resolution\":{\"width\":" + texW + ",\"height\":" + texH + "},\n\"elements\":[\n" + el + "\n],\n"
				+ "\"outliner\":[\n" + ol + "\n],\n\"textures\":[{\"path\":\"\",\"name\":\"" + name + ".png\",\"folder\":\"\",\"namespace\":\"\","
				+ "\"id\":\"0\",\"group\":\"\",\"width\":" + texW + ",\"height\":" + texH + ",\"uv_width\":" + texW + ",\"uv_height\":" + texH
				+ ",\"particle\":false,\"use_as_default\":false,\"layers_enabled\":false,\"sync_to_project\":\"\",\"render_mode\":\"default\","
				+ "\"render_sides\":\"auto\",\"pbr_channel\":\"color\",\"frame_time\":1,\"frame_order_type\":\"loop\",\"frame_order\":\"\","
				+ "\"frame_interpolate\":false,\"visible\":true,\"internal\":true,\"saved\":false,\"uuid\":\"" + uuid(name + "/texture")
				+ "\",\"relative_path\":\"textures/" + name + ".png\",\"source\":\"data:image/png;base64," + png + "\"}]\n}\n";
	}

	static void outline(StringBuilder sb, String model, Node n) {
		double w = Math.max(n.sw, 1e-9);
		sb.append("{\"name\":\"").append(n.name).append("\",\"origin\":[").append(num(n.sx / w)).append(',').append(num(n.sy / w)).append(',')
				.append(num(n.sz / w)).append("],\"color\":0,\"uuid\":\"").append(uuid(model + "/group/" + n.path))
				.append("\",\"export\":true,\"mirror_uv\":false,\"isOpen\":false,\"locked\":false,\"visibility\":true,\"autouv\":0,\"children\":[");
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
		boolean flipped = SCALE[0] < 0;
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
				# Sheep anatomy at %1$s

				The same veterinary model of a ewe as `../sheep_anatomy`, at the scale of the game: squashed into the vanilla sheep's
				8x6x16 body, inside its fat and muscle, cut into voxels of 16/%2$s of a pixel and drawn with the mod's own
				`anatomy.png` tiles, exactly as the game draws them. %3$s

				| File | What | Cubes |
				|---|---|---|
				%4$s

				- Blockbench units are the vanilla sheep's own model pixels: the body is x -4..4, 12..18 up, z -8..8, the head
				  towards north (-Z). As in the game's model, the sheep's right is -X.
				- The `vanilla_sheep_reference` group (hidden) is the vanilla body, head and legs: show it to see where the organs sit.
				- Every cube is named by its tissue; groups are organs and their parts, as in the full-size files.
				- At this grain the thinnest vessels and ducts may be left out, and %5$s.
				""".formatted(res, res.replace("x", ""), res.equals(MOD_RES + "x")
						? "The mod's ruminant trunk (`src/main/java/dev/fluidgore/anatomy/SheepTrunk.java`) is generated from `sheep_trunk_" + res + "`."
						: "The mod carries it too (`SheepTrunk." + (res.equals("16x") ? "coarse" : "fine") + "()`), to compare detail with the " + MOD_RES + "x grain in the `resolution` demo.", String.join("\n", rows),
				res.equals("16x") ? "only every fourth rib is kept, so a gap shows between them" : res.equals("32x") ? "only every second rib is kept, so a gap shows between them" : "every rib is kept");
	}

	static String readme(List<String> rows) {
		return """
				# Sheep anatomy models

				Blockbench models of a ewe's insides, generated by `tools/SheepAnatomyModels.java` (re-run it to rebuild everything:
				`java tools/SheepAnatomyModels.java art/sheep_anatomy`). Open any `.bbmodel` in Blockbench (File > Open Model, or
				drag it onto the window). The texture is embedded in each file; a copy is in `textures/`. `previews/` has a render
				of each model from four sides.

				| File | What | Cubes | Voxel | Size |
				|---|---|---|---|---|
				%s

				## Scale and orientation

				- One Blockbench unit is one voxel: 0.5 cm in the organ files (2 px per cm, so the liver is about 50 px across), 1 cm
				  in `sheep_in_situ`.
				- Every organ sits exactly where it lies in a standing sheep, so the organ files are the same pieces as the whole
				  body, just cut finer. The sheep faces **north** (-Z). +X is the sheep's right, +Y is up.
				- Each organ is a group; its lobes, chambers, loops and vessels are subgroups, and every cube is named after its
				  tissue. Hide a group with its eye icon to look inside (hide `skeleton` in `sheep_in_situ` first).

				## What to look for

				- **Liver**: one plate moulded on the diaphragm, lying obliquely on the right; thin ventral border low on the left,
				  thick dorsal border high on the right with the caudal vena cava sunk in it. Left, quadrate, right and caudate
				  lobes (subgroups), the notch of the round ligament, the green gallbladder, the caudate process cupping the right
				  kidney, the portal vein at the porta. Dents from the reticulum, omasum and kidney on its visceral face.
				- **Heart**: blunt cone, base up and forward, apex down, back and to the left; the white collar of coronary fat and
				  fat down both interventricular grooves; auricles folded round the aorta and pulmonary trunk; cut vessels show their
				  lumen.
				- **Lungs**: ruminant lobation. Right: cranial lobe in two parts with its own tracheal bronchus, middle, caudal,
				  accessory. Left: cranial lobe in two parts, caudal. Cardiac notches leave the heart bare low on both sides.
				- **Kidneys**: smooth bean shape (not lobed like a cow's), hilus inwards, in suet; the right one's suet peeled back.
				- **Stomachs**: rumen with dorsal and ventral sacs, blind sacs and grooves (fat and vessels in the grooves);
				  windows in the rumen and reticulum show papillae, honeycomb and the fibrous mat of cud. Omasum and abomasum on
				  the right.
				- **Intestines**: the spiral colon as a flat double coil in its mesentery (outgoing coils beading into pellets), the
				  jejunum as a garland of loops round its rim (one subgroup per loop), duodenum, ileum, caecum, colon and rectum.
				- **Skeleton**: 13 thoracic (tall spines at the withers), 6 lumbar (long transverse processes), sacrum, tail root;
				  13 pairs of ribs (8 sternal, 4 asternal on the costal arch, 1 floating) with costal cartilage; 7 sternebrae and
				  the xiphoid; shoulder blades; pelvis with the obturator foramen.

				## Sources

				Shapes and topography follow standard veterinary anatomy of the sheep (Dyce, Sack & Wensing, *Textbook of Veterinary
				Anatomy*, ruminant abdomen and thorax chapters; Popesko, *Atlas of Topographical Anatomy of the Domestic Animals*).
				Proportions are scaled to a ewe of about 65 kg and simplified to blocks: they are meant to read correctly at a glance,
				not to be measured.
				""".formatted(String.join("\n", rows));
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
