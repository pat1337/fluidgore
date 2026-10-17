package dev.fluidgore.dismember;

import dev.fluidgore.blood.BloodCell;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.SurfaceProbe;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.trigger.Fate;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.chicken.ChickenVariants;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Loose feathers. A blow on a bird knocks a tuft of them out; plucking pulls them out by the handful; a bird dying in
 * its wing-beats sheds them; a blast throws up a cloud. A dry feather is all drag: it sails and rocks from side to side
 * as it drifts down, and lies where it lands until someone walking through scatters it again. Feathers from a bleeding
 * wound come out wet with blood, and a feather that lands in a pool of blood or lies in the rain soaks it up: wet, it
 * clumps into strands, goes dark and drops like a stone.
 * <p>
 * Flat arrays with a fixed capacity, drawn by {@code render/FeatherRenderer}.
 */
public final class Feathers {
	public static final int CAPACITY = 3000;
	private static final RandomSource RANDOM = Fate.stream("feathers");
	/** A white hen, a rusty warm-climate one, a grey cold-climate one. */
	private static final int TEMPERATE = 0xF4F2EC;
	private static final int WARM = 0xB86A3E;
	private static final int COLD = 0xC4C0B8;
	/** How much darker a feather soaked through with blood is than a clean one. */
	private static final float DARKEN = 0.45F;
	private static final float HALF = 0.002F;

	public final double[] x = new double[CAPACITY];
	public final double[] y = new double[CAPACITY];
	public final double[] z = new double[CAPACITY];
	public final double[] px = new double[CAPACITY];
	public final double[] py = new double[CAPACITY];
	public final double[] pz = new double[CAPACITY];
	private final double[] vx = new double[CAPACITY];
	private final double[] vy = new double[CAPACITY];
	private final double[] vz = new double[CAPACITY];
	/** Heading of the quill and how far it rocks out of the flat, in radians, now and last tick. */
	public final float[] yaw = new float[CAPACITY];
	public final float[] pyaw = new float[CAPACITY];
	public final float[] tilt = new float[CAPACITY];
	public final float[] ptilt = new float[CAPACITY];
	private final float[] spin = new float[CAPACITY];
	private final float[] phase = new float[CAPACITY];
	/** Length of the feather in blocks. */
	public final float[] size = new float[CAPACITY];
	/** Which of the four drawn feathers. */
	public final int[] variant = new int[CAPACITY];
	private final int[] plumage = new int[CAPACITY];
	private final int[] bloodRgb = new int[CAPACITY];
	/** How much blood and water it has soaked up, 0..1. */
	public final float[] blood = new float[CAPACITY];
	public final float[] wet = new float[CAPACITY];
	public final int[] light = new int[CAPACITY];
	private final boolean[] resting = new boolean[CAPACITY];
	public final int[] age = new int[CAPACITY];
	public final int[] life = new int[CAPACITY];
	private int count;
	private int ticks;

	public int count() {
		return count;
	}

	/** True if {@code entity} is a bird with its feathers on. */
	public static boolean feathered(LivingEntity entity) {
		return entity instanceof Chicken;
	}

	/** The colour of {@code entity}'s feathers, or of a sheep's fleece (clumps of it come away the same way). */
	public static int plumage(Entity entity) {
		if (entity instanceof Sheep sheep) {
			return sheep.getColor().getTextureDiffuseColor() & 0xFFFFFF;
		}

		if (entity instanceof Chicken chicken) {
			if (chicken.getVariant().is(ChickenVariants.WARM)) {
				return WARM;
			}

			if (chicken.getVariant().is(ChickenVariants.COLD)) {
				return COLD;
			}
		}

		return TEMPERATE;
	}

	/**
	 * A tuft of {@code n} feathers knocked or pulled out of {@code entity} at {@code at}, thrown off along {@code away}
	 * (or every way, if null) at about {@code speed}. {@code bloody} 0..1 is how wet with blood they come out.
	 */
	public void tuft(LivingEntity entity, Vec3 at, Vec3 away, int n, double speed, float bloody, int bloodColor) {
		GoreConfig config = GoreConfig.get();
		if (!config.feathers || !config.goreLevel.enabled()) {
			return;
		}

		n = Math.min(Math.round(n * config.goreLevel.particles), config.maxFeathers);
		if (n <= 0) {
			return;
		}

		makeRoom(n, config.maxFeathers);
		int base = plumage(entity);
		int lifetime = Math.max(400, Math.round(config.persistenceSeconds * 20 * config.goreLevel.bloodDuration));
		float scale = entity.isBaby() ? 0.55F : 1.0F;
		for (int k = 0; k < n; k++) {
			int i = count++;
			x[i] = px[i] = at.x + (RANDOM.nextDouble() - 0.5) * 0.2;
			y[i] = py[i] = at.y + (RANDOM.nextDouble() - 0.5) * 0.15;
			z[i] = pz[i] = at.z + (RANDOM.nextDouble() - 0.5) * 0.2;
			double s = speed * (0.4 + RANDOM.nextDouble() * 0.8);
			vx[i] = away.x * s + RANDOM.nextGaussian() * speed * 0.5;
			vy[i] = Math.max(0.0, away.y) * s + 0.02 + RANDOM.nextDouble() * speed * 0.6;
			vz[i] = away.z * s + RANDOM.nextGaussian() * speed * 0.5;
			yaw[i] = pyaw[i] = RANDOM.nextFloat() * Mth.TWO_PI;
			tilt[i] = ptilt[i] = 0.0F;
			spin[i] = (RANDOM.nextFloat() - 0.5F) * 0.3F;
			phase[i] = RANDOM.nextFloat() * Mth.TWO_PI;
			// Five to fifteen centimetres: down from the breast up to a wing feather.
			size[i] = (0.07F + RANDOM.nextFloat() * 0.07F) * scale;
			variant[i] = RANDOM.nextInt(4);
			plumage[i] = base;
			bloodRgb[i] = bloodColor;
			// Not every feather of a bloody tuft is soaked: some only spotted, a few clean.
			blood[i] = Mth.clamp(bloody * (0.3F + RANDOM.nextFloat() * 1.0F), 0.0F, 1.0F);
			wet[i] = 0.0F;
			resting[i] = false;
			age[i] = 0;
			life[i] = lifetime + RANDOM.nextInt(200);
			light[i] = LightCoordsUtil.getLightCoords(entity.level(), BlockPos.containing(at));
		}
	}

