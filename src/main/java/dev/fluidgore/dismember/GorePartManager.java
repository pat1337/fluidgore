package dev.fluidgore.dismember;

import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodTextures;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.BoneEmitter;
import dev.fluidgore.blood.BrokenBlocks;
import dev.fluidgore.blood.SurfaceProbe;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.event.Events;
import dev.fluidgore.physics.Buoyancy;
import dev.fluidgore.material.Condition;
import dev.fluidgore.material.Exposure;
import dev.fluidgore.material.HeatEffects;
import dev.fluidgore.material.Thermal;
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
import net.minecraft.util.Mth;
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
	/** Vanilla's hold of water on a mob, per tick. */
	private static final double WATER_DRAG = 0.8;
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
	/** Hisses and crackles of heat left this tick. */
	private int hisses;

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
		hisses = 2;
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

	/** Moves a piece inside a block to the nearest open spot, sideways first, then up, and lets it fall from there. */
	private static void pushOut(Level level, GorePart p) {
		for (double step = 0.25; step <= 1.5; step += 0.25) {
			for (int k = 0; k < 5; k++) {
				double dx = k == 0 ? step : k == 1 ? -step : 0.0;
				double dz = k == 2 ? step : k == 3 ? -step : 0.0;
				double dy = k == 4 ? step : 0.0;
				if (SurfaceProbe.isOpen(level, p.x + dx, p.y + dy, p.z + dz)) {
					p.x = p.prevX = p.x + dx;
					p.y = p.prevY = p.y + dy;
					p.z = p.prevZ = p.z + dz;
					p.resting = false;
					p.landed = false;
					return;
				}
			}
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
			// Burning or glowing hot, it lights itself.
			p.light = p.condition.burning || p.condition.temperature > 500.0F ? LightCoordsUtil.FULL_BRIGHT
					: LightCoordsUtil.getLightCoords(level, BlockPos.containing(p.x, p.y, p.z));
		}

		// Heat and water go on working on it whether it moves, lies still or is carried: hot or near heat often, cold now and then.
		GoreConfig config = GoreConfig.get();
		if (config.pieceReactions) {
			int every = p.condition.burning || p.condition.temperature > 35.0F ? 2 : 10;
			if (Math.floorMod(p.age + (int) p.key, every) == 0) {
				react(level, blood, p, every, config.cremationSpeedPercent / 100.0F);
			}
		}

		if (p.age % 5 == 0 && !SurfaceProbe.isOpen(level, p.x, p.y, p.z)) {
			// A block was placed on it: shoved out of the way.
			pushOut(level, p);
		}

		if (p.held) {
			// Positioned by PartInteraction while carried.
			return true;
		}

		if (p.ender() && blink(level, p)) {
			return true;
		}

		float r = radius(p);
		if (p.resting && p.age % 20 == 0 && p.material().density() < 1.0F && Buoyancy.submerged(level, p.x, p.y - r, p.y + r, p.z) > 0.0F) {
			// Water came over a piece that floats (fat, a lung): it comes up.
			p.resting = false;
		}

		if (p.resting) {
			settle(level, blood, p);
			return true;
		}

		float under = Buoyancy.submerged(level, p.x, p.y - r, p.y + r, p.z);
		if (under > 0.0F) {
			// In water a piece sinks or floats by what it is made of (bone and liver sink, fat and lung float), held back
			// by the water and carried by its current; it turns slowly.
			Vec3 current = Buoyancy.current(level, p.x, p.y, p.z);
			p.vy = (p.vy - GRAVITY * (1.0F - under / p.material().density())) * WATER_DRAG;
			p.vx = current.x + (p.vx - current.x) * WATER_DRAG;
			p.vz = current.z + (p.vz - current.z) * WATER_DRAG;
			p.spinSpeed *= 0.85F;
		} else {
			p.vy -= GRAVITY;
			p.vx *= AIR_DRAG;
			p.vy *= AIR_DRAG;
			p.vz *= AIR_DRAG;
		}

		AABB box = new AABB(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r);
		Vec3 wanted = new Vec3(p.vx, p.vy, p.vz);
		Vec3 moved = Entity.collideBoundingBox(CollisionContext.empty(), wanted, box, level, List.of());
		p.x += moved.x;
		p.y += moved.y;
		p.z += moved.z;

		// Burnt bone is brittle: calcined, it breaks at a fraction of the speed.
		if (p.bone() && !p.held && (moved.x != wanted.x ? Math.abs(wanted.x) : moved.z != wanted.z ? Math.abs(wanted.z) : moved.y != wanted.y && wanted.y < 0.0 ? -wanted.y : 0.0)
				> BONE_BREAK_SPEED * (0.35F + 0.65F * p.condition.integrity)) {
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
					} else if (Thermal.crumbles(p.material(), p.condition, -wanted.y)) {
						crumble(level, p, -wanted.y);
					} else if (wet(p) > 0.0F && under == 0.0F) {
						// As much blood as it still has: a piece cooked through leaves none.
						float splash = blood(p, 0.12F * GoreConfig.get().goreLevel.puddles * wet(p));
						blood.landOnFloor(level, p.x, p.y - r, p.z, splash, p.bloodRgb, blood.decalLifetime());
						if (p.event != 0L) {
							Events.record(p.event, p.eventSource, Events.Channel.COLLISION, -1, Events.Effect.WOUND, Events.piece(p.key),
									String.format(java.util.Locale.ROOT, "landed at %.2f, left blood %.4f", -wanted.y, splash));
						}
					}
				} else if (-wanted.y > IMPACT_SPEED && p.age - p.lastSplat > 5) {
					// Thrown or kicked part slamming into the ground.
					p.lastSplat = p.age;
					float speed = (float) -wanted.y;
					if (p.bone()) {
						level.playLocalSound(p.x, p.y, p.z, SoundEvents.BONE_BLOCK_HIT, SoundSource.NEUTRAL, Math.min(1.0F, speed * 2.0F), 0.8F, false);
					} else if (Thermal.crumbles(p.material(), p.condition, speed)) {
						crumble(level, p, speed);
					} else if (wet(p) < 0.15F) {
						level.playLocalSound(p.x, p.y, p.z, SoundEvents.MUD_HIT, SoundSource.NEUTRAL, Math.min(1.0F, speed), 0.9F, false);
					} else {
					float splash = blood(p, 0.04F * speed);
					if (splash > 0.0F) {
						blood.landOnFloor(level, p.x, p.y - r, p.z, splash, p.bloodRgb, blood.decalLifetime());
						BloodEmitter.burst(blood, (ClientLevel) level, new Vec3(p.x, p.y - r, p.z), p.bloodRgb, Math.min(0.03F, splash), 5);
					}
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
				// Half a head falls open as it is split, onto its outer side, the cut face up.
				boolean right = p.section != null && p.section.half() < 0;
				target.rotateZ((float) (right ? -Math.PI / 2.0 : Math.PI / 2.0));
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

	/**
	 * {@code ticks} of what lies round the piece working on it (see {@link Thermal}): it steams, cooks, dries, chars,
	 * catches and is put out like any piece of a body; heat seals its cut ends so it bleeds no more, and water rinses them.
	 */
	private void react(Level level, BloodWorld blood, GorePart p, int ticks, float pace) {
		Condition c = p.condition;
		float r = radius(p);
		Exposure around = Exposure.at(level, p.x, p.y, p.z, r);
		if (!Thermal.active(c, around)) {
			return;
		}

		float charredBefore = c.charred;
		boolean lit = c.burning;
		int events = Thermal.step(p.material(), c, around, p.thickness(), ticks, pace);
		Events.heat(Events.piece(p.key), new Vec3(p.x, p.y, p.z), around, events, charredBefore, c);
		if (c.cooked > 0.3F) {
			p.bleedLeft = Math.min(p.bleedLeft, 0.1F * (1.0F - c.cooked));
		}

		if ((events & Thermal.WASHED) != 0) {
			// Water rinses the blood out of the cut ends (rain more slowly): it runs off with the water, not onto the ground.
			p.bleedLeft = Math.max(0.0F, p.bleedLeft - ticks * pace * (around.submerged() ? 0.02F : 0.005F));
		}

		if (c.burning != lit) {
			p.light = c.burning ? LightCoordsUtil.FULL_BRIGHT : LightCoordsUtil.getLightCoords(level, BlockPos.containing(p.x, p.y, p.z));
		}

		float size = Math.max(p.halfExtents.x, Math.max(p.halfExtents.y, p.halfExtents.z)) * 2.0F;
		hisses = HeatEffects.show(level, events, p.material(), p.x, p.y, p.z, p.y + r, size, hisses);
	}

	/**
	 * The acting event's cloud on a severed piece (see {@link Events#cloud}): a charge's fireball sears its surface and
	 * leaves it hot; a gas vessel's expanding gas frosts it. Its depth alone decides how much, so every screen agrees.
	 */
	public static void cloud(GorePart p) {
		float depth = Events.cloud(new Vec3(p.x, p.y, p.z));
		if (depth <= 0.0F) {
			return;
		}

		if (p.key == 0L) {
			// Named now (not when added), so the trace can say which piece this was.
			p.key = Fate.key();
		}

		Condition c = p.condition;
		if (Events.thermal()) {
			// A limb is thick: the flash sears its skin, not much more.
			float sear = 0.7F * depth;
			c.sear(sear);
			c.temperature = Math.max(c.temperature, Condition.AMBIENT + 450.0F * depth);
			Events.record(Events.Channel.THERMAL, -1, Events.Effect.MATERIAL, Events.piece(p.key),
					String.format(java.util.Locale.ROOT, "fireball, %.0f%% in, sear %.2f", depth * 100.0F, sear));
		} else {
			c.temperature = Math.min(c.temperature, Condition.AMBIENT - 25.0F * depth);
			Events.record(Events.Channel.EXPANSION, -1, Events.Effect.MATERIAL, Events.piece(p.key),
					String.format(java.util.Locale.ROOT, "chilled by the expanding gas, %.0f%% in, %.0f C", depth * 100.0F, c.temperature));
		}
	}

	/**
	 * A blast of {@code tntKg} at {@code centre} ({@code psi} caps it close in, for a vessel) reaching the severed pieces
	 * already lying about: each is thrown by the wave's push on it (a thin piece faster than a thick one), a bone close in
	 * breaks, and the blast's cloud sears or frosts those in it. Effects go to the event now acting.
	 */
	public void blast(ClientLevel level, Vec3 centre, double tntKg, double psi) {
		double reach = dev.fluidgore.physics.Blast.reach(tntKg, psi, 30.0F);
		for (GorePart p : parts) {
			if (p.held || p.shattered) {
				continue;
			}

			Vec3 out = new Vec3(p.x, p.y, p.z).subtract(centre);
			double metres = out.length();
			if (metres > reach) {
				continue;
			}

			Vec3 at = new Vec3(p.x, p.y, p.z);
			float kPa = Events.overpressure(level, at, Math.max(0.3, metres), tntKg, psi, Double.NaN);
			if (p.bone() && kPa >= dev.fluidgore.physics.Blast.TEAR_KPA) {
				shatter(level, p);
				p.shattered = true;
				Events.record(Events.Channel.PRESSURE, -1, Events.Effect.WOUND, Events.piece(p.key), String.format(java.util.Locale.ROOT, "broken at %.0f kPa", kPa));
				continue;
			}

			Vec3 dir = metres > 1.0E-3 ? out.scale(1.0 / metres) : new Vec3(0.0, 1.0, 0.0);
			double speed = Math.min(1.4, Events.throwSpeed(level, at, metres, tntKg, p.thickness() / 16.0) / 20.0);
			if (speed > 0.02) {
				PieceSync.moved(p);
				p.resting = false;
				p.restTicks = 0;
				p.landed = false;
				p.vx += dir.x * speed;
				p.vy += Math.max(0.0, dir.y) * speed + speed * 0.4;
				p.vz += dir.z * speed;
				p.spinAxis.set(random.nextFloat() - 0.5F, random.nextFloat() - 0.5F, random.nextFloat() - 0.5F).normalize();
				if (!p.spinAxis.isFinite()) {
					p.spinAxis.set(1.0F, 0.0F, 0.0F);
				}

				p.spinSpeed = (float) Math.min(0.8, speed * 0.6);
				Events.record(Events.Channel.PRESSURE, -1, Events.Effect.IMPULSE, Events.piece(p.key),
						String.format(java.util.Locale.ROOT, "thrown at %.1f m/s by %.0f kPa", speed * 20.0, kPa));
			} else if (Events.stirred(level, at, tntKg)) {
				// Under water the wave hardly moves it; the bubble's flow will, so it must not lie asleep.
				PieceSync.moved(p);
				p.resting = false;
				p.restTicks = 0;
				p.landed = false;
			}

			cloud(p);
		}
	}

	/** Continuous gas momentum on loose organs, with the same cover and direction as the server's jet. */
	public void jet(ClientLevel level, Vec3 origin, Vec3 direction, double psi, double area) {
		double reach = dev.fluidgore.physics.GasJet.reach(level, origin, direction, psi, area);
		for (GorePart p : parts) {
			if (p.held || p.shattered || p.remoteHeld > 0) continue;
			Vec3 at = new Vec3(p.x, p.y, p.z);
			if (at.distanceToSqr(origin) > (reach + 0.5) * (reach + 0.5)) continue;
			double shove = dev.fluidgore.physics.GasJet.push(new net.minecraft.world.phys.AABB(at, at).inflate(0.06), origin, direction, psi, area, reach);
			if (shove < 0.0002) continue;
			// All observers apply the same environmental force; do not give every observer ownership of the piece.
			p.resting = false;
			p.restTicks = 0;
			p.landed = false;
			p.vx = Math.clamp(p.vx + direction.x * shove, -1.4, 1.4);
			p.vy = Math.clamp(p.vy + direction.y * shove + shove * 0.08, -1.4, 1.4);
			p.vz = Math.clamp(p.vz + direction.z * shove, -1.4, 1.4);
			p.spinSpeed = Math.min(0.6F, p.spinSpeed + (float) shove);
		}
	}

	/** How much of a raw piece's blood it still has to give, 0..1: none once it is cooked or dried through. */
	/**
	 * How much of {@code amount} of blood piece {@code p} gives up: an organ (and every portion cut from it) holds a finite
	 * share, taken out as it goes, so no number of landings, throws, stamps or cuts makes more; a limb or a joint bleeds
	 * from its cut end as it always has.
	 */
	static float blood(GorePart p, float amount) {
		return p.organ != null ? Thermal.release(p.condition, amount) : amount;
	}

	private static float wet(GorePart p) {
		return Mth.clamp(p.condition.moisture * (1.0F - p.condition.cooked), 0.0F, 1.0F);
	}

	/** The piece hit something hard enough to break: charred through, char and ash come off it; little left, it goes. */
	private static void crumble(Level level, GorePart p, double speed) {
		float size = Math.max(p.halfExtents.x, Math.max(p.halfExtents.y, p.halfExtents.z));
		BoneEmitter.burnt((ClientLevel) level, new Vec3(p.x, p.y, p.z), null, Math.round(3 + size * 20.0F), Math.round(4 + size * 30.0F), 0.12,
				p.condition.charred);
		level.playLocalSound(p.x, p.y, p.z, SoundEvents.BASALT_BREAK, SoundSource.NEUTRAL, (float) Mth.clamp(speed * 1.5, 0.3, 0.9), 1.0F, false);
		Events.record(p.event, p.eventSource, Events.Channel.COLLISION, -1, Events.Effect.MATERIAL, Events.piece(p.key),
				String.format(java.util.Locale.ROOT, "crumbled hitting at %.2f, integrity %.2f", speed, p.condition.integrity * 0.7F));
		p.condition.integrity *= 0.7F;
		if (p.condition.integrity < 0.2F) {
			PieceSync.shattered(p);
			p.shattered = true;
		}
	}

	/** A part hitting a wall hard leaves a splat on it and squelches. {@code face} is the face that was hit. */
	private static void splatWall(Level level, BloodWorld blood, GorePart p, float r, Direction face, double speed) {
		if (speed < IMPACT_SPEED || p.age - p.lastSplat <= 5) {
			return;
		}

		p.lastSplat = p.age;
		if (Thermal.crumbles(p.material(), p.condition, speed)) {
			crumble(level, p, speed);
			return;
		}

		if (wet(p) < 0.15F) {
			// Cooked dry: it hits the wall dull and leaves nothing on it.
			level.playLocalSound(p.x, p.y, p.z, SoundEvents.MUD_HIT, SoundSource.NEUTRAL, (float) Math.min(1.0, speed), 0.9F, false);
			return;
		}

		// The block is just past the part on the side opposite the face's normal.
		BlockPos block = BlockPos.containing(p.x - face.getStepX() * (r + 0.05), p.y, p.z - face.getStepZ() * (r + 0.05));
		double a = face.getAxis() == Direction.Axis.X ? p.z : p.x;
		blood.decals().addWall(level, block, face, a, p.y, 0.2F + (float) Math.min(0.2, speed * 0.3),
				BloodTextures.splat(RANDOM), RANDOM.nextInt(4), p.bloodRgb, blood.decalLifetime());
		level.playLocalSound(p.x, p.y, p.z, SoundEvents.SLIME_SQUISH, SoundSource.NEUTRAL, (float) Math.min(1.0, speed), 0.9F, false);
		p.bleedLeft += 0.01F * wet(p);
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
