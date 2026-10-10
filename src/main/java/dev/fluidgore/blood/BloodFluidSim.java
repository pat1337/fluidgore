package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * A cheap cellular "shallow water" model for blood on the ground.
 *
 * <p>Every cell has a hydraulic head = surface height + volume * {@link #HEAD_PER_VOLUME}. Each step a cell pushes
 * part of its volume to any neighbour with a lower head, so blood runs down slopes and steps, spreads out and
 * thins on flat ground, and collects in dips. Below {@link #MIN_MOBILE} a film is too thin to move, which acts as
 * viscosity and makes puddles settle. At a ledge with no surface within one block below, volume leaves as falling
 * droplets that splat on whatever is underneath.
 *
 * <p>Only cells whose volume recently changed are queued, and at most
 * {@link GoreConfig#maxActiveCellsPerTick} of them are processed per tick, so cost is bounded regardless of how
 * much blood exists.
 */
public final class BloodFluidSim {
	private static final float HEAD_PER_VOLUME = 0.25F;
	private static final float FLOW_RATE = 0.22F;
	private static final float MIN_MOBILE = 0.06F;
	private static final float MIN_TRANSFER = 0.004F;
	/** Volume lost to drying each tick, as a fraction. */
	private static final float EVAPORATION = 0.0006F;
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

	private final BloodWorld world;
	private final Long2ObjectLinkedOpenHashMap<BloodCell> cells = new Long2ObjectLinkedOpenHashMap<>();
	private final LongArrayFIFOQueue active = new LongArrayFIFOQueue();
	private final RandomSource random = RandomSource.create();

	BloodFluidSim(BloodWorld world) {
		this.world = world;
	}

	public Collection<BloodCell> cells() {
		return cells.values();
	}

	public int size() {
		return cells.size();
	}

	public @Nullable BloodCell cellAt(int x, int y, int z) {
		return cells.get(BlockPos.asLong(x, y, z));
	}

	/**
	 * Adds blood to the surface at (x, surfaceY, z), creating a cell if needed. {@code surfaceY} should be an
	 * actual top-face height (from {@link SurfaceProbe}).
	 */
	public @Nullable BloodCell addVolume(Level level, double x, double surfaceY, double z, float volume, int rgb, int lifetime) {
		if (volume <= 0.0F) {
			return null;
		}

		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		int by = SurfaceProbe.airBlockY(surfaceY);
		long key = BlockPos.asLong(bx, by, bz);
		BloodCell cell = cells.get(key);
		if (cell == null) {
			cell = createCell(level, key, bx, by, bz, surfaceY, rgb, lifetime);
			if (cell == null) {
				return null;
			}
		} else {
			float total = cell.volume + volume;
			cell.rgb = mixColor(cell.rgb, rgb, volume / total);
			// Fresh blood re-wets the puddle.
			cell.age = Math.min(cell.age, cell.lifetime / 5);
			cell.lifetime = Math.max(cell.lifetime, lifetime);
		}

		cell.volume += volume;
		enqueue(cell);
		return cell;
	}

	private @Nullable BloodCell createCell(Level level, long key, int bx, int by, int bz, double hintY, int rgb, int lifetime) {
		double cx = bx + 0.5;
		double cz = bz + 0.5;
		double center = SurfaceProbe.surfaceBelow(level, cx, hintY + 0.3, hintY - 0.6, cz);
		if (Double.isNaN(center)) {
			// The landing point was on a block edge; trust the measured height there.
			center = hintY;
		}

		if (SurfaceProbe.airBlockY(center) != by) {
			return null;
		}

		BloodCell cell = new BloodCell(key, bx, by, bz, world.nextSerial());
		cell.surfaceY = center;
		cell.rgb = rgb;
		cell.lifetime = lifetime;
		cell.light = DecalManager.light(level, BlockPos.of(key));
		measureQuadrants(level, cell);
		cells.put(key, cell);
		world.enforceCap();
		return cells.get(key);
	}

	private static void measureQuadrants(Level level, BloodCell cell) {
		for (int q = 0; q < 4; q++) {
			double qx = cell.bx + ((q & 1) == 0 ? 0.25 : 0.75);
			double qz = cell.bz + ((q & 2) == 0 ? 0.25 : 0.75);
			double h = SurfaceProbe.surfaceBelow(level, qx, cell.surfaceY + 0.55, cell.surfaceY - 0.55, qz);
			cell.quadrantHeights[q] = Double.isNaN(h) ? Float.NaN : (float) (h - cell.surfaceY);
		}
	}

	private void enqueue(BloodCell cell) {
		if (!cell.queued) {
			cell.queued = true;
			active.enqueue(cell.key);
		}
	}

	void tick(Level level, int tickCount) {
		GoreConfig config = GoreConfig.get();
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

		age(level, tickCount, config);
	}

	/** One flow step for one cell. Returns true if it should stay active. */
	private boolean flow(Level level, BloodCell cell) {
		if (cell.volume < MIN_MOBILE) {
			return false;
		}

		double head = cell.surfaceY + cell.volume * HEAD_PER_VOLUME;
		float[] outflow = new float[4];
		BloodCell[] targets = new BloodCell[4];
		double[] targetSurface = new double[4];
		float total = 0.0F;
		float mobile = cell.volume - MIN_MOBILE * 0.5F;
		boolean spilled = false;

		for (int i = 0; i < 4; i++) {
			Direction dir = HORIZONTAL[i];
			int nx = cell.bx + dir.getStepX();
			int nz = cell.bz + dir.getStepZ();
			double cx = nx + 0.5;
			double cz = nz + 0.5;
			double startY = cell.surfaceY + 0.2;
			if (!level.isLoaded(BlockPos.containing(cx, startY, cz)) || !SurfaceProbe.isOpen(level, cx, startY, cz)) {
				// Wall or unloaded: no flow that way.
				continue;
			}

			double surface = SurfaceProbe.surfaceBelow(level, cx, startY, cell.surfaceY - 1.05, cz);
			if (Double.isNaN(surface)) {
				// Ledge: the column next to us drops more than a block (or into nothing). Spill over the edge.
				float spill = Math.min(cell.volume * 0.12F, 0.05F);
				if (mobile > spill && spill > MIN_TRANSFER) {
					spillOverEdge(level, cell, dir, spill);
					cell.volume -= spill;
					mobile -= spill;
					spilled = true;
				}

				continue;
			}

			BloodCell neighbour = cells.get(BlockPos.asLong(nx, SurfaceProbe.airBlockY(surface), nz));
			double neighbourHead = surface + (neighbour == null ? 0.0 : neighbour.volume * HEAD_PER_VOLUME);
			double diff = head - neighbourHead;
			if (diff <= 0.0) {
				continue;
			}

			// Convert the head difference back into volume. Half of it would equalise the pair; the flow rate
			// keeps the motion smooth over several ticks.
			outflow[i] = (float) Math.min(diff / HEAD_PER_VOLUME * 0.5, cell.volume) * FLOW_RATE;
			targets[i] = neighbour;
			targetSurface[i] = surface;
			total += outflow[i];
		}

		if (total <= MIN_TRANSFER || mobile <= 0.0F) {
			// A cell draining over a ledge stays active until it is too thin to move.
			return spilled;
		}

		float scale = total > mobile ? mobile / total : 1.0F;
		boolean moved = false;
		for (int i = 0; i < 4; i++) {
			float amount = outflow[i] * scale;
			if (amount < MIN_TRANSFER) {
				continue;
			}

			Direction dir = HORIZONTAL[i];
			int nx = cell.bx + dir.getStepX();
			int nz = cell.bz + dir.getStepZ();
			BloodCell target = targets[i];
			if (target == null) {
				target = addVolume(level, nx + 0.5, targetSurface[i], nz + 0.5, amount, cell.rgb, cell.lifetime);
				if (target == null) {
					continue;
				}
			} else {
				target.volume += amount;
				target.rgb = mixColor(target.rgb, cell.rgb, amount / target.volume);
				target.age = Math.min(target.age, cell.age);
				enqueue(target);
			}

			cell.volume -= amount;
			moved = true;
		}

		return moved || spilled;
	}

	/** Blood running off a ledge: a streak down the side of the ledge block and droplets falling below. */
	private void spillOverEdge(Level level, BloodCell cell, Direction dir, float volume) {
		double edgeX = cell.bx + 0.5 + dir.getStepX() * 0.5;
		double edgeZ = cell.bz + 0.5 + dir.getStepZ() * 0.5;
		double along = random.nextDouble() * 0.6 - 0.3;
		double dropX = dir.getAxis() == Direction.Axis.X ? edgeX + dir.getStepX() * 0.03 : edgeX + along;
		double dropZ = dir.getAxis() == Direction.Axis.Z ? edgeZ + dir.getStepZ() * 0.03 : edgeZ + along;

		world.droplets().spawn(dropX, cell.surfaceY + 0.01, dropZ,
				dir.getStepX() * 0.02, 0.0, dir.getStepZ() * 0.02, volume, cell.rgb, 0.035F);

		int bit = 1 << dir.get2DDataValue();
		if ((cell.streakedEdges & bit) == 0 && GoreConfig.get().wallSplatter) {
			cell.streakedEdges |= bit;
			// The ledge block is the one under the cell; its face pointing `dir` gets a vertical drip streak.
			BlockPos ledge = BlockPos.containing(cell.bx + 0.5, cell.surfaceY - 0.01, cell.bz + 0.5);
			double a = dir.getAxis() == Direction.Axis.X ? dropZ : dropX;
			world.decals().addWall(level, ledge, dir, a, cell.surfaceY - 0.32, 0.3F, BloodTextures.STREAK, 0,
					cell.rgb, cell.lifetime);
		}
	}

	private void age(Level level, int tickCount, GoreConfig config) {
		ObjectIterator<BloodCell> it = cells.values().iterator();
		while (it.hasNext()) {
			BloodCell cell = it.next();
			cell.age++;
			cell.volume -= cell.volume * EVAPORATION;

			if (((cell.serial + tickCount) % 20) == 0 || BrokenBlocks.near(cell.bx + 0.5, cell.surfaceY, cell.bz + 0.5)) {
				BlockPos pos = BlockPos.of(cell.key);
				if (!level.isLoaded(pos)) {
					it.remove();
					continue;
				}

				if (level.getFluidState(pos).is(FluidTags.WATER) || level.getFluidState(pos).is(FluidTags.LAVA)) {
					// Blood disperses in water and burns off in lava.
					it.remove();
					continue;
				}

				if (!SurfaceProbe.stillSupported(level, cell.bx + 0.5, cell.surfaceY, cell.bz + 0.5)) {
					// The block under the puddle was removed: the blood falls.
					if (cell.volume > MIN_TRANSFER) {
						world.droplets().spawn(cell.bx + 0.5, cell.surfaceY, cell.bz + 0.5, 0.0, 0.0, 0.0,
								cell.volume, cell.rgb, 0.05F);
					}

					it.remove();
					continue;
				}

				cell.light = DecalManager.light(level, pos);
				cell.pooled = isPooled(cell);
			}

			if (config.rainWashesBlood && ((cell.serial + tickCount) % 10) == 0 && level.isRainingAt(BlockPos.of(cell.key))) {
				cell.age += 30;
				cell.volume *= 0.9F;
			}

			if (cell.age >= cell.lifetime || cell.volume < 0.002F) {
				it.remove();
			}
		}
	}

	private boolean isPooled(BloodCell cell) {
		if (cell.volume < BloodCell.FULL * 0.75F) {
			return false;
		}

		int wet = 0;
		for (Direction dir : HORIZONTAL) {
			BloodCell n = cells.get(BlockPos.asLong(cell.bx + dir.getStepX(), cell.by, cell.bz + dir.getStepZ()));
			if (n != null && n.volume > BloodCell.FULL * 0.3F) {
				wet++;
			}
		}

		return wet >= 3;
	}

	/** Takes some blood off a cell (used by footprints picking it up). */
	public void drain(BloodCell cell, float amount) {
		cell.volume = Math.max(0.0F, cell.volume - amount);
	}

	long removeOldest() {
		if (cells.isEmpty()) {
			return -1;
		}

		return cells.removeFirst().serial;
	}

	long oldestSerial() {
		return cells.isEmpty() ? Long.MAX_VALUE : cells.get(cells.firstLongKey()).serial;
	}

	void removeChunk(int chunkX, int chunkZ) {
		cells.values().removeIf(c -> (c.bx >> 4) == chunkX && (c.bz >> 4) == chunkZ);
	}

	void clear() {
		cells.clear();
		active.clear();
	}

	static int mixColor(int a, int b, float t) {
		t = Mth.clamp(t, 0.0F, 1.0F);
		int r = (int) Mth.lerp(t, (a >> 16) & 0xFF, (b >> 16) & 0xFF);
		int g = (int) Mth.lerp(t, (a >> 8) & 0xFF, (b >> 8) & 0xFF);
		int bl = (int) Mth.lerp(t, a & 0xFF, b & 0xFF);
		return (r << 16) | (g << 8) | bl;
	}
}