	/**
	 * Colour of feather {@code i} as drawn: its plumage dyed by the blood in it and darkened by water. Blood dyes a
	 * feather the way it dyes cloth, multiplying into it: a little is already a strong red, not a pink-grey, and a soaked
	 * one is dark with it.
	 */
	public int color(int i) {
		int rgb = plumage[i];
		float r = (rgb >> 16 & 0xFF) / 255.0F;
		float g = (rgb >> 8 & 0xFF) / 255.0F;
		float bl = (rgb & 0xFF) / 255.0F;
		float b = Math.min(1.0F, blood[i]);
		if (b > 0.0F) {
			int stain = bloodRgb[i];
			// The blood's hue at full strength: its brightest channel brought up to 1.
			float sr = (stain >> 16 & 0xFF) / 255.0F;
			float sg = (stain >> 8 & 0xFF) / 255.0F;
			float sb = (stain & 0xFF) / 255.0F;
			float top = Math.max(0.05F, Math.max(sr, Math.max(sg, sb)));
			float t = Mth.sqrt(b);
			float dark = 1.0F - DARKEN * b;
			r *= Mth.lerp(t, 1.0F, sr / top) * dark;
			g *= Mth.lerp(t, 1.0F, sg / top) * dark;
			bl *= Mth.lerp(t, 1.0F, sb / top) * dark;
		}

		float dark = 1.0F - 0.3F * wet[i];
		return Math.round(r * dark * 255.0F) << 16 | Math.round(g * dark * 255.0F) << 8 | Math.round(bl * dark * 255.0F);
	}

	/** True if feather {@code i} is soaked enough to be clumped into strands. */
	public boolean clumped(int i) {
		return Math.max(blood[i], wet[i]) > 0.45F;
	}

	/** How much of its full size feather {@code i} still has; feathers shrink away at the end of their life. */
	public float scale(int i) {
		int left = life[i] - age[i];
		return left >= 40 ? 1.0F : Math.max(0.0F, left / 40.0F);
	}

	public void tick(ClientLevel level, BloodWorld world) {
		ticks++;
		LocalPlayer player = Minecraft.getInstance().player;
		Vec3 feet = player != null ? player.position() : null;
		Vec3 stride = player != null ? player.getDeltaMovement() : Vec3.ZERO;
		boolean walking = stride.horizontalDistanceSqr() > 0.002;
		for (int i = 0; i < count; i++) {
			px[i] = x[i];
			py[i] = y[i];
			pz[i] = z[i];
			pyaw[i] = yaw[i];
			ptilt[i] = tilt[i];
			if (++age[i] > life[i]) {
				remove(i--);
				continue;
			}

			if (resting[i]) {
				rest(level, world, i, feet, stride, walking);
				continue;
			}

			fly(level, world, i);
		}
	}

