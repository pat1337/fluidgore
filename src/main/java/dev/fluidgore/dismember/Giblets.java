package dev.fluidgore.dismember;

import dev.fluidgore.blood.BloodTextures;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.BrokenBlocks;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.trigger.Fate;
import dev.fluidgore.wound.Flesh;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Small pieces of a body: the confetti a blast makes of an animal, and the bits a pick or an axe knocks out of a
 * wound. Each piece is a tiny box of one tissue (muscle, fat, bone, hide, gut, cud, organ) a few model pixels across,
 * thrown out, tumbling, bouncing by how firm it is, and leaving a smear where wet ones land. Hide pieces wear a few
 * texels of the animal's own skin. Flat arrays with a fixed capacity; resting pieces stop simulating.
 */
public final class Giblets {
	public static final int CAPACITY = 4000;
	private static final double GRAVITY = 0.06;
	private static final double DRAG = 0.985;
	private static final RandomSource RANDOM = Fate.stream("giblets");

	public final double[] x = new double[CAPACITY];
	public final double[] y = new double[CAPACITY];
	public final double[] z = new double[CAPACITY];
	public final double[] px = new double[CAPACITY];
	public final double[] py = new double[CAPACITY];
	public final double[] pz = new double[CAPACITY];
	private final double[] vx = new double[CAPACITY];
	private final double[] vy = new double[CAPACITY];
	private final double[] vz = new double[CAPACITY];
	/** Half sizes, in blocks. */
	public final float[] hx = new float[CAPACITY];
	public final float[] hy = new float[CAPACITY];
	public final float[] hz = new float[CAPACITY];
	/** Tumble: angles (radians) now and last tick, and spin per tick. */
	public final float[] rx = new float[CAPACITY];
	public final float[] ry = new float[CAPACITY];
	public final float[] prx = new float[CAPACITY];
	public final float[] pry = new float[CAPACITY];
	private final float[] sx = new float[CAPACITY];
	private final float[] sy = new float[CAPACITY];
	public final int[] color = new int[CAPACITY];
	public final int[] light = new int[CAPACITY];
	/** Texture window (u, v, width, height); texture null = the shared flesh texture. */
	public final float[] u = new float[CAPACITY];
	public final float[] v = new float[CAPACITY];
	public final float[] du = new float[CAPACITY];
	public final float[] dv = new float[CAPACITY];
	public final @Nullable Identifier[] texture = new Identifier[CAPACITY];
	private final Tissue[] tissue = new Tissue[CAPACITY];
	private final int[] blood = new int[CAPACITY];
	private final boolean[] resting = new boolean[CAPACITY];
	private final boolean[] splatted = new boolean[CAPACITY];
	/** Ticks a wet piece stays stuck to the wall it slapped into before it peels off and drops; 0 if not stuck. */
	private final int[] stuck = new int[CAPACITY];
	public final int[] age = new int[CAPACITY];
	public final int[] life = new int[CAPACITY];
	private int count;
	/** Landing sounds left this tick: a rain of meat is a patter, not a wall of noise. */
	private int sounds;
	private int ticks;

	public int count() {
		return count;
	}

	/** How much of its full size piece {@code i} still has; pieces shrink away at the end of their life. */
	public float scale(int i) {
		int left = life[i] - age[i];
		return left >= 40 ? 1.0F : Math.max(0.0F, left / 40.0F);
	}

	/**
	 * A body torn apart by a blast at {@code from} (or from inside, if null): pieces of every tissue in their real
	 * proportions, thrown away from the blast. Returns the number of pieces made.
	 */
	public int burst(ClientLevel level, BloodWorld world, LivingEntity entity, @Nullable Vec3 from) {
		return pieces(level, world, entity, from, 1.0F, false);
	}

	/**
	 * A body ripped open by a blast that did not shred it: the gut, the organs, the rumen's half-digested grass and
	 * some muscle and fat spill out of the belly and are thrown off. {@code force} 0..1 is how hard the blast hit.
	 */
	public int spill(ClientLevel level, BloodWorld world, LivingEntity entity, Vec3 from, float force) {
		return pieces(level, world, entity, from, 0.2F + 0.25F * force, true);
	}

