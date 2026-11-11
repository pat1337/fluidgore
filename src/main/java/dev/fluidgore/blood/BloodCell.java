package dev.fluidgore.blood;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The blood standing in one block of space, and what has soaked into or dried onto the floor of it.
 *
 * <p>The block is cut into {@link #N} by {@link #N} sub-cells, an eighth of a block (12.5 cm) on a side. Each has its
 * own floor height, taken from what the block and the one under it draw, so a cell follows stairs, slabs, carpets and
 * the inside of a cauldron, and each holds its own depth of blood in millimetres. A millimetre over a whole block is
 * a litre, so a sub-cell holds {@link #LITRES_PER_MM} litres for each millimetre it is deep. {@link BloodFluidSim}
 * moves the blood between sub-cells.
 */
public final class BloodCell {
	/** Sub-cells along one side of a block. */
	public static final int N = 8;
	public static final int COUNT = N * N;
	/** Side of a sub-cell, blocks. */
	public static final float SIZE = 1.0F / N;
	/** Litres in a sub-cell for each millimetre of depth. */
	public static final float LITRES_PER_MM = 1.0F / COUNT;
	/** {@link #ground} of a sub-cell the block fills to its top: the floor there belongs to the cell above. */
	public static final float SOLID = Float.POSITIVE_INFINITY;
	/** Share of a sub-cell a box must cover to be its floor or its wall. */
	private static final float COVER = 0.49F;
	private static final float EDGE = 1.0E-3F;
	/** A gap under a box lower than this, blocks, is sealed: blood lands on the box, not under it. */
	private static final float GAP = 0.3F;

	public final long key;
	public final int bx;
	public final int by;
	public final int bz;
	/** Order of filling: the oldest cell goes first when there are too many. Set when blood first reaches it; -1 until then. */
	public long serial = -1L;
	/**
	 * Floor of each sub-cell above {@link #by}, 0 to just under 1. NaN where the floor lies lower, in a cell below;
	 * {@link #SOLID} where the block fills the sub-cell to its top.
	 */
	public final float[] ground = new float[COUNT];
	/**
	 * How far each sub-cell's floor lies above or below its measured height, mm: the unevenness of the floor (see
	 * {@link Surface#relief}). The same at the same place for everyone. Blood runs by it; nothing is drawn of it.
	 */
	public final float[] relief = new float[COUNT];
	/** Depth of liquid blood on each sub-cell, millimetres. */
	public final float[] mm = new float[COUNT];
	/** Blood soaked into or dried onto each sub-cell's floor, as the millimetres of blood it took. */
	public final float[] soak = new float[COUNT];
	/** What the floor is made of. */
	public Surface surface = Surface.STONE;
	/** Height of the floor most of the cell has, world y; for anything that asks where the cell's blood lies. */
	public double surfaceY;
	public int rgb;
	/** Ticks since the blood here was fresh: it thickens, sets and dries with it. */
	public int age;
	public int lifetime;
	public int light;
	/** Litres of liquid in the cell, kept in step with {@link #mm}. */
	public float litres;
	/** Litres soaked in or dried on, kept in step with {@link #soak}. */
	public float soaked;
	/** Bitmask of horizontal directions in which blood has already run down the face of the ledge. */
	public int streakedEdges;
	/** True while queued for a simulation step. */
	boolean queued;
	/** Counts up whenever what is drawn of the cell changes, so its mesh is rebuilt only then. */
	public int version;
	/** Tick it was last used, for cells that hold nothing. */
	long touched;
	/** How many sub-cell edges poured blood down a step in the last flow step. */
	public int poured;
	/**
	 * For sub-cell {@code i} and side {@code k} (west, east, north, south), at {@code i * 4 + k}: how far, blocks, blood
	 * poured down from that edge in the last flow step, 0 if none did. Null until the cell first pours.
	 */
	public float @Nullable [] pourDrop;
	/** The renderer's mesh of the cell (see {@code render/FluidRenderer}). */
	public @Nullable Object mesh;
	private @Nullable BlockState hereState;
	private @Nullable BlockState belowState;

	public BloodCell(long key, int bx, int by, int bz) {
		this.key = key;
		this.bx = bx;
		this.by = by;
		this.bz = bz;
	}

	public static int index(int sx, int sz) {
		return sz * N + sx;
	}

	/** The sub-cell under local coordinate {@code v} (0..1) along one axis. */
	public static int sub(double v) {
		return Math.clamp((int) Math.floor(v * N), 0, N - 1);
	}

	/** True if sub-cell {@code i} has a floor in this cell. */
	public boolean floored(int i) {
		float g = ground[i];
		return g == g && g != SOLID;
	}

	/** True if the cell holds neither liquid nor a stain. */
	public boolean empty() {
		return litres <= 1.0E-5F && soaked <= 1.0E-5F;
	}

	/** Liquid in the cell in pool units (see {@link BloodSupply#LITRES_PER_UNIT}). */
	public float volume() {
		return litres / BloodSupply.LITRES_PER_UNIT;
	}

	/** Depth of liquid, mm, on the sub-cell under world (x, z). */
	public float depthAt(double x, double z) {
		return mm[index(sub(x - bx), sub(z - bz))];
	}

	/** True if the blood on sub-cell {@code i}, liquid and soaked in, is enough to be drawn (see {@link Surface#cover}). */
	public boolean shows(int i) {
		return mm[i] + soak[i] >= surface.cover() * 0.5F;
	}

	/** Deepest liquid in the cell, mm. */
	public float deepest() {
		float most = 0.0F;
		for (float d : mm) {
			most = Math.max(most, d);
		}

		return most;
	}

	/** Share of the cell's floor that liquid at least {@code least} mm deep covers. */
	public float cover(float least) {
		int wet = 0;
		int floor = 0;
		for (int i = 0; i < COUNT; i++) {
			if (floored(i)) {
				floor++;
				if (mm[i] >= least) {
					wet++;
				}
			}
		}

		return floor == 0 ? 0.0F : wet / (float) floor;
	}

	/** Adds {@code amount} mm of liquid (or takes it, if negative) on sub-cell {@code i}; returns what was really added. */
	float add(int i, float amount) {
		float before = mm[i];
		float after = Math.max(0.0F, before + amount);
		mm[i] = after;
		litres = Math.max(0.0F, litres + (after - before) * LITRES_PER_MM);
		return after - before;
	}

	/** Adds {@code amount} mm to the stain on sub-cell {@code i} (or lifts it, if negative). */
	void stain(int i, float amount) {
		float before = soak[i];
		float after = Math.max(0.0F, before + amount);
		soak[i] = after;
		soaked = Math.max(0.0F, soaked + (after - before) * LITRES_PER_MM);
	}

	/** Recounts {@link #litres} and {@link #soaked} from the sub-cells, so rounding never builds up. */
	void recount() {
		float l = 0.0F;
		float s = 0.0F;
		for (int i = 0; i < COUNT; i++) {
			l += mm[i];
			s += soak[i];
		}

		litres = l * LITRES_PER_MM;
		soaked = s * LITRES_PER_MM;
	}

	/**
	 * How many times longer than a film this cell's blood lasts. A film dries in minutes; a pool that stands
	 * centimetres deep is still wet hours later. Up to three litres in the block count as a film; from there every five
	 * litres add as long again, to twenty-one times as long for a pit.
	 */
	public float keeps() {
		return 1.0F + Math.min(20.0F, Math.max(0.0F, litres - 3.0F) / 5.0F);
	}

	/** Ticks the cell lasts: its lifetime, longer the more blood stands in it (see {@link #keeps}). */
	public int lasts() {
		return Math.round(lifetime * keeps());
	}

	/** 0 fresh to 1 dry, over the first half of its life. */
	public float dryness() {
		return Math.min(1.0F, age / (lasts() * 0.5F));
	}

	/** Fades the cell out over the end of its life. */
	public float fade() {
		int fade = Math.min(100, lifetime / 4);
		int left = lasts() - age;
		if (left >= fade) {
			return 1.0F;
		}

		float t = Math.max(0.0F, left / (float) fade);
		return t * t * (3.0F - 2.0F * t);
	}

	/**
	 * Reads the floor of every sub-cell from the block here and the one under it. Returns false if neither block has
	 * changed since the last reading (nothing is read again).
	 */
	boolean measure(Level level) {
		BlockPos here = new BlockPos(bx, by, bz);
		BlockPos under = here.below();
		BlockState state = level.getBlockState(here);
		BlockState below = level.getBlockState(under);
		if (state == hereState && below == belowState) {
			return false;
		}

		hereState = state;
		belowState = below;
		List<AABB> upper = state.isAir() ? List.of() : SurfaceProbe.shape(level, here, state).toAabbs();
		List<AABB> lower = below.isAir() ? List.of() : SurfaceProbe.shape(level, under, below).toAabbs();
		for (int sz = 0; sz < N; sz++) {
			for (int sx = 0; sx < N; sx++) {
				ground[index(sx, sz)] = floor(upper, lower, sx * SIZE, sz * SIZE);
			}
		}

		surface = Surfaces.of(upper.isEmpty() ? below : state);
		surfaceY = by + commonGround();
		for (int sz = 0; sz < N; sz++) {
			for (int sx = 0; sx < N; sx++) {
				relief[index(sx, sz)] = surface.relief * unevenness(bx * N + sx, bz * N + sz);
			}
		}

		return true;
	}

	/**
	 * The lie of the land at a sub-cell, -1 to 1: slow swells three sub-cells across with a little grain on them, read
	 * from where the sub-cell is in the world and nothing else.
	 */
	private static float unevenness(int gx, int gz) {
		int ix = Math.floorDiv(gx, 3);
		int iz = Math.floorDiv(gz, 3);
		float fx = (gx - ix * 3) / 3.0F;
		float fz = (gz - iz * 3) / 3.0F;
		fx = fx * fx * (3.0F - 2.0F * fx);
		fz = fz * fz * (3.0F - 2.0F * fz);
		float a = hash(ix, iz);
		float b = hash(ix + 1, iz);
		float c = hash(ix, iz + 1);
		float d = hash(ix + 1, iz + 1);
		float swell = a + (b - a) * fx + (c + (d - c) * fx - a - (b - a) * fx) * fz;
		return swell * 0.75F + hash(gx * 7 + 3, gz * 7 + 5) * 0.25F;
	}

	/** A fixed number from -1 to 1 for a pair of integers. */
	private static float hash(int x, int z) {
		int h = x * 374761393 + z * 668265263;
		h = (h ^ h >>> 13) * 1274126177;
		h ^= h >>> 16;
		return (h & 0xFFFF) / 32767.5F - 1.0F;
	}

	/** The floor under the sub-cell whose low corner is local (x, z), from the boxes of the block here and the one below. */
	private static float floor(List<AABB> upper, List<AABB> lower, float x, float z) {
		// What the block below puts under it: flush with this block's bottom, or nothing.
		float floor = Float.NaN;
		for (AABB box : lower) {
			if (box.maxY >= 1.0 - EDGE && covers(box, x, z)) {
				floor = 0.0F;
				break;
			}
		}

		// The block here stacks on that. A gap over the floor leaves the floor open (blood runs under a top slab), unless
		// it is too low to count (the space between a cauldron's feet): then the box stands on the floor.
		boolean grew = true;
		while (grew) {
			grew = false;
			for (AABB box : upper) {
				if (!covers(box, x, z)) {
					continue;
				}

				if (floor != floor) {
					if (box.minY <= EDGE) {
						floor = (float) box.maxY;
						grew = true;
					}
				} else if (box.minY <= floor + GAP && box.maxY > floor + EDGE) {
					floor = (float) box.maxY;
					grew = true;
				}
			}
		}

		if (floor != floor) {
			// Nothing reaches down to this block's bottom: a box hanging in it (a top slab over air) is a floor of its own.
			float lowest = Float.NaN;
			for (AABB box : upper) {
				if (covers(box, x, z) && (lowest != lowest || box.maxY < lowest)) {
					lowest = (float) box.maxY;
				}
			}

			return lowest != lowest ? Float.NaN : lowest >= 1.0F - EDGE ? SOLID : lowest;
		}

		return floor >= 1.0F - EDGE ? SOLID : floor;
	}

	private static boolean covers(AABB box, float x, float z) {
		double ox = Math.min(x + SIZE, box.maxX) - Math.max(x, box.minX);
		double oz = Math.min(z + SIZE, box.maxZ) - Math.max(z, box.minZ);
		return ox > 0.0 && oz > 0.0 && ox * oz >= COVER * SIZE * SIZE;
	}

	/** The floor height most sub-cells share (0 if the cell has no floor at all). */
	private float commonGround() {
		float best = 0.0F;
		int most = 0;
		for (int i = 0; i < COUNT; i++) {
			if (!floored(i)) {
				continue;
			}

			int same = 0;
			for (int j = 0; j < COUNT; j++) {
				if (floored(j) && Math.abs(ground[j] - ground[i]) < 0.01F) {
					same++;
				}
			}

			if (same > most) {
				most = same;
				best = ground[i];
			}
		}

		return best;
	}
}