	/** A feather in the air: it sails, rocks and spins down, or falls straight if it is soaked. */
	private void fly(ClientLevel level, BloodWorld world, int i) {
		float heavy = Math.max(Math.min(1.0F, blood[i]), wet[i]);
		float dry = 1.0F - heavy;
		boolean water = !level.getFluidState(BlockPos.containing(x[i], y[i], z[i])).isEmpty();
		if (water) {
			wet[i] = 1.0F;
		}

		phase[i] += 0.22F + 0.1F * dry;
		double gravity = 0.004 + 0.035 * heavy;
		double drag = water ? 0.6 : 0.88 + 0.1 * heavy;
		vy[i] = (vy[i] - gravity) * (water ? 0.6 : 0.9 + 0.08 * heavy);
		// A dry feather sways from side to side as it falls, the way a leaf does.
		vx[i] = vx[i] * drag + Mth.cos(phase[i]) * 0.006 * dry;
		vz[i] = vz[i] * drag + Mth.sin(phase[i] * 1.3F) * 0.006 * dry;
		tilt[i] = Mth.sin(phase[i]) * 0.7F * dry;
		yaw[i] += spin[i] * (0.3F + dry);

		AABB box = new AABB(x[i] - HALF, y[i] - HALF, z[i] - HALF, x[i] + HALF, y[i] + HALF, z[i] + HALF);
		Vec3 wanted = new Vec3(vx[i], vy[i], vz[i]);
		Vec3 moved = Entity.collideBoundingBox(CollisionContext.empty(), wanted, box, level, List.of());
		x[i] += moved.x;
		y[i] += moved.y;
		z[i] += moved.z;
		if (Math.abs(moved.x - wanted.x) > 1.0E-7) {
			vx[i] = 0.0;
		}

		if (Math.abs(moved.z - wanted.z) > 1.0E-7) {
			vz[i] = 0.0;
		}

		if (wanted.y < 0.0 && moved.y > wanted.y) {
			// Down: it lies flat where it landed, and drinks up whatever blood is there.
			resting[i] = true;
			vx[i] = vy[i] = vz[i] = 0.0;
			tilt[i] = 0.0F;
			y[i] += 0.004 + (i % 7) * 0.0015;
			soak(world, i);
			light[i] = LightCoordsUtil.getLightCoords(level, BlockPos.containing(x[i], y[i] + 0.1, z[i]));
		} else if (ticks % 10 == i % 10) {
			light[i] = LightCoordsUtil.getLightCoords(level, BlockPos.containing(x[i], y[i], z[i]));
		}
	}

	/** A feather lying on the ground: soaking in the rain or a pool, scattered by someone walking through. */
	private void rest(ClientLevel level, BloodWorld world, int i, Vec3 feet, Vec3 stride, boolean walking) {
		if (feet != null && walking && Math.abs(feet.y - y[i]) < 0.6 && sq(feet.x - x[i]) + sq(feet.z - z[i]) < 0.45 * 0.45 && wet[i] < 0.5F
				&& blood[i] < 0.6F) {
			// Kicked up by a passing foot: a dry feather lifts and sails off; a soaked one stays stuck down.
			resting[i] = false;
			vx[i] = stride.x * 0.6 + RANDOM.nextGaussian() * 0.03;
			vy[i] = 0.06 + RANDOM.nextDouble() * 0.05;
			vz[i] = stride.z * 0.6 + RANDOM.nextGaussian() * 0.03;
			return;
		}

		if (ticks % 20 != i % 20) {
			return;
		}

		BlockPos pos = BlockPos.containing(x[i], y[i] + 0.05, z[i]);
		if (level.isRainingAt(pos) || !level.getFluidState(pos).isEmpty()) {
			wet[i] = Math.min(1.0F, wet[i] + 0.15F);
		} else if (wet[i] > 0.0F) {
			wet[i] = Math.max(0.0F, wet[i] - 0.01F);
		}

		soak(world, i);
		if (!SurfaceProbe.stillSupported(level, x[i], y[i] - 0.004, z[i]) && SurfaceProbe.isOpen(level, x[i], y[i] - 0.05, z[i])) {
			// What it lay on is gone.
			resting[i] = false;
		}
	}

	/** Lying in a pool of blood, it drinks it up. */
	private void soak(BloodWorld world, int i) {
		BloodCell cell = world.fluid().cellAt(Mth.floor(x[i]), SurfaceProbe.airBlockY(y[i] - 0.01), Mth.floor(z[i]));
		if (cell != null && cell.volume > 0.02F) {
			if (blood[i] < 0.05F) {
				bloodRgb[i] = cell.rgb;
			}

			blood[i] = Math.min(1.0F, blood[i] + 0.35F);
		}
	}

	private static double sq(double v) {
		return v * v;
	}

	/** Frees room for {@code n} more feathers by dropping the oldest. */
	private void makeRoom(int n, int cap) {
		int over = count + n - Math.min(cap, CAPACITY);
		if (over <= 0) {
			return;
		}

		int drop = Math.min(over, count);
		for (int i = drop; i < count; i++) {
			move(i, i - drop);
		}

		count -= drop;
	}

	private void remove(int i) {
		count--;
		if (i != count) {
			move(count, i);
		}
	}

	private void move(int from, int to) {
		x[to] = x[from];
		y[to] = y[from];
		z[to] = z[from];
		px[to] = px[from];
		py[to] = py[from];
		pz[to] = pz[from];
		vx[to] = vx[from];
		vy[to] = vy[from];
		vz[to] = vz[from];
		yaw[to] = yaw[from];
		pyaw[to] = pyaw[from];
		tilt[to] = tilt[from];
		ptilt[to] = ptilt[from];
		spin[to] = spin[from];
		phase[to] = phase[from];
		size[to] = size[from];
		variant[to] = variant[from];
		plumage[to] = plumage[from];
		bloodRgb[to] = bloodRgb[from];
		blood[to] = blood[from];
		wet[to] = wet[from];
		light[to] = light[from];
		resting[to] = resting[from];
		age[to] = age[from];
		life[to] = life[from];
	}

	public void clear() {
		count = 0;
	}
}
