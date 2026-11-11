package dev.fluidgore.blood;

import java.util.Arrays;
import net.minecraft.core.Direction;

/**
 * Marks cut into the game's own pixels.
 *
 * <p>A mark is a shape from blood.png laid on a floor or a wall wherever a drop came down and as big as the drop made
 * it. Drawn as it is, its outline would run across the pixels of the block under it, and its own pixels would be a
 * different size from the block's. Here it is cut into the block's pixels instead, {@code texels} of them to a block,
 * on the grid the block's own texture uses: a pixel belongs to the mark if the shape covers enough of it, and a mark
 * too small to cover that much of any pixel is the one pixel it covers most of. The blood on the ground is cut the same
 * way (see {@code render/FluidRenderer}), so a pool and the spatter round it are the same pixels.
 *
 * <p>Called from the render thread only; what it works out is kept on the mark until its pieces or the resolution
 * change.
 */
public final class MarkPixels {
	private static final int SIZE = MarkMasks.SIZE;
	/** The share of a pixel a mark's shape has to cover for the pixel to be part of the mark. */
	private static final float COVERED = 0.45F;
	/** Summed areas of each tile's shape, so the blood in any rectangle of a tile is four lookups. */
	private static final int[][] SUMS = sums();
	/** Scratch for one piece: a block of pixels at 64 to a block is the most a piece can be. */
	private static final int SPAN = 66;
	private static final boolean[] ON = new boolean[SPAN * SPAN];
	private static float[] out = new float[Decal.PIECE_STRIDE * 64];
	private static int count;

	private MarkPixels() {
	}

	private static int[][] sums() {
		int[][] sums = new int[MarkMasks.ROWS.length][];
		int stride = SIZE + 1;
		for (int tile = 0; tile < sums.length; tile++) {
			int[] sum = new int[stride * stride];
			for (int y = 0; y < SIZE; y++) {
				long row = MarkMasks.ROWS[tile][y];
				int run = 0;
				for (int x = 0; x < SIZE; x++) {
					run += (int) (row >>> x & 1L);
					sum[(y + 1) * stride + x + 1] = sum[y * stride + x + 1] + run;
				}
			}

			sums[tile] = sum;
		}

		return sums;
	}

	/**
	 * The rectangles of pixels {@code d} is made of at {@code texels} pixels to a block, each as a piece of a mark is: a0,
	 * b0, a1, b1 from the mark's centre and its height off the mark's plane. Their edges lie on the pixel grid.
	 */
	public static float[] of(Decal d, int texels) {
		if (d.pixels != null && d.pixelsFor == texels) {
			return d.pixels;
		}

		count = 0;
		int[] sums = d.tile >= 0 && d.tile < SUMS.length ? SUMS[d.tile] : null;
		double size = d.half * 2.0;
		double texel = 1.0 / texels;
		float most = 0.0F;
		int mostI = 0;
		int mostJ = 0;
		float mostH = 0.0F;
		for (int p = 0; p < d.pieces.length; p += Decal.PIECE_STRIDE) {
			// The pixels whose middles are on this piece.
			int i0 = (int) Math.ceil((d.centerA + d.pieces[p]) * texels - 0.5 - 1.0E-4);
			int i1 = (int) Math.floor((d.centerA + d.pieces[p + 2]) * texels - 0.5 + 1.0E-4);
			int j0 = (int) Math.ceil((d.centerB + d.pieces[p + 1]) * texels - 0.5 - 1.0E-4);
			int j1 = (int) Math.floor((d.centerB + d.pieces[p + 3]) * texels - 0.5 + 1.0E-4);
			int wide = Math.min(i1 - i0 + 1, SPAN);
			int high = Math.min(j1 - j0 + 1, SPAN);
			if (wide <= 0 || high <= 0) {
				continue;
			}

			float h = d.pieces[p + 4];
			for (int j = 0; j < high; j++) {
				for (int i = 0; i < wide; i++) {
					float covered = sums == null ? 1.0F : covered(d, sums, size, (i0 + i) * texel - d.centerA, (j0 + j) * texel - d.centerB, texel);
					ON[j * SPAN + i] = covered >= COVERED;
					if (covered > most) {
						most = covered;
						mostI = i0 + i;
						mostJ = j0 + j;
						mostH = h;
					}
				}
			}

			rectangles(d, texel, i0, j0, wide, high, h);
		}

		if (count == 0 && d.pieces.length > 0) {
			// Too small to cover that much of any pixel: the pixel it covers most of, or else the one its middle is in.
			if (most <= 0.0F) {
				mostI = (int) Math.floor(d.centerA * texels);
				mostJ = (int) Math.floor(d.centerB * texels);
				mostH = heightAtCentre(d);
			}

			put((float) (mostI * texel - d.centerA), (float) (mostJ * texel - d.centerB), (float) ((mostI + 1) * texel - d.centerA),
					(float) ((mostJ + 1) * texel - d.centerB), mostH);
		}

		d.pixels = Arrays.copyOf(out, count * Decal.PIECE_STRIDE);
		d.pixelsFor = texels;
		return d.pixels;
	}

