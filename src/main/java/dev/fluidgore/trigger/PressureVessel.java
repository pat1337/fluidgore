package dev.fluidgore.trigger;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodSupply;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.tank.Blast;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.wound.HitFeedback;
import dev.fluidgore.wound.Weapon;
import dev.fluidgore.wound.Wounds;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A 2x2 of TNT stands in for a carbon-overwrapped pressure vessel (COPV): by default the Roadster's, 200 litres of gas
 * at 10,000 psi. When the first of the four goes off, the vessel bursts, and every body around it is hurt the way a
 * real blast of that size hurts: by the peak overpressure where it stood, not by vanilla's TNT formula.
 * <p>
 * The physics:
 * <ul>
 * <li>Stored energy: Brode's isentropic expansion of the gas from vessel pressure down to the air,
 *     {@code E = pV / (gamma - 1) * (1 - (p0 / p)^((gamma - 1) / gamma))}, nitrogen (gamma 1.4). 200 L at 10,000 psi
 *     holds about 29 MJ, some 7 kg of TNT.</li>
 * <li>On the ground the blast reflects off it and acts like a charge 1.8 times as big (a hemispherical surface burst).</li>
 * <li>Peak side-on overpressure from the scaled distance {@code Z = r / W^(1/3)} by Mills (1987):
 *     {@code P = 1772/Z^3 - 114/Z^2 + 108/Z} kPa, capped at the vessel's own pressure.</li>
 * <li>A wall between the body and the vessel takes some of it (a blast wave bends round cover, so never all).</li>
 * <li>What a blast does to a body, by peak overpressure: deafness and ringing from about 7 kPa, burst eardrums from
 *     35, bruised lungs (coughing blood) from 100, torn lungs (mostly fatal) from 300, limbs torn off by the blast wind
 *     from 550, nothing recognisable left from 1400.</li>
 * <li>The overwrap and its liner fly apart in a few hundred fragments, thrown evenly over a hemisphere: the chance a
 *     body is hit falls with its area over the square of the distance.</li>
 * </ul>
 * All of it is cosmetic: who lives and dies is still the server's (vanilla) business. Happily, vanilla's four TNT kill
 * a player out to about 4.4 blocks, where the real burst gives about 300 kPa, so the two mostly agree.
 */
public final class PressureVessel {
	/** Peak overpressure, kPa, at which each effect begins (see {@link Blast}). */
	public static final float RINGING_KPA = Blast.RINGING_KPA;
	public static final float EARDRUM_KPA = Blast.EARDRUM_KPA;
	public static final float LUNG_KPA = Blast.LUNG_KPA;
	public static final float LETHAL_KPA = Blast.LETHAL_KPA;
	public static final float TEAR_KPA = Blast.TEAR_KPA;
	public static final float SHRED_KPA = Blast.SHRED_KPA;
	/** Fragments from a 200 L vessel; more for a bigger one. */
	public static final float FRAGMENTS_PER_LITRE = 1.5F;
	/** Ticks after a burst during which bits of the casing come down round it. */
	private static final int CLATTER_TICKS = 50;
	/** Ticks a vessel stays armed after its first TNT is lit (a lit TNT burns 80; chained ones go within 30 more). */
	private static final int ARMED_TICKS = 200;
	/** Ticks after the burst during which the rest of its TNT going off counts as the same burst. */
	private static final int AFTERSHOCK_TICKS = 60;
	/** How far an explosion may be from a vessel's centre and still be one of its TNT. */
	private static final double OWN_TNT = 3.0;
	private static final RandomSource RANDOM = Fate.stream("vessel");

	/**
	 * A 2x2 of TNT that has been lit, or a tank block the server says is bursting: where its centre is, when it was
	 * armed, and when it burst (-1 until it has).
	 */
	public static final class Vessel {
		public final Vec3 centre;
		/** TNT equivalent, kg, of the gas it holds (as configured when it was lit). */
		public final float tntKg;
		public final float litres;
		/** The pressure it burst at, psi. */
		public final float psi;
		/** For a tank block: the fragments the server drove into each body (by entity id), so every screen agrees. */
		final @Nullable Int2IntOpenHashMap hits;
		final int armed;
		int burstAt = -1;

		/** A tank block: its damage is real (the server's), so a lung blast kills from {@link Blast#FATAL_KPA}. */
		public boolean real() {
			return hits != null;
		}

