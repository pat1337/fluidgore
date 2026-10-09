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
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				double dx = x + 0.5 - (cx + 0.5);
				double dy = y + 0.5 - (cy + 0.5);
				if (dx * dx + dy * dy <= r * r) {
					mask[y][x] = true;
				}
			}
		}
	}

	private static void set(boolean[][] mask, int x, int y) {
		if (x >= 1 && y >= 1 && x < 15 && y < 15) {
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
