import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The modelling kit the anatomy generators share: shapes as signed distance fields (ellipsoids, tubes, triangles,
 * unions and cuts), the tissues that paint them, the list of named parts, and the voxel grid the parts are cut into,
 * merged into boxes and ray cast for previews. Every unit is the centimetre of the animal being built.
 *
 * <p>Not a program on its own: {@code java tools/SheepAnatomyModels.java} and {@code java tools/CowAnatomyModels.java}
 * pull it in from this folder. The state below is set by the generator running (the grain being cut, the parts made so far).
 */
class AnatomyKit {
	/** The voxel edge being cut, in cm. Thin parts are thickened to at least about one voxel so they never vanish. */
	static double VOX = 1.0;

	/** The radius a {@link Tube#duct} keeps at least: a little over half the tallest side of a voxel, so a thin duct running down across the grid stays one piece. */
	static double DUCT = 0;

	/** The least radius of a thin part, in voxels: enough to light one voxel centre, more at the mod's coarse grain. */
	static double THIN = 0.55;

	/** How far past a shape its box reaches: past the thickening of thin parts at the coarsest grain (0.8 of a 4.7 cm voxel). */
	static final double PAD = 4.0;

	static final List<Part> PARTS = new ArrayList<>();

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

	/**
	 * Names of vessels, ducts and small processes thinner than a pixel: left out at the mod's coarse grain, where they
	 * would fatten to a pixel. Each generator sets its own.
	 */
	static Set<String> FINE = Set.of();

	static void part(String path, String name, Mat mat, Shape shape) {
		if (LOD >= 2.5 && FINE.contains(name)) {
			return;
		}

		PARTS.add(new Part(path, name, mat, shape));
	}

	/** Faces: 0 east +x, 1 west -x, 2 up +y, 3 down -y, 4 south +z, 5 north -z. */
	static final String[] FACE = {"east", "west", "up", "down", "south", "north"};

	static final int[][] NORMAL = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

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
	}

	/** Centimetres per voxel, for choosing detail: every rib, or only some with a gap between them. */
	static double LOD = 0.5;

	/** Mod mode: anatomy.png, each part drawn with its tissue's 16px tile as the mod draws it. */
	static BufferedImage TILES;

	static final class Box {
		final int x0, y0, z0, x1, y1, z1, key;

		Box(int x0, int y0, int z0, int x1, int y1, int z1, int key) {
			this.x0 = x0;
			this.y0 = y0;
			this.z0 = z0;
			this.x1 = x1;
			this.y1 = y1;
			this.z1 = z1;
			this.key = key;
		}
	}

	/** Greedy merge: each run of one part's voxels grown along x, then z, then y into the biggest box it fills. */
	static List<Box> boxes(Grid g) {
		return boxes(g, key -> false, Integer.MAX_VALUE);
	}

	/**
	 * {@link #boxes(Grid)}, with the boxes of the keys {@code small} accepts grown no further than {@code most} voxels along
	 * any axis: a sheet of skin or wool over a flat face would otherwise merge into one slab, and a blast should tear it
	 * into patches.
	 */
	static List<Box> boxes(Grid g, java.util.function.IntPredicate small, int most) {
		boolean[] used = new boolean[g.cell.length];
		List<Box> out = new ArrayList<>();
		for (int j = 0; j < g.ny; j++) {
			for (int k = 0; k < g.nz; k++) {
				for (int i = 0; i < g.nx; i++) {
					int key = g.at(i, j, k);
					if (key == 0 || used[(i * g.ny + j) * g.nz + k]) {
						continue;
					}

					int cap = small.test(key) ? most : Integer.MAX_VALUE;
					int i1 = i + 1;
					while (i1 < g.nx && i1 - i < cap && g.at(i1, j, k) == key && !used[(i1 * g.ny + j) * g.nz + k]) {
						i1++;
					}

					int k1 = k + 1;
					grow:
					while (k1 < g.nz && k1 - k < cap) {
						for (int a = i; a < i1; a++) {
							if (g.at(a, j, k1) != key || used[(a * g.ny + j) * g.nz + k1]) {
								break grow;
							}
						}

						k1++;
					}

					int j1 = j + 1;
					grow:
					while (j1 < g.ny && j1 - j < cap) {
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

	/** How a generator maps a voxel back into the animal: its centre in the body's centimetres, or null outside the body. */
	interface Frame {
		double[] cm(Grid g, int i, int j, int k);
	}

	/** The frame in force. The full-size files are cut in centimetres already. */
	static Frame FRAME = (g, i, j, k) -> new double[] {g.wx(i), g.wy(j), g.wz(k)};

	/** In mod mode ({@link #TILES} set), the anatomy.png tile a part is drawn with. Each generator sets its own. */
	static java.util.function.ToIntFunction<Part> TILE_OF;

	/**
	 * A muscle's face where another muscle lies next to it on the same surface: the pale seam of fascia and fat a
	 * butcher follows between two muscles, so each one reads on its own.
	 */
	static int seam(Grid g, Part p, int i, int j, int k, int[] nn, int c) {
		int own = p.index + 1;
		for (int du = -1; du <= 1; du += 2) {
			for (int along = 0; along < 2; along++) {
				int x = i;
				int y = j;
				int z = k;
				if (nn[0] != 0) {
					y += along == 0 ? du : 0;
					z += along == 1 ? du : 0;
				} else if (nn[1] != 0) {
					x += along == 0 ? du : 0;
					z += along == 1 ? du : 0;
				} else {
					x += along == 0 ? du : 0;
					y += along == 1 ? du : 0;
				}

				int q = g.at(x, y, z);
				if (q != 0 && q < own) {
					Part o = PARTS.get(q - 1);
					if (o.path.startsWith("muscles") && !o.path.equals("muscles/other") && !p.path.equals("muscles/other") && !muscle(o.path).equals(muscle(p.path))) {
						return mix(c, 0xE6D9C4, 0.55);
					}
				}
			}
		}

		return c;
	}

	/** A muscle's own group, "muscles/<region>/<muscle>": the layers of one wall (a flank's) share it, so no seam runs between them. */
	static String muscle(String path) {
		int a = path.indexOf('/');
		int b = path.indexOf('/', a + 1);
		int c = path.indexOf('/', b + 1);
		return c < 0 ? path : path.substring(0, c);
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
			int tile = TILE_OF.applyAsInt(p);
			int u = Math.floorMod(nn[0] != 0 ? k : i, 16);
			int v = Math.floorMod(nn[1] != 0 ? k : -j, 16);
			c = TILES.getRGB(tile % 8 * 16 + u, tile / 8 * 16 + v) & 0xFFFFFF;
		} else {
			double[] c0 = FRAME.cm(g, i, j, k);
			double[] c1 = FRAME.cm(g, ai, aj, ak);
			if (c1 == null) {
				c1 = c0;
			}

			c = p.mat.rgb(c0[0], c0[1], c0[2], face, c1[0], c1[1], c1[2]);
			if (p.path.startsWith("muscles")) {
				c = seam(g, p, i, j, k, nn, c);
			}
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

	static final class Rect {
		final Box box;
		final int face, w, h;
		final int[] at;

		Rect(Box box, int face, int w, int h, int[] at) {
			this.box = box;
			this.face = face;
			this.w = w;
			this.h = h;
			this.at = at;
		}
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

	static String uuid(String s) {
		return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8)).toString();
	}

	static String num(double d) {
		if (d == Math.rint(d)) {
			return Long.toString((long) d);
		}

		return String.format(Locale.ROOT, "%.3f", d).replaceAll("0+$", "");
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
