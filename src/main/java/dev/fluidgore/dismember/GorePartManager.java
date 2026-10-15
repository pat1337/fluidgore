package dev.fluidgore.dismember;

import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodTextures;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.BoneEmitter;
import dev.fluidgore.blood.BrokenBlocks;
import dev.fluidgore.blood.SurfaceProbe;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.net.GoreSync;
import dev.fluidgore.net.PieceSync;
import dev.fluidgore.trigger.Fate;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Simulates severed parts: gravity, drag, block collision with a slight bounce, tumbling, and finally settling
 * flat on the ground. Collision uses vanilla's entity collision routine against block shapes, so parts land
 * correctly on slabs, stairs and other partial blocks.
 */
public final class GorePartManager {
	private static final double GRAVITY = 0.06;
	private static final double AIR_DRAG = 0.98;
	private static final double BOUNCE = 0.3;
	private static final RandomSource RANDOM = Fate.stream("parts");
	/** Speed (blocks/tick) above which hitting a surface splats blood and squelches. */
	private static final double IMPACT_SPEED = 0.3;
	/** A loose bone hitting anything faster than this breaks apart. */
	private static final double BONE_BREAK_SPEED = 0.45;
	/** How long an enderman's pieces keep blinking about after they come off, in ticks, unless water keeps them at it. */
	private static final int ENDER_RESTLESS = 600;

	private final List<GorePart> parts = new ArrayList<>();
	private final RandomSource random = Fate.stream("partsMotion");

	public List<GorePart> parts() {
		return parts;
	}

	public void add(GorePart part) {
		if (part.key == 0L) {
			part.key = Fate.key();
		}

		if (GoreSync.local()) {
			// Cut off by this player: their game says where it flies.
			PieceSync.moved(part);
		}

		parts.add(part);
		while (parts.size() > GoreConfig.get().maxDetachedParts) {
			parts.removeFirst();
		}
	}

	/** The piece with this {@link GorePart#key}, or null. */
	public @Nullable GorePart byKey(long key) {
		for (GorePart part : parts) {
			if (part.key == key) {
				return part;
			}
		}

		return null;
	}

	public void tick(Level level, BloodWorld blood) {
		int write = 0;
		for (int i = 0; i < parts.size(); i++) {
			GorePart part = parts.get(i);
			if (step(level, blood, part)) {
				parts.set(write++, part);
			}
		}

		parts.subList(write, parts.size()).clear();
	}

	/**
	 * An enderman's pieces will not lie still. For a while after they come off they blink to another spot close by every
	 * few seconds, in a puff of portal sparks; water burns them as it burns an enderman, so a wet piece fizzes and keeps
	 * blinking until it finds somewhere dry. Returns true if the piece moved this tick.
	 */
	private boolean blink(Level level, GorePart p) {
		BlockPos at = BlockPos.containing(p.x, p.y, p.z);
		boolean wet = level.isRainingAt(at) || level.getFluidState(at).is(net.minecraft.tags.FluidTags.WATER);
		if (wet && p.age % 4 == 0) {
			level.addParticle(ParticleTypes.SMOKE, p.x, p.y + 0.1, p.z, 0.0, 0.04, 0.0);
			if (p.age % 20 == 0) {
				level.playLocalSound(p.x, p.y, p.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.25F, 1.6F, false);
			}
		}

		if (!wet && p.age > ENDER_RESTLESS) {
			return false;
		}

		if (p.blinkIn <= 0) {
			p.blinkIn = wet ? 10 + random.nextInt(15) : 40 + random.nextInt(100);
			return false;
		}

		if (--p.blinkIn > 0) {
			return false;
		}

		for (int tries = 0; tries < 10; tries++) {
			double x = p.x + (random.nextDouble() - 0.5) * 6.0;
			double z = p.z + (random.nextDouble() - 0.5) * 6.0;
			for (int dy = 2; dy >= -3; dy--) {
				BlockPos spot = BlockPos.containing(x, p.y + dy, z);
				if (level.getBlockState(spot).getCollisionShape(level, spot).isEmpty() && level.getFluidState(spot).isEmpty()
						&& !level.getBlockState(spot.below()).getCollisionShape(level, spot.below()).isEmpty()) {
					portal(level, p.x, p.y, p.z);
					p.x = p.prevX = x;
					p.y = p.prevY = spot.getY() + 0.3;
					p.z = p.prevZ = z;
					p.vx = p.vz = 0.0;
					p.vy = 0.1;
					p.resting = false;
					p.landed = false;
					p.restTarget = null;
					p.spinSpeed = 0.2F;
					portal(level, p.x, p.y, p.z);
					level.playLocalSound(p.x, p.y, p.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 0.4F, 1.3F + random.nextFloat() * 0.3F, false);
					return true;
				}
			}
		}

		return false;
	}