		Vessel(Vec3 centre, float litres, float psi, @Nullable Int2IntOpenHashMap hits, int armed) {
			this.centre = centre;
			this.tntKg = (float) Blast.tntKg(litres, psi);
			this.litres = litres;
			this.psi = psi;
			this.hits = hits;
			this.armed = armed;
		}
	}

	private final List<Vessel> vessels = new ArrayList<>();
	private IntOpenHashSet primed = new IntOpenHashSet();
	private int tick;

	public List<Vessel> vessels() {
		return vessels;
	}

	/** Watches for TNT being lit: one lit inside a 2x2 of TNT arms a vessel there. */
	public void tick(ClientLevel level) {
		tick++;
		vessels.removeIf(v -> v.burstAt < 0 ? tick - v.armed > ARMED_TICKS : tick - v.burstAt > Math.max(AFTERSHOCK_TICKS, CLATTER_TICKS));
		for (Vessel v : vessels) {
			if (v.burstAt >= 0) {
				clatter(level, v, tick - v.burstAt);
			}
		}

		IntOpenHashSet now = new IntOpenHashSet();
		List<PrimedTnt> lit = new ArrayList<>();
		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof PrimedTnt tnt) {
				now.add(tnt.getId());
				lit.add(tnt);
			}
		}

		if (GoreConfig.get().pressureVessel) {
			for (PrimedTnt tnt : lit) {
				if (!primed.contains(tnt.getId())) {
					arm(level, tnt, lit);
				}
			}
		}

		primed = now;
	}

	/**
	 * A TNT was just lit: if it is one corner of a 2x2 square of TNT (in any plane; the other three still blocks, or lit
	 * at the same moment), that square is a vessel.
	 */
	private void arm(ClientLevel level, PrimedTnt tnt, List<PrimedTnt> lit) {
		BlockPos at = tnt.blockPosition();
		// The two axes of each plane the square can lie in: XZ (lying flat), XY and ZY (standing).
		int[][][] planes = {{{1, 0, 0}, {0, 0, 1}}, {{1, 0, 0}, {0, 1, 0}}, {{0, 0, 1}, {0, 1, 0}}};
		for (int[][] plane : planes) {
			for (int du = -1; du <= 0; du++) {
				for (int dv = -1; dv <= 0; dv++) {
					BlockPos corner = at.offset(plane[0][0] * du + plane[1][0] * dv, plane[0][1] * du + plane[1][1] * dv, plane[0][2] * du + plane[1][2] * dv);
					boolean full = true;
					Vec3 sum = Vec3.ZERO;
					for (int u = 0; u <= 1 && full; u++) {
						for (int v = 0; v <= 1; v++) {
							BlockPos cell = corner.offset(plane[0][0] * u + plane[1][0] * v, plane[0][1] * u + plane[1][1] * v, plane[0][2] * u + plane[1][2] * v);
							sum = sum.add(Vec3.atCenterOf(cell));
							if (!cell.equals(at) && !tntAt(level, cell, lit)) {
								full = false;
								break;
							}
						}
					}

					if (full) {
						Vec3 centre = sum.scale(0.25);
						for (Vessel v : vessels) {
							if (v.centre.distanceToSqr(centre) < 2.0 * 2.0) {
								return;
							}
						}

						GoreConfig config = GoreConfig.get();
						vessels.add(new Vessel(centre, config.vesselLitres, config.vesselPsi, null, tick));
						return;
					}
				}
			}
		}
	}

	private static boolean tntAt(ClientLevel level, BlockPos cell, List<PrimedTnt> lit) {
		if (level.getBlockState(cell).is(Blocks.TNT)) {
			return true;
		}

		for (PrimedTnt other : lit) {
			if (other.blockPosition().equals(cell)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * The server says a tank block is bursting at {@code centre} (its explosion packet follows, if this player is near
	 * enough to be sent one): the vessel that explosion will belong to.
	 */
	public Vessel told(Vec3 centre, float litres, float psi, Int2IntOpenHashMap hits) {
		vessels.removeIf(v -> v.burstAt < 0 && v.centre.distanceToSqr(centre) < OWN_TNT * OWN_TNT);
		Vessel vessel = new Vessel(centre, litres, psi, hits, tick);
		vessels.add(vessel);
		return vessel;
	}

	/**
	 * An explosion went off at {@code at}: the vessel it belongs to, or null. {@code burst} is told whether this is the
	 * explosion that burst it (the first of its TNT) rather than the rest of its TNT going off after.
	 */
	public @Nullable Vessel explosion(Vec3 at, boolean[] burst) {
		for (Vessel v : vessels) {
			if (v.centre.distanceToSqr(at) < OWN_TNT * OWN_TNT) {
				burst[0] = v.burstAt < 0;
				if (burst[0]) {
					v.burstAt = tick;
				}

				return v;
			}
		}

		return null;
	}

	public static double tntKg(double litres, double psi) {
		return Blast.tntKg(litres, psi);
	}

	/** Peak overpressure, kPa, on {@code entity}'s body: by its distance, less what cover took out of it. */
	public static float overpressure(Vessel vessel, Entity entity) {
		double r = entity.getBoundingBox().getCenter().distanceTo(vessel.centre);
		float seen = ServerExplosion.getSeenPercent(vessel.centre, entity);
		return Blast.covered(Blast.overpressure(r, vessel.tntKg, vessel.psi), seen);
	}

	/**
	 * Overpressure as the 0..1 impact {@link DamageTracker} grades blasts by, so its tear and shred thresholds fall at
	 * {@link #TEAR_KPA} and {@link #SHRED_KPA}: logarithmic in between, as injury is.
	 */
	public static float impact(float kPa, float tearImpact, float shredImpact) {
		double t = Math.log(Math.max(1.0F, kPa) / TEAR_KPA) / Math.log(SHRED_KPA / TEAR_KPA);
		return Mth.clamp((float) (tearImpact + (shredImpact - tearImpact) * t), 0.0F, 1.0F);
	}

	/** Fragments that hit {@code entity}: the server's count for a tank block, else drawn from the dice (Poisson), at most 6. */
	public static int fragments(Vessel vessel, Entity entity, RandomSource random) {
		if (vessel.hits != null) {
			return vessel.hits.get(entity.getId());
		}

		double r = Math.max(1.0, entity.getBoundingBox().getCenter().distanceTo(vessel.centre));
		double area = entity.getBbWidth() * entity.getBbHeight();
		double mean = FRAGMENTS_PER_LITRE * vessel.litres * area / (2.0 * Math.PI * r * r) * ServerExplosion.getSeenPercent(vessel.centre, entity);
		// Knuth's method: count draws until their product falls below e^-mean.
		double limit = Math.exp(-Math.min(mean, 20.0));
		double product = random.nextDouble();
		int hits = 0;
		while (product > limit && hits < 6) {
			hits++;
			product *= random.nextDouble();
		}

		return hits;
	}

	/**
	 * The moment of the burst: the crack and the cloud, and what it does to every body around it that the TNT's damage
	 * does not reach or does not kill: burst eardrums, bruised lungs, fragments. The local player gets
	 * {@link dev.fluidgore.player.PlayerInjuries#burst}.
	 */
	public static void burst(ClientLevel level, BloodWorld blood, Dismemberment dismemberment, Vessel vessel) {
		GoreConfig config = GoreConfig.get();
		Vec3 c = vessel.centre;
		float size = (float) Math.cbrt(vessel.tntKg / 7.0);
		// A gas burst is a crack more than a boom, then the roar of the gas.
		level.playLocalSound(c.x, c.y, c.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.BLOCKS, 6.0F * size, 0.55F, false);
		level.playLocalSound(c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 6.0F * size, 0.5F, false);
		// The gas cools as it expands: a white flash of fog thrown out along the ground; black shreds of carbon fibre.
		int fog = Math.round(160 * size * config.goreLevel.particles);
		for (int i = 0; i < fog; i++) {
			double a = RANDOM.nextDouble() * Math.PI * 2.0;
			double speed = 0.6 + RANDOM.nextDouble() * 1.2 * size;
			level.addParticle(i % 3 == 0 ? ParticleTypes.POOF : ParticleTypes.CLOUD, c.x, c.y - 0.3 + RANDOM.nextDouble() * 0.6, c.z,
					Math.cos(a) * speed, RANDOM.nextDouble() * 0.25, Math.sin(a) * speed);
		}

		int shreds = Math.round(70 * size * config.goreLevel.particles);
		for (int i = 0; i < shreds; i++) {
			Vec3 d = new Vec3(RANDOM.nextGaussian(), Math.abs(RANDOM.nextGaussian()), RANDOM.nextGaussian()).normalize().scale(0.8 + RANDOM.nextDouble() * 1.6);
			level.addParticle(i % 4 == 0 ? ParticleTypes.CRIT : ParticleTypes.SQUID_INK, c.x, c.y, c.z, d.x, d.y, d.z);
		}

		Minecraft mc = Minecraft.getInstance();
		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
				continue;
			}

			float kPa = overpressure(vessel, living);
			// The same dice on every client: the body, its health as the blast reached it, and where the vessel stood.
			Fate.begin(living, null);
			BloodSupply.begin(living);
			try {
				int hits = fragments(vessel, living, RANDOM);
				if (living == mc.player) {
					GoreSystems.injuries().burst(mc.player, vessel, kPa, hits);
				}

				if (kPa >= RINGING_KPA || hits > 0) {
					shock(level, blood, dismemberment, vessel, living, kPa, hits);
				}
			} finally {
				BloodSupply.end();
				Fate.end();
			}
		}
	}

	/** The burst on one body: fragments driven in, blood from the ears, blood coughed up. */
	private static void shock(ClientLevel level, BloodWorld blood, Dismemberment dismemberment, Vessel vessel, LivingEntity body, float kPa, int hits) {
		if (!GoreConfig.get().goreLevel.enabled() || !EntityFilter.affects(body)) {
			return;
		}

		Vec3 mid = body.getBoundingBox().getCenter();
		for (int i = 0; i < hits; i++) {
			Vec3 target = mid.add((RANDOM.nextDouble() - 0.5) * body.getBbWidth(), (RANDOM.nextDouble() - 0.5) * body.getBbHeight(),
					(RANDOM.nextDouble() - 0.5) * body.getBbWidth());
			Vec3 dir = target.subtract(vessel.centre);
			double length = dir.length();
			if (length < 1.0E-4) {
				continue;
			}

			dir = dir.scale(1.0 / length);
			Wounds.Strike strike = dismemberment.wounds().strike(body, vessel.centre, dir, length + 2.0, Weapon.PIERCE, 3.0F + RANDOM.nextFloat() * 5.0F);
			if (strike != null) {
				HitFeedback.hit(level, blood, body, strike, Weapon.PIERCE, dir);
			}
		}

		if (EntityFilter.isBone(body) || EntityFilter.isBloodless(body)) {
			return;
		}

		int rgb = EntityFilter.bloodColor(body);
		if (kPa >= EARDRUM_KPA) {
			// Both eardrums: a thin run of blood from each side of the head.
			Vec3 head = body.getEyePosition();
			float yaw = body.getYHeadRot() * Mth.DEG_TO_RAD;
			Vec3 side = new Vec3(Mth.cos(yaw), 0.0, Mth.sin(yaw)).scale(body.getBbWidth() * 0.35);
			float volume = 0.006F * Mth.clamp(kPa / EARDRUM_KPA, 1.0F, 3.0F);
			BloodEmitter.drip(blood, head.add(side), rgb, volume);
			BloodEmitter.drip(blood, head.subtract(side), rgb, volume);
		}

		if (kPa >= LUNG_KPA) {
			// Lungs torn by the pressure: blood coughed out of the mouth and nose, more the harder they were hit.
			Vec3 mouth = Wounds.mouth(body);
			if (mouth != null) {
				float strength = Mth.clamp((kPa - LUNG_KPA) / (LETHAL_KPA - LUNG_KPA), 0.0F, 1.0F);
				Vec3 out = body.getLookAngle().add(0.0, -0.4, 0.0).normalize();
				BloodEmitter.cut(blood, level, mouth, out, rgb, 0.02F + 0.04F * strength, Math.round(4 + 8 * strength));
			}
		}
	}

	/** After the crack, the casing comes down: bits of carbon and metal landing round the vessel for a couple of seconds. */
	private static void clatter(ClientLevel level, Vessel vessel, int age) {
		if (age < 8 || age > CLATTER_TICKS || RANDOM.nextFloat() > 0.3F) {
			return;
		}

		double a = RANDOM.nextDouble() * Math.PI * 2.0;
		double r = 3.0 + RANDOM.nextDouble() * 12.0 * Math.cbrt(vessel.tntKg / 7.0);
		double x = vessel.centre.x + Math.cos(a) * r;
		double z = vessel.centre.z + Math.sin(a) * r;
		level.playLocalSound(x, vessel.centre.y, z, RANDOM.nextBoolean() ? SoundEvents.CHAIN_FALL : SoundEvents.COPPER_FALL, SoundSource.BLOCKS,
				0.6F + RANDOM.nextFloat() * 0.6F, 0.7F + RANDOM.nextFloat() * 0.6F, false);
	}

	public void clear() {
		vessels.clear();
		primed.clear();
	}
}