	/** The height of the piece the mark's middle lies on, or of its first piece. */
	private static float heightAtCentre(Decal d) {
		for (int p = 0; p < d.pieces.length; p += Decal.PIECE_STRIDE) {
			if (d.pieces[p] <= 0.0F && d.pieces[p + 2] >= 0.0F && d.pieces[p + 1] <= 0.0F && d.pieces[p + 3] >= 0.0F) {
				return d.pieces[p + 4];
			}
		}

		return d.pieces[4];
	}

	/**
	 * How much of the pixel whose low corner is (a, b) from the mark's centre the mark's shape covers, 0 to 1. The pixel
	 * is found on the mark's tile the way the tile is laid on the mark when it is drawn smooth (see
	 * {@code GoreRenderer}), turned and flipped as the mark is.
	 */
	private static float covered(Decal d, int[] sums, double size, double a, double b, double texel) {
		double u0 = (a + d.half) / size;
		double u1 = (a + texel + d.half) / size;
		double v0 = (b + d.half) / size;
		double v1 = (b + texel + d.half) / size;
		if (d.face != Direction.UP) {
			// On walls b is height: the top of the tile is at the top of the wall.
			double t = 1.0 - v1;
			v1 = 1.0 - v0;
			v0 = t;
		}

		if ((d.orient & 4) != 0) {
			double t = u0;
			u0 = v0;
			v0 = t;
			t = u1;
			u1 = v1;
			v1 = t;
		}

		if ((d.orient & 1) != 0) {
			double t = 1.0 - u1;
			u1 = 1.0 - u0;
			u0 = t;
		}

		if ((d.orient & 2) != 0) {
			double t = 1.0 - v1;
			v1 = 1.0 - v0;
			v0 = t;
		}

		double x0 = u0 * SIZE;
		double x1 = u1 * SIZE;
		double y0 = v0 * SIZE;
		double y1 = v1 * SIZE;
		if (x1 - x0 < 2.0) {
			// The tile's own pixels are nearly as big as this one, or bigger: what the tile has in the middle of it.
			int x = (int) Math.floor((x0 + x1) * 0.5);
			int y = (int) Math.floor((y0 + y1) * 0.5);
			return x < 0 || y < 0 || x >= SIZE || y >= SIZE ? 0.0F : sums[(y + 1) * (SIZE + 1) + x + 1] - sums[y * (SIZE + 1) + x + 1]
					- sums[(y + 1) * (SIZE + 1) + x] + sums[y * (SIZE + 1) + x];
		}

		int ix0 = Math.clamp((int) Math.round(x0), 0, SIZE);
		int ix1 = Math.clamp((int) Math.round(x1), 0, SIZE);
		int iy0 = Math.clamp((int) Math.round(y0), 0, SIZE);
		int iy1 = Math.clamp((int) Math.round(y1), 0, SIZE);
		if (ix1 <= ix0 || iy1 <= iy0) {
			return 0.0F;
		}

		int stride = SIZE + 1;
		int blood = sums[iy1 * stride + ix1] - sums[iy0 * stride + ix1] - sums[iy1 * stride + ix0] + sums[iy0 * stride + ix0];
		// Of the whole pixel, so one that hangs over the edge of the tile counts what is off it as bare.
		return (float) (blood / ((x1 - x0) * (y1 - y0)));
	}

	/** Gathers the pixels marked in {@link #ON} into as few rectangles as a simple sweep finds. */
	private static void rectangles(Decal d, double texel, int i0, int j0, int wide, int high, float h) {
		for (int j = 0; j < high; j++) {
			int i = 0;
			while (i < wide) {
				if (!ON[j * SPAN + i]) {
					i++;
					continue;
				}

				int end = i;
				while (end < wide && ON[j * SPAN + end]) {
					end++;
				}

				// Down as far as the rows below have all of this run.
				int rows = 1;
				while (j + rows < high && all(j + rows, i, end)) {
					rows++;
				}

				for (int r = 0; r < rows; r++) {
					Arrays.fill(ON, (j + r) * SPAN + i, (j + r) * SPAN + end, false);
				}

				put((float) ((i0 + i) * texel - d.centerA), (float) ((j0 + j) * texel - d.centerB), (float) ((i0 + end) * texel - d.centerA),
						(float) ((j0 + j + rows) * texel - d.centerB), h);
				i = end;
			}
		}
	}

	private static boolean all(int row, int from, int to) {
		for (int i = from; i < to; i++) {
			if (!ON[row * SPAN + i]) {
				return false;
			}
		}

		return true;
	}

	private static void put(float a0, float b0, float a1, float b1, float h) {
		int o = count * Decal.PIECE_STRIDE;
		if (o + Decal.PIECE_STRIDE > out.length) {
			out = Arrays.copyOf(out, out.length * 2);
		}

		out[o] = a0;
		out[o + 1] = b0;
		out[o + 2] = a1;
		out[o + 3] = b1;
		out[o + 4] = h;
		count++;
	}
}