	private void portal(Level level, double x, double y, double z) {
		for (int i = 0; i < 16; i++) {
			level.addParticle(ParticleTypes.PORTAL, x, y, z, (random.nextDouble() - 0.5) * 1.5, random.nextDouble() * 0.8, (random.nextDouble() - 0.5) * 1.5);
		}
	}

	private boolean step(Level level, BloodWorld blood, GorePart p) {
		p.prevX = p.x;
		p.prevY = p.y;
		p.prevZ = p.z;
		p.prevRotation.set(p.rotation);
		if (p.shattered || ++p.age >= p.lifetime || !level.isLoaded(BlockPos.containing(p.x, p.y, p.z))) {
			return false;
		}

		if (p.age % 10 == 1) {
			p.light = LightCoordsUtil.getLightCoords(level, BlockPos.containing(p.x, p.y, p.z));
		}

		if (p.held) {
			// Positioned by PartInteraction while carried.
			return true;
		}

		if (p.ender() && blink(level, p)) {
			return true;
		}

		if (p.resting) {
			settle(level, blood, p);
			return true;
		}

		p.vy -= GRAVITY;
		p.vx *= AIR_DRAG;
		p.vy *= AIR_DRAG;
		p.vz *= AIR_DRAG;

		float r = radius(p);
		AABB box = new AABB(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r);
		Vec3 wanted = new Vec3(p.vx, p.vy, p.vz);
		Vec3 moved = Entity.collideBoundingBox(CollisionContext.empty(), wanted, box, level, List.of());
		p.x += moved.x;
		p.y += moved.y;
		p.z += moved.z;

		if (p.bone() && !p.held && (moved.x != wanted.x ? Math.abs(wanted.x) : moved.z != wanted.z ? Math.abs(wanted.z) : moved.y != wanted.y && wanted.y < 0.0 ? -wanted.y : 0.0) > BONE_BREAK_SPEED) {
			shatter((ClientLevel) level, p);
			return false;
		}

		if (moved.x != wanted.x) {
			splatWall(level, blood, p, r, wanted.x > 0 ? Direction.WEST : Direction.EAST, Math.abs(wanted.x));
			p.vx = -p.vx * BOUNCE;
			p.spinSpeed *= 0.8F;
		}

		if (moved.z != wanted.z) {
			splatWall(level, blood, p, r, wanted.z > 0 ? Direction.NORTH : Direction.SOUTH, Math.abs(wanted.z));
			p.vz = -p.vz * BOUNCE;
			p.spinSpeed *= 0.8F;
		}

		boolean onGround = false;
		if (moved.y != wanted.y) {
			if (wanted.y < 0.0) {
				onGround = true;
				if (!p.landed) {
					p.landed = true;
					if (p.bone()) {
						BoneEmitter.burst((ClientLevel) level, new Vec3(p.x, p.y - r, p.z), null, 4, 6, 0.1);
						level.playLocalSound(p.x, p.y, p.z, SoundEvents.BONE_BLOCK_FALL, SoundSource.NEUTRAL, 0.9F, 0.8F + random.nextFloat() * 0.4F, false);
					} else {
					float splash = 0.12F * GoreConfig.get().goreLevel.puddles;
					blood.landOnFloor(level, p.x, p.y - r, p.z, splash, p.bloodRgb, blood.decalLifetime());
					}
				} else if (-wanted.y > IMPACT_SPEED && p.age - p.lastSplat > 5) {
					// Thrown or kicked part slamming into the ground.
					p.lastSplat = p.age;
					float speed = (float) -wanted.y;
					if (p.bone()) {
						level.playLocalSound(p.x, p.y, p.z, SoundEvents.BONE_BLOCK_HIT, SoundSource.NEUTRAL, Math.min(1.0F, speed * 2.0F), 0.8F, false);
					} else {
					blood.landOnFloor(level, p.x, p.y - r, p.z, 0.04F * speed, p.bloodRgb, blood.decalLifetime());
					BloodEmitter.burst(blood, (ClientLevel) level, new Vec3(p.x, p.y - r, p.z), p.bloodRgb, 0.03F, 5);
					level.playLocalSound(p.x, p.y, p.z, SoundEvents.SLIME_SQUISH, SoundSource.NEUTRAL, Math.min(1.0F, speed), 0.8F, false);
					}
				}
			}

			p.vy = Math.abs(p.vy) > 0.12 ? -p.vy * BOUNCE : 0.0;
			// Ground friction.
			p.vx *= 0.7;
			p.vz *= 0.7;
			p.spinSpeed *= 0.6F;
		}

		if (p.spinSpeed > 1.0E-4F) {
			p.rotation.premul(new Quaternionf().fromAxisAngleRad(p.spinAxis, p.spinSpeed));
		}

		if (p.bleedLeft > 0.0F && p.age % 3 == 0) {
			BloodEmitter.drip(blood, new Vec3(p.x, p.y, p.z), p.bloodRgb, 0.008F);
			p.bleedLeft -= 0.008F;
		}

		if (onGround && Math.abs(p.vx) < 0.01 && Math.abs(p.vz) < 0.01 && Math.abs(p.vy) < 0.01) {
			p.resting = true;
			p.vx = p.vy = p.vz = 0.0;
			p.spinSpeed = 0.0F;
		}

		return true;
	}

