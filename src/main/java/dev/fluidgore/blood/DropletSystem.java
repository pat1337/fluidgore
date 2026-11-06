package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.trigger.Fate;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Ballistic blood droplets. They fly under gravity and drag, ray-cast against block collision shapes each tick,
 * and turn into a floor splat + puddle volume or a wall splat when they hit something.
 */
public final class DropletSystem {
	private static final double GRAVITY = 0.045;
	private static final double DRAG = 0.98;
	private static final int MAX_AGE = 160;

	public static final class Droplet {
		public double x, y, z;
		public double prevX, prevY, prevZ;
		double vx, vy, vz;
		final float volume;
		public final int rgb;
		public final float size;
		public int light;
		int age;
		boolean slid;

		Droplet(double x, double y, double z, double vx, double vy, double vz, float volume, int rgb, float size) {
			this.x = this.prevX = x;
			this.y = this.prevY = y;
			this.z = this.prevZ = z;
			this.vx = vx;
			this.vy = vy;
			this.vz = vz;
			this.volume = volume;
			this.rgb = rgb;
			this.size = size;
		}
	}

	private final BloodWorld world;
	private final List<Droplet> droplets = new ArrayList<>();
	private final RandomSource random = Fate.stream("droplets");

	DropletSystem(BloodWorld world) {
		this.world = world;
	}

	public List<Droplet> droplets() {
		return droplets;
	}

	public boolean hasRoom() {
		return droplets.size() < GoreConfig.get().maxDroplets;
	}

	public void spawn(double x, double y, double z, double vx, double vy, double vz, float volume, int rgb, float size) {
		if (!hasRoom()) {
			return;
		}

		// Out of the body being bled, if any: as much as it really has left to give, at the pressure it has left.
		float real = BloodSupply.draw(volume);
		if (volume > 0.0F && real < volume * 0.02F) {
			return;
		}

		// A thinner flow throws smaller drops.
		float shrink = volume > 0.0F ? (float) Math.sqrt(real / volume) : 1.0F;
		droplets.add(new Droplet(x, y, z, vx, vy, vz, real, rgb, size * Math.max(0.4F, shrink)));
	}

	void tick(Level level) {
		int write = 0;
		for (int i = 0; i < droplets.size(); i++) {
			Droplet d = droplets.get(i);
			if (step(level, d)) {
				droplets.set(write++, d);
			}
		}

		droplets.subList(write, droplets.size()).clear();
	}

	/** Advances one droplet; returns false once it has landed or expired. */
	private boolean step(Level level, Droplet d) {
		d.prevX = d.x;
		d.prevY = d.y;
		d.prevZ = d.z;
		if (++d.age > MAX_AGE || !level.isLoaded(BlockPos.containing(d.x, d.y, d.z))) {
			return false;
		}

		if (level.getFluidState(BlockPos.containing(d.x, d.y, d.z)).is(net.minecraft.tags.FluidTags.WATER)) {
			// Blood let out under water does not fly: the water stops it within a few centimetres and it clouds out
			// where it left the body, a little way along the way it was going.
			world.clouds().add(level, d.x + Math.clamp(d.vx, -0.15, 0.15), d.y + Math.clamp(d.vy, -0.15, 0.15), d.z + Math.clamp(d.vz, -0.15, 0.15),
					d.volume, d.rgb);
			return false;
		}

		d.vy -= GRAVITY;
		d.vx *= DRAG;
		d.vy *= DRAG;
		d.vz *= DRAG;

		Vec3 from = new Vec3(d.x, d.y, d.z);
		Vec3 to = new Vec3(d.x + d.vx, d.y + d.vy, d.z + d.vz);
		BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));

		if (hit.getType() != HitResult.Type.BLOCK) {
			d.x = to.x;
			d.y = to.y;
			d.z = to.z;
			if ((d.age & 3) == 1) {
				d.light = DecalManager.light(level, BlockPos.containing(d.x, d.y, d.z));
			}

			// Blood entering water clouds out in it; in lava it just burns off.
			net.minecraft.world.level.material.FluidState fluid = level.getFluidState(BlockPos.containing(d.x, d.y, d.z));
			if (fluid.isEmpty()) {
				return true;
			}

			if (fluid.is(net.minecraft.tags.FluidTags.WATER)) {
				world.clouds().add(level, d.x, d.y, d.z, d.volume, d.rgb);
			}

			return false;
		}

		Vec3 at = hit.getLocation();
		d.x = at.x;
		d.y = at.y;
		d.z = at.z;
		Direction face = hit.getDirection();
		GoreConfig config = GoreConfig.get();
		int lifetime = world.decalLifetime();

		if (face == Direction.UP) {
			world.landOnFloor(level, at.x, at.y, at.z, d.vx, d.vy, d.vz, d.volume, d.rgb, lifetime);
			return false;
		}

		if (face == Direction.DOWN) {
			// Hit a ceiling: lose upward speed and fall back down.
			d.vy = 0.0;
			d.y -= 0.02;
			return true;
		}

		// Side face.
		if (config.wallSplatter && !d.slid) {
			boolean xNormal = face.getAxis() == Direction.Axis.X;
			double a = xNormal ? at.z : at.x;
			float half = 0.08F + (float) Math.min(0.25, Math.sqrt(d.volume) * 0.9);
			world.decals().addWall(level, hit.getBlockPos(), face, a, at.y, half,
					BloodTextures.splat(random), random.nextInt(4), d.rgb, lifetime);
			if (d.volume > 0.012F && random.nextFloat() < 0.35F) {
				world.decals().addWall(level, hit.getBlockPos(), face, a, at.y - 0.25, 0.22F, BloodTextures.STREAK, 0, d.rgb, lifetime);
			}
		}

		if (d.slid) {
			return false;
		}

		// The rest of the drop slides down the wall.
		d.slid = true;
		d.vx = face.getStepX() * 0.01;
		d.vz = face.getStepZ() * 0.01;
		d.vy = -0.02;
		d.x += face.getStepX() * 0.02;
		d.z += face.getStepZ() * 0.02;
		return true;
	}

	void clear() {
		droplets.clear();
	}
}
