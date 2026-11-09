package dev.fluidgore.anatomy;

import dev.fluidgore.anatomy.Anatomy.Kind;
import dev.fluidgore.anatomy.Anatomy.Piece;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * What lies under a point on a body's surface, read off an anatomy table: the tissue at a given depth, or every tissue
 * in order inward. This is the one question wounds, fire and arrows ask of the body ("what is under this point, how
 * deep"), so that a cut in the flank finds the belly wall's layers and the rumen behind them, a cut over the loin the
 * loin eye and a rib, and an arrow in the chest a lung.
 *
 * <p>Points, depths and the pieces are all in the table's canonical pixels (see {@link Anatomy.Region}); a caller turns
 * a cube's own pixels into them with the inverse of {@link Anatomy#fit}. Each table is indexed once, in a coarse grid of
 * cells that list the pieces reaching into them, so a lookup touches a handful of pieces rather than the whole table.
 */
public final class Probe {
	/** One stretch of tissue on the way in: {@code piece} from {@code from} to {@code to} pixels under the surface. */
	public record Layer(Piece piece, float from, float to) {
		public Kind kind() {
			return piece.kind();
		}

		public String name() {
			return piece.name();
		}
	}

	/** How far between two samples on the way in, in canonical pixels: a quarter of the finest voxel. */
	private static final float STEP = 0.125F;
	/** The grid's cell size, canonical pixels. */
	private static final float CELL = 1.0F;

	private static final Map<Piece[], Index> INDEXES = new IdentityHashMap<>();

	private Probe() {
	}

	/** The piece of {@code table} that holds the point, or null where there is none (outside the body, or a cavity). */
	public static @Nullable Piece at(Piece[] table, float x, float y, float z) {
		Index index = index(table);
		int[] candidates = index.cell(x, y, z);
		if (candidates == null) {
			return null;
		}

		for (int k : candidates) {
			Piece p = table[k];
			if (x >= p.x0() && x < p.x1() && y >= p.y0() && y < p.y1() && z >= p.z0() && z < p.z1()) {
				return p;
			}
		}

		return null;
	}

	/**
	 * The piece {@code depth} pixels under the surface point {@code at}, going in along {@code axis} from the face on
	 * side {@code sign} (the face at the cube's max for a positive sign). Null where nothing lies that deep.
	 */
	public static @Nullable Piece under(Piece[] table, float[] at, int axis, int sign, float depth) {
		float x = at[0];
		float y = at[1];
		float z = at[2];
		float d = -sign * depth;
		return at(table, axis == 0 ? x + d : x, axis == 1 ? y + d : y, axis == 2 ? z + d : z);
	}

	/**
	 * Every tissue from the surface point {@code at} inward to {@code depth}, in order, each with how deep it starts and
	 * ends. Gaps (nothing in the table) are left out, so the first layer may start deeper than zero.
	 */
	public static List<Layer> layers(Piece[] table, float[] at, int axis, int sign, float depth) {
		List<Layer> out = new ArrayList<>();
		Piece last = null;
		float started = 0.0F;
		float d = 0.0F;
		for (; d <= depth; d += STEP) {
			Piece here = under(table, at, axis, sign, d + STEP * 0.5F);
			if (here != last) {
				if (last != null) {
					out.add(new Layer(last, started, d));
				}

				last = here;
				started = d;
			}
		}

		if (last != null) {
			out.add(new Layer(last, started, d));
		}

		return out;
	}

	/**
	 * Whether any tissue that {@code what} accepts lies in the first {@code within} pixels under the body's own surface
	 * at this point: the column is read from {@code outward} pixels outside the face (a sheep's back and belly reach
	 * past its body cube into the fleece, so the real skin can lie outside the cube) to {@code inward} pixels inside it,
	 * and the first tissue met is the surface. Wool lies outside the skin and is passed over.
	 */
	public static boolean underSkin(Piece[] table, float[] at, int axis, int sign, float outward, float inward, float within, Predicate<Kind> what) {
		float skin = Float.NaN;
		for (float d = -outward + STEP * 0.5F; d <= inward; d += STEP) {
			Piece here = under(table, at, axis, sign, d);
			if (here == null || here.kind() == Kind.WOOL) {
				continue;
			}

			if (Float.isNaN(skin)) {
				skin = d;
			}

			if (d - skin > within) {
				return false;
			}

			if (what.test(here.kind())) {
				return true;
			}
		}

		return false;
	}

	/** Whether any tissue that {@code what} accepts lies within {@code depth} under the surface point. */
	public static boolean has(Piece[] table, float[] at, int axis, int sign, float depth, Predicate<Kind> what) {
		for (float d = STEP * 0.5F; d <= depth; d += STEP) {
			Piece here = under(table, at, axis, sign, d);
			if (here != null && what.test(here.kind())) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Takes a point in a cube's own pixels to the canonical pixels of {@code fit} (as {@link Anatomy#fit} gives it:
	 * {@code real = canonical * scale + offset}, per axis).
	 */
	public static float[] canonical(float[] fit, float x, float y, float z) {
		return new float[] {(x - fit[3]) / fit[0], (y - fit[4]) / fit[1], (z - fit[5]) / fit[2]};
	}

	/**
	 * Which way the face of a cube on side {@code sign} of its axis {@code axis} faces in canonical pixels: a fit that
	 * runs an axis backwards turns the face's side round too.
	 */
	public static int canonicalSign(float[] fit, int axis, int sign) {
		return fit[axis] < 0.0F ? -sign : sign;
	}

	private static Index index(Piece[] table) {
		Index index = INDEXES.get(table);
		if (index == null) {
			index = new Index(table);
			INDEXES.put(table, index);
		}

		return index;
	}

	/** A grid over a table's extent; each cell lists the pieces that reach into it. */
	private static final class Index {
		private final float ox;
		private final float oy;
		private final float oz;
		private final int nx;
		private final int ny;
		private final int nz;
		private final int[] @Nullable [] cells;

		Index(Piece[] table) {
			float x0 = Float.MAX_VALUE;
			float y0 = Float.MAX_VALUE;
			float z0 = Float.MAX_VALUE;
			float x1 = -Float.MAX_VALUE;
			float y1 = -Float.MAX_VALUE;
			float z1 = -Float.MAX_VALUE;
			for (Piece p : table) {
				x0 = Math.min(x0, p.x0());
				y0 = Math.min(y0, p.y0());
				z0 = Math.min(z0, p.z0());
				x1 = Math.max(x1, p.x1());
				y1 = Math.max(y1, p.y1());
				z1 = Math.max(z1, p.z1());
			}

			if (table.length == 0) {
				x0 = y0 = z0 = 0.0F;
				x1 = y1 = z1 = CELL;
			}

			ox = x0;
			oy = y0;
			oz = z0;
			nx = Math.max(1, (int) Math.ceil((x1 - x0) / CELL) + 1);
			ny = Math.max(1, (int) Math.ceil((y1 - y0) / CELL) + 1);
			nz = Math.max(1, (int) Math.ceil((z1 - z0) / CELL) + 1);
			IntArrayList[] lists = new IntArrayList[nx * ny * nz];
			for (int k = 0; k < table.length; k++) {
				Piece p = table[k];
				int i0 = clampX((int) Math.floor((p.x0() - ox) / CELL));
				int i1 = clampX((int) Math.floor((p.x1() - ox) / CELL));
				int j0 = clampY((int) Math.floor((p.y0() - oy) / CELL));
				int j1 = clampY((int) Math.floor((p.y1() - oy) / CELL));
				int l0 = clampZ((int) Math.floor((p.z0() - oz) / CELL));
				int l1 = clampZ((int) Math.floor((p.z1() - oz) / CELL));
				for (int i = i0; i <= i1; i++) {
					for (int j = j0; j <= j1; j++) {
						for (int l = l0; l <= l1; l++) {
							int c = (i * ny + j) * nz + l;
							if (lists[c] == null) {
								lists[c] = new IntArrayList(4);
							}

							lists[c].add(k);
						}
					}
				}
			}

			cells = new int[lists.length][];
			for (int c = 0; c < lists.length; c++) {
				cells[c] = lists[c] == null ? null : lists[c].toIntArray();
			}
		}

		int @Nullable [] cell(float x, float y, float z) {
			int i = (int) Math.floor((x - ox) / CELL);
			int j = (int) Math.floor((y - oy) / CELL);
			int l = (int) Math.floor((z - oz) / CELL);
			if (i < 0 || j < 0 || l < 0 || i >= nx || j >= ny || l >= nz) {
				return null;
			}

			return cells[(i * ny + j) * nz + l];
		}

		private int clampX(int i) {
			return Math.clamp(i, 0, nx - 1);
		}

		private int clampY(int j) {
			return Math.clamp(j, 0, ny - 1);
		}

		private int clampZ(int l) {
			return Math.clamp(l, 0, nz - 1);
		}
	}
}
