package dev.fluidgore.blood;

/**
 * One block column's worth of pooled blood resting on a surface. Volume is in "pool units": 1.0 is roughly a
 * full block covered by a thick pool. Cells exchange volume with their four neighbours (see {@link BloodFluidSim}).
 */
public final class BloodCell {
	/** Volume at which the puddle covers the whole block. */
	public static final float FULL = 0.6F;

	public final long key;
	public final int bx;
	public final int by;
	public final int bz;
	public final long serial;
	/** Surface height at the block centre, used for flow. */
	public double surfaceY;
	/**
	 * Surface heights of the four half-block quadrants (-x-z, +x-z, -x+z, +x+z) relative to {@link #surfaceY},
	 * NaN where there is no surface. Lets a puddle on stairs follow both steps.
	 */
	public final float[] quadrantHeights = new float[4];
	public float volume;
	public int rgb;
	public int age;
	public int lifetime;
	public int light;
	/** Bitmask of horizontal directions that already got a drip streak down their ledge. */
	public int streakedEdges;
	/** True while queued for a simulation step. */
	public boolean queued;
	/** Cached by the renderer: whether the cell is surrounded by enough neighbours to render as a full pool. */
	public boolean pooled;

	public BloodCell(long key, int bx, int by, int bz, long serial) {
		this.key = key;
		this.bx = bx;
		this.by = by;
		this.bz = bz;
		this.serial = serial;
	}

	/** Visual half-size of the puddle within its block (0.5 = whole block). */
	public float halfSize() {
		float shrink = 1.0F - 0.25F * dryness();
		return Math.max(0.07F, Math.min(0.5F, 0.5F * (float) Math.sqrt(volume / FULL))) * shrink;
	}

	public float dryness() {
		return Math.min(1.0F, age / (lifetime * 0.5F));
	}

	public float alpha() {
		float thickness = Math.min(0.97F, 0.72F + volume * 1.2F);
		int fade = Math.min(100, lifetime / 4);
		int left = lifetime - age;
		if (left >= fade) {
			return thickness;
		}

		float t = Math.max(0.0F, left / (float) fade);
		return thickness * t * t * (3.0F - 2.0F * t);
	}
}
