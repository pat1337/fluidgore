package dev.fluidgore.blood;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A flat blood mark on one block face direction. The square decal is pre-split into "pieces" (sub-rectangles
 * that each lie on a real surface), which is how it follows stairs/slabs and stops at ledges instead of
 * hanging in the air. Pieces are computed once when the decal is placed.
 */
public final class Decal {
	/** Floats per piece: a0, b0, a1, b1 (tangent-plane rectangle) and h (plane coordinate along the normal). */
	public static final int PIECE_STRIDE = 5;
	/**
	 * The share of its life in which a mark dries. A mark is a film a fraction of a millimetre thick: it has gone dark
	 * and dull while the pool beside it is still wet.
	 */
	private static final float DRIES_IN = 0.2F;

	public final long serial;
	public final Direction face;
	/** Centre in world space; for floors (a, b) = (x, z), for walls the two tangent axes. */
	public final double centerA;
	public final double centerB;
	public final float half;
	/** The shape: a tile of blood.png, or {@link BloodTextures#RUN}. */
	public final int tile;
	/** 0-7: bit 0 flips U, bit 1 flips V, bit 2 swaps U/V. Gives 8 orientations without UVs leaving the tile. */
	public final int orient;
	public final int rgb;
	public final boolean dries;
	/** Re-trimmed when the ground under some of them goes (see DecalManager): a piece over nothing is dropped. */
	public float[] pieces;
	public final BlockPos anchor;
	/** Base plane along the normal: surface Y for floor decals, the face coordinate for wall decals. */
	public final double plane;
	public final int lifetime;
	public int age;
	public int light;
	/** True on a floor that drinks blood (turf, sand, wool): the mark is a stain in it, not a film on it. */
	public boolean porous;
	/**
	 * A run down a wall that is still creeping: the blood it still has to lay down, pool units. Nothing once it has
	 * stopped, and for every mark that is not a run.
	 */
	public float carried;
	/** The mark cut into pixels (see {@link MarkPixels}) at {@link #pixelsFor} to a block; null until asked for. */
	float[] pixels;
	int pixelsFor;

	public Decal(long serial, Direction face, double centerA, double centerB, float half, int tile, int orient, int rgb,
			boolean dries, float[] pieces, BlockPos anchor, double plane, int lifetime, int light) {
		this.serial = serial;
		this.face = face;
		this.centerA = centerA;
		this.centerB = centerB;
		this.half = half;
		this.tile = tile;
		this.orient = orient;
		this.rgb = rgb;
		this.dries = dries;
		this.pieces = pieces;
		this.anchor = anchor;
		this.plane = plane;
		this.lifetime = lifetime;
		this.light = light;
	}

	public boolean expired() {
		return age >= lifetime;
	}

	/** Opacity: fully visible, then a smooth fade over the last quarter of life (max 5 s). */
	public float alpha() {
		int fade = Math.min(100, lifetime / 4);
		int left = lifetime - age;
		if (left >= fade) {
			return 1.0F;
		}

		float t = Math.max(0.0F, left / (float) fade);
		return t * t * (3.0F - 2.0F * t);
	}

	/** Drying factor 0 (fresh) to 1 (dry), over the first part of its life. */
	public float dryness() {
		return dries ? Math.min(1.0F, age / (lifetime * DRIES_IN)) : 0.0F;
	}

	/** New pieces for the mark: what was cut from the old ones is thrown away. */
	void reshape(float[] pieces) {
		this.pieces = pieces;
		this.pixels = null;
	}
}