	private int pieces(ClientLevel level, BloodWorld world, LivingEntity entity, @Nullable Vec3 from, float share, boolean innards) {
		GoreConfig config = GoreConfig.get();
		GoreLevel preset = config.goreLevel;
		Flesh flesh = Flesh.of(entity);
		int rgb = dev.fluidgore.trigger.EntityFilter.bloodType(entity).rgb;
		float width = entity.getBbWidth();
		float height = entity.getBbHeight();
		// A cow makes about 160 pieces at Medium; smaller bodies fewer.
		float body = width * width * height / (0.9F * 0.9F * 1.4F);
		int n = Math.round(Mth.clamp(160.0F * body, 20.0F, 500.0F) * preset.particles * share);
		n = Math.min(n, config.maxGiblets);
		makeRoom(n, config.maxGiblets);

		List<float[]> skin = skinWindows(entity);
		Identifier skinTexture = skin.isEmpty() ? null : skinTexture(entity);
		Vec3 centre = entity.position().add(0.0, height * 0.5, 0.0);
		Vec3 origin = from != null ? from : centre.subtract(0.0, height * 0.3, 0.0);
		int lightAt = LightCoordsUtil.getLightCoords(level, entity.blockPosition());
		int made = 0;
		for (int k = 0; k < n && count < config.maxGiblets; k++) {
			Tissue t = switch (flesh) {
				case BONE -> Tissue.BONE;
				case DRIED -> RANDOM.nextFloat() < 0.3F ? Tissue.BONE : RANDOM.nextFloat() < 0.4F ? Tissue.HIDE : Tissue.MUSCLE;
				case MEAT, POULTRY, PORK, BEEF, ENDER, ROTTEN -> innards ? Tissue.pickInnards(RANDOM.nextFloat()) : Tissue.pick(RANDOM.nextFloat());
			};
			if (t == Tissue.HIDE && skinTexture == null) {
				t = Tissue.MUSCLE;
			}

			// Innards come out of the belly; a shredded body comes from all of it.
			double spread = innards ? 0.35 : 1.0;
			double ox = (RANDOM.nextDouble() - 0.5) * width * spread;
			double oy = innards ? height * (0.3 + RANDOM.nextDouble() * 0.25) : RANDOM.nextDouble() * height;
			double oz = (RANDOM.nextDouble() - 0.5) * width * spread;
			Vec3 at = entity.position().add(ox, oy, oz);
			Vec3 out = at.subtract(origin);
			double dist = out.length();
			out = dist < 1.0E-3 ? new Vec3(RANDOM.nextGaussian(), 1.0, RANDOM.nextGaussian()).normalize() : out.scale(1.0 / dist);
			// Lighter, smaller pieces fly further; the nearer the blast, the harder.
			double speed = (0.35 + RANDOM.nextDouble() * 0.55) * (t == Tissue.BONE ? 0.8 : 1.0) * Mth.clamp(2.5 / (dist + 1.0), 0.6, 1.6)
					* (innards ? 0.5 : 1.0);
			Vec3 vel = out.scale(speed).add(RANDOM.nextGaussian() * 0.12, 0.15 + RANDOM.nextDouble() * 0.25, RANDOM.nextGaussian() * 0.12);
			int i = spawn(at, vel, t, flesh, rgb, lightAt, Math.round((config.partLifetimeSeconds * 10 + RANDOM.nextInt(200)) * preset.duration));
			// The side that took the fireball is seared black.
			float sear = from == null ? 0.0F : (float) Mth.clamp(1.2 - dist, 0.0, 1.0) * RANDOM.nextFloat();
			if (t == Tissue.HIDE) {
				float[] w = skin.get(RANDOM.nextInt(skin.size()));
				texture[i] = skinTexture;
				u[i] = w[0];
				v[i] = w[1];
				du[i] = w[2];
				dv[i] = w[3];
				color[i] = 0xFFFFFF;
			}

			if (sear > 0.3F) {
				color[i] = sear(color[i], sear);
			}

			made++;
		}

		return made;
	}

