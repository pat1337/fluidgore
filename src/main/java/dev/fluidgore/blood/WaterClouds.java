package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Blood in water. Blood is a little denser than water and mixes into it rather than floating: it billows out into a
 * dark red cloud, sinks slowly, drifts with any current and thins away over a minute or two, tinting the water it
 * spreads through. Each cloud is fed by every drop, pool or rinse that reaches water near it, so a body bleeding in a
 * pond feeds one growing plume instead of a scatter of dots.
 * <p>
 * Clouds only follow from blood that reached water and from the water itself (no dice), so every screen draws them
 * alike.
 */
public final class WaterClouds {
	/** Most clouds at once; the oldest goes first. */
	private static final int CAPACITY = 384;
	/** Blood that reaches water within this much of a cloud's edge joins it. */
	private static final double JOIN = 0.35;
	/** Sinking speed, blocks per tick: about four centimetres a second, as blood settles in still water. */
	private static final double SINK = 0.002;
	/** How much a current carries a cloud along, per tick, of the water's own flow. */
	private static final double CARRY = 0.03;
	/** Shortest and longest life, ticks: a drop's wisp fades in half a minute, a bleeding body's plume in three. */
	private static final int SHORTEST = 600;
	private static final int LONGEST = 3600;
	/** Widest a cloud spreads, blocks of radius. */
	private static final float WIDEST = 2.2F;

	/** One cloud: where it is, how much blood is in it (pool units), how wide it has spread and how old it is. */
	public static final class Cloud {
		public double x;
		public double y;
		public double z;
		public double prevX;
		public double prevY;
		public double prevZ;
		public float volume;
		public float radius;
		public int rgb;
		public int age;
		public int life;
		public int light;
		/** The top of the water over it (see {@link #surface}): drawn, it stays under it. */
		public double top = Double.MAX_VALUE;
		public final long serial;

		Cloud(double x, double y, double z, float volume, int rgb, long serial) {
			this.x = this.prevX = x;
			this.y = this.prevY = y;
			this.z = this.prevZ = z;
			this.volume = volume;
			this.rgb = rgb;
			this.serial = serial;
			this.radius = 0.12F + 0.5F * (float) Math.cbrt(volume);
			this.life = life(volume);
		}

		/** How dark it shows, 0..1: thick blood is opaque, spread thin it fades, and it clears at the end of its life. */
		public float alpha() {
			float thick = Mth.clamp(volume * 6.0F / (radius * radius + 0.05F), 0.0F, 0.85F);
			float left = 1.0F - age / (float) life;
			float fade = Mth.clamp(left * 4.0F, 0.0F, 1.0F);
			float bloom = Mth.clamp(age / 6.0F, 0.0F, 1.0F);
			return thick * fade * bloom;
		}
	}

	private final List<Cloud> clouds = new ArrayList<>();
	private long serial;

	private static int life(float volume) {
		return Mth.clamp(Math.round(SHORTEST + volume * 8000.0F), SHORTEST, LONGEST);
	}

	public List<Cloud> clouds() {
		return clouds;
	}

	/** True if blood at this point is in water. */
	public static boolean inWater(Level level, double x, double y, double z) {
		return level.getFluidState(BlockPos.containing(x, y, z)).is(FluidTags.WATER);
	}

	/** Blood ({@code volume} pool units of {@code rgb}) reached water at {@code x, y, z}: it clouds out there. */
	public void add(Level level, double x, double y, double z, float volume, int rgb) {
		if (volume <= 0.0F || !GoreConfig.get().bloodInWater || !GoreConfig.get().goreLevel.enabled()) {
			return;
		}

		for (Cloud cloud : clouds) {
			double reach = cloud.radius + JOIN;
			if (Mth.square(cloud.x - x) + Mth.square(cloud.y - y) + Mth.square(cloud.z - z) < reach * reach) {
				// It feeds the cloud already there: the cloud thickens, and fresh blood keeps it going.
				float total = cloud.volume + volume;
				cloud.rgb = BloodFluidSim.mixColor(cloud.rgb, rgb, volume / total);
				cloud.volume = total;
				cloud.radius = Math.min(WIDEST, Math.max(cloud.radius, 0.12F + 0.5F * (float) Math.cbrt(total)));
				cloud.life = Math.max(cloud.life, cloud.age + life(volume) / 2);
				cloud.life = Math.min(cloud.life, cloud.age + LONGEST);
				return;
			}
		}

		if (clouds.size() >= CAPACITY) {
			clouds.removeFirst();
		}

		Cloud cloud = new Cloud(x, y, z, volume, rgb, serial++);
		cloud.light = LightCoordsUtil.getLightCoords(level, BlockPos.containing(x, y, z));
		clouds.add(cloud);
	}

	public void tick(Level level) {
		if (clouds.isEmpty()) {
			return;
		}

		if (!GoreConfig.get().bloodInWater) {
			clouds.clear();
			return;
		}

		int write = 0;
		for (int i = 0; i < clouds.size(); i++) {
			Cloud c = clouds.get(i);
			c.prevX = c.x;
			c.prevY = c.y;
			c.prevZ = c.z;
			if (++c.age >= c.life) {
				continue;
			}

			BlockPos pos = BlockPos.containing(c.x, c.y, c.z);
			if (!level.isLoaded(pos)) {
				continue;
			}

			FluidState water = level.getFluidState(pos);
			if (!water.is(FluidTags.WATER)) {
				// Drained away or carried out of the water: it is gone with it.
				continue;
			}

			// A current carries it along.
			Vec3 flow = water.getFlow(level, pos);
			c.x += flow.x * CARRY;
			c.z += flow.z * CARRY;
			// And a charge's gas bubble pushes it out and draws it back in, as it does the water.
			Vec3 pushed = dev.fluidgore.physics.Buoyancy.disturbance(level, c.x, c.y, c.z);
			c.x += pushed.x;
			c.y += pushed.y;
			c.z += pushed.z;
			// It sinks, until it settles on the bottom.
			double below = c.y - SINK - 0.08;
			if (level.getFluidState(BlockPos.containing(c.x, below, c.z)).is(FluidTags.WATER)) {
				c.y -= SINK;
			}

			// It spreads, quickly at first as it billows out, then slowly as it thins.
			c.radius = Math.min(WIDEST, c.radius + 0.006F / (1.0F + c.age / 200.0F));
			if (c.age == 1 || (c.age + c.serial) % 10 == 0) {
				c.top = surface(level, c.x, c.y, c.z);
			}

			if ((c.age + c.serial) % 20 == 0) {
				c.light = LightCoordsUtil.getLightCoords(level, BlockPos.containing(c.x, c.y, c.z));
			}

			clouds.set(write++, c);
		}

		while (clouds.size() > write) {
			clouds.removeLast();
		}
	}

	/** The top of the water a point in it is under: up the column of water to the top block, and as high as that is full. */
	private static double surface(Level level, double x, double y, double z) {
		BlockPos.MutableBlockPos pos = BlockPos.containing(x, y, z).mutable();
		for (int i = 0; i < 32 && level.getFluidState(pos.above()).is(FluidTags.WATER); i++) {
			pos.move(0, 1, 0);
		}

		return pos.getY() + level.getFluidState(pos).getHeight(level, pos);
	}

	public void removeChunk(int chunkX, int chunkZ) {
		clouds.removeIf(c -> (Mth.floor(c.x) >> 4) == chunkX && (Mth.floor(c.z) >> 4) == chunkZ);
	}

	public void clear() {
		clouds.clear();
	}
}