	/**
	 * Once at rest, rotate the part so its thinnest axis points up (an arm lies on its side, a head sits upright),
	 * keeping a random yaw. Without this a part could come to rest balanced on a corner or sunk into the floor.
	 */
	private void settle(Level level, BloodWorld blood, GorePart p) {
		if (p.restTicks++ == 0) {
			float yaw = random.nextFloat() * (float) (Math.PI * 2.0);
			Quaternionf target = new Quaternionf().rotationY(yaw);
			float hx = p.halfExtents.x;
			float hy = p.halfExtents.y;
			float hz = p.halfExtents.z;
			if (hx < hy && hx <= hz) {
				target.rotateZ((float) (Math.PI / 2.0));
			} else if (hz < hy && hz < hx) {
				target.rotateX((float) (Math.PI / 2.0));
			}

			p.restTarget = target;
		}

		if (p.restTicks <= 12 && p.restTarget != null) {
			p.rotation.slerp(p.restTarget, 0.3F);
		}

		// Fall if the block below is removed: checked at once when a block nearby breaks, otherwise once a second.
		if (p.restTicks % 20 == 0 || BrokenBlocks.near(p.x, p.y, p.z)) {
			float r = radius(p);
			if (Double.isNaN(SurfaceProbe.surfaceBelow(level, p.x, p.y - r + 0.05, p.y - r - 0.05, p.z))) {
				p.resting = false;
				p.restTicks = 0;
			}
		}

		if (p.bleedLeft > 0.0F && p.restTicks % 10 == 0) {
			float r = radius(p);
			blood.landOnFloor(level, p.x, p.y - r, p.z, 0.02F, p.bloodRgb, blood.decalLifetime());
			p.bleedLeft -= 0.02F;
		}
	}

	public static void shatter(ClientLevel level, GorePart p) {
		PieceSync.shattered(p);
		BoneEmitter.shatter(level, new Vec3(p.x, p.y, p.z), Math.min(1.5F, 0.6F + radius(p) * 3.0F));
	}

	/** A part hitting a wall hard leaves a splat on it and squelches. {@code face} is the face that was hit. */
	private static void splatWall(Level level, BloodWorld blood, GorePart p, float r, Direction face, double speed) {
		if (speed < IMPACT_SPEED || p.age - p.lastSplat <= 5) {
			return;
		}

		p.lastSplat = p.age;
		// The block is just past the part on the side opposite the face's normal.
		BlockPos block = BlockPos.containing(p.x - face.getStepX() * (r + 0.05), p.y, p.z - face.getStepZ() * (r + 0.05));
		double a = face.getAxis() == Direction.Axis.X ? p.z : p.x;
		blood.decals().addWall(level, block, face, a, p.y, 0.2F + (float) Math.min(0.2, speed * 0.3),
				BloodTextures.splat(RANDOM), RANDOM.nextInt(4), p.bloodRgb, blood.decalLifetime());
		level.playLocalSound(p.x, p.y, p.z, SoundEvents.SLIME_SQUISH, SoundSource.NEUTRAL, (float) Math.min(1.0, speed), 0.9F, false);
		p.bleedLeft += 0.01F;
	}

	/** Collision radius: the smallest half extent, so a part resting on its flattest side touches the ground. */
	static float radius(GorePart p) {
		return Math.max(0.04F, Math.min(p.halfExtents.x, Math.min(p.halfExtents.y, p.halfExtents.z)));
	}

	public void removeChunk(int chunkX, int chunkZ) {
		parts.removeIf(p -> (((int) Math.floor(p.x)) >> 4) == chunkX && (((int) Math.floor(p.z)) >> 4) == chunkZ);
	}

	public void clear() {
		parts.clear();
	}
}