	/**
	 * Bits knocked out of a wound by a pick or an axe: skin and fat first, muscle as it goes deeper, bone once it is
	 * down to the bone. {@code normal} points out of the wound.
	 */
	public void chips(ClientLevel level, LivingEntity entity, Vec3 at, Vec3 normal, int n, float depth) {
		GoreConfig config = GoreConfig.get();
		Flesh flesh = Flesh.of(entity);
		int rgb = dev.fluidgore.trigger.EntityFilter.bloodType(entity).rgb;
		makeRoom(n, config.maxGiblets);
		int lightAt = LightCoordsUtil.getLightCoords(level, BlockPos.containing(at));
		for (int k = 0; k < n && count < config.maxGiblets; k++) {
			Tissue t;
			if (flesh == Flesh.BONE) {
				t = Tissue.BONE;
			} else {
				float roll = RANDOM.nextFloat();
				t = depth >= 0.75F && roll < 0.4F ? Tissue.BONE : depth >= 0.4F && roll < 0.8F ? Tissue.MUSCLE : Tissue.FAT;
			}

			Vec3 vel = normal.scale(0.15 + RANDOM.nextDouble() * 0.2).add(RANDOM.nextGaussian() * 0.07, 0.12 + RANDOM.nextDouble() * 0.1,
					RANDOM.nextGaussian() * 0.07);
			spawn(at, vel, t, flesh, rgb, lightAt, Math.round((config.partLifetimeSeconds * 10 + RANDOM.nextInt(100)) * config.goreLevel.duration));
		}
	}

	private int spawn(Vec3 at, Vec3 vel, Tissue t, Flesh flesh, int rgb, int lightAt, int lifetime) {
		int i = count++;
		x[i] = px[i] = at.x;
		y[i] = py[i] = at.y;
		z[i] = pz[i] = at.z;
		vx[i] = vel.x;
		vy[i] = vel.y;
		vz[i] = vel.z;
		float size = (t.minPx + RANDOM.nextInt(t.maxPx - t.minPx + 1)) / 32.0F;
		hx[i] = size;
		hy[i] = t == Tissue.HIDE ? 1.0F / 64.0F : size * (0.7F + RANDOM.nextFloat() * 0.6F);
		// Bone and gut come in long bits.
		hz[i] = t == Tissue.BONE || t == Tissue.GUT ? size * (1.5F + RANDOM.nextFloat()) : size * (0.7F + RANDOM.nextFloat() * 0.6F);
		rx[i] = prx[i] = RANDOM.nextFloat() * Mth.TWO_PI;
		ry[i] = pry[i] = RANDOM.nextFloat() * Mth.TWO_PI;
		sx[i] = (RANDOM.nextFloat() - 0.5F) * 0.8F;
		sy[i] = (RANDOM.nextFloat() - 0.5F) * 0.8F;
		tissue[i] = t;
		blood[i] = t == Tissue.CUD ? Tissue.CUD.rgb : rgb;
		color[i] = tint(t, flesh);
		light[i] = lightAt;
		texture[i] = null;
		// A random 4x4-texel window of the 16x16 flesh texture, so no two pieces look the same.
		u[i] = RANDOM.nextInt(4) * 0.25F;
		v[i] = RANDOM.nextInt(4) * 0.25F;
		du[i] = 0.25F;
		dv[i] = 0.25F;
		resting[i] = false;
		splatted[i] = false;
		stuck[i] = 0;
		age[i] = 0;
		life[i] = Math.max(100, lifetime);
		return i;
	}

	/** Chars pieces {@code from} up to the newest by {@code amount} (0..1): the scraps of a body that burnt. */
	public void searFrom(int from, float amount) {
		for (int i = Math.max(0, from); i < count; i++) {
			color[i] = sear(color[i], amount);
		}
	}

	/** Darkens a colour towards charred brown-black by {@code amount} (0..1). */
	private static int sear(int rgb, float amount) {
		float k = 0.8F * amount;
		int r = Math.round((rgb >> 16 & 0xFF) * (1.0F - k) + 0x24 * k);
		int g = Math.round((rgb >> 8 & 0xFF) * (1.0F - k) + 0x18 * k);
		int b = Math.round((rgb & 0xFF) * (1.0F - k) + 0x12 * k);
		return r << 16 | g << 8 | b;
	}

