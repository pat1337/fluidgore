package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.Arrays;
import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Blood on the ground as a fluid of a fixed amount, measured in litres.
 *
 * <p>It moves the way the game's water does, as far as a liquid that is not a block can: it runs to lower ground,
 * spreads on the flat, goes round walls, fills a pit from the bottom up and overflows its rim, and falls off ledges.
 * Unlike water it is never made or lost on the way: every litre came out of a body, and what lies on the ground plus
 * what has soaked into it is always what came out.
 *
 * <p>Each block of space is a {@link BloodCell} of 8 by 8 sub-cells. Every sub-cell has a head, its floor height plus
 * its depth of blood, and each step it passes blood to any neighbour with a lower head. A film thinner than the floor
 * holds ({@link Surface#hold}) does not move, which is what stops a pool spreading for ever; blood thickens as it
 * sets, so a pool stops running after a while even where it could go on. Porous floors drink it
 * ({@link Surface#soakRate}), and what is left dries to a stain.
 *
 * <p>Only cells whose blood recently moved are queued, and at most {@link GoreConfig#maxActiveCellsPerTick} are
 * stepped a tick, so the cost is bounded however much blood there is.
 */
public final class BloodFluidSim {
	/** Share of a head difference a sub-cell passes on in one step. */
	private static final float RATE = 0.22F;
	/** Smallest transfer worth making, mm. */
	private static final float MIN_MOVE = 0.02F;
	/** Most a sub-cell sheds over a ledge in one step, mm. */
	private static final float SPILL_MM = 3.0F;
	/** A step down of more than this, blocks, is a fall: the blood pours down it. */
	private static final float FALL = 0.03F;
	/** Ticks before fresh blood starts to thicken. */
	private static final int GEL_START = 200;
	/** Ticks until a thin film has set and no longer runs; a deep pool takes {@link #GEL_PER_LITRE} longer per litre. */
	private static final int GEL_BASE = 700;
	private static final int GEL_PER_LITRE = 40;
	/** Thinner than this, mm, a film is a stain: it dries where it lies. */
	public static final float FILM = 0.12F;
	private static final int SETTLE_EVERY = 10;
	/** How many of the oldest cells are looked through for the one to give up at the cap. */
	private static final int OLDEST = 64;
	/** The most of a spill over a ledge, pool units, that runs down the ledge's face instead of falling clear of it. */
	private static final float RUNS_OFF_LEDGE = 0.005F;
	private static final int[] DX = {-1, 1, 0, 0};
	private static final int[] DZ = {0, 0, -1, 1};
	private static final Direction[] SIDE = {Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH};

	private final BloodWorld world;
	/** Cells that hold blood or a stain, oldest first. */
	private final Long2ObjectLinkedOpenHashMap<BloodCell> cells = new Long2ObjectLinkedOpenHashMap<>();
	/** Floors measured for blood to flow onto that hold nothing yet. */
	private final Long2ObjectOpenHashMap<BloodCell> dry = new Long2ObjectOpenHashMap<>();
	private final LongArrayFIFOQueue active = new LongArrayFIFOQueue();
	private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
	private int now;

	// Scratch for one flow step.
	private final float[] snap = new float[BloodCell.COUNT];
	private final float[] delta = new float[BloodCell.COUNT];
	private final float[] out = new float[4];
	private final BloodCell[] target = new BloodCell[4];
	private final int[] targetIndex = new int[4];
	private final float[] targetDrop = new float[4];
	private final BloodCell[] sides = new BloodCell[4];
	private final boolean[] sideKnown = new boolean[4];
	private final float[] spilled = new float[4];
	private final double[] spillX = new double[4];
	private final double[] spillZ = new double[4];
	private final double[] spillY = new double[4];
	// Scratch for one pour.
	private final BloodCell[] pourCell = new BloodCell[49];
	private final int[] pourIndex = new int[49];
	private final float[] pourWeight = new float[49];

	BloodFluidSim(BloodWorld world) {
		this.world = world;
	}

	/** Every cell that holds blood or a stain. */
	public Collection<BloodCell> cells() {
		return cells.values();
	}

	public int size() {
		return cells.size();
	}

	/** The cell of block (x, y, z) if blood or a stain lies in it. */
	public @Nullable BloodCell cellAt(int x, int y, int z) {
		return cells.get(BlockPos.asLong(x, y, z));
	}

	/** The cell of block (x, y, z) if its floor has been measured, wet or dry; nothing is measured for the asking. */
	public @Nullable BloodCell known(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		BloodCell cell = cells.get(key);
		return cell != null ? cell : dry.get(key);
	}

	/** Litres of liquid blood lying about. */
	public float litres() {
		float sum = 0.0F;
		for (BloodCell cell : cells.values()) {
			sum += cell.litres;
		}

		return sum;
	}

	/** Litres soaked into floors or dried onto them. */
	public float soaked() {
		float sum = 0.0F;
		for (BloodCell cell : cells.values()) {
			sum += cell.soaked;
		}

		return sum;
	}

	/** The cell of block (bx, by, bz), its floor measured if it was not: null only where the world is not loaded. */
	private @Nullable BloodCell floorAt(Level level, int bx, int by, int bz) {
		long key = BlockPos.asLong(bx, by, bz);
		BloodCell cell = cells.get(key);
		if (cell != null) {
			return cell;
		}

		cell = dry.get(key);
		if (cell == null) {
			if (!level.isLoaded(probe.set(bx, by, bz))) {
				return null;
			}

			cell = new BloodCell(key, bx, by, bz);
			cell.measure(level);
			dry.put(key, cell);
		} else if (now - cell.touched > 20) {
			// A floor kept for a while may have been built on or dug out since.
			cell.measure(level);
		}

		cell.touched = now;
		return cell;
	}

	/**
	 * Adds blood in pool units (see {@link BloodSupply#LITRES_PER_UNIT}) at (x, z) on the floor at {@code surfaceY}, a
	 * top-face height from {@link SurfaceProbe}. Returns the cell it landed in, or null if there is no floor there.
	 */
	public @Nullable BloodCell addVolume(Level level, double x, double surfaceY, double z, float volume, int rgb, int lifetime) {
		return pour(level, x, surfaceY, z, volume * BloodSupply.LITRES_PER_UNIT, rgb, lifetime);
	}

	/**
	 * Pours {@code litres} of blood onto the floor at (x, surfaceY, z). A splash lands over a patch as wide as it is
	 * big, then runs on from there.
	 */
	public @Nullable BloodCell pour(Level level, double x, double surfaceY, double z, float litres, int rgb, int lifetime) {
		if (!(litres > 0.0F)) {
			return null;
		}

		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		int by = SurfaceProbe.airBlockY(surfaceY);
		BloodCell cell = floorAt(level, bx, by, bz);
		if (cell == null) {
			return null;
		}

		int sx = BloodCell.sub(x - bx);
		int sz = BloodCell.sub(z - bz);
		int at = landing(cell, sx, sz, (float) (surfaceY - by));
		if (at < 0) {
			// Not this cell's floor: under the point it lies a block lower, or the point is on top of this block.
			float g = cell.ground[BloodCell.index(sx, sz)];
			cell = floorAt(level, bx, g == BloodCell.SOLID ? by + 1 : by - 1, bz);
			if (cell == null) {
				return null;
			}

			at = landing(cell, sx, sz, Float.NaN);
			if (at < 0) {
				return null;
			}
		}

		sx = at % BloodCell.N;
		sz = at / BloodCell.N;
		float floor = cell.ground[at];
		int reach = litres < 0.03F ? 0 : litres < 0.3F ? 1 : litres < 2.5F ? 2 : 3;
		int n = 0;
		float sum = 0.0F;
		float span = (reach + 0.6F) * (reach + 0.6F);
		for (int dz = -reach; dz <= reach; dz++) {
			for (int dx = -reach; dx <= reach; dx++) {
				float weight = 1.0F - (dx * dx + dz * dz) / span;
				if (weight <= 0.0F) {
					continue;
				}

				int gx = sx + dx;
				int gz = sz + dz;
				BloodCell t = cell;
				if (gx < 0 || gx >= BloodCell.N || gz < 0 || gz >= BloodCell.N) {
					t = floorAt(level, cell.bx + Math.floorDiv(gx, BloodCell.N), cell.by, cell.bz + Math.floorDiv(gz, BloodCell.N));
					if (t == null) {
						continue;
					}

					gx = Math.floorMod(gx, BloodCell.N);
					gz = Math.floorMod(gz, BloodCell.N);
				}

				int j = BloodCell.index(gx, gz);
				if (!t.floored(j) || Math.abs(t.ground[j] - floor) > FALL) {
					continue;
				}

				pourCell[n] = t;
				pourIndex[n] = j;
				pourWeight[n] = weight;
				sum += weight;
				n++;
			}
		}

		float mm = litres / BloodCell.LITRES_PER_MM;
		for (int k = 0; k < n; k++) {
			give(level, pourCell[k], pourIndex[k], mm * pourWeight[k] / sum, rgb, 0, lifetime);
			pourCell[k] = null;
		}

		return cell;
	}

	/** The sub-cell of {@code cell} that blood landing on (sx, sz) comes to rest on, or -1 if none of its floor is there. */
	private static int landing(BloodCell cell, int sx, int sz, float height) {
		int at = BloodCell.index(sx, sz);
		if (cell.floored(at)) {
			return at;
		}

		// On the very edge of a face that covers less than half the sub-cell: the sub-cell beside it has the floor.
		int best = -1;
		float nearest = Float.MAX_VALUE;
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				int x = sx + dx;
				int z = sz + dz;
				if (x < 0 || x >= BloodCell.N || z < 0 || z >= BloodCell.N) {
					continue;
				}

				int j = BloodCell.index(x, z);
				if (!cell.floored(j)) {
					continue;
				}

				float off = height != height ? 0.0F : Math.abs(cell.ground[j] - height);
				float score = off * 8.0F + dx * dx + dz * dz;
				if (off < 0.06F && score < nearest) {
					nearest = score;
					best = j;
				}
			}
		}

		return best;
	}

	/** Puts {@code amount} mm of blood of the given colour and age on sub-cell {@code j} of {@code to}. */
	private void give(Level level, BloodCell to, int j, float amount, int rgb, int age, int lifetime) {
		if (to.serial < 0L) {
			dry.remove(to.key);
			to.serial = world.nextSerial();
			to.rgb = rgb;
			to.age = age;
			to.lifetime = lifetime;
			to.light = DecalManager.light(level, BlockPos.of(to.key));
			cells.put(to.key, to);
			world.enforceCap();
		}

		float before = to.litres;
		float added = to.add(j, amount) * BloodCell.LITRES_PER_MM;
		float total = before + added;
		if (added > 0.0F && total > 1.0E-6F) {
			float share = added / total;
			to.rgb = mixColor(to.rgb, rgb, share);
			to.age = Math.round(to.age + (age - to.age) * share);
		}

		to.lifetime = Math.max(to.lifetime, lifetime);
		to.version++;
		enqueue(to);
	}

	private void enqueue(BloodCell cell) {
		if (!cell.queued && cell.serial >= 0L) {
			cell.queued = true;
			active.enqueue(cell.key);
		}
	}

	/** Sets a cell and the cells round it flowing again: something was taken out of it or changed beside it. */
	private void wake(BloodCell cell) {
		enqueue(cell);
		for (int k = 0; k < 4; k++) {
			BloodCell n = cells.get(BlockPos.asLong(cell.bx + DX[k], cell.by, cell.bz + DZ[k]));
			if (n != null) {
				enqueue(n);
			}
		}
	}

	void tick(Level level, int tickCount) {
		now = tickCount;
		GoreConfig config = GoreConfig.get();
		if (BrokenBlocks.any()) {
			// A wall that was there may be gone: nothing remembered about dry floors is trusted.
			dry.clear();
		}

		int budget = config.maxActiveCellsPerTick;
		while (budget-- > 0 && !active.isEmpty()) {
			BloodCell cell = cells.get(active.dequeueLong());
			if (cell == null) {
				continue;
			}

			cell.queued = false;
			if (flow(level, cell)) {
				enqueue(cell);
			}
		}

		settle(level, tickCount, config);
		if (tickCount % 100 == 0 && !dry.isEmpty()) {
			dry.values().removeIf(c -> tickCount - c.touched > 100);
		}
	}

	/** How far the blood in {@code c} has set: 0 runs freely, 1 no longer runs at all. A deep pool stays liquid longer. */
	public static float gel(BloodCell c) {
		if (c.age <= GEL_START) {
			return 0.0F;
		}

		float sets = GEL_BASE + GEL_PER_LITRE * Math.min(400.0F, c.litres);
		return Math.min(1.0F, (c.age - GEL_START) / (sets - GEL_START));
	}

	/** One flow step for one cell. Returns true if it should stay active. */
	private boolean flow(Level level, BloodCell c) {
		if (c.poured != 0) {
			// What poured over an edge last step is shown as pouring only while it does.
			c.poured = 0;
			if (c.pourDrop != null) {
				Arrays.fill(c.pourDrop, 0.0F);
			}

			c.version++;
		}

		if (c.litres <= 1.0E-4F) {
			return false;
		}

		float gel = gel(c);
		if (gel >= 1.0F) {
			return false;
		}

		// Thickening blood stands deeper before it runs, and runs slower.
		float hold = c.surface.hold * (1.0F + 2.0F * gel);
		float rate = RATE * (1.0F - 0.8F * gel);
		System.arraycopy(c.mm, 0, snap, 0, BloodCell.COUNT);
		Arrays.fill(delta, 0.0F);
		Arrays.fill(sideKnown, false);
		Arrays.fill(spilled, 0.0F);
		boolean moved = false;
		for (int sz = 0; sz < BloodCell.N; sz++) {
			for (int sx = 0; sx < BloodCell.N; sx++) {
				int i = BloodCell.index(sx, sz);
				float depth = snap[i];
				float mobile = depth - hold;
				if (mobile <= MIN_MOVE) {
					continue;
				}

				float floor = c.ground[i] * 1000.0F + c.relief[i];
				float head = floor + depth;
				float total = 0.0F;
				for (int k = 0; k < 4; k++) {
					out[k] = 0.0F;
					target[k] = null;
					int nx = sx + DX[k];
					int nz = sz + DZ[k];
					BloodCell t = c;
					if (nx < 0 || nx >= BloodCell.N || nz < 0 || nz >= BloodCell.N) {
						if (!sideKnown[k]) {
							sideKnown[k] = true;
							sides[k] = floorAt(level, c.bx + DX[k], c.by, c.bz + DZ[k]);
						}

						t = sides[k];
						if (t == null) {
							continue;
						}

						nx &= BloodCell.N - 1;
						nz &= BloodCell.N - 1;
					}

					int j = BloodCell.index(nx, nz);
					float tg = t.ground[j];
					float lift = 0.0F;
					if (tg != tg) {
						// The floor is lower there: on the block below, or further down than that.
						BloodCell below = floorAt(level, t.bx, t.by - 1, t.bz);
						if (below == null) {
							continue;
						}

						float bg = below.ground[j];
						if (bg == BloodCell.SOLID) {
							continue;
						}

						if (bg != bg) {
							BloodCell deep = deepPool(t.bx, t.by, t.bz, j, floor);
							if (deep == null) {
								// A drop of more than a block: over the edge it goes, to fall as drops.
								out[k] = Math.min(mobile * 0.25F, SPILL_MM);
								total += out[k];
								continue;
							}

							lift = (deep.by - c.by) * 1000.0F;
							t = deep;
							tg = deep.ground[j];
						} else {
							lift = -1000.0F;
							t = below;
							tg = bg;
						}
					} else if (tg == BloodCell.SOLID) {
						// A wall. A pool deep enough goes over the top of it.
						BloodCell over = null;
						for (int up = 1; up <= 6 && head > up * 1000.0F; up++) {
							BloodCell above = floorAt(level, t.bx, t.by + up, t.bz);
							if (above == null) {
								break;
							}

							float ag = above.ground[j];
							if (ag == BloodCell.SOLID) {
								continue;
							}

							if (ag == ag) {
								over = above;
								lift = up * 1000.0F;
								tg = ag;
							}

							break;
						}

						if (over == null) {
							continue;
						}

						t = over;
					}

					float there = lift + tg * 1000.0F + t.relief[j];
					float standing = t == c ? snap[j] : t.mm[j];
					float diff = head - (there + standing);
					if (diff <= MIN_MOVE) {
						continue;
					}

					out[k] = Math.min(diff * 0.5F, mobile) * rate;
					target[k] = t;
					targetIndex[k] = j;
					targetDrop[k] = (floor - there - standing) / 1000.0F;
					total += out[k];
				}

				if (total <= MIN_MOVE) {
					continue;
				}

				float scale = total > mobile * 0.9F ? mobile * 0.9F / total : 1.0F;
				for (int k = 0; k < 4; k++) {
					float amount = out[k] * scale;
					if (amount < MIN_MOVE) {
						continue;
					}

					BloodCell t = target[k];
					if (t == null) {
						double weight = amount / (spilled[k] + amount);
						double ex = c.bx + (sx + 0.5 + DX[k] * 0.5) * BloodCell.SIZE;
						double ez = c.bz + (sz + 0.5 + DZ[k] * 0.5) * BloodCell.SIZE;
						spillX[k] += (ex - spillX[k]) * weight;
						spillZ[k] += (ez - spillZ[k]) * weight;
						spillY[k] += (c.by + c.ground[i] - spillY[k]) * weight;
						spilled[k] += amount;
					} else if (t == c) {
						delta[targetIndex[k]] += amount;
					} else {
						give(level, t, targetIndex[k], amount, c.rgb, c.age, c.lifetime);
					}

					if (t != null && targetDrop[k] > FALL) {
						if (c.pourDrop == null) {
							c.pourDrop = new float[BloodCell.COUNT * 4];
						}

						c.pourDrop[i * 4 + k] = targetDrop[k];
						c.poured++;
					}

					delta[i] -= amount;
					moved = true;
				}
			}
		}

		if (!moved) {
			return false;
		}

		for (int i = 0; i < BloodCell.COUNT; i++) {
			if (delta[i] != 0.0F) {
				c.add(i, delta[i]);
			}
		}

		c.version++;
		for (int k = 0; k < 4; k++) {
			if (spilled[k] > 0.0F) {
				spillOverEdge(level, c, k, spilled[k]);
				target[k] = null;
			}
		}

		return true;
	}

	/**
	 * A pool that has filled a pit: the cell with the floor under sub-cell {@code j} of column (bx, bz), more than a
	 * block below {@code by}, if its blood stands as high as {@code floor} (mm above the asking cell). Blood beside it
	 * then meets its surface instead of falling in.
	 */
	private @Nullable BloodCell deepPool(int bx, int by, int bz, int j, float floor) {
		for (int down = 2; down <= 8; down++) {
			BloodCell deep = cells.get(BlockPos.asLong(bx, by - down, bz));
			if (deep == null) {
				continue;
			}

			if (!deep.floored(j)) {
				return null;
			}

			float surface = (deep.ground[j] - down) * 1000.0F + deep.mm[j];
			return surface > floor - 60.0F ? deep : null;
		}

		return null;
	}

	/**
	 * Blood running off a ledge on side {@code k}: it falls from the edge as drops, and the first of it to go over runs
	 * down the face of the ledge.
	 */
	private void spillOverEdge(Level level, BloodCell cell, int k, float mm) {
		Direction dir = SIDE[k];
		float units = mm * BloodCell.LITRES_PER_MM / BloodSupply.LITRES_PER_UNIT;
		int bit = 1 << k;
		if ((cell.streakedEdges & bit) == 0 && GoreConfig.get().wallSplatter) {
			cell.streakedEdges |= bit;
			// The ledge block is the one under the edge; the run goes down its face pointing that way.
			BlockPos ledge = BlockPos.containing(spillX[k] - DX[k] * 0.05, spillY[k] - 0.01, spillZ[k] - DZ[k] * 0.05);
			double a = dir.getAxis() == Direction.Axis.X ? spillZ[k] : spillX[k];
			float runs = Math.min(units * 0.5F, RUNS_OFF_LEDGE);
			if (world.decals().run(level, ledge, dir, a, spillY[k], runs, cell.rgb, cell.lifetime) != null) {
				units -= runs;
			}
		}

		world.droplets().runoff(spillX[k] + DX[k] * 0.03, spillY[k] + 0.01, spillZ[k] + DZ[k] * 0.03, DX[k] * 0.02, DZ[k] * 0.02, units, cell.rgb);
		spillX[k] = 0.0;
		spillZ[k] = 0.0;
		spillY[k] = 0.0;
	}

	/** Ageing, soaking in, drying, and the world changing under the blood. */
	private void settle(Level level, int tickCount, GoreConfig config) {
		ObjectIterator<BloodCell> it = cells.values().iterator();
		while (it.hasNext()) {
			BloodCell cell = it.next();
			cell.age++;
			boolean broken = BrokenBlocks.near(cell.bx + 0.5, cell.surfaceY, cell.bz + 0.5);
			if (((cell.serial + tickCount) % 20) == 0 || broken) {
				BlockPos pos = BlockPos.of(cell.key);
				if (!level.isLoaded(pos)) {
					it.remove();
					continue;
				}

				FluidState fluid = level.getFluidState(pos);
				if (fluid.is(FluidTags.WATER) || fluid.is(FluidTags.LAVA)) {
					// Water flooding a pool lifts it off the floor in a cloud; lava burns it off.
					if (fluid.is(FluidTags.WATER) && cell.litres > 0.0F) {
						world.clouds().add(level, cell.bx + 0.5, cell.surfaceY + 0.1, cell.bz + 0.5, cell.volume(), cell.rgb);
					}

					it.remove();
					continue;
				}

				if (cell.measure(level)) {
					unfloor(cell);
					enqueue(cell);
				} else if (broken) {
					// A wall beside it may have gone: it may run again.
					enqueue(cell);
				}

				cell.light = DecalManager.light(level, pos);
			}

			if (((cell.serial + tickCount) % SETTLE_EVERY) == 0) {
				soakAndDry(cell);
				if (config.rainWashesBlood && level.isRainingAt(BlockPos.of(cell.key))) {
					wash(cell);
				}
			}

			if (cell.age >= cell.lasts() || cell.empty()) {
				it.remove();
			}
		}
	}

	/** What the floor drinks and what dries onto it, for {@link #SETTLE_EVERY} ticks. */
	private static void soakAndDry(BloodCell cell) {
		if (cell.litres <= 0.0F) {
			return;
		}

		Surface surface = cell.surface;
		float gel = gel(cell);
		// Soaking slows as the blood sets and as the floor fills.
		float drinks = surface.soakRate * (SETTLE_EVERY / 20.0F) * (1.0F - 0.85F * gel);
		// A film as deep as the floor holds has dried onto it by the time the cell is dry.
		float dries = surface.hold * SETTLE_EVERY / Math.max(200.0F, cell.lasts() * 0.5F);
		boolean changed = false;
		for (int i = 0; i < BloodCell.COUNT; i++) {
			float depth = cell.mm[i];
			if (depth <= 0.0F) {
				continue;
			}

			float taken = 0.0F;
			if (drinks > 0.0F && cell.soak[i] < surface.takes) {
				taken = Math.min(depth, drinks * Math.max(0.1F, 1.0F - cell.soak[i] / surface.takes));
			}

			float rest = depth - taken;
			float gone = taken + (rest < FILM ? rest : Math.min(rest, dries));
			if (gone > 0.0F) {
				cell.mm[i] = depth - gone;
				cell.soak[i] += gone;
				changed = true;
			}
		}

		if (changed) {
			cell.recount();
			cell.version++;
		}
	}

	/** Rain on a cell: it thins the blood and washes the stain out, and the cell ages faster. */
	private static void wash(BloodCell cell) {
		cell.age += 30;
		for (int i = 0; i < BloodCell.COUNT; i++) {
			cell.mm[i] *= 0.9F;
			cell.soak[i] *= 0.96F;
		}

		cell.recount();
		cell.version++;
	}

	/**
	 * The floor under a cell has changed. Blood over a sub-cell that has lost its floor falls; blood where a block now
	 * stands is gone with the space it was in.
	 */
	private void unfloor(BloodCell cell) {
		float falling = 0.0F;
		double x = 0.0;
		double z = 0.0;
		for (int i = 0; i < BloodCell.COUNT; i++) {
			if (cell.floored(i) || (cell.mm[i] <= 0.0F && cell.soak[i] <= 0.0F)) {
				continue;
			}

			if (cell.ground[i] != BloodCell.SOLID && cell.mm[i] > 0.0F) {
				float share = cell.mm[i] / (falling + cell.mm[i]);
				x += (cell.bx + (i % BloodCell.N + 0.5) * BloodCell.SIZE - x) * share;
				z += (cell.bz + (i / BloodCell.N + 0.5) * BloodCell.SIZE - z) * share;
				falling += cell.mm[i];
			}

			cell.mm[i] = 0.0F;
			cell.soak[i] = 0.0F;
		}

		cell.recount();
		cell.version++;
		if (falling > MIN_MOVE) {
			world.droplets().runoff(x, cell.by + 0.02, z, 0.0, 0.0, falling * BloodCell.LITRES_PER_MM / BloodSupply.LITRES_PER_UNIT, cell.rgb);
		}
	}

	/** Depth of liquid, mm, at a point on a floor near {@code y} (within a block above or below it). */
	public float depthAt(double x, double y, double z) {
		BloodCell cell = at(x, y, z);
		return cell == null ? 0.0F : cell.depthAt(x, z);
	}

	/**
	 * True where the blood on the floor at (x, z) near {@code y} is enough to be drawn as part of a pool or a stain. A
	 * drop that lands where it is not is drawn as a mark of its own instead (see {@link BloodWorld#landOnFloor}).
	 */
	public boolean shows(double x, double y, double z) {
		BloodCell cell = at(x, y, z);
		return cell != null && cell.shows(BloodCell.index(BloodCell.sub(x - cell.bx), BloodCell.sub(z - cell.bz)));
	}

	/** The cell whose blood lies under (x, z) on a floor near {@code y}: the wetter of the block there and the one below. */
	public @Nullable BloodCell at(double x, double y, double z) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		int by = SurfaceProbe.airBlockY(y);
		BloodCell best = null;
		float most = 0.0F;
		for (int dy = 0; dy >= -1; dy--) {
			BloodCell cell = cells.get(BlockPos.asLong(bx, by + dy, bz));
			if (cell == null) {
				continue;
			}

			float depth = cell.depthAt(x, z) + 1.0E-4F;
			if (best == null || depth > most) {
				best = cell;
				most = depth;
			}
		}

		return best;
	}

	/**
	 * Takes liquid off the floors within {@code radius} of (x, z) near height {@code y}, up to {@code litres} of it,
	 * leaving {@code film} mm behind on each sub-cell. Returns the litres taken; the blood round the place runs in.
	 */
	public float take(double x, double y, double z, double radius, float litres, float film) {
		if (!(litres > 0.0F) || cells.isEmpty()) {
			return 0.0F;
		}

		float there = reach(x, y, z, radius, film, 0.0F);
		if (there <= 0.0F) {
			return 0.0F;
		}

		return reach(x, y, z, radius, film, Math.min(1.0F, litres / there));
	}

	/** Litres of liquid above {@code film} mm within {@code radius} of (x, z) near {@code y}: what a bucket dipped there can take. */
	public float within(double x, double y, double z, double radius, float film) {
		return cells.isEmpty() ? 0.0F : reach(x, y, z, radius, film, 0.0F);
	}

	/** Takes the share {@code share} of the liquid within {@code radius} of (x, z) near {@code y}; returns the litres taken. */
	public float lift(double x, double y, double z, double radius, float share) {
		return share > 0.0F && !cells.isEmpty() ? reach(x, y, z, radius, 0.0F, Math.min(1.0F, share)) : 0.0F;
	}

	/**
	 * The liquid above {@code film} mm within {@code radius} of (x, z) near {@code y}, litres; with {@code share} over
	 * zero, that share of it is taken away and the litres taken are returned.
	 */
	private float reach(double x, double y, double z, double radius, float film, float share) {
		int by = SurfaceProbe.airBlockY(y);
		float sum = 0.0F;
		for (int bx = Mth.floor(x - radius); bx <= Mth.floor(x + radius); bx++) {
			for (int bz = Mth.floor(z - radius); bz <= Mth.floor(z + radius); bz++) {
				for (int dy = -1; dy <= 1; dy++) {
					BloodCell cell = cells.get(BlockPos.asLong(bx, by + dy, bz));
					if (cell == null || cell.litres <= 0.0F) {
						continue;
					}

					float before = sum;
					for (int i = 0; i < BloodCell.COUNT; i++) {
						float over = cell.mm[i] - film;
						if (over <= 0.0F) {
							continue;
						}

						double dx = cell.bx + (i % BloodCell.N + 0.5) * BloodCell.SIZE - x;
						double dz = cell.bz + (i / BloodCell.N + 0.5) * BloodCell.SIZE - z;
						if (dx * dx + dz * dz > radius * radius) {
							continue;
						}

						sum += over * BloodCell.LITRES_PER_MM * (share > 0.0F ? share : 1.0F);
						if (share > 0.0F) {
							cell.mm[i] -= over * share;
						}
					}

					if (share > 0.0F && sum > before) {
						cell.recount();
						cell.version++;
						wake(cell);
					}
				}
			}
		}

		return sum;
	}

	/** Lifts the share {@code share} of the stain within {@code radius} of (x, z) near {@code y}; returns litres lifted. */
	public float scrubStain(double x, double y, double z, double radius, float share) {
		int by = SurfaceProbe.airBlockY(y);
		float lifted = 0.0F;
		for (int bx = Mth.floor(x - radius); bx <= Mth.floor(x + radius); bx++) {
			for (int bz = Mth.floor(z - radius); bz <= Mth.floor(z + radius); bz++) {
				for (int dy = -1; dy <= 1; dy++) {
					BloodCell cell = cells.get(BlockPos.asLong(bx, by + dy, bz));
					if (cell == null || cell.soaked <= 0.0F) {
						continue;
					}

					boolean any = false;
					for (int i = 0; i < BloodCell.COUNT; i++) {
						if (cell.soak[i] <= 0.0F) {
							continue;
						}

						double dx = cell.bx + (i % BloodCell.N + 0.5) * BloodCell.SIZE - x;
						double dz = cell.bz + (i / BloodCell.N + 0.5) * BloodCell.SIZE - z;
						if (dx * dx + dz * dz <= radius * radius) {
							lifted += cell.soak[i] * share * BloodCell.LITRES_PER_MM;
							cell.soak[i] *= 1.0F - share;
							any = true;
						}
					}

					if (any) {
						cell.recount();
						cell.version++;
					}
				}
			}
		}

		return lifted;
	}

	/** Takes {@code amount} pool units of liquid off a cell, evenly (footprints and coats picking it up). */
	public void drain(BloodCell cell, float amount) {
		float litres = amount * BloodSupply.LITRES_PER_UNIT;
		if (cell.litres <= 0.0F || litres <= 0.0F) {
			return;
		}

		float keep = Math.max(0.0F, 1.0F - litres / cell.litres);
		for (int i = 0; i < BloodCell.COUNT; i++) {
			cell.mm[i] *= keep;
		}

		cell.recount();
		cell.version++;
	}

	/**
	 * How slippery the floor is at a point, 0 to 1, from the blood on it: a wet film on a smooth floor is as slippery
	 * as ice, less so on rougher floors, and not at all once it has set (clotting blood is tacky) or where the floor
	 * drinks it.
	 */
	public float slickAt(double x, double y, double z) {
		BloodCell cell = at(x, y, z);
		if (cell == null || cell.surface.slip <= 0.0F) {
			return 0.0F;
		}

		float depth = cell.depthAt(x, z);
		if (depth < 0.3F) {
			return 0.0F;
		}

		return cell.surface.slip * Math.min(1.0F, depth / 1.2F) * (1.0F - gel(cell));
	}

	/** What a blast lifts off one cell: how much, where from, and how hard the air was moving there. */
	public record Thrown(double x, double y, double z, float litres, int rgb, float kPa) {
	}

	/**
	 * A blast over standing blood. The wind behind a blast wave strips a thin film off a floor and throws a pool out
	 * ahead of it: each cell loses a share of its liquid that grows with the overpressure there ({@code kPa} gives it for
	 * a distance in blocks), and {@code out} is handed what was lifted to throw. Inside {@code fireball} blocks of the
	 * centre what is left is seared onto the floor at once, dark and dry.
	 */
	public void blast(double cx, double cy, double cz, double reach, java.util.function.DoubleUnaryOperator kPa, double fireball,
			java.util.function.Consumer<Thrown> out) {
		for (BloodCell cell : cells.values()) {
			double dx = cell.bx + 0.5 - cx;
			double dy = cell.surfaceY - cy;
			double dz = cell.bz + 0.5 - cz;
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (distance > reach) {
				continue;
			}

			float pressure = (float) kPa.applyAsDouble(Math.max(0.5, distance));
			float share = Mth.clamp((pressure - 12.0F) / 90.0F, 0.0F, 0.92F);
			if (share > 0.0F && cell.litres > 0.005F) {
				float lifted = cell.litres * share;
				for (int i = 0; i < BloodCell.COUNT; i++) {
					cell.mm[i] *= 1.0F - share;
				}

				cell.recount();
				cell.version++;
				wake(cell);
				out.accept(new Thrown(cell.bx + 0.5, cell.surfaceY, cell.bz + 0.5, lifted, cell.rgb, pressure));
			}

			if (distance <= fireball) {
				// What the flash leaves is cooked onto the floor.
				for (int i = 0; i < BloodCell.COUNT; i++) {
					cell.soak[i] += cell.mm[i];
					cell.mm[i] = 0.0F;
				}

				cell.recount();
				cell.age = Math.max(cell.age, cell.lasts() / 2);
				cell.version++;
			}
		}
	}

	/**
	 * Takes the blood off one block of floor to make room: of the oldest of them, the one that holds least, so that a
	 * stain goes before a pool does.
	 */
	void removeOldest() {
		BloodCell least = null;
		int seen = 0;
		for (BloodCell cell : cells.values()) {
			if (least == null || cell.litres + cell.soaked < least.litres + least.soaked) {
				least = cell;
			}

			if (++seen >= OLDEST) {
				break;
			}
		}

		if (least != null) {
			cells.remove(least.key);
		}
	}

	void removeChunk(int chunkX, int chunkZ) {
		cells.values().removeIf(c -> (c.bx >> 4) == chunkX && (c.bz >> 4) == chunkZ);
		dry.values().removeIf(c -> (c.bx >> 4) == chunkX && (c.bz >> 4) == chunkZ);
	}

	/** Takes away every pool and stain inside {@code box}. */
	void removeIn(AABB box) {
		cells.values().removeIf(c -> box.contains(c.bx + 0.5, c.surfaceY, c.bz + 0.5));
	}

	void clear() {
		cells.clear();
		dry.clear();
		active.clear();
	}

	public static int mixColor(int a, int b, float t) {
		t = Mth.clamp(t, 0.0F, 1.0F);
		int r = (int) Mth.lerp(t, (a >> 16) & 0xFF, (b >> 16) & 0xFF);
		int g = (int) Mth.lerp(t, (a >> 8) & 0xFF, (b >> 8) & 0xFF);
		int bl = (int) Mth.lerp(t, a & 0xFF, b & 0xFF);
		return (r << 16) | (g << 8) | bl;
	}
}
