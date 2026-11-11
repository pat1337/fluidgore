import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Generates the procedural textures shipped with Fluid Gore. Run from the project root with:
 * <pre>java tools/GenerateTextures.java src/main/resources/assets/fluidgore</pre>
 * Output is deterministic (fixed seeds), so re-running produces identical PNGs.
 *
 * <p>blood.png is an 8x8 atlas of 64px tiles. Shapes are drawn white/grey with alpha; the game tints them with
 * the vertex colour, so one atlas serves every blood colour. Tile order must match BloodTextures.
 *
 * <p>The shapes of its marks (the first {@link #MARK_TILES} tiles) are also written out as a Java table,
 * {@code blood/MarkMasks.java}, which the mod cuts marks into block pixels from. When the output folder is the
 * project's own assets folder the table goes into the mod's sources; anywhere else it is written beside the textures.
 */
public final class GenerateTextures {
	private static final int TILE = 64;
	/** Tiles along one side of blood.png. */
	private static final int GRID = 8;
	/** The first tiles of blood.png are marks with a shape of their own: splats, a drop, prints. */
	private static final int MARK_TILES = 14;

	/** A soft circle contributing to a metaball field. */
	private record Ball(double x, double y, double r) {
	}

	public static void main(String[] args) throws IOException {
		File root = new File(args.length > 0 ? args[0] : "src/main/resources/assets/fluidgore");
		File misc = new File(root, "textures/misc");
		if (!misc.isDirectory() && !misc.mkdirs()) {
			throw new IOException("Cannot create " + misc);
		}

		BufferedImage atlas = new BufferedImage(TILE * GRID, TILE * GRID, BufferedImage.TYPE_INT_ARGB);
		for (int i = 0; i < 4; i++) {
			drawField(atlas, i, splat(new Random(1000 + i)), 0.5, new Random(2000 + i));
		}

		drawField(atlas, 6, List.of(new Ball(0.5, 0.5, 0.36)), 0.5, new Random(5000));
		drawField(atlas, 7, footprint(), 0.5, new Random(6000));
		drawField(atlas, 9, smallSplat(new Random(8000)), 0.5, new Random(8001));
		// The prints of other feet: a cloven hoof, a horse's hoof, a paw, a bird's foot.
		drawField(atlas, 10, clovenPrint(), 0.5, new Random(6010));
		drawField(atlas, 11, hoofPrint(), 0.5, new Random(6020));
		drawField(atlas, 12, pawPrint(), 0.5, new Random(6030));
		drawField(atlas, 13, birdPrint(), 0.5, new Random(6040));
		// The fluid's own tiles: each covers its whole tile and repeats without a seam, one tile to a block. One set for
		// each resolution blood can be drawn at: 64 pixels to a block from tile 16, 32 from tile 24, 16 from tile 32.
		int[] fine = {64, 32, 16};
		for (int i = 0; i < fine.length; i++) {
			int first = 16 + i * 8;
			drawFilm(atlas, first, fine[i], new Random(9500));
			drawStain(atlas, first + 1, fine[i], new Random(9510));
			drawCrust(atlas, first + 2, fine[i], new Random(9520));
			drawRibbon(atlas, first + 4, fine[i], new Random(9530));
			drawSpark(atlas, first + 5, fine[i], new Random(9540));
		}

		drawFlat(atlas, 19);
		ImageIO.write(atlas, "png", new File(misc, "blood.png"));
		writeMarkMasks(atlas, root);
		ImageIO.write(wounds(), "png", new File(misc, "wounds.png"));
		ImageIO.write(flesh(), "png", new File(misc, "flesh.png"));
		ImageIO.write(hurt(), "png", new File(misc, "hurt.png"));
		ImageIO.write(feathers(), "png", new File(misc, "feather.png"));
		ImageIO.write(anatomy(), "png", new File(misc, "anatomy.png"));
		ImageIO.write(waterCloud(), "png", new File(misc, "water_cloud.png"));

		File block = new File(root, "textures/block");
		if (!block.isDirectory() && !block.mkdirs()) {
			throw new IOException("Cannot create " + block);
		}

		File item = new File(root, "textures/item");
		if (!item.isDirectory() && !item.mkdirs()) {
			throw new IOException("Cannot create " + item);
		}

		for (int stage = 1; stage <= 3; stage++) {
			ImageIO.write(spongeBlood(stage), "png", new File(block, "sponge_blood_" + stage + ".png"));
			ImageIO.write(brushBlood(stage), "png", new File(item, "brush_blood_" + stage + ".png"));
			ImageIO.write(bucketBlood(stage), "png", new File(item, "bucket_blood_" + stage + ".png"));
		}

		ImageIO.write(icon(), "png", new File(root, "icon.png"));
		System.out.println("Wrote textures to " + root.getAbsolutePath());
	}

	/**
	 * Blood soaked into a sponge (3.1), laid over each face of the vanilla sponge (16x16, clear where clean), light grey
	 * so the blood's own colour tints it. Stage 1: blotches; stage 2: most of the face; stage 3: all of it, darker where it
	 * pools in the pores. Same seed for every stage, so each keeps the last one's stains.
	 */
	private static BufferedImage spongeBlood(int stage) {
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Random random = new Random(13_000);
		double[][] field = new double[16][16];
		for (int i = 0; i < 9; i++) {
			double cx = random.nextDouble() * 16.0;
			double cy = random.nextDouble() * 16.0;
			double r = 2.5 + random.nextDouble() * 3.0;
			for (int y = 0; y < 16; y++) {
				for (int x = 0; x < 16; x++) {
					double d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
					field[y][x] += Math.max(0.0, 1.0 - d / r);
				}
			}
		}

		double threshold = stage == 1 ? 0.95 : stage == 2 ? 0.3 : -1.0;
		Random grain = new Random(13_100);
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double pore = grain.nextDouble();
				if (field[y][x] + pore * 0.25 > threshold) {
					// Pores hold more: darker specks through the stain.
					int shade = pore < 0.2 ? 120 : 190 + (int) (pore * 60.0);
					image.setRGB(x, y, argb(stage == 1 ? 210 : 235, shade, shade, shade));
				}
			}
		}

		return image;
	}

/** Which pixels of the vanilla bucket (16x16) show what is in it: where the water of a water bucket is drawn. */
private static final String[] BUCKET_MOUTH = {
		"................",
		"................",
		"................",
		"....########....",
		"...##########...",
		".....######.....",
		"................",
		"................",
		"................",
		"................",
		"................",
		"................",
		"................",
		"................",
		"................",
		"................"};

/**
 * Blood in a bucket, over the vanilla bucket as a second layer, light grey for the blood colour to tint. It shows in
 * the bucket's mouth: stage 1 a little at the bottom of it, stage 2 most of it, stage 3 to the brim.
 */
private static BufferedImage bucketBlood(int stage) {
	BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
	Random random = new Random(13_300);
	int top = stage == 1 ? 5 : stage == 2 ? 4 : 3;
	for (int y = 0; y < 16; y++) {
		for (int x = 0; x < 16; x++) {
			int shade = 185 + random.nextInt(40) + (y == top ? 25 : 0);
			if (BUCKET_MOUTH[y].charAt(x) == '#' && y >= top) {
				image.setRGB(x, y, argb(255, shade, shade, shade));
			}
		}
	}

	return image;
}

/** Which pixels of the vanilla brush (16x16) are its bristles: their outline included, the handle and ferrule not. */
	private static final String[] BRISTLES = {
			"................",
			".........###....",
			"........#######.",
			".......########.",
			"......########..",
			"......#########.",
			"......#########.",
			".....#########..",
			".......######...",
			"................",
			"................",
			"................",
			"................",
			"................",
			"................",
			"................"};

	/**
	 * Blood in a brush's bristles (3.1), over the vanilla brush as a second layer, light grey for the blood colour to
	 * tint. It soaks up from the tips: stage 1 the tips, stage 2 half the bristles, stage 3 all of them and a run down onto
	 * the ferrule.
	 */
	private static BufferedImage brushBlood(int stage) {
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Random random = new Random(13_200);
		int reach = stage == 1 ? 6 : stage == 2 ? 3 : -2;
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				int shade = 170 + random.nextInt(70);
				// The tips are up and to the right; the bristles run down and left into the ferrule.
				if (BRISTLES[y].charAt(x) == '#' && x - y >= reach + random.nextInt(2)) {
					image.setRGB(x, y, argb(255, shade, shade, shade));
				}
			}
		}

		if (stage == 3) {
			// Run down over the ferrule.
			int[][] drips = {{5, 8}, {6, 8}, {5, 9}, {6, 9}, {4, 10}};
			for (int[] d : drips) {
				image.setRGB(d[0], d[1], argb(255, 150, 150, 150));
			}
		}

		return image;
	}

	/** Central blob with satellite droplets and a few radial spikes, like a drop that hit the ground hard. */
	private static List<Ball> splat(Random random) {
		List<Ball> balls = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			balls.add(new Ball(0.5 + gauss(random) * 0.07, 0.5 + gauss(random) * 0.07, 0.12 + random.nextDouble() * 0.08));
		}

		int spikes = 5 + random.nextInt(5);
		for (int s = 0; s < spikes; s++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double length = 0.22 + random.nextDouble() * 0.2;
			for (double t = 0.15; t < length; t += 0.035) {
				double r = 0.045 * (1.0 - t / length) + 0.012;
				balls.add(new Ball(0.5 + Math.cos(angle) * t, 0.5 + Math.sin(angle) * t, r));
			}
		}

		for (int d = 0; d < 10; d++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double dist = 0.28 + random.nextDouble() * 0.17;
			balls.add(new Ball(0.5 + Math.cos(angle) * dist, 0.5 + Math.sin(angle) * dist, 0.012 + random.nextDouble() * 0.025));
		}

		return balls;
	}

	/** A shoe print pointing up (toe at the top of the tile = low V): the sole, and the heel behind it. */
	private static List<Ball> footprint() {
		List<Ball> balls = new ArrayList<>();
		for (double y = 0.16; y <= 0.44; y += 0.04) {
			double width = 0.145 - Math.abs(y - 0.3) * 0.14;
			balls.add(new Ball(0.5 - width * 0.3, y, width));
			balls.add(new Ball(0.5 + width * 0.3, y, width));
		}

		for (double y = 0.76; y <= 0.84; y += 0.04) {
			balls.add(new Ball(0.5, y, 0.1));
		}

		return balls;
	}

	/**
	 * The print of a cloven hoof (a pig, a cow, a sheep, a goat) pointing up the tile: two claws side by side, each
	 * rounded at the heel and drawn to a blunt point at the toe, with a narrow gap between them.
	 */
	private static List<Ball> clovenPrint() {
		List<Ball> balls = new ArrayList<>();
		for (int side = -1; side <= 1; side += 2) {
			for (double t = 0.0; t <= 1.0; t += 0.05) {
				// From the toe (the top) to the heel: widest two thirds of the way back.
				double width = 0.03 + 0.07 * Math.sin(Math.PI * Math.pow(t, 0.75));
				balls.add(new Ball(0.5 + side * (0.075 + width * 0.85), 0.2 + t * 0.58, width));
			}
		}

		return balls;
	}

	/** The print of a horse's hoof pointing up the tile: the wall of the hoof as an arch, open at the heel. */
	private static List<Ball> hoofPrint() {
		List<Ball> balls = new ArrayList<>();
		for (double angle = -140.0; angle <= 140.0; angle += 5.0) {
			double a = Math.toRadians(angle);
			balls.add(new Ball(0.5 + Math.sin(a) * 0.21, 0.52 - Math.cos(a) * 0.25, 0.06 - 0.02 * Math.abs(angle) / 140.0));
		}

		// The frog: the wedge in the middle of the sole, towards the heel.
		for (double t = 0.0; t <= 1.0; t += 0.25) {
			balls.add(new Ball(0.5, 0.56 + t * 0.14, 0.03 + 0.03 * t));
		}

		return balls;
	}

	/** A paw print pointing up the tile: the pad and four toes over it. */
	private static List<Ball> pawPrint() {
		List<Ball> balls = new ArrayList<>();
		balls.add(new Ball(0.5, 0.62, 0.12));
		balls.add(new Ball(0.41, 0.67, 0.09));
		balls.add(new Ball(0.59, 0.67, 0.09));
		balls.add(new Ball(0.26, 0.42, 0.062));
		balls.add(new Ball(0.4, 0.28, 0.062));
		balls.add(new Ball(0.6, 0.28, 0.062));
		balls.add(new Ball(0.74, 0.42, 0.062));
		return balls;
	}

	/** A bird's footprint pointing up the tile: three toes spread forward and a short one behind. */
	private static List<Ball> birdPrint() {
		List<Ball> balls = new ArrayList<>();
		for (double angle : new double[]{-38.0, 0.0, 38.0, 180.0}) {
			double a = Math.toRadians(angle);
			double length = angle == 180.0 ? 0.17 : 0.37;
			for (double t = 0.0; t <= length; t += 0.02) {
				balls.add(new Ball(0.5 + Math.sin(a) * t, 0.6 - Math.cos(a) * t, 0.036 - 0.014 * t / length));
			}
		}

		return balls;
	}

	private static List<Ball> smallSplat(Random random) {
		List<Ball> balls = new ArrayList<>();
		balls.add(new Ball(0.5, 0.5, 0.18));
		for (int d = 0; d < 6; d++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double dist = 0.18 + random.nextDouble() * 0.2;
			balls.add(new Ball(0.5 + Math.cos(angle) * dist, 0.5 + Math.sin(angle) * dist, 0.03 + random.nextDouble() * 0.04));
		}

		return balls;
	}

	/**
	 * Rasterises a metaball field into one tile: alpha is a smooth threshold of the field, brightness gets
	 * gentle noise plus a highlight near the thick centre so the blood looks wet.
	 */
	private static void drawField(BufferedImage image, int tile, List<Ball> balls, double threshold, Random noiseSeed) {
		int ox = (tile % GRID) * TILE;
		int oy = (tile / GRID) * TILE;
		double[] noise = valueNoise(noiseSeed);
		for (int py = 0; py < TILE; py++) {
			for (int px = 0; px < TILE; px++) {
				double x = (px + 0.5) / TILE;
				double y = (py + 0.5) / TILE;
				// Keep a transparent 1px border so nothing touches the tile edge.
				if (px == 0 || py == 0 || px == TILE - 1 || py == TILE - 1) {
					image.setRGB(ox + px, oy + py, 0);
					continue;
				}

				double field = 0.0;
				for (Ball b : balls) {
					double dx = x - b.x();
					double dy = y - b.y();
					field += kernel(b, dx, dy);
				}

				double alpha = smoothstep(threshold * 0.8, threshold * 1.25, field);
				if (alpha <= 0.0) {
					image.setRGB(ox + px, oy + py, 0);
					continue;
				}

				double thick = Math.min(1.0, (field - threshold) / (threshold * 3.0));
				double light = 0.72 + 0.18 * noise[py * TILE + px] + 0.1 * Math.max(0.0, thick);
				image.setRGB(ox + px, oy + py, argb(alpha, light));
			}
		}
	}

	/**
	 * Value noise over one tile that repeats at the tile's edges, in [-1, 1]: {@code grid} lattice cells across, the
	 * last wrapping onto the first.
	 */
	private static double[] wrapNoise(Random random, int grid) {
		double[] lattice = new double[grid * grid];
		for (int i = 0; i < lattice.length; i++) {
			lattice[i] = random.nextDouble() * 2.0 - 1.0;
		}

		double[] out = new double[TILE * TILE];
		for (int py = 0; py < TILE; py++) {
			for (int px = 0; px < TILE; px++) {
				double gx = (px + 0.5) / TILE * grid;
				double gy = (py + 0.5) / TILE * grid;
				int ix = (int) gx;
				int iy = (int) gy;
				double fx = smoothstep(0.0, 1.0, gx - ix);
				double fy = smoothstep(0.0, 1.0, gy - iy);
				double a = lattice[iy % grid * grid + ix % grid];
				double b = lattice[iy % grid * grid + (ix + 1) % grid];
				double c = lattice[(iy + 1) % grid * grid + ix % grid];
				double d = lattice[(iy + 1) % grid * grid + (ix + 1) % grid];
				out[py * TILE + px] = lerp(fy, lerp(fx, a, b), lerp(fx, c, d));
			}
		}

		return out;
	}

	/** One pixel of a tile drawn at a chosen resolution: its colour from where its middle is, 0 to 1 across the tile. */
	private interface FineTexel {
		int at(double x, double y);
	}

	/** Fills a tile of blood.png with {@code res} by {@code res} pixels, each as many of the tile's own as it takes. */
	private static void drawTexels(BufferedImage image, int tile, int res, FineTexel texel) {
		int ox = (tile % GRID) * TILE;
		int oy = (tile / GRID) * TILE;
		int step = TILE / res;
		for (int ty = 0; ty < res; ty++) {
			for (int tx = 0; tx < res; tx++) {
				int argb = texel.at((tx + 0.5) / res, (ty + 0.5) / res);
				for (int py = 0; py < step; py++) {
					for (int px = 0; px < step; px++) {
						image.setRGB(ox + tx * step + px, oy + ty * step + py, argb);
					}
				}
			}
		}
	}

	/** {@code noise} (one tile of it, see {@link #wrapNoise}) at (x, y), 0 to 1 across the tile. */
	private static double sample(double[] noise, double x, double y) {
		return noise[Math.min(TILE - 1, (int) (y * TILE)) * TILE + Math.min(TILE - 1, (int) (x * TILE))];
	}

	/** Rounds to steps of {@code step} when drawing in pixels a block texture's size, so shades band as hand-set pixels do. */
	private static double banded(double value, double step, int res) {
		return res >= TILE ? value : Math.round(value / step) * step;
	}

	/**
	 * The surface of standing blood: all of the tile, nearly even, with slow mottling where it lies thicker or thinner.
	 * At the lower resolutions each pixel also gets a shade of its own, as the pixels of a block texture have.
	 */
	private static void drawFilm(BufferedImage image, int tile, int res, Random random) {
		double[] broad = wrapNoise(random, 3);
		double[] fine = wrapNoise(random, 9);
		Random grain = new Random(9501 + res);
		double rough = res >= TILE ? 0.0 : res == 32 ? 0.05 : 0.08;
		drawTexels(image, tile, res, (x, y) -> {
			double light = 0.88 + 0.07 * sample(broad, x, y) + 0.035 * sample(fine, x, y) + rough * (grain.nextDouble() * 2.0 - 1.0);
			return argb(1.0, banded(light, 0.05, res));
		});
	}

	/** Blood soaked into a floor: all of the tile, blotchy, thinner in places so the floor's own grain shows through. */
	private static void drawStain(BufferedImage image, int tile, int res, Random random) {
		double[] broad = wrapNoise(random, 4);
		double[] fine = wrapNoise(random, 16);
		Random grain = new Random(9511 + res);
		double rough = res >= TILE ? 0.0 : res == 32 ? 0.08 : 0.12;
		drawTexels(image, tile, res, (x, y) -> {
			double speck = rough * (grain.nextDouble() * 2.0 - 1.0);
			double alpha = 0.78 + 0.14 * sample(broad, x, y) + 0.08 * sample(fine, x, y) + speck;
			double light = 0.86 + 0.08 * sample(broad, x, y) + 0.05 * sample(fine, x, y) + speck * 0.4;
			return argb(Math.max(0.45, banded(alpha, 0.1, res)), banded(light, 0.05, res));
		});
	}

	/**
	 * The cracks of a dried pool: a dried sheet of blood shrinks and splits into plates. The lines between the cells of
	 * scattered points, repeated past the tile's edges so the cracks run on into the next tile. Clear between the cracks.
	 */
	private static void drawCrust(BufferedImage image, int tile, int res, Random random) {
		int points = 15;
		double[] xs = new double[points];
		double[] ys = new double[points];
		for (int i = 0; i < points; i++) {
			xs[i] = random.nextDouble();
			ys[i] = random.nextDouble();
		}

		double[] fine = wrapNoise(random, 12);
		drawTexels(image, tile, res, (x, y) -> {
			double nearest = 9.0;
			double second = 9.0;
			for (int i = 0; i < points; i++) {
				for (int wy = -1; wy <= 1; wy++) {
					for (int wx = -1; wx <= 1; wx++) {
						double d = Math.hypot(x - xs[i] - wx, y - ys[i] - wy);
						if (d < nearest) {
							second = nearest;
							nearest = d;
						} else if (d < second) {
							second = d;
						}
					}
				}
			}

			// Thin where two plates meet, a little ragged. In coarse pixels a crack is a pixel wide or it is not there.
			double edge = second - nearest;
			if (res < TILE) {
				return edge < (res == 32 ? 0.034 : 0.06) ? argb(1.0, 1.0) : 0;
			}

			double alpha = 1.0 - smoothstep(0.012, 0.03 + 0.012 * sample(fine, x, y), edge);
			return alpha <= 0.04 ? 0 : argb(alpha, 1.0);
		});
	}

	/** Plain white: for anything that takes all of its look from its vertex colours. */
	private static void drawFlat(BufferedImage image, int tile) {
		drawTexels(image, tile, 1, (x, y) -> argb(1.0, 1.0));
	}

	/**
	 * Blood pouring down a face: strands side by side, each as it is all the way down, thinner between them. It repeats
	 * down and across.
	 */
	private static void drawRibbon(BufferedImage image, int tile, int res, Random random) {
		double[] strands = wrapNoise(random, 10);
		double[] fine = wrapNoise(random, 6);
		drawTexels(image, tile, res, (x, y) -> {
			// One row of the noise read across: the same strand at every height.
			double strand = sample(strands, x, 0.0);
			double alpha = 0.45 + 0.5 * smoothstep(-0.35, 0.3, strand) + (res >= TILE ? 0.05 * sample(fine, x, y) : 0.0);
			return argb(banded(alpha, 0.25, res), banded(0.88 + 0.1 * smoothstep(-0.2, 0.6, strand), 0.05, res));
		});
	}

	/**
	 * Highlights on wet blood: a scatter of small bright marks, each leaning the same way as light catching a liquid
	 * does, clear between them. In coarse pixels a mark is one bright pixel, some with a dimmer one beside it.
	 */
	private static void drawSpark(BufferedImage image, int tile, int res, Random random) {
		int marks = res == 16 ? 7 : res == 32 ? 12 : 16;
		double[] alpha = new double[res * res];
		for (int i = 0; i < marks; i++) {
			int x = random.nextInt(res);
			int y = random.nextInt(res);
			boolean pair = random.nextBoolean();
			if (res < TILE) {
				alpha[y * res + x] = 1.0;
				if (pair) {
					int beside = Math.floorMod(y - 1, res) * res + Math.floorMod(x + 1, res);
					alpha[beside] = Math.max(0.55, alpha[beside]);
				}

				continue;
			}

			// A soft streak a few pixels long, up and to the right.
			double length = pair ? 3.0 : 1.5;
			for (int py = -6; py <= 6; py++) {
				for (int px = -6; px <= 6; px++) {
					double along = (px - py) / Math.sqrt(2.0);
					double across = (px + py) / Math.sqrt(2.0);
					double a = Math.exp(-(along * along) / (length * length) - (across * across) / 1.1);
					int at = Math.floorMod(y + py, res) * res + Math.floorMod(x + px, res);
					alpha[at] = Math.max(alpha[at], a);
				}
			}
		}

		drawTexels(image, tile, res, (x, y) -> {
			double a = alpha[Math.min(res - 1, (int) (y * res)) * res + Math.min(res - 1, (int) (x * res))];
			return a < 0.12 ? 0 : argb(a, 1.0);
		});
	}

	/**
	 * Writes {@code blood/MarkMasks.java}: for each mark tile, which of its pixels are blood (at least half opaque), one
	 * long a row with bit x set for the pixel at x.
	 */
	private static void writeMarkMasks(BufferedImage atlas, File root) throws IOException {
		StringBuilder out = new StringBuilder();
		out.append("package dev.fluidgore.blood;\n\n");
		out.append("/**\n");
		out.append(" * GENERATED by tools/GenerateTextures.java; do not edit. The shapes of the marks in blood.png (its first tiles):\n");
		out.append(" * which of a tile's pixels are blood, one long a row, bit x set for the pixel at x. {@link MarkPixels} cuts marks\n");
		out.append(" * into the game's own pixels from them.\n");
		out.append(" */\n");
		out.append("final class MarkMasks {\n");
		out.append("\t/** Pixels along one side of a tile. */\n");
		out.append("\tstatic final int SIZE = ").append(TILE).append(";\n");
		out.append("\tstatic final long[][] ROWS = {\n");
		for (int tile = 0; tile < MARK_TILES; tile++) {
			int ox = (tile % GRID) * TILE;
			int oy = (tile / GRID) * TILE;
			out.append("\t\t{");
			for (int py = 0; py < TILE; py++) {
				long row = 0L;
				for (int px = 0; px < TILE; px++) {
					if ((atlas.getRGB(ox + px, oy + py) >>> 24) >= 128) {
						row |= 1L << px;
					}
				}

				out.append(py == 0 ? "" : py % 4 == 0 ? ",\n\t\t\t" : ", ").append(String.format("0x%016XL", row));
			}

			out.append(tile == MARK_TILES - 1 ? "}\n" : "},\n");
		}

		out.append("\t};\n\n");
		out.append("\tprivate MarkMasks() {\n\t}\n}\n");
		String path = root.getPath().replace('\\', '/');
		File table = path.endsWith("src/main/resources/assets/fluidgore") ? new File(root, "../../../java/dev/fluidgore/blood/MarkMasks.java")
				: new File(root, "MarkMasks.java");
		java.nio.file.Files.writeString(table.toPath(), out.toString());
	}

	private static BufferedImage icon() {
		BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
		List<Ball> drop = new ArrayList<>();
		// A blood drop: round bottom, tapering to a point at the top.
		for (double t = 0.0; t <= 1.0; t += 0.02) {
			drop.add(new Ball(0.5, 0.18 + t * 0.42, 0.015 + t * t * 0.11));
		}

		drop.add(new Ball(0.5, 0.64, 0.2));
		List<Ball> pool = splat(new Random(9000));
		for (int py = 0; py < 128; py++) {
			for (int px = 0; px < 128; px++) {
				double x = (px + 0.5) / 128.0;
				double y = (py + 0.5) / 128.0;
				double fDrop = field(drop, x, y);
				double fPool = field(pool, (x - 0.5) * 1.3 + 0.5, (y - 0.86) * 3.2 + 0.5);
				double aDrop = smoothstep(0.4, 0.62, fDrop);
				double aPool = smoothstep(0.4, 0.62, fPool) * 0.95;
				double alpha = Math.max(aDrop, aPool);
				if (alpha <= 0.0) {
					continue;
				}

				// Deep red with a highlight on the upper left of the drop.
				double hx = x - 0.43;
				double hy = y - 0.55;
				double highlight = aDrop > 0.5 ? Math.max(0.0, 1.0 - Math.sqrt(hx * hx + hy * hy) / 0.08) : 0.0;
				int r = (int) Math.min(255, 150 + highlight * 105);
				int g = (int) Math.min(255, 8 + highlight * 140);
				int b = (int) Math.min(255, 10 + highlight * 140);
				image.setRGB(px, py, ((int) (alpha * 255) << 24) | (r << 16) | (g << 8) | b);
			}
		}

		return image;
	}

	private static double field(List<Ball> balls, double x, double y) {
		double f = 0.0;
		for (Ball b : balls) {
			double dx = x - b.x();
			double dy = y - b.y();
			f += kernel(b, dx, dy);
		}

		return f;
	}

	private static void fillCircle(boolean[][] mask, double cx, double cy, double r) {
		for (int y = 0; y < mask.length; y++) {
			for (int x = 0; x < mask[y].length; x++) {
				double dx = x + 0.5 - (cx + 0.5);
				double dy = y + 0.5 - (cy + 0.5);
				if (dx * dx + dy * dy <= r * r) {
					mask[y][x] = true;
				}
			}
		}
	}

	private static void set(boolean[][] mask, int x, int y) {
		if (x >= 1 && y >= 1 && y < mask.length - 1 && x < mask[y].length - 1) {
			mask[y][x] = true;
		}
	}

	/**
	 * Compact metaball kernel (1 - d^2/R^2)^2 with support R = 1.85 r, which crosses 0.5 at distance r from the
	 * centre. Compact support keeps many small balls from summing into one big blob.
	 */
	private static double kernel(Ball b, double dx, double dy) {
		double support = b.r() * 1.85;
		double q = (dx * dx + dy * dy) / (support * support);
		return q >= 1.0 ? 0.0 : (1.0 - q) * (1.0 - q);
	}

	/** Smoothed 8x8 value noise over one tile, values in [-1, 1]. */
	private static double[] valueNoise(Random random) {
		int grid = 8;
		double[] lattice = new double[(grid + 1) * (grid + 1)];
		for (int i = 0; i < lattice.length; i++) {
			lattice[i] = random.nextDouble() * 2.0 - 1.0;
		}

		double[] out = new double[TILE * TILE];
		for (int py = 0; py < TILE; py++) {
			for (int px = 0; px < TILE; px++) {
				double gx = px / (double) TILE * grid;
				double gy = py / (double) TILE * grid;
				int ix = (int) gx;
				int iy = (int) gy;
				double fx = smoothstep(0.0, 1.0, gx - ix);
				double fy = smoothstep(0.0, 1.0, gy - iy);
				double a = lattice[iy * (grid + 1) + ix];
				double b = lattice[iy * (grid + 1) + ix + 1];
				double c = lattice[(iy + 1) * (grid + 1) + ix];
				double d = lattice[(iy + 1) * (grid + 1) + ix + 1];
				out[py * TILE + px] = lerp(fy, lerp(fx, a, b), lerp(fx, c, d));
			}
		}

		return out;
	}



	/**
	 * wounds.png: an 8x21 grid of 16px pixel-art tiles, greyscale with hard alpha; the game tints each by the body's
	 * flesh and blood. Eight variants per row.
	 * <ul>
	 * <li>Rows 0-3, round wounds (lacerations from fists, clubs, teeth, points): bruise (a soft, dithered flush), gash,
	 *     open meat, bone.</li>
	 * <li>Rows 4-8, slashes (incised and chop wounds), long and narrow along the cut: bruised margin, the yellow rim of
	 *     fat under the skin, the dark split, muscle in the depth, a line of bone. Column {@code c} runs at
	 *     {@code (c % 4) * 45} degrees, so the game can lay a slash along the swing.</li>
	 * <li>Rows 9-10, rot (zombies): beads of pus, and maggots.</li>
	 * <li>Row 11, plucked skin: bare, goose-pimpled bird skin dotted with the pits the feathers came out of, its edge
	 *     ragged where feathers are left.</li>
	 * <li>Row 12, crushed bone: a caved-in skull or a snapped limb, splinters of bone at odd angles in dark blood.</li>
	 * <li>Row 13, stove-in ribs: broken rib ends, pale bars snapped and pushed in, over a dark hollow.</li>
	 * <li>Rows 14-17, the cut face of a piece of meat, as on a butcher's block: the whole face of muscle with its grain,
	 *     the marbling (veins and flecks of fat), a rim of fat along one edge (column % 4 says which), and wet smears of
	 *     blood (also the blood soaked into a sheep's wool).</li>
	 * <li>Row 18, blood running down the skin from a wound (see {@link #runTile}).</li>
	 * <li>Row 19, blood soaked into a coat, in eight nested levels (see {@link #coatSoakTiles}).</li>
	 * <li>Row 20, blood wicked up a coat from below to a ragged line (see {@link #coatTideTile}).</li>
	 * </ul>
	 */
	private static BufferedImage wounds() {
		BufferedImage image = new BufferedImage(128, 336, BufferedImage.TYPE_INT_ARGB);
		for (int v = 0; v < 8; v++) {
			for (int layer = 0; layer < 4; layer++) {
				woundTile(image, layer, v, new Random(11000 + layer * 1000 + v));
			}

			for (int sub = 0; sub < 5; sub++) {
				slashTile(image, sub, v, new Random(21000 + sub * 1000 + v));
			}

			rotTile(image, 0, v, new Random(31000 + v));
			rotTile(image, 1, v, new Random(32000 + v));
			skinTile(image, v, new Random(33000 + v));
			crushTile(image, v, new Random(34000 + v));
			ribsTile(image, v, new Random(35000 + v));
			grainTile(image, v, new Random(36000 + v));
			marbleTile(image, v, new Random(37000 + v));
			rimTile(image, v, new Random(38000 + v));
			stainTile(image, v, new Random(39000 + v));
			runTile(image, v, new Random(40000 + v));
			coatTideTile(image, v, new Random(42000 + v));
		}

		coatSoakTiles(image);
		return image;
	}

	/**
	 * Row 19, blood soaked into a coat (hide, wool or feathers): eight nested levels of the same mottled stain, column
	 * {@code k} covering about {@code (k + 1) / 8} of the tile. Every level holds all of the one before it, so a coat
	 * soaking up more blood only ever spreads its stain. One shared field, so the levels nest.
	 */
	private static void coatSoakTiles(BufferedImage image) {
		Random random = new Random(41000);
		double[] field = new double[256];
		// Blots of different sizes: where blood landed and spread through the hair.
		double[][] blots = new double[14][3];
		for (double[] blot : blots) {
			blot[0] = random.nextDouble() * 16.0;
			blot[1] = random.nextDouble() * 16.0;
			blot[2] = 1.5 + random.nextDouble() * 4.0;
		}

		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double best = 1.0;
				for (double[] blot : blots) {
					// Wrapped, so a stain cut anywhere from the tile has no hard seam.
					double dx = Math.min(Math.abs(x + 0.5 - blot[0]), 16.0 - Math.abs(x + 0.5 - blot[0]));
					double dy = Math.min(Math.abs(y + 0.5 - blot[1]), 16.0 - Math.abs(y + 0.5 - blot[1]));
					best = Math.min(best, Math.sqrt(dx * dx + dy * dy) / (blot[2] * 2.2));
				}

				field[y * 16 + x] = Math.min(0.999, best * 0.8 + random.nextDouble() * 0.2);
			}
		}

		// Rank the pixels, so level k holds exactly the (k + 1) / 8 most soaked of them.
		Integer[] order = new Integer[256];
		for (int i = 0; i < 256; i++) {
			order[i] = i;
		}

		java.util.Arrays.sort(order, (a, b) -> Double.compare(field[a], field[b]));
		int[] rank = new int[256];
		for (int i = 0; i < 256; i++) {
			rank[order[i]] = i;
		}

		for (int k = 0; k < 8; k++) {
			int ox = k * 16;
			int oy = 19 * 16;
			int covered = (k + 1) * 32;
			for (int i = 0; i < 256; i++) {
				// Darkest where it is thickest: the first pixels to take blood hold the most.
				double light = 0.62 + 0.38 * rank[i] / 255.0 - (random.nextDouble() < 0.15 ? 0.08 : 0.0);
				image.setRGB(ox + i % 16, oy + i / 16, rank[i] < covered ? argb(1.0, light) : 0);
			}
		}
	}

	/**
	 * Row 20, blood wicked up a coat from below (legs that stood in a pool, the side a body lay on): solid from the bottom
	 * of the tile to a ragged line in its top third, with fingers of blood climbing past it and spots above. The game
	 * lays the line at the height the blood has climbed to.
	 */
	private static void coatTideTile(BufferedImage image, int variant, Random random) {
		int ox = variant * 16;
		int oy = 20 * 16;
		double[] edge = new double[16];
		double level = 4.0 + random.nextDouble();
		for (int x = 0; x < 16; x++) {
			level = Math.max(2.5, Math.min(6.0, level + (random.nextDouble() - 0.5) * 1.6));
			edge[x] = level;
		}

		// A few fingers where the fibres drew it higher.
		for (int i = 0; i < 2 + random.nextInt(2); i++) {
			int x = random.nextInt(16);
			double up = 1.5 + random.nextDouble() * 2.0;
			edge[x] = Math.max(0.0, edge[x] - up);
			if (x + 1 < 16) {
				edge[x + 1] = Math.max(0.0, edge[x + 1] - up * 0.5);
			}
		}

		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				boolean wet = y >= edge[x] || y >= edge[x] - 2.0 && random.nextDouble() < 0.12;
				double light = 0.66 + random.nextDouble() * 0.22 - (y > 12 ? 0.1 : 0.0);
				image.setRGB(ox + x, oy + y, wet ? argb(1.0, light) : 0);
			}
		}
	}

	/** A 4x4 ordered-dither threshold matrix, 0-15. */
	private static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

	/**
	 * Row 18, blood running down skin from a wound: a sheet at the top where it leaves the wound, splitting into
	 * rivulets that narrow as they run and end in a bead. Columns 0-3 run down the tile, 4-7 across it (the same
	 * runs turned), so the game can lay one along either axis of a face.
	 */
	private static void runTile(BufferedImage image, int variant, Random random) {
		int ox = variant * 16;
		int oy = 18 * 16;
		int[] mask = new int[256];
		double[] shade = new double[256];
		// Two to four rivulets: where across the tile, how far down, how wide at the top.
		int count = 2 + random.nextInt(3);
		for (int k = 0; k < count; k++) {
			double centre = 2.5 + random.nextDouble() * 11.0;
			double reach = 7.0 + random.nextDouble() * 9.0;
			double width = 1.2 + random.nextDouble() * 2.2;
			double wander = (random.nextDouble() - 0.5) * 2.0;
			for (int y = 0; y < 16; y++) {
				if (y > reach) {
					break;
				}

				double t = y / reach;
				double x0 = centre + wander * t * t;
				double half = Math.max(0.5, width * (1.0 - 0.6 * t)) + (y >= reach - 1.5 ? 0.6 : 0.0);
				for (int x = 0; x < 16; x++) {
					if (Math.abs(x + 0.5 - x0) <= half) {
						mask[y * 16 + x] = 1;
						// Wet on the left edge of each rivulet, where the light catches it.
						shade[y * 16 + x] = x + 0.5 < x0 - half * 0.4 ? 1.0 : 0.78 + random.nextDouble() * 0.12;
					}
				}
			}
		}

		// The top rows are a sheet: blood leaving the wound before it splits.
		for (int y = 0; y < 3; y++) {
			for (int x = 1; x < 15; x++) {
				if (y < 2 || random.nextDouble() < 0.6) {
					mask[y * 16 + x] = 1;
					shade[y * 16 + x] = 0.75 + random.nextDouble() * 0.15;
				}
			}
		}

		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				int i = y * 16 + x;
				int rgb = mask[i] == 0 ? 0 : argb(1.0, shade[i]);
				if (variant < 4) {
					image.setRGB(ox + x, oy + y, rgb);
				} else {
					image.setRGB(ox + y, oy + x, rgb);
				}
			}
		}
	}

	private static void woundTile(BufferedImage image, int layer, int variant, Random random) {
		int ox = variant * 16;
		int oy = layer * 16;
		// The gash runs at one of four angles; the other layers are lumpy blobs.
		double angle = (variant % 4) * Math.PI / 4.0 + (random.nextDouble() - 0.5) * 0.3;
		double ca = Math.cos(angle);
		double sa = Math.sin(angle);
		double[] lumps = new double[8];
		for (int i = 0; i < lumps.length; i++) {
			lumps[i] = 0.8 + random.nextDouble() * 0.35;
		}

		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double dx = (x + 0.5 - 8.0) / 8.0;
				double dy = (y + 0.5 - 8.0) / 8.0;
				double along = dx * ca + dy * sa;
				double across = -dx * sa + dy * ca;
				double theta = Math.atan2(dy, dx) / (Math.PI * 2.0) * lumps.length;
				int li = Math.floorMod((int) Math.floor(theta), lumps.length);
				double lump = lerp(theta - Math.floor(theta), lumps[li], lumps[(li + 1) % lumps.length]);
				double r = Math.sqrt(dx * dx + dy * dy) / lump;
				double noise = random.nextDouble();
				double light;
				boolean inside;
				switch (layer) {
					case 0 -> {
						// Bruise: blood pooled under unbroken skin. A solid flush, darkest over the blow, thinning out
						// through an ordered dither, so the edge is soft and has no stray pixels.
						double fade = 1.0 - (r - 0.72) / 0.26;
						inside = (r < 0.72 || r < 0.98 && fade > (BAYER[y & 3][x & 3] + 0.5) / 16.0) && dx * dx + dy * dy < 0.78;
						light = 0.68 + r * 0.3 + (noise - 0.5) * 0.08;
					}
					case 1 -> {
						// Gash: a lens along the cut, dark at the split, ragged raised edges.
						double width = 0.32 * (1.0 - along * along) + (noise - 0.5) * 0.08;
						inside = Math.abs(along) < 0.95 && Math.abs(across) < width;
						light = Math.abs(across) < width * 0.4 ? 0.25 + noise * 0.1 : 0.75 + noise * 0.25;
					}
					case 2 -> {
						// Open meat: a wet blob with darker muscle fibres and bright wet highlights.
						inside = r < 0.82 || r < 0.95 && noise < 0.5;
						boolean fibre = Math.floorMod((int) Math.floor((along * 6.0 + across * 1.5) + noise * 0.6), 3) == 0;
						light = fibre ? 0.55 + noise * 0.1 : 0.8 + noise * 0.2;
						if (noise > 0.94) {
							light = 1.0;
						}
					}
					default -> {
						// Bone: a pale knob with a grey rim, a crack and a dark marrow pixel at the centre.
						inside = r < 0.6;
						light = r > 0.45 ? 0.7 : 0.92 + noise * 0.08;
						if (Math.abs(across) < 0.07 && along > -0.1 && r < 0.55) {
							light = 0.45;
						}
						if (r < 0.14) {
							light = 0.3;
						}
					}
				}

				image.setRGB(ox + x, oy + y, inside ? argb(1.0, light) : 0);
			}
		}
	}

	/**
	 * One layer of a slash, at {@code (variant % 4) * 45} degrees. A cut gapes most in the middle and tapers to sharp
	 * ends (a lens); the skin's edges bruise, the fat under them shows as a pale rim, and the muscle and bone show
	 * only down the middle of a deep one.
	 */
	private static void slashTile(BufferedImage image, int sub, int variant, Random random) {
		int ox = variant * 16;
		int oy = (4 + sub) * 16;
		double angle = (variant % 4) * Math.PI / 4.0 + (random.nextDouble() - 0.5) * 0.12;
		double ca = Math.cos(angle);
		double sa = Math.sin(angle);
		// Slight curve and an uneven gape, so no two cuts are alike.
		double bend = (random.nextDouble() - 0.5) * 0.25;
		double skew = (random.nextDouble() - 0.5) * 0.3;
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double dx = (x + 0.5 - 8.0) / 8.0;
				double dy = (y + 0.5 - 8.0) / 8.0;
				double along = dx * ca + dy * sa;
				double across = -dx * sa + dy * ca - bend * (1.0 - along * along);
				double taper = Math.max(0.0, 1.0 - Math.pow(Math.abs(along + skew * 0.3) / 0.98, 2.2));
				double noise = random.nextDouble();
				double width;
				boolean inside;
				double light;
				switch (sub) {
					case 0 -> {
						// Margin: bruised, abraded skin either side of the cut, breaking up at its edge.
						width = 0.36 * taper + 0.04;
						inside = Math.abs(across) < width * (noise < 0.35 ? 1.15 : 0.9) && Math.abs(along) < 1.0;
						light = 0.5 + noise * 0.35 - Math.abs(across) / Math.max(0.05, width) * 0.15;
					}
					case 1 -> {
						// Fat: a lumpy pale band just inside the skin edge, globules catching the light.
						width = 0.24 * taper;
						double inner = width * 0.55;
						inside = Math.abs(across) < width && Math.abs(across) > inner * (0.8 + noise * 0.3);
						light = 0.8 + noise * 0.2;
					}
					case 2 -> {
						// The split: the gaping cut itself, dark and wet, darkest along its middle.
						width = 0.2 * taper + (noise - 0.5) * 0.03;
						inside = Math.abs(across) < width;
						light = Math.abs(across) < width * 0.45 ? 0.18 + noise * 0.08 : 0.4 + noise * 0.15;
					}
					case 3 -> {
						// Muscle: red fibres running across the depth of the cut, with wet highlights.
						width = 0.14 * taper;
						inside = Math.abs(across) < width;
						boolean fibre = Math.floorMod((int) Math.floor(along * 9.0 + noise * 0.5), 2) == 0;
						light = fibre ? 0.55 + noise * 0.1 : 0.8 + noise * 0.15;
						if (noise > 0.95) {
							light = 1.0;
						}
					}
					default -> {
						// Bone: a pale line at the bottom of the cut, notched where the edge bit into it.
						width = 0.055;
						inside = Math.abs(across) < width && Math.abs(along) < 0.55;
						light = 0.9 + noise * 0.1;
						if (Math.abs(along) < 0.08) {
							light = 0.45;
						}
					}
				}

				image.setRGB(ox + x, oy + y, inside ? argb(1.0, light) : 0);
			}
		}
	}

	/** Crushed bone: a dark, wet hollow with splinters of bone in it at every angle. */
	private static void crushTile(BufferedImage image, int variant, Random random) {
		int ox = variant * 16;
		int oy = 12 * 16;
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double dx = (x + 0.5 - 8.0) / 8.0;
				double dy = (y + 0.5 - 8.0) / 8.0;
				double r = Math.sqrt(dx * dx + dy * dy);
				if (r < 0.7 || r < 0.85 && random.nextDouble() < 0.5) {
					image.setRGB(ox + x, oy + y, argb(1.0, 0.18 + random.nextDouble() * 0.12));
				}
			}
		}

		int shards = 5 + random.nextInt(4);
		for (int k = 0; k < shards; k++) {
			double angle = random.nextDouble() * Math.PI;
			double cx = 8 + (random.nextDouble() - 0.5) * 8.0;
			double cy = 8 + (random.nextDouble() - 0.5) * 8.0;
			int length = 2 + random.nextInt(4);
			for (int s = -length / 2; s <= length / 2; s++) {
				int x = (int) Math.round(cx + Math.cos(angle) * s);
				int y = (int) Math.round(cy + Math.sin(angle) * s);
				if (x >= 0 && x < 16 && y >= 0 && y < 16) {
					image.setRGB(ox + x, oy + y, argb(1.0, s == length / 2 ? 0.7 : 0.88 + random.nextDouble() * 0.12));
				}
			}
		}
	}

	/** Stove-in ribs: a dark hollow crossed by pale rib bars, each snapped and the ends pushed in. */
	private static void ribsTile(BufferedImage image, int variant, Random random) {
		int ox = variant * 16;
		int oy = 13 * 16;
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double dx = (x + 0.5 - 8.0) / 8.0;
				double dy = (y + 0.5 - 8.0) / 8.0;
				double r = Math.sqrt(dx * dx * 0.8 + dy * dy);
				if (r < 0.78 || r < 0.92 && random.nextDouble() < 0.45) {
					image.setRGB(ox + x, oy + y, argb(1.0, 0.15 + random.nextDouble() * 0.1));
				}
			}
		}

		// Three or four ribs across it, each broken somewhere along its length, the inner end sunk out of line.
		int ribs = 3 + random.nextInt(2);
		for (int k = 0; k < ribs; k++) {
			int y = 3 + k * (10 / ribs) + random.nextInt(2);
			int brk = 5 + random.nextInt(6);
			for (int x = 2; x < 14; x++) {
				if (x == brk) {
					continue;
				}

				int yy = x > brk ? y + 1 : y;
				if (yy < 16) {
					image.setRGB(ox + x, oy + yy, argb(1.0, x == brk - 1 || x == brk + 1 ? 0.72 : 0.9 + random.nextDouble() * 0.1));
				}
			}
		}
	}

	/**
	 * The cut face of muscle, edge to edge: bundles of fibre in bands across the tile, each pixel a little lighter or
	 * darker, the odd dark pore where a vessel was cut. Fully opaque.
	 */
	private static void grainTile(BufferedImage image, int variant, Random random) {
		int ox = variant * 16;
		int oy = 14 * 16;
		double[] band = new double[16];
		for (int i = 0; i < band.length; i++) {
			band[i] = 0.78 + random.nextDouble() * 0.16;
		}

		double slope = (random.nextDouble() - 0.5) * 0.5;
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				int b = Math.floorMod((int) Math.floor(y + x * slope), 16);
				double light = band[b] + (random.nextDouble() - 0.5) * 0.1;
				if (random.nextDouble() < 0.025) {
					light = 0.45;
				}

				image.setRGB(ox + x, oy + y, argb(1.0, light));
			}
		}
	}

	/** Marbling: thin wandering veins of fat through the meat and loose flecks of it. Only the fat is drawn. */
	private static void marbleTile(BufferedImage image, int variant, Random random) {
		int ox = variant * 16;
		int oy = 15 * 16;
		boolean[][] fat = new boolean[16][16];
		int veins = 2 + random.nextInt(3);
		for (int v = 0; v < veins; v++) {
			double x = random.nextDouble() * 16.0;
			double y = random.nextDouble() * 16.0;
			double heading = random.nextDouble() * Math.PI * 2.0;
			int steps = 8 + random.nextInt(14);
			for (int s = 0; s < steps; s++) {
				set(fat, (int) x, (int) y);
				heading += (random.nextDouble() - 0.5) * 0.9;
				x += Math.cos(heading);
				y += Math.sin(heading);
			}
		}

		for (int i = 0; i < 10; i++) {
			set(fat, random.nextInt(16), random.nextInt(16));
		}

		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				image.setRGB(ox + x, oy + y, fat[y][x] ? argb(1.0, 0.85 + random.nextDouble() * 0.15) : 0);
			}
		}
	}

	/**
	 * A rim of fat along one edge of the cut ({@code variant % 4}: top, right, bottom, left), ragged where it meets
	 * the meat, with the darker line of skin on the outside.
	 */
	private static void rimTile(BufferedImage image, int variant, Random random) {
		int ox = variant * 16;
		int oy = 16 * 16;
		int[] depth = new int[16];
		int d = 3 + random.nextInt(2);
		for (int i = 0; i < 16; i++) {
			d = Math.max(2, Math.min(5, d + random.nextInt(3) - 1));
			depth[i] = d;
		}

		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				// Distance in from the rim's edge, and position along it.
				int in = switch (variant % 4) {
					case 0 -> y;
					case 1 -> 15 - x;
					case 2 -> 15 - y;
					default -> x;
				};
				int along = variant % 2 == 0 ? x : y;
				int argb = in >= depth[along] ? 0 : in == 0 ? argb(1.0, 0.72) : argb(1.0, 0.9 + random.nextDouble() * 0.1);
				image.setRGB(ox + x, oy + y, argb);
			}
		}
	}

	/** A wet smear of blood: an uneven blot with runs dripping down from it, darker where it pools. */
	private static void stainTile(BufferedImage image, int variant, Random random) {
		int ox = variant * 16;
		int oy = 17 * 16;
		boolean[][] wet = new boolean[16][16];
		int blots = 3 + random.nextInt(3);
		for (int i = 0; i < blots; i++) {
			fillCircle(wet, 4 + random.nextDouble() * 8, 3 + random.nextDouble() * 7, 1.5 + random.nextDouble() * 2.5);
		}

		for (int i = 0; i < 3; i++) {
			int x = 3 + random.nextInt(10);
			int from = 6 + random.nextInt(4);
			int to = Math.min(15, from + 3 + random.nextInt(6));
			for (int y = from; y <= to; y++) {
				set(wet, x, y);
			}
		}

		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double light = 0.7 + random.nextDouble() * 0.3 - (y > 10 ? 0.12 : 0.0);
				image.setRGB(ox + x, oy + y, wet[y][x] && random.nextDouble() > 0.06 ? argb(1.0, light) : 0);
			}
		}
	}

	/** A patch of plucked skin: a lumpy blob, ragged at the rim, with feather pits in loose rows. */
	private static void skinTile(BufferedImage image, int variant, Random random) {
		int ox = variant * 16;
		int oy = 11 * 16;
		double[] lumps = new double[7];
		for (int i = 0; i < lumps.length; i++) {
			lumps[i] = 0.82 + random.nextDouble() * 0.3;
		}

		int shift = random.nextInt(3);
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double dx = (x + 0.5 - 8.0) / 8.0;
				double dy = (y + 0.5 - 8.0) / 8.0;
				double theta = Math.atan2(dy, dx) / (Math.PI * 2.0) * lumps.length;
				int li = Math.floorMod((int) Math.floor(theta), lumps.length);
				double lump = lerp(theta - Math.floor(theta), lumps[li], lumps[(li + 1) % lumps.length]);
				double r = Math.sqrt(dx * dx + dy * dy) / lump;
				double noise = random.nextDouble();
				// Feathers left standing at the edge: the rim breaks up.
				boolean inside = r < 0.8 || r < 0.98 && noise < 0.4;
				// Feather tracts: pits in staggered rows, each with a pale raised ring.
				boolean pit = (x + shift + (y / 3 % 2) * 2) % 4 == 0 && y % 3 == 1;
				double light = pit ? 0.62 : 0.86 + noise * 0.12;
				image.setRGB(ox + x, oy + y, inside ? argb(1.0, light) : 0);
			}
		}
	}

	/** Rot on a zombie's wounds: beads of pus ({@code kind} 0) or a few maggots ({@code kind} 1), scattered over it. */
	private static void rotTile(BufferedImage image, int kind, int variant, Random random) {
		int ox = variant * 16;
		int oy = (9 + kind) * 16;
		if (kind == 0) {
			int beads = 5 + random.nextInt(4);
			for (int k = 0; k < beads; k++) {
				double angle = random.nextDouble() * Math.PI * 2.0;
				double dist = random.nextDouble() * 5.0;
				int cx = (int) Math.round(8 + Math.cos(angle) * dist);
				int cy = (int) Math.round(8 + Math.sin(angle) * dist);
				int size = random.nextDouble() < 0.4 ? 2 : 1;
				for (int y = cy - size + 1; y <= cy; y++) {
					for (int x = cx - size + 1; x <= cx; x++) {
						if (x >= 0 && x < 16 && y >= 0 && y < 16) {
							image.setRGB(ox + x, oy + y, argb(1.0, x == cx - size + 1 && y == cy - size + 1 ? 1.0 : 0.75 + random.nextDouble() * 0.15));
						}
					}
				}
			}

			return;
		}

		int maggots = 4 + random.nextInt(4);
		for (int k = 0; k < maggots; k++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double dist = random.nextDouble() * 4.5;
			int cx = (int) Math.round(8 + Math.cos(angle) * dist);
			int cy = (int) Math.round(8 + Math.sin(angle) * dist);
			// A grub two or three pixels long, its head darker.
			boolean flat = random.nextBoolean();
			int length = 2 + random.nextInt(2);
			for (int s = 0; s < length; s++) {
				int x = flat ? cx + s : cx;
				int y = flat ? cy : cy + s;
				if (x >= 0 && x < 16 && y >= 0 && y < 16) {
					image.setRGB(ox + x, oy + y, argb(1.0, s == 0 ? 0.7 : 0.95 + random.nextDouble() * 0.05));
				}
			}
		}
	}

	/**
	 * flesh.png: 16x16 greyscale pixel noise with faint fibres, fully opaque. Giblets show a random 4x4 window of it,
	 * tinted by tissue, so every piece has its own grain.
	 */
	private static BufferedImage flesh() {
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Random random = new Random(15000);
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double light = 0.72 + random.nextDouble() * 0.2;
				if ((x + y * 3) % 7 == 0) {
					light -= 0.12;
				}

				if (random.nextDouble() > 0.93) {
					light = 1.0;
				}

				image.setRGB(x, y, argb(1.0, light));
			}
		}

		return image;
	}

	/**
	 * hurt.png, 192x64, for the injured player's screen. Left, 64x64: a vignette in big ordered-dither steps, clear in
	 * the middle and thickening to the edges, stretched over the whole screen so every texel is a chunky block. Middle,
	 * a 2x2 grid of 32px blood splats. Right, four 16x64 drips: a run of blood from the top down, a fat drop at its foot,
	 * revealed a bit more every tick so the blood creeps down the screen. All white/grey; the game tints them.
	 */
	private static BufferedImage hurt() {
		BufferedImage image = new BufferedImage(192, 64, BufferedImage.TYPE_INT_ARGB);
		int[][] bayer = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
		for (int y = 0; y < 64; y++) {
			for (int x = 0; x < 64; x++) {
				double dx = (x + 0.5) / 32.0 - 1.0;
				double dy = (y + 0.5) / 32.0 - 1.0;
				// A rounded square, so the corners close in first.
				double d = Math.pow(Math.pow(Math.abs(dx), 3.0) + Math.pow(Math.abs(dy), 3.0), 1.0 / 3.0);
				double t = smoothstep(0.45, 1.05, d);
				// Four flat steps of alpha, dithered in between, like an old 16-colour fade.
				double level = t * 4.0;
				int step = (int) level;
				if (level - step > (bayer[y % 4][x % 4] + 0.5) / 16.0) {
					step++;
				}

				if (step > 0) {
					image.setRGB(x, y, argb(Math.min(4, step) / 4.0, 1.0 - 0.12 * Math.min(4, step)));
				}
			}
		}

		for (int i = 0; i < 4; i++) {
			Random random = new Random(9500 + i);
			boolean[][] mask = new boolean[32][32];
			int blobs = 3 + random.nextInt(3);
			for (int b = 0; b < blobs; b++) {
				fillCircle(mask, 15.5 + random.nextGaussian() * 2.5, 15.5 + random.nextGaussian() * 2.5, 3.0 + random.nextDouble() * 3.5);
			}

			for (int a = 0; a < 5 + random.nextInt(4); a++) {
				double angle = random.nextDouble() * Math.PI * 2.0;
				double x = 15.5;
				double y = 15.5;
				int length = 8 + random.nextInt(7);
				for (int s = 0; s < length; s++) {
					angle += random.nextGaussian() * 0.3;
					x += Math.cos(angle);
					y += Math.sin(angle);
					set(mask, (int) Math.round(x), (int) Math.round(y));
				}

				fillCircle(mask, x, y, 1.0 + random.nextDouble());
			}

			for (int s = 0; s < 14; s++) {
				double angle = random.nextDouble() * Math.PI * 2.0;
				double dist = 9.0 + random.nextDouble() * 6.0;
				set(mask, (int) Math.round(15.5 + Math.cos(angle) * dist), (int) Math.round(15.5 + Math.sin(angle) * dist));
			}

			shadeMask(image, 64 + (i % 2) * 32, (i / 2) * 32, mask, random);
		}

		for (int i = 0; i < 4; i++) {
			Random random = new Random(9600 + i);
			boolean[][] mask = new boolean[64][16];
			double x = 7.5 + random.nextGaussian();
			double width = 1.6 + random.nextDouble() * 1.2;
			int length = 44 + random.nextInt(12);
			for (int y = 0; y < length; y++) {
				x += random.nextGaussian() * 0.25;
				// The run thins as the blood is left behind on the way down.
				double w = width * (1.0 - 0.35 * y / length);
				for (int px = 0; px < 16; px++) {
					if (Math.abs(px + 0.5 - x) <= w) {
						mask[y][px] = true;
					}
				}
			}

			double r = width + 1.2;
			for (int y = 0; y < 64; y++) {
				for (int px = 0; px < 16; px++) {
					double ddx = px + 0.5 - x;
					double ddy = y + 0.5 - (length + 1.0);
					if (ddx * ddx + ddy * ddy * 0.6 <= r * r) {
						mask[y][px] = true;
					}
				}
			}

			shadeMask(image, 128 + i * 16, 0, mask, random);
		}

		return image;
	}

	/** Draws a mask texel for texel, each texel one of a few shades, darker at the edges, with a wet highlight inside. */
	private static void shadeMask(BufferedImage image, int ox, int oy, boolean[][] mask, Random random) {
		double[] shades = {0.5, 0.62, 0.75, 0.88};
		for (int y = 0; y < mask.length; y++) {
			for (int x = 0; x < mask[y].length; x++) {
				if (!mask[y][x]) {
					continue;
				}

				int neighbours = 0;
				for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					int nx = x + d[0];
					int ny = y + d[1];
					if (ny >= 0 && nx >= 0 && ny < mask.length && nx < mask[y].length && mask[ny][nx]) {
						neighbours++;
					}
				}

				int shade = Math.max(0, Math.min(3, neighbours - 1 + random.nextInt(2) - (random.nextInt(4) == 0 ? 1 : 0)));
				boolean shine = neighbours == 4 && random.nextInt(9) == 0;
				image.setRGB(ox + x, oy + y, argb(1.0, shine ? 1.0 : shades[shade]));
			}
		}
	}

	/**
	 * feather.png, 64x32: loose feathers as 16x16 pixel art, a quill up the middle and the vanes either side, curving a
	 * little. Top row, four dry feathers: full, soft vanes and down at the foot. Bottom row, the same four wet (with blood
	 * or water): the vanes clumped into a few thin strands. All white/grey; the game tints them.
	 */
	private static int argb(int a, int r, int g, int b) {
		return a << 24 | r << 16 | g << 8 | b;
	}

	/**
	 * Blood clouding out in water (water_cloud.png): a 16x16 pixel-art puff, a few lobes of a soft blob, its alpha in four
	 * steps from a dense middle out to ragged single pixels at the edge, like vanilla's own smoke and cloud particles.
	 * White and grey, so the blood's colour tints it; no hard rim, so many of them overlap into one cloud.
	 */
	private static BufferedImage waterCloud() {
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Random random = new Random(12_100);
		double[][] lobes = {{7.5, 7.5, 5.2}, {5.0, 6.0, 3.4}, {10.4, 6.6, 3.2}, {7.0, 10.2, 3.6}, {9.6, 9.8, 3.0}};
		int[] alphas = {0, 34, 76, 124, 176};
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double field = 0.0;
				for (double[] l : lobes) {
					double dx = (x + 0.5 - l[0]) / l[2];
					double dy = (y + 0.5 - l[1]) / l[2];
					double d = dx * dx + dy * dy;
					field += d < 1.0 ? (1.0 - d) * (1.0 - d) : 0.0;
				}

				double v = field * 1.15 + (random.nextDouble() - 0.5) * 0.35;
				int step = v > 1.45 ? 4 : v > 0.95 ? 3 : v > 0.5 ? 2 : v > 0.16 ? 1 : 0;
				if (step == 0) {
					continue;
				}

				int grey = 200 + random.nextInt(4) * 16;
				image.setRGB(x, y, argb(alphas[step], grey, grey, grey));
			}
		}

		return image;
	}

	private static BufferedImage feathers() {
		BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
		for (int variant = 0; variant < 4; variant++) {
			for (int wet = 0; wet < 2; wet++) {
				Random random = new Random(16000 + variant);
				int length = 13 + random.nextInt(3);
				double width = 1.9 + random.nextDouble() * 0.9;
				double bend = (random.nextDouble() - 0.5) * 3.0;
				Random strands = new Random(16100 + variant);
				int ox = variant * 16;
				int oy = wet * 16;
				for (int row = 0; row < length; row++) {
					// t runs from the foot of the quill (0) to the tip (1).
					double t = row / (double) (length - 1);
					int y = oy + 15 - row;
					double centre = 7.5 + bend * t * t;
					double half = width * Math.pow(Math.sin(Math.PI * Math.min(1.0, 0.1 + t * 0.92)), 0.5) * (wet == 1 ? 0.65 : 1.0);
					for (int dx = -5; dx <= 5; dx++) {
						int x = (int) Math.round(centre) + dx;
						if (x < 0 || x > 15) {
							continue;
						}

						double d = Math.abs(x - centre);
						if (d < 0.6) {
							// The quill, a little greyer, to the tip.
							image.setRGB(ox + x, y, argb(1.0, 0.62 + 0.2 * t));
							continue;
						}

						double noise = strands.nextDouble();
						if (t < 0.2) {
							// The bare quill at the foot, with a few loose wisps of down.
							if (wet == 0 && t > 0.08 && d < 2.2 && noise > 0.55) {
								image.setRGB(ox + x, y, argb(0.7, 0.97));
							}

							continue;
						}

						if (d > half + 0.4) {
							continue;
						}

						// Wet vanes stick together into a few strands with gaps between.
						if (wet == 1 && ((row + (x > centre ? 1 : 0)) % 3 == 0 || noise > 0.8)) {
							continue;
						}

						// The barbs run up and out from the quill: faint slanting lines.
						boolean barb = (row + (int) Math.round(d)) % 3 == 0;
						double light = 0.92 + 0.08 * noise - 0.1 * (d / Math.max(1.0, half)) - (barb ? 0.08 : 0.0);
						image.setRGB(ox + x, y, argb(1.0, light));
					}
				}
			}
		}

		return image;
	}

	/**
	 * anatomy.png (3.2), 128x128: the inside of a person (and from 3.3 of a sheep, a pig and a cow), burning down layer by layer
	 * ({@code dev.fluidgore.anatomy}). Eight 16px tiles a row at one texel per model pixel, like a skin, in full colour:
	 * the game multiplies a cooking tint in. The chest, back and face tiles are laid out for the 8x12 and 8x8 faces they
	 * cover, from their top left corner. Tile order must match {@code Anatomy.Kind}:
	 * <ol start="0">
	 * <li>bone, bone end, skull, teeth, socket, eye, cartilage, vertebra</li>
	 * <li>muscle, chest, back, face, tongue, fat, dermis, blister</li>
	 * <li>lung, heart, aorta, liver, stomach, small intestine, colon, kidney</li>
	 * <li>spleen, bladder, trachea, char, ember, calcined bone, molten fat, brain</li>
	 * <li>the bones of a hand or foot; cooked muscle, chest, back and face; heat-fixed viscera; suet; lard</li>
	 * <li>mutton, pork, beef; hoof, horn; rumen; cooked mutton, cooked pork</li>
	 * <li>cooked beef; gallbladder, reticulum, vein; cut liver, cut lung; gland, testis</li>
	 * <li>cut heart, cut brain, cut kidney, cut bone; wool</li>
	 * </ol>
	 */
	private static BufferedImage anatomy() {
		BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
		Random random = new Random(21000);
		// Bone: ivory, a grain along its length, pores.
		paint(image, 0, (x, y) -> {
			double n = random.nextDouble();
			int c = n < 0.12 ? 0xD2C5A5 : n > 0.94 ? 0xF1EBDA : 0xE3D9C0;
			if (x % 5 == 2 && random.nextDouble() < 0.4) {
				c = 0xCDBF9F;
			}

			return random.nextDouble() < 0.025 ? 0xB3A482 : c;
		});
		// The ends of a long bone: spongy.
		paint(image, 1, (x, y) -> {
			double n = random.nextDouble();
			return n < 0.16 ? 0xB8A67F : n < 0.21 ? 0x9E8C66 : n > 0.92 ? 0xEDE5CF : 0xDCCFB0;
		});
		// Skull: smooth ivory with the zigzag sutures, sagittal down the middle and coronal across.
		paint(image, 2, (x, y) -> {
			boolean sagittal = Math.abs(x - 8 - ((y / 2) % 2)) == 0;
			boolean coronal = Math.abs(y - 5 - ((x / 2) % 2)) == 0;
			double n = random.nextDouble();
			return sagittal || coronal ? 0xA4957A : n < 0.1 ? 0xD6CAAD : n > 0.95 ? 0xF2ECDC : 0xE6DDC6;
		});
		// Teeth, read at two texels a pixel: each tooth two wide with a dark line between, the gum line at the root.
		paint(image, 3, (x, y) -> {
			if (y == 0 || y == 15) {
				return 0xB86860;
			}

			if (x % 3 == 2) {
				return 0x7E6F57;
			}

			return y > 11 ? 0xDCD3B8 : x % 3 == 0 ? 0xF4F0E1 : 0xE6E0CC;
		});
		// An empty socket or cavity: dark.
		paint(image, 4, (x, y) -> {
			double n = random.nextDouble();
			return n < 0.3 ? 0x1E0F0D : n < 0.8 ? 0x2B1714 : 0x3D201A;
		});
		// An eye, read at four texels a pixel from the middle: white, a few veins, a blue-grey iris and the pupil.
		paint(image, 5, (x, y) -> {
			double d = Math.hypot(x - 7.5, y - 7.5);
			if (d < 1.3) {
				return 0x111111;
			}

			if (d < 2.8) {
				return d < 2.0 ? 0x3E5C8A : 0x2E4566;
			}

			return random.nextDouble() < 0.05 ? 0xD39A9A : random.nextDouble() < 0.2 ? 0xE2DFD5 : 0xEFEDE6;
		});
		// Cartilage: pearly, bluish.
		paint(image, 6, (x, y) -> {
			double n = random.nextDouble();
			return n < 0.2 ? 0xB3C1C5 : n > 0.85 ? 0xDDE7E9 : 0xC8D4D6;
		});
		// A vertebra: bone, with the darker disc under each one.
		paint(image, 7, (x, y) -> {
			if (y % 4 == 3) {
				return x % 2 == 0 ? 0xBBA98A : 0xC4B396;
			}

			double n = random.nextDouble();
			return n < 0.12 ? 0xCFC1A1 : n > 0.93 ? 0xF0E8D4 : 0xE0D5BA;
		});
		// Muscle: red, in bundles of fibres running down its length, with dark seams and the odd streak of white fascia.
		paint(image, 8, (x, y) -> muscle(random, x, 0.0));
		// The front of the trunk, for its 8x12 face: the two pectorals with their fibres running up to the shoulders, a
		// shadow under them, the six blocks of the rectus between its tendons, the obliques at the sides.
		paint(image, 9, (x, y) -> {
			if (y < 5) {
				if (y == 4 && x > 0 && x < 7) {
					return 0x711A16;
				}

				boolean cleft = x == 3 || x == 4;
				int c = muscle(random, x + (x < 4 ? y : -y), 0.08);
				return cleft ? shade(c, 0.82) : c;
			}

			if (x == 0 || x == 7) {
				return shade(muscle(random, x + y, 0.0), 0.86);
			}

			if (y == 7 || y == 9 || y == 11) {
				return random.nextDouble() < 0.3 ? 0xA34C40 : 0xB0574A;
			}

			int c = shade(muscle(random, x, 0.0), 1.06);
			return x == 3 || x == 4 ? shade(c, 0.8) : c;
		});
		// The back, for its 8x12 face: the trapezius fanning in to the spine above, the lats below it, the pale sheet of
		// fascia over the small of the back, the groove of the spine down the middle.
		paint(image, 10, (x, y) -> {
			if (x == 3 || x == 4) {
				return y > 8 ? 0xBE9A84 : shade(muscle(random, x, 0.0), 0.7);
			}

			if (y > 8 && x > 1 && x < 6) {
				return random.nextDouble() < 0.25 ? 0xA98470 : 0xB98E78;
			}

			if (y < 5) {
				return shade(muscle(random, x + (x < 4 ? -y : y), 0.05), 1.08);
			}

			return muscle(random, x + (x < 4 ? y : -y), 0.03);
		});
		// The face, for its 8x8 face: the brow muscle over the forehead, the rings round the eyes and the mouth (the
		// eyes and lips themselves burn through first), the cheek muscles, the chin.
		paint(image, 11, (x, y) -> {
			if (y < 3) {
				return muscle(random, x, 0.03);
			}

			if (y == 4 && (x == 1 || x == 2 || x == 5 || x == 6) || y == 6 && x > 1 && x < 6) {
				return 0x2B1714;
			}

			if (y == 3 || y == 5 && (x < 3 || x > 4)) {
				return shade(muscle(random, x + y, 0.0), 0.88);
			}

			return shade(muscle(random, x + y * 2, 0.0), 1.04);
		});
		// The tongue: rough with papillae, a groove down the middle.
		paint(image, 12, (x, y) -> x == 7 || x == 8 ? 0xA2464E : random.nextDouble() < 0.18 ? 0xD47A80 : 0xC05A62);
		// Fat: pale yellow lobules with darker walls between them and a shine on each.
		paint(image, 13, lobules(random, 11, 0xEED48A, 0xCCA85A, 0xF8EBC0));
		// Raw dermis where the skin has peeled: wet pink-red.
		paint(image, 14, (x, y) -> {
			double n = random.nextDouble();
			return n < 0.15 ? 0xA63A45 : n > 0.88 ? 0xE07C84 : 0xC64E58;
		});
		// A burn blister: pale fluid under lifted skin, bright on top, darker at its rim.
		paint(image, 15, (x, y) -> {
			double d = Math.hypot((x % 8) - 3.5, (y % 8) - 3.5);
			return d > 3.2 ? 0xD4C08E : d < 1.6 && (x % 8) < 4 && (y % 8) < 4 ? 0xFFF7E2 : 0xF0E4BE;
		});
		// Lung: pink, the lobules outlined, flecked with black.
		paint(image, 16, lobules(random, 9, 0xD88A93, 0xB86B76, 0xEAA6AE));
		speckle(image, 16, random, 0x5A4A4E, 0.04);
		// Heart: dark red muscle, the coronary vessels branching over it, yellow fat along the groove at the top.
		paint(image, 17, (x, y) -> {
			if (y < 3 && random.nextDouble() < 0.6) {
				return random.nextDouble() < 0.5 ? 0xD9B860 : 0xC9A54E;
			}

			double n = random.nextDouble();
			return n < 0.2 ? 0x6A181B : n > 0.9 ? 0x922A2C : 0x7D2023;
		});
		vessels(image, 17, random, 0x4E0F12, 3);
		vessels(image, 17, random, 0xA33333, 2);
		// The aorta: a pale tube with lines along it.
		paint(image, 18, (x, y) -> x % 4 == 0 ? 0xB06A64 : x % 4 == 1 ? 0xDE9C94 : 0xC8807A);
		// Liver: smooth, dark red-brown, glossy.
		paint(image, 19, (x, y) -> {
			double n = random.nextDouble();
			return n < 0.25 ? 0x5A1C1A : n > 0.93 ? 0x8E3A33 : 0x6C2320;
		});
		// Stomach: pinkish beige, the folds of its lining in waves, a few vessels.
		paint(image, 20, (x, y) -> (y + (x / 3) % 2) % 3 == 0 ? 0xB57A68 : random.nextDouble() < 0.03 ? 0xB34B48 : 0xD39C88);
		// Small intestine: loops of pink tube, lit on top and shadowed under, packed together.
		paint(image, 21, (x, y) -> 0x8F5546);
		coils(image, 21, random);
		// Colon: tan, pouched in bands, a stripe of muscle along it.
		paint(image, 22, (x, y) -> x == 7 ? 0xD8B09C : y % 3 == 2 ? 0xA06C59 : random.nextDouble() < 0.1 ? 0xB37E69 : 0xC48E78);
		// Kidney: dark red, smooth.
		paint(image, 23, (x, y) -> {
			double n = random.nextDouble();
			return n < 0.25 ? 0x63231F : n > 0.9 ? 0x96443E : 0x7B2F2B;
		});
		// Spleen: purple.
		paint(image, 24, (x, y) -> {
			double n = random.nextDouble();
			return n < 0.25 ? 0x55203A : n > 0.9 ? 0x84405C : 0x6A2C46;
		});
		// Bladder: pale, a few veins.
		paint(image, 25, (x, y) -> random.nextDouble() < 0.2 ? 0xCDAE8C : 0xE0C6A6);
		vessels(image, 25, random, 0xC88E86, 2);
		// Trachea: rings of cartilage with the membrane between.
		paint(image, 26, (x, y) -> y % 2 == 0 ? (random.nextDouble() < 0.2 ? 0xC5CCCB : 0xD9DEDD) : 0xC4A6A0);
		// Char: black crust, cracked, with dull glints.
		paint(image, 27, (x, y) -> {
			double n = random.nextDouble();
			return n < 0.5 ? 0x1B1311 : n < 0.85 ? 0x261A16 : n < 0.95 ? 0x3D2A22 : 0x0C0807;
		});
		vessels(image, 27, random, 0x0A0605, 4);
		// Embers: orange fire in the cracks.
		paint(image, 28, (x, y) -> {
			double n = random.nextDouble();
			return n < 0.25 ? 0xC84A12 : n < 0.7 ? 0xFF7A1C : n < 0.93 ? 0xFFAA3C : 0xFFD27A;
		});
		// Calcined bone: chalk white, checked with the cracks of the heat.
		paint(image, 29, (x, y) -> random.nextDouble() < 0.2 ? 0xD7D2C7 : 0xE9E5DC);
		vessels(image, 29, random, 0xA8A296, 5);
		// Molten fat: amber, glossy.
		paint(image, 30, (x, y) -> {
			double n = random.nextDouble();
			return n < 0.25 ? 0xB27A1E : n > 0.85 ? 0xF2C45C : 0xD49A2C;
		});
		// Brain: pinkish grey, the folds wandering over it.
		paint(image, 31, (x, y) -> random.nextDouble() < 0.15 ? 0xC99B9B : 0xD8A8A8);
		vessels(image, 31, random, 0xAE7C7C, 6);
		// The bones of a hand or foot, read at two texels a pixel: a finger a pixel wide, the dark seam between two, the
		// knuckles across.
		paint(image, 32, (x, y) -> {
			if (x % 4 == 3) {
				return 0x8A7A5E;
			}

			if (y % 5 == 4) {
				return 0xC4B494;
			}

			return random.nextDouble() < 0.12 ? 0xD2C5A5 : 0xE3D9C0;
		});
		// Cooked muscle, the plain, chest, back and face tiles again: past about 60 C the red of the meat goes, and it is
		// the grey-brown of well-done meat, its fibres and seams where they were.
		for (int k = 0; k < 4; k++) {
			cookedCopy(image, 8 + k, 33 + k);
		}

		// Heat-fixed viscera: an organ cooked through is firm and grey-brown, mottled darker, whatever it was.
		paint(image, 37, (x, y) -> {
			double n = random.nextDouble();
			return n < 0.2 ? 0x6A5244 : n > 0.9 ? 0xA48A76 : 0x8A6E5C;
		});
		vessels(image, 37, random, 0x4E3A30, 3);
		// Beasts (3.3), from their own seed so the tiles above stay as they were.
		Random beasts = new Random(33000);
		// Suet: a sheep's hard fat, chalk white and crumbly, in lumps with faint cream seams and a dull bloom.
		paint(image, 38, lobules(beasts, 14, 0xF2EFE6, 0xD9D2BE, 0xFBFAF4));
		speckle(image, 38, beasts, 0xE4DECD, 0.12);
		// Lard: a pig's soft fat, smooth glossy white with a faint pink, the lobules barely showing.
		paint(image, 39, lobules(beasts, 6, 0xF7F0EC, 0xE8D8D2, 0xFFFDFB));
		// Mutton: dark brick red, close-grained, the seams of fat between its muscles hard and white.
		paint(image, 40, (x, y) -> {
			if (Math.floorMod(x + y / 6, 7) == 6 && beasts.nextDouble() < 0.8) {
				return 0xEDE8DA;
			}

			int bundle = Math.floorMod(x, 2);
			double n = beasts.nextDouble();
			return n < 0.15 ? 0x6E1A1E : n > 0.93 ? 0xA8343A : bundle == 0 ? 0x8A2329 : 0x942830;
		});
		// Pork: pale pink-red lean, soft, a little grey, with fine pale seams.
		paint(image, 41, (x, y) -> {
			if (Math.floorMod(x, 4) == 3 && beasts.nextDouble() < 0.5) {
				return 0xE8C2BC;
			}

			double n = beasts.nextDouble();
			return n < 0.15 ? 0xC4676A : n > 0.92 ? 0xF0A2A0 : 0xDA8584;
		});
		// Beef: cherry red, coarse-grained, flecked through with creamy marbling.
		paint(image, 42, (x, y) -> {
			if (beasts.nextDouble() < 0.07) {
				return beasts.nextBoolean() ? 0xF4E6C6 : 0xEAD8B0;
			}

			int c = muscle(beasts, x, 0.0);
			return shade(c, 1.12);
		});
		// Hoof: dark grey-black horn with the growth rings across it and a pale line at the sole.
		paint(image, 43, (x, y) -> {
			if (y == 15) {
				return 0x9A8E7A;
			}

			double n = beasts.nextDouble();
			int c = y % 3 == 0 ? 0x2A2624 : 0x3A3532;
			return n < 0.1 ? 0x504840 : c;
		});
		// Horn: cream at the root darkening to grey-brown at the tip, ringed.
		paint(image, 44, (x, y) -> {
			int c = y < 6 ? 0xD8CCB0 : y < 11 ? 0xB0A288 : 0x6E6252;
			return y % 3 == 2 ? shade(c, 0.85) : beasts.nextDouble() < 0.1 ? shade(c, 1.08) : c;
		});
		// Rumen: the lining shaggy with papillae, brown and grey, green with the cud showing between them.
		paint(image, 45, (x, y) -> {
			double n = beasts.nextDouble();
			if (n < 0.18) {
				return beasts.nextBoolean() ? 0x5C6A2E : 0x4A5A26;
			}

			return n < 0.55 ? 0x7A6A52 : n < 0.85 ? 0x8E7C60 : 0x5E5040;
		});
		// The beasts' lean cooked: mutton and beef grey-brown, pork pale grey-white, as well-done meat goes.
		cookedCopy(image, 40, 46);
		// Pork cooks pale: grey-white, its grain still showing (too light for cookedCopy, which keeps only the darker range).
		paint(image, 47, (x, y) -> {
			int rgb = image.getRGB(1 * 16 + x, 5 * 16 + y);
			double l = 0.3 * (rgb >> 16 & 0xFF) + 0.59 * (rgb >> 8 & 0xFF) + 0.11 * (rgb & 0xFF);
			int r = (int) Math.min(255, Math.round(l * 0.75 + 70));
			int g = (int) Math.min(255, Math.round(l * 0.7 + 58));
			int b = (int) Math.min(255, Math.round(l * 0.62 + 48));
			return r << 16 | g << 8 | b;
		});
		cookedCopy(image, 42, 48);
		// The sheep's generated anatomy (SheepTrunk), from their own seed so the tiles above stay as they were.
		Random organs = new Random(34000);
		// Gallbladder: dark olive green bile through a thin glossy wall, a bluish sheen on top.
		paint(image, 49, (x, y) -> {
			double n = organs.nextDouble();
			if (y < 4 && n < 0.25) {
				return 0x6F8C7A;
			}

			return n < 0.25 ? 0x2F4A24 : n > 0.9 ? 0x5C7A46 : 0x3F5D30;
		});
		// Reticulum: the honeycomb, dark ridged cells with paler walls.
		paint(image, 50, lobules(organs, 16, 0x4E4128, 0x958461, 0x655436));
		// Vein: a collapsed vein, dark purple-red, its wall a little paler along the edges.
		paint(image, 51, (x, y) -> {
			if (x == 0 || x == 15) {
				return 0x62405E;
			}

			double n = organs.nextDouble();
			return n < 0.2 ? 0x3A2038 : n > 0.9 ? 0x6A4466 : 0x4D2E4B;
		});
		// Cut faces of an organ thrown out and cut with a blade (3.3), from their own seed so every tile above stays as it was.
		Random sections = new Random(35000);
		// The cut liver: smooth, glossy, dark red-brown parenchyma with faint lobules, and the round holes of the veins and
		// portal vessels cut across, each with a paler wall.
		int[][] veins = {{4, 5, 2}, {11, 3, 1}, {9, 11, 2}, {2, 12, 1}, {13, 13, 1}};
		paint(image, 52, (x, y) -> {
			for (int[] v : veins) {
				double d = Math.hypot(x - v[0], y - v[1]);
				if (d < v[2] - 0.4) {
					return 0x240807;
				}

				if (d < v[2] + 0.6) {
					return 0x7A3A30;
				}
			}

			double n = sections.nextDouble();
			// Lobules: a paler dot at every fourth texel or so, where the hepatic venules sit.
			if ((x * 7 + y * 3) % 5 == 0 && n < 0.5) {
				return 0x6E2620;
			}

			return n < 0.08 ? 0x8C3E36 : n < 0.3 ? 0x4A1612 : 0x5A1C17;
		});
		// The cut lung: spongy and pink-red, speckled with air spaces and pockets of blood, a bronchus cut across with its
		// ring of pale cartilage round a dark lumen.
		paint(image, 53, (x, y) -> {
			double d = Math.hypot(x - 10.5, y - 5.5);
			if (d < 1.4) {
				return 0x3A2228;
			}

			if (d < 2.6) {
				return 0xE6D6CC;
			}

			double small = Math.hypot(x - 3.5, y - 11.5);
			if (small < 0.9) {
				return 0x4A2A30;
			}

			if (small < 1.8) {
				return 0xD8C0B8;
			}

			double n = sections.nextDouble();
			return n < 0.06 ? 0x8A2030 : n < 0.3 ? 0xA45464 : n > 0.85 ? 0xE2A2AC : 0xC77A88;
		});
		// Gland (pancreas, thymus, salivary glands, thyroid, adrenals), from its own seed: small pale pink-cream lobules
		// packed together, the thin seams of connective tissue between them a little darker.
		paint(image, 54, lobules(new Random(36000), 22, 0xD9A894, 0xA8705E, 0xEAC4B2));
		// Testis, from its own seed: the tough glossy white tunic, fine red-purple vessels winding over it.
		Random testis = new Random(37000);
		paint(image, 55, (x, y) -> {
			double wind = Math.sin(x * 0.9 + 2.0 * Math.sin(y * 0.55)) + 0.6 * Math.sin(y * 1.3 + x * 0.2);
			double n = testis.nextDouble();
			if (Math.abs(wind) < 0.12 && n < 0.8) {
				return n < 0.3 ? 0x8E3E4E : 0xA8566A;
			}

			return n < 0.1 ? 0xD8CCC8 : n > 0.9 ? 0xFAF4F0 : 0xECE2DE;
		});
		// More cut faces (a blade through an organ), each from its own seed. The heart cut across its ventricles: the thick
		// round wall of the left ventricle about its lumen, the thin crescent of the right one against the septum, dark
		// clotted blood in both, pale endocardium lining them, a little white coronary fat at the top.
		Random heart = new Random(38000);
		paint(image, 56, (x, y) -> {
			double cx = x + 0.5;
			double cy = y + 0.5;
			double left = Math.hypot(cx - 10.5, cy - 8.5);
			double right = Math.hypot(cx - 3.5, cy - 8.5);
			double n = heart.nextDouble();
			if (left < 2.4) {
				return n < 0.3 ? 0x1E0604 : 0x2C0A07;
			}

			if (left < 3.1) {
				return 0xC98A80;
			}

			if (right < 3.4 && left > 5.6) {
				return n < 0.3 ? 0x24070A : 0x320C0C;
			}

			if (right < 4.0 && left > 5.0) {
				return 0xC98A80;
			}

			if (cy < 2.2 && n < 0.6) {
				return n < 0.2 ? 0xE2CC94 : 0xF0E2B8;
			}

			return n < 0.12 ? 0x6E1E1A : n > 0.9 ? 0xA43A32 : 0x8A2A24;
		});
		// The brain cut through: pinkish grey matter folded about branching white matter, a dark line in each fold.
		Random brain = new Random(39000);
		paint(image, 57, (x, y) -> {
			double v = Math.sin(x * 0.85 + 1.7 * Math.sin(y * 0.45)) + Math.sin(y * 0.7 + 0.8 * Math.sin(x * 0.6));
			double n = brain.nextDouble();
			if (Math.abs(v) < 0.26) {
				return n < 0.2 ? 0xE2D8D0 : 0xF0E8E0;
			}

			if (v > 1.55 && n < 0.85) {
				return 0x7E5E5A;
			}

			return n < 0.15 ? 0xA48884 : n > 0.9 ? 0xC8AEA8 : 0xB89A94;
		});
		// The kidney cut lengthwise: the dark granular cortex outside, the striped pyramids of the medulla, the pale pelvis
		// and its fat at the hilus in the middle.
		Random kidney = new Random(40000);
		paint(image, 58, (x, y) -> {
			double dx = x + 0.5 - 8.0;
			double dy = y + 0.5 - 9.5;
			double d = Math.hypot(dx, dy * 1.2);
			double n = kidney.nextDouble();
			if (d < 2.4) {
				return n < 0.3 ? 0xE0CDB8 : 0xEFE2D2;
			}

			if (d < 5.6) {
				boolean stripe = Math.floorMod((int) Math.round(Math.atan2(dy, dx) * 5.0), 2) == 0;
				return stripe ? (n < 0.3 ? 0x7A2E28 : 0x8E3E36) : (n < 0.3 ? 0x9C4C42 : 0xA85A4E);
			}

			return n < 0.2 ? 0x4E1612 : n > 0.9 ? 0x6E2A22 : 0x5E1E19;
		});
		// Bone sawn or cut through: ivory struts of spongy bone round red marrow.
		paint(image, 59, lobules(new Random(41000), 9, 0xA8443A, 0xE6DABF, 0xC0584C));
		// Wool (4.1), from its own seed: the fleece of a sheep's head as a loose tuft, cream staples with the crimp running
		// across them in waves, a yellow tinge of grease at the roots and a few darker locks. Drawn in the fleece's own white,
		// so a dyed fleece's tufts take their dye as a tint.
		Random wool = new Random(42000);
		paint(image, 60, (x, y) -> {
			double crimp = Math.sin(x * 1.9 + 1.6 * Math.sin(y * 0.8)) * 0.5 + 0.5;
			double n = wool.nextDouble();
			if (n < 0.06) {
				return 0xB8AE9A;
			}

			if (crimp > 0.78) {
				return n < 0.5 ? 0xFFFEF8 : 0xF6F2E6;
			}

			if (crimp < 0.22) {
				return n < 0.4 ? 0xD9D0BC : 0xE3DAC6;
			}

			return n < 0.15 ? 0xF0E6CC : 0xECE6D6;
		});
		return image;
	}

	/** Tile {@code to} is tile {@code from} cooked: the red gone, grey-brown by how light each texel was. */
	private static void cookedCopy(BufferedImage image, int from, int to) {
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				int rgb = image.getRGB((from % 8) * 16 + x, (from / 8) * 16 + y);
				// Pale fascia cooks to a dull tan, not a bright one.
				double l = Math.min(130.0, 0.3 * (rgb >> 16 & 0xFF) + 0.59 * (rgb >> 8 & 0xFF) + 0.11 * (rgb & 0xFF));
				int r = (int) Math.min(255, Math.round(l * 1.1 + 42));
				int g = (int) Math.min(255, Math.round(l * 0.92 + 30));
				int b = (int) Math.min(255, Math.round(l * 0.76 + 22));
				image.setRGB((to % 8) * 16 + x, (to / 8) * 16 + y, 0xFF000000 | r << 16 | g << 8 | b);
			}
		}
	}

	private interface Texel {
		int at(int x, int y);
	}

	/** Fills anatomy tile {@code tile} (eight to a row) pixel by pixel, opaque. */
	private static void paint(BufferedImage image, int tile, Texel texel) {
		int ox = (tile % 8) * 16;
		int oy = (tile / 8) * 16;
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				image.setRGB(ox + x, oy + y, 0xFF000000 | texel.at(x, y));
			}
		}
	}

	/** A texel of muscle in fibre bundle {@code column}: bundles three wide, a seam between them, now and then fascia. */
	private static int muscle(Random random, int column, double fascia) {
		int bundle = Math.floorMod(column, 3);
		if (bundle == 2 && random.nextDouble() < 0.85) {
			return 0x7A1C18;
		}

		if (random.nextDouble() < 0.02 + fascia) {
			return 0xD8B0A0;
		}

		double n = random.nextDouble();
		return n < 0.12 ? 0x8E251F : n > 0.94 ? 0xC2433A : bundle == 0 ? 0xA8302A : 0xB5382F;
	}

	private static int shade(int rgb, double f) {
		int r = (int) Math.min(255, Math.round((rgb >> 16 & 0xFF) * f));
		int g = (int) Math.min(255, Math.round((rgb >> 8 & 0xFF) * f));
		int b = (int) Math.min(255, Math.round((rgb & 0xFF) * f));
		return r << 16 | g << 8 | b;
	}

	/** Cells round {@code count} random points: walls where two cells meet, a shine towards each cell's top left. */
	private static Texel lobules(Random random, int count, int body, int wall, int shine) {
		double[] px = new double[count];
		double[] py = new double[count];
		for (int i = 0; i < count; i++) {
			px[i] = random.nextDouble() * 16.0;
			py[i] = random.nextDouble() * 16.0;
		}

		return (x, y) -> {
			double first = Double.MAX_VALUE;
			double second = Double.MAX_VALUE;
			int nearest = 0;
			for (int i = 0; i < count; i++) {
				// Wrapped, so the tile repeats without a seam.
				double dx = Math.abs(x + 0.5 - px[i]);
				double dy = Math.abs(y + 0.5 - py[i]);
				double d = Math.hypot(Math.min(dx, 16.0 - dx), Math.min(dy, 16.0 - dy));
				if (d < first) {
					second = first;
					first = d;
					nearest = i;
				} else if (d < second) {
					second = d;
				}
			}

			if (second - first < 0.9) {
				return wall;
			}

			boolean lit = x + 0.5 < px[nearest] && y + 0.5 < py[nearest] && first < 1.8;
			return lit ? shine : body;
		};
	}

	private static void speckle(BufferedImage image, int tile, Random random, int rgb, double share) {
		int ox = (tile % 8) * 16;
		int oy = (tile / 8) * 16;
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				if (random.nextDouble() < share) {
					image.setRGB(ox + x, oy + y, 0xFF000000 | rgb);
				}
			}
		}
	}

	/** {@code count} wandering lines across a tile: vessels, cracks or folds. */
	private static void vessels(BufferedImage image, int tile, Random random, int rgb, int count) {
		int ox = (tile % 8) * 16;
		int oy = (tile / 8) * 16;
		for (int i = 0; i < count; i++) {
			int x = random.nextInt(16);
			int y = random.nextInt(16);
			int dx = random.nextBoolean() ? 1 : -1;
			int dy = random.nextBoolean() ? 1 : 0;
			for (int step = 0; step < 14; step++) {
				image.setRGB(ox + Math.floorMod(x, 16), oy + Math.floorMod(y, 16), 0xFF000000 | rgb);
				if (random.nextDouble() < 0.4) {
					int t = dx;
					dx = random.nextBoolean() ? dy : -dy;
					dy = t;
				}

				x += dx == 0 && dy == 0 ? 1 : dx;
				y += dy;
			}
		}
	}

	/** The loops of the small intestine: bands of tube winding over the dark between them. */
	private static void coils(BufferedImage image, int tile, Random random) {
		int ox = (tile % 8) * 16;
		int oy = (tile / 8) * 16;
		double phase = random.nextDouble() * 6.0;
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				// A tube is where the wave is near a whole number: three rows of loops, wobbling.
				double wave = y / 5.0 + 0.45 * Math.sin(x * 0.85 + phase + y * 0.3);
				double f = wave - Math.floor(wave);
				if (f < 0.62) {
					int c = f < 0.16 ? 0xF2C1B1 : f > 0.48 ? 0xB87766 : 0xE3A592;
					image.setRGB(ox + x, oy + y, 0xFF000000 | c);
				}
			}
		}
	}

	private static int argb(double alpha, double light) {
		int a = (int) Math.round(Math.max(0.0, Math.min(1.0, alpha)) * 255.0);
		int l = (int) Math.round(Math.max(0.0, Math.min(1.0, light)) * 255.0);
		return (a << 24) | (l << 16) | (l << 8) | l;
	}

	private static double smoothstep(double edge0, double edge1, double x) {
		double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
		return t * t * (3.0 - 2.0 * t);
	}

	private static double lerp(double t, double a, double b) {
		return a + (b - a) * t;
	}

	private static double gauss(Random random) {
		return random.nextGaussian();
	}
}