	private static int tint(Tissue t, Flesh flesh) {
		int base = t == Tissue.ORGAN && RANDOM.nextBoolean() ? 0xD48A8E : t.rgb;
		if (flesh == Flesh.DRIED && t == Tissue.MUSCLE) {
			base = 0x6B5A3C;
		} else if (flesh == Flesh.POULTRY && (t == Tissue.MUSCLE || t == Tissue.FAT)) {
			// Raw chicken: pink meat, yellow fat.
			base = t == Tissue.MUSCLE ? 0xF4A2AC : 0xF4E2A8;
		} else if ((flesh == Flesh.PORK || flesh == Flesh.BEEF || flesh == Flesh.ENDER) && (t == Tissue.MUSCLE || t == Tissue.FAT)) {
			base = flesh.tint(t == Tissue.MUSCLE ? Flesh.Tissue.MUSCLE : Flesh.Tissue.FAT, 0);
		} else if (flesh == Flesh.ROTTEN) {
			// Rotting: grey-green muscle, waxy grey fat, black-brown guts and organs, stained bone.
			base = switch (t) {
				case MUSCLE -> 0x6B5E3A;
				case FAT -> 0xB5B08C;
				case BONE -> 0xBFAE7A;
				case GUT -> 0x5E4A30;
				case ORGAN -> 0x3A2818;
				case BLOOD -> 0x3A2414;
				default -> base;
			};
		}

		// Every piece a little different.
		float f = 0.85F + RANDOM.nextFloat() * 0.25F;
		int r = Math.min(255, Math.round((base >> 16 & 0xFF) * f));
		int g = Math.min(255, Math.round((base >> 8 & 0xFF) * f));
		int b = Math.min(255, Math.round((base & 0xFF) * f));
		return r << 16 | g << 8 | b;
	}

	public void tick(ClientLevel level, BloodWorld world) {
		sounds = 3;
		ticks++;
		int kept = 0;
		for (int i = 0; i < count; i++) {
			age[i]++;
			if (age[i] >= life[i]) {
				continue;
			}

			px[i] = x[i];
			py[i] = y[i];
			pz[i] = z[i];
			prx[i] = rx[i];
			pry[i] = ry[i];
			if (stuck[i] > 0 && --stuck[i] == 0) {
				// Peels off the wall and drops.
				resting[i] = false;
				vx[i] = vy[i] = vz[i] = 0.0;
			}

			// A resting piece whose floor or wall was broken away falls: at once if it was a block nearby this tick, and
			// in any case on its own check every second.
			if (resting[i] && ((i + ticks) % 20 == 0 || BrokenBlocks.near(x[i], y[i], z[i])) && !supported(level, i)) {
				resting[i] = false;
				stuck[i] = 0;
				vx[i] = vy[i] = vz[i] = 0.0;
			}

			if (!resting[i]) {
				step(level, world, i);
			}

			if (kept != i) {
				move(i, kept);
			}

			kept++;
		}

		for (int i = kept; i < count; i++) {
			texture[i] = null;
		}

		count = kept;
	}

	private void step(ClientLevel level, BloodWorld world, int i) {
		vy[i] = (vy[i] - GRAVITY) * DRAG;
		vx[i] *= DRAG;
		vz[i] *= DRAG;
		Tissue t = tissue[i];
		double bottom = hy[i];
		boolean landed = false;
		// Speed it came down at, for the sound of it landing.
		double fall = 0.0;
		// Axis by axis, so a piece slides along a wall or the floor instead of stopping dead.
		if (solid(level, x[i] + vx[i], y[i] - bottom, z[i])) {
			if (hitWall(level, world, i, Direction.Axis.X)) {
				return;
			}

			vx[i] = -vx[i] * t.restitution;
		} else {
			x[i] += vx[i];
		}

		if (solid(level, x[i], y[i] - bottom, z[i] + vz[i])) {
			if (hitWall(level, world, i, Direction.Axis.Z)) {
				return;
			}

			vz[i] = -vz[i] * t.restitution;
		} else {
			z[i] += vz[i];
		}

		if (solid(level, x[i], y[i] + vy[i] - bottom, z[i])) {
			if (vy[i] < 0.0) {
				landed = true;
				fall = -vy[i];
				y[i] = surface(level, x[i], y[i] + vy[i] - bottom, z[i]) + bottom;
				vy[i] = -vy[i] * t.restitution;
				vx[i] *= 0.55;
				vz[i] *= 0.55;
				sx[i] *= 0.5F;
				sy[i] *= 0.5F;
			} else {
				vy[i] = 0.0;
			}
		} else {
			y[i] += vy[i];
		}

		rx[i] += sx[i];
		ry[i] += sy[i];
		if (landed) {
			if (!splatted[i] && fall > 0.15) {
				thud(level, i, t, fall);
			}

			if (!splatted[i] && t.wet > 0.0F) {
				splatted[i] = true;
				world.landOnFloor(level, x[i], y[i] - bottom, z[i], t.wet * GoreConfig.get().goreLevel.puddles, blood[i], world.decalLifetime());
			}

			if (vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i] < 0.0025) {
				resting[i] = true;
				// Settle flat: lying on its broad side.
				rx[i] = Math.round(rx[i] / Mth.HALF_PI) * Mth.HALF_PI;
				prx[i] = rx[i];
				light[i] = LightCoordsUtil.getLightCoords(level, BlockPos.containing(x[i], y[i] + 0.1, z[i]));
			}
		}

