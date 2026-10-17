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
 * <p>blood.png is a 4x4 atlas of 64px tiles. Shapes are drawn white/grey with alpha; the game tints them with
 * the vertex colour, so one atlas serves every blood colour. Tile order must match BloodTextures.
 */
public final class GenerateTextures {
	private static final int TILE = 64;

	/** A soft circle contributing to a metaball field. */
	private record Ball(double x, double y, double r) {
	}

	public static void main(String[] args) throws IOException {
		File root = new File(args.length > 0 ? args[0] : "src/main/resources/assets/fluidgore");
		File misc = new File(root, "textures/misc");
		if (!misc.isDirectory() && !misc.mkdirs()) {
			throw new IOException("Cannot create " + misc);
		}

		BufferedImage atlas = new BufferedImage(TILE * 4, TILE * 4, BufferedImage.TYPE_INT_ARGB);
		for (int i = 0; i < 4; i++) {
			drawField(atlas, i, splat(new Random(1000 + i)), 0.5, new Random(2000 + i));
		}

		drawField(atlas, 4, puddle(new Random(3000)), 0.5, new Random(3001));
		drawPool(atlas, 5, new Random(4000));
		drawField(atlas, 6, List.of(new Ball(0.5, 0.5, 0.36)), 0.5, new Random(5000));
		drawField(atlas, 7, footprint(), 0.5, new Random(6000));
		drawField(atlas, 8, streak(new Random(7000)), 0.5, new Random(7001));
		drawField(atlas, 9, smallSplat(new Random(8000)), 0.5, new Random(8001));
		// Pixel-art "redstone" style: 16x16 shapes upscaled 4x so the pixels match Minecraft's texel size.
		for (int i = 0; i < 4; i++) {
			drawPixelArt(atlas, 10 + i, pixelSplat(new Random(9100 + i), 0.9 + i * 0.25), new Random(9200 + i));
		}

		drawPixelArt(atlas, 14, pixelPuddle(new Random(9300)), new Random(9301));
		drawPixelArt(atlas, 15, pixelDroplet(), new Random(9400));
		ImageIO.write(atlas, "png", new File(misc, "blood.png"));
		ImageIO.write(wounds(), "png", new File(misc, "wounds.png"));
		ImageIO.write(flesh(), "png", new File(misc, "flesh.png"));
		ImageIO.write(hurt(), "png", new File(misc, "hurt.png"));
		ImageIO.write(feathers(), "png", new File(misc, "feather.png"));

		ImageIO.write(icon(), "png", new File(root, "icon.png"));
		System.out.println("Wrote textures to " + root.getAbsolutePath());
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

	private static List<Ball> puddle(Random random) {
		List<Ball> balls = new ArrayList<>();
		// Uneven lobes around a core, plus a few separate drops, so tiled puddles do not read as circles.
		for (int i = 0; i < 12; i++) {
			double angle = i / 12.0 * Math.PI * 2.0 + random.nextDouble() * 0.4;
			double dist = 0.08 + random.nextDouble() * 0.17;
			balls.add(new Ball(0.5 + Math.cos(angle) * dist, 0.5 + Math.sin(angle) * dist, 0.07 + random.nextDouble() * 0.11));
		}

		balls.add(new Ball(0.5, 0.5, 0.2));
		for (int d = 0; d < 5; d++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double dist = 0.36 + random.nextDouble() * 0.08;
			balls.add(new Ball(0.5 + Math.cos(angle) * dist, 0.5 + Math.sin(angle) * dist, 0.02 + random.nextDouble() * 0.025));
		}
		return balls;
	}

	/** A shoe print pointing up (toe at the top of the tile = low V), narrow so it reads as one foot. */
	private static List<Ball> footprint() {
		List<Ball> balls = new ArrayList<>();
		for (double y = 0.18; y <= 0.52; y += 0.04) {
			double width = 0.11 - Math.abs(y - 0.33) * 0.12;
			balls.add(new Ball(0.5 - width * 0.3, y, width));
			balls.add(new Ball(0.5 + width * 0.3, y, width));
		}

		for (double y = 0.68; y <= 0.84; y += 0.04) {
			balls.add(new Ball(0.5, y, 0.085));
		}

		return balls;
	}

	/** Blood running down a wall: a blob at the top and a few thinning trails ending in drops. */
	private static List<Ball> streak(Random random) {
		List<Ball> balls = new ArrayList<>();
		balls.add(new Ball(0.5, 0.12, 0.14));
		balls.add(new Ball(0.42, 0.1, 0.1));
		balls.add(new Ball(0.58, 0.12, 0.1));
		int trails = 2 + random.nextInt(3);
		for (int t = 0; t < trails; t++) {
			double x = 0.38 + random.nextDouble() * 0.24;
			double end = 0.55 + random.nextDouble() * 0.38;
			for (double y = 0.15; y < end; y += 0.02) {
				balls.add(new Ball(x + Math.sin(y * 9.0 + t) * 0.008, y, 0.028 * (1.0 - (y - 0.15) / end * 0.5)));
			}

			balls.add(new Ball(x, end, 0.045));
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
		int ox = (tile % 4) * TILE;
		int oy = (tile / 4) * TILE;
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

	/** Fully covered tile for deep pools, so neighbouring pool cells join without seams. */
	private static void drawPool(BufferedImage image, int tile, Random random) {
		int ox = (tile % 4) * TILE;
		int oy = (tile / 4) * TILE;
		double[] noise = valueNoise(random);
		for (int py = 0; py < TILE; py++) {
			for (int px = 0; px < TILE; px++) {
				double light = 0.75 + 0.2 * noise[py * TILE + px];
				image.setRGB(ox + px, oy + py, argb(1.0, light));
			}
		}
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

	/**
	 * A blocky splat on a 16x16 grid, like redstone dust smeared on the ground: a lumpy core, a few jagged arms
	 * and loose single-pixel specks. {@code size} scales the core.
	 */
	private static boolean[][] pixelSplat(Random random, double size) {
		boolean[][] mask = new boolean[16][16];
		int blobs = 3 + random.nextInt(3);
		for (int b = 0; b < blobs; b++) {
			double cx = 7.5 + random.nextGaussian() * 1.4;
			double cy = 7.5 + random.nextGaussian() * 1.4;
			double r = (1.6 + random.nextDouble() * 1.6) * size;
			fillCircle(mask, cx, cy, r);
		}

		int arms = 3 + random.nextInt(4);
		for (int a = 0; a < arms; a++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double x = 7.5;
			double y = 7.5;
			int length = 4 + random.nextInt(4);
			for (int s = 0; s < length; s++) {
				angle += random.nextGaussian() * 0.35;
				x += Math.cos(angle);
				y += Math.sin(angle);
				set(mask, (int) Math.round(x), (int) Math.round(y));
				if (s < length / 2 && random.nextBoolean()) {
					set(mask, (int) Math.round(x + Math.sin(angle)), (int) Math.round(y - Math.cos(angle)));
				}
			}

			// A drop at the end of the arm.
			fillCircle(mask, x + Math.cos(angle), y + Math.sin(angle), 0.9 + random.nextDouble() * 0.6);
		}

		int specks = 6 + random.nextInt(8);
		for (int s = 0; s < specks; s++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double dist = 4.0 + random.nextDouble() * 3.5;
			set(mask, (int) Math.round(7.5 + Math.cos(angle) * dist), (int) Math.round(7.5 + Math.sin(angle) * dist));
		}

		return mask;
	}

	private static boolean[][] pixelPuddle(Random random) {
		boolean[][] mask = new boolean[16][16];
		fillCircle(mask, 7.5, 7.5, 5.2);
		for (int i = 0; i < 7; i++) {
			double angle = i / 7.0 * Math.PI * 2.0 + random.nextDouble() * 0.5;
			fillCircle(mask, 7.5 + Math.cos(angle) * 4.0, 7.5 + Math.sin(angle) * 4.0, 1.5 + random.nextDouble() * 1.6);
		}

		for (int s = 0; s < 5; s++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			set(mask, (int) Math.round(7.5 + Math.cos(angle) * 7.2), (int) Math.round(7.5 + Math.sin(angle) * 7.2));
		}

		return mask;
	}

	/** A chunky drop: a plus shape filled out to a 4x4 pixel blob, centred. */
	private static boolean[][] pixelDroplet() {
		boolean[][] mask = new boolean[16][16];
		for (int y = 5; y <= 10; y++) {
			for (int x = 5; x <= 10; x++) {
				boolean corner = (x == 5 || x == 10) && (y == 5 || y == 10);
				mask[y][x] = !corner;
			}
		}

		return mask;
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
	 * Draws a 16x16 mask as 4x4 blocks of solid pixels. Each texel gets one of a few shades, darker towards the
	 * edge of the shape, which reads like the speckled red of redstone dust.
	 */
	private static void drawPixelArt(BufferedImage image, int tile, boolean[][] mask, Random random) {
		int ox = (tile % 4) * TILE;
		int oy = (tile / 4) * TILE;
		double[] shades = {0.5, 0.62, 0.75, 0.88};
		for (int ty = 0; ty < 16; ty++) {
			for (int tx = 0; tx < 16; tx++) {
				int argb = 0;
				if (mask[ty][tx]) {
					int neighbours = 0;
					for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
						int nx = tx + d[0];
						int ny = ty + d[1];
						if (nx >= 0 && ny >= 0 && nx < 16 && ny < 16 && mask[ny][nx]) {
							neighbours++;
						}
					}

					int shade = Math.max(0, Math.min(3, neighbours - 1 + random.nextInt(2) - (random.nextInt(4) == 0 ? 1 : 0)));
					argb = argb(1.0, shades[shade]);
				}

				for (int py = 0; py < 4; py++) {
					for (int px = 0; px < 4; px++) {
						image.setRGB(ox + tx * 4 + px, oy + ty * 4 + py, argb);
					}
				}
			}
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
	 * wounds.png: an 8x18 grid of 16px pixel-art tiles, greyscale with hard alpha; the game tints each by the body's
	 * flesh and blood. Eight variants per row.
	 * <ul>
	 * <li>Rows 0-3, round wounds (lacerations from fists, clubs, teeth, points): bruise, gash, open meat, bone.</li>
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
	 * </ul>
	 */
	private static BufferedImage wounds() {
		BufferedImage image = new BufferedImage(128, 288, BufferedImage.TYPE_INT_ARGB);
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
		}

		return image;
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
						// Bruise: a mottled blob whose rim breaks up into scattered pixels.
						inside = r < 0.75 || r < 0.98 && noise < 0.45;
						light = 0.55 + noise * 0.35 - r * 0.2;
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

	/** Draws a mask texel for texel, darker at the edges like {@link #drawPixelArt}, with a wet highlight inside. */
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