		if (y[i] < level.getMinY() - 16) {
			age[i] = life[i];
		}
	}

	/**
	 * Piece {@code i} slapped into a wall moving along {@code axis}. A wet piece leaves a smear on the wall, and one
	 * that hit hard enough sticks there for a while before it peels off. Returns true if it stuck.
	 */
	private boolean hitWall(ClientLevel level, BloodWorld world, int i, Direction.Axis axis) {
		Tissue t = tissue[i];
		double along = axis == Direction.Axis.X ? vx[i] : vz[i];
		double speed = Math.abs(along);
		if (t.wet <= 0.0F || speed < 0.12) {
			return false;
		}

		BlockPos block = axis == Direction.Axis.X ? BlockPos.containing(x[i] + vx[i], y[i], z[i]) : BlockPos.containing(x[i], y[i], z[i] + vz[i]);
		// The face that was hit looks back at the piece.
		Direction face = axis == Direction.Axis.X ? (along > 0.0 ? Direction.WEST : Direction.EAST) : (along > 0.0 ? Direction.NORTH : Direction.SOUTH);
		if (GoreConfig.get().wallSplatter) {
			double a = axis == Direction.Axis.X ? z[i] : x[i];
			float half = Math.min(0.4F, 0.06F + hx[i] * 3.0F + (float) speed * 0.2F);
			world.decals().addWall(level, block, face, a, y[i], half, BloodTextures.splat(RANDOM), RANDOM.nextInt(8),
					world.vary(blood[i], 0.15F), world.decalLifetime());
		}

		if (speed < 0.3 || RANDOM.nextFloat() > 0.5F) {
			return false;
		}

		// Flattened against the wall, right on its surface.
		double plane = axis == Direction.Axis.X ? (along > 0.0 ? block.getX() : block.getX() + 1.0) : (along > 0.0 ? block.getZ() : block.getZ() + 1.0);
		double offset = (along > 0.0 ? -1.0 : 1.0) * hy[i];
		if (axis == Direction.Axis.X) {
			x[i] = plane + offset;
			ry[i] = Mth.HALF_PI;
		} else {
			z[i] = plane + offset;
			ry[i] = 0.0F;
		}

		rx[i] = Mth.HALF_PI;
		prx[i] = rx[i];
		pry[i] = ry[i];
		vx[i] = vy[i] = vz[i] = 0.0;
		resting[i] = true;
		stuck[i] = 30 + RANDOM.nextInt(400);
		thud(level, i, t, speed);
		return true;
	}

	/** A piece of meat or bone slapping down, while this tick's budget of landing sounds lasts. */
	private void thud(ClientLevel level, int i, Tissue t, double impact) {
		if (sounds <= 0 || !GoreConfig.get().goreSounds || RANDOM.nextInt(4) != 0) {
			return;
		}

		sounds--;
		SoundEvent sound = t == Tissue.BONE ? SoundEvents.BONE_BLOCK_FALL : RANDOM.nextBoolean() ? SoundEvents.SLIME_SQUISH_SMALL : SoundEvents.HONEY_BLOCK_FALL;
		float volume = (float) Mth.clamp(impact * 1.5, 0.15, 0.7);
		level.playLocalSound(x[i], y[i], z[i], sound, SoundSource.NEUTRAL, volume, 0.8F + RANDOM.nextFloat() * 0.5F, false);
	}

	/** A resting piece still lies on a floor, or a stuck one still has its wall. */
	private boolean supported(ClientLevel level, int i) {
		if (stuck[i] == 0) {
			return solid(level, x[i], y[i] - hy[i] - 0.02, z[i]);
		}

		double reach = hy[i] + 0.02;
		return solid(level, x[i] + reach, y[i], z[i]) || solid(level, x[i] - reach, y[i], z[i]) || solid(level, x[i], y[i], z[i] + reach)
				|| solid(level, x[i], y[i], z[i] - reach);
	}

	/** True if the point is inside a block's collision box (shape bounds: exact for full blocks and slabs). */
	private static boolean solid(ClientLevel level, double wx, double wy, double wz) {
		BlockPos pos = BlockPos.containing(wx, wy, wz);
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return false;
		}

		VoxelShape shape = state.getCollisionShape(level, pos);
		if (shape.isEmpty()) {
			return false;
		}

		double lx = wx - pos.getX();
		double ly = wy - pos.getY();
		double lz = wz - pos.getZ();
		return lx >= shape.min(Direction.Axis.X) && lx <= shape.max(Direction.Axis.X)
				&& ly >= shape.min(Direction.Axis.Y) && ly <= shape.max(Direction.Axis.Y)
				&& lz >= shape.min(Direction.Axis.Z) && lz <= shape.max(Direction.Axis.Z);
	}

	/** Top of the collision box at a point inside it. */
	private static double surface(ClientLevel level, double wx, double wy, double wz) {
		BlockPos pos = BlockPos.containing(wx, wy, wz);
		VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
		return shape.isEmpty() ? wy : pos.getY() + shape.max(Direction.Axis.Y);
	}

	/** Drops the oldest pieces (lowest indices: pieces are kept in spawn order) so {@code n} more fit under the cap. */
	private void makeRoom(int n, int cap) {
		int drop = count + n - Math.min(cap, CAPACITY);
		if (drop <= 0) {
			return;
		}

		drop = Math.min(drop, count);
		for (int i = drop; i < count; i++) {
			move(i, i - drop);
		}

		count -= drop;
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
		hx[to] = hx[from];
		hy[to] = hy[from];
		hz[to] = hz[from];
		rx[to] = rx[from];
		ry[to] = ry[from];
		prx[to] = prx[from];
		pry[to] = pry[from];
		sx[to] = sx[from];
		sy[to] = sy[from];
		color[to] = color[from];
		light[to] = light[from];
		u[to] = u[from];
		v[to] = v[from];
		du[to] = du[from];
		dv[to] = dv[from];
		texture[to] = texture[from];
		tissue[to] = tissue[from];
		blood[to] = blood[from];
		resting[to] = resting[from];
		splatted[to] = splatted[from];
		stuck[to] = stuck[from];
		age[to] = age[from];
		life[to] = life[from];
	}

	/** Texel windows on the animal's own skin: a small piece of a random face of each of its model's cubes. */
	private static List<float[]> skinWindows(LivingEntity entity) {
		List<float[]> windows = new ArrayList<>();
		ModelParts.Posed posed = ModelParts.pose(entity);
		if (posed == null) {
			return windows;
		}

		for (ModelPart part : posed.model().root().getAllParts()) {
			for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).fluidgore$getCubes()) {
				for (ModelPart.Polygon polygon : cube.polygons) {
					float u0 = Float.MAX_VALUE;
					float v0 = Float.MAX_VALUE;
					float u1 = -Float.MAX_VALUE;
					float v1 = -Float.MAX_VALUE;
					for (ModelPart.Vertex vertex : polygon.vertices()) {
						u0 = Math.min(u0, vertex.u());
						v0 = Math.min(v0, vertex.v());
						u1 = Math.max(u1, vertex.u());
						v1 = Math.max(v1, vertex.v());
					}

					float w = (u1 - u0) * 0.2F;
					float h = (v1 - v0) * 0.2F;
					if (w > 0.0F && h > 0.0F) {
						float su = u0 + RANDOM.nextFloat() * (u1 - u0 - w);
						float sv = v0 + RANDOM.nextFloat() * (v1 - v0 - h);
						windows.add(new float[]{su, sv, w, h});
					}
				}
			}
		}

		return windows;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static @Nullable Identifier skinTexture(LivingEntity entity) {
		ModelParts.Posed posed = ModelParts.pose(entity);
		return posed == null ? null : ((net.minecraft.client.renderer.entity.LivingEntityRenderer) posed.renderer()).getTextureLocation(posed.state());
	}

	public void clear() {
		for (int i = 0; i < count; i++) {
			texture[i] = null;
		}

		count = 0;
	}
}
