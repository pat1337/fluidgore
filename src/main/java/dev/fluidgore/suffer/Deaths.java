package dev.fluidgore.suffer;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.trigger.DamageTracker;
import dev.fluidgore.wound.Weapon;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * How an animal dies once it is down, modelled on what slaughterhouse and veterinary staff see after a fatal blow or
 * a stun, compressed into three to five seconds:
 * <ol>
 * <li>Collapse: the legs go at once and the body falls onto its side.</li>
 * <li>Tonic phase: rigid, legs locked out, head arched back (opisthotonus), no breathing.</li>
 * <li>Clonic phase: involuntary paddling in bursts with pauses, uncoordinated, each leg on its own, fading out.</li>
 * <li>Agonal gasps: a few irregular gasps, further and further apart, weaker each time.</li>
 * <li>Fasciculation: fine flickers of muscle, then the body is limp.</li>
 * </ol>
 * Meanwhile the heart pumps blood out of the worst wound in beats that start fast and weaken and slow until it stops.
 * The cause of death ({@link Corpse.Kind}) shapes all of it, and every length, strength and rhythm is rolled from the
 * body's own dice, so no two deaths are alike but every player sees the same one.
 */
public final class Deaths {
	/** Where the killing blow landed on the body. */
	public enum Zone {
		HEAD, BODY, LEG, UNKNOWN
	}

	/** The pose being built up for one body this tick. */
	static final class Posture {
		float tremble, thrash, kick, kickRate, tiltX, tiltZ, drop, stagger, headDip, lookYaw, foldFront, foldHind, stiff, headTilt, hindLimp,
				legSeed, gaspAt, gasp;

		Posture reset() {
			tremble = thrash = kick = tiltX = tiltZ = drop = stagger = headDip = lookYaw = foldFront = foldHind = stiff = headTilt = hindLimp = 0.0F;
			legSeed = gasp = 0.0F;
			gaspAt = -1000.0F;
			kickRate = 0.6F;
			return this;
		}

		SufferPose build() {
			return new SufferPose(tremble, thrash, kick, kickRate, tiltX, tiltZ, drop, stagger, headDip, lookYaw, foldFront, foldHind, stiff,
					headTilt, hindLimp, legSeed, gaspAt, gasp);
		}
	}

	/** The fewest and most ticks a body spends dying on the ground: three to five seconds. */
	static final int MIN_THROES = 60;
	static final int MAX_THROES = 100;

	private final ScreamManager screams;
	private final RandomSource random;

	Deaths(ScreamManager screams, RandomSource random) {
		this.screams = screams;
		this.random = random;
	}

	/** A horse: the biggest, hardest death (see {@link #plan}) and the most blood. */
	static boolean horse(Corpse c) {
		return c.entity.getType() == EntityTypes.HORSE;
	}

	/** Chance that a death ends with the body bursting apart, 0..1. */
	static float popChance() {
		GoreConfig config = GoreConfig.get();
		return config.deathPop ? config.deathPopPercent / 100.0F : 0.0F;
	}

	/**
	 * What killed it. Some causes are certain (a head off, a close blast, fire, a beating); the rest are weighed by the
	 * weapon and where it landed, with a little of each always possible.
	 *
	 * @param weapon what struck the killing blow, null for none (a fall, fire)
	 * @param blast  how hard a blast hit it, 0..1
	 * @param four   it stands on four legs (a broken back needs a hind end to lose)
	 */
	Corpse.Kind choose(@Nullable Weapon weapon, Zone zone, boolean fall, boolean headless, boolean burnt, float blast, int hits,
			int beatenHits, float blowShare, boolean four) {
		if (headless) {
			return Corpse.Kind.DECAPITATION;
		}

		if (blast >= DamageTracker.TEAR_IMPACT) {
			return Corpse.Kind.TRAUMA;
		}

		if (burnt) {
			return Corpse.Kind.BURN;
		}

		if (beatenHits >= 2) {
			return Corpse.Kind.BEATEN;
		}

		boolean heavy = weapon == Weapon.BLUNT || weapon == Weapon.CHOP || weapon == Weapon.PICK;
		boolean edge = weapon == Weapon.BLADE || weapon == Weapon.CHOP || weapon == Weapon.FLINT || weapon == Weapon.BITE;
		boolean oneBlow = hits <= 1 && blowShare >= 0.6F;
		Corpse.Kind[] kinds = {Corpse.Kind.CONCUSSION, Corpse.Kind.EXSANGUINATION, Corpse.Kind.SPINAL, Corpse.Kind.CHEST, Corpse.Kind.BEATEN,
				Corpse.Kind.TRAUMA};
		float[] weight = {
				0.6F + (zone == Zone.HEAD ? 2.0F : 0.0F) + (zone == Zone.HEAD && heavy ? 6.0F : 0.0F) + (oneBlow && heavy ? 2.0F : 0.0F)
						+ (weapon == Weapon.BLUNT ? 1.5F : 0.0F),
				0.6F + (edge ? 5.0F : 0.0F) + (zone == Zone.LEG ? 1.0F : 0.0F),
				four ? 0.3F + (fall ? 8.0F : 0.0F) + (zone == Zone.BODY && heavy ? 2.0F : 0.0F) : 0.0F,
				0.4F + (weapon == Weapon.PIERCE ? 6.0F : 0.0F) + (zone == Zone.BODY && edge ? 1.5F : 0.0F),
				0.3F + (hits >= 5 ? 3.0F : 0.0F),
				blast > 0.0F ? 3.0F : 0.0F
		};
		float total = 0.0F;
		for (float w : weight) {
			total += w;
		}

		float pick = random.nextFloat() * total;
		for (int i = 0; i < kinds.length; i++) {
			pick -= weight[i];
			if (pick < 0.0F) {
				return kinds[i];
			}
		}

		return Corpse.Kind.EXSANGUINATION;
	}

	/** Ticks the legs take to give way before the body goes over: a few, never more than about a third of a second. */
	int collapse(Corpse.Kind kind) {
		return kind == Corpse.Kind.DECAPITATION || kind == Corpse.Kind.TRAUMA ? 2 + random.nextInt(2) : 3 + random.nextInt(5);
	}

	/** Length of the death cry, 0..1, or negative for none: only a conscious animal cries out, and only weakly. */
	static float cry(Corpse.Kind kind) {
		return switch (kind) {
			case EXSANGUINATION -> 0.15F;
			case SPINAL -> 0.3F;
			case CHEST -> 0.05F;
			case BURN -> 0.2F;
			case BEATEN -> 0.25F;
			case CONCUSSION, DECAPITATION, TRAUMA -> -1.0F;
		};
	}

	/**
	 * Rolls this death from the body's own dice: how long each phase lasts, how hard and fast it kicks, when it gasps
	 * and when the heart stops. Times are ground ticks, counted from when the body starts to fall (it lands about
	 * {@link Corpse#FALL_TICKS} later).
	 */
	void plan(Corpse c) {
		RandomSource r = c.random;
		float frantic = c.self().frantic();
		int land = Math.round(Corpse.FALL_TICKS);
		int gasps;
		int gaspFrom;
		c.legSeed = r.nextFloat() * 100.0F;
		switch (c.kind) {
			case CONCUSSION -> {
				c.tonicEnd = land + 10 + r.nextInt(15);
				c.clonicStart = c.tonicEnd;
				c.clonicEnd = c.clonicStart + 28 + r.nextInt(21);
				c.kickPeak = 0.8F + 0.2F * r.nextFloat();
				c.kickRate = 0.75F + 0.2F * r.nextFloat();
				gasps = 2 + r.nextInt(3);
				gaspFrom = c.clonicEnd - 12 + r.nextInt(8);
				c.heartEnd = c.clonicEnd;
				c.voiceEnd = 0;
			}
			case EXSANGUINATION -> {
				c.tonicEnd = land + r.nextInt(5);
				// It lies quiet while it bleeds; the paddling comes as the brain runs out of blood.
				c.clonicStart = c.tonicEnd + 14 + r.nextInt(13);
				c.clonicEnd = c.clonicStart + 20 + r.nextInt(15);
				c.kickPeak = 0.5F + 0.25F * r.nextFloat();
				c.kickRate = 0.65F + 0.2F * r.nextFloat();
				gasps = 3 + r.nextInt(3);
				gaspFrom = c.tonicEnd + 6 + r.nextInt(6);
				c.heartEnd = c.clonicEnd + 5;
				c.voiceEnd = land + 10;
			}
			case SPINAL -> {
				c.hindLimp = 1.0F;
				c.tonicEnd = land + 4 + r.nextInt(7);
				c.clonicStart = c.tonicEnd;
				c.clonicEnd = c.clonicStart + 30 + r.nextInt(16);
				c.kickPeak = 0.3F + 0.15F * r.nextFloat();
				c.kickRate = 0.6F + 0.15F * r.nextFloat();
				gasps = 3 + r.nextInt(3);
				gaspFrom = c.tonicEnd + r.nextInt(8);
				c.heartEnd = c.clonicEnd;
				c.voiceEnd = land + 18;
			}
			case DECAPITATION -> {
				// No brain to hold the spinal cord back: the hardest kicking of all, and no breath.
				c.tonicEnd = land + 2 + r.nextInt(5);
				c.clonicStart = c.tonicEnd;
				c.clonicEnd = c.clonicStart + 40 + r.nextInt(21);
				c.kickPeak = 1.0F;
				c.kickRate = 0.9F + 0.2F * r.nextFloat();
				gasps = 0;
				gaspFrom = 0;
				c.heartEnd = c.clonicEnd - 10;
				c.voiceEnd = 0;
			}
			case CHEST -> {
				c.tonicEnd = land + 2 + r.nextInt(5);
				c.clonicStart = c.tonicEnd + 6 + r.nextInt(9);
				c.clonicEnd = c.clonicStart + 18 + r.nextInt(13);
				c.kickPeak = 0.4F + 0.2F * r.nextFloat();
				c.kickRate = 0.65F + 0.15F * r.nextFloat();
				gasps = 4 + r.nextInt(3);
				gaspFrom = c.tonicEnd + r.nextInt(4);
				c.heartEnd = c.clonicEnd;
				c.voiceEnd = land + 14;
			}
			case TRAUMA -> {
				// Torn open by a blast: limp at once, a few twitches.
				c.tonicEnd = land;
				c.clonicStart = land;
				c.clonicEnd = land + 10 + r.nextInt(9);
				c.kickPeak = 0.25F + 0.15F * r.nextFloat();
				c.kickRate = 0.7F + 0.2F * r.nextFloat();
				gasps = r.nextInt(2);
				gaspFrom = land + 4 + r.nextInt(6);
				c.heartEnd = land + 8;
				c.voiceEnd = 0;
			}
			case BURN -> {
				c.tonicEnd = land;
				c.clonicStart = land + 4 + r.nextInt(5);
				c.clonicEnd = c.clonicStart + 20 + r.nextInt(11);
				c.kickPeak = 0.3F + 0.15F * r.nextFloat();
				c.kickRate = 0.6F + 0.2F * r.nextFloat();
				gasps = 1 + r.nextInt(3);
				gaspFrom = c.clonicStart + r.nextInt(10);
				c.heartEnd = 0;
				c.voiceEnd = land + 8;
			}
			case BEATEN -> {
				c.tonicEnd = land + 2 + r.nextInt(7);
				c.clonicStart = c.tonicEnd;
				c.clonicEnd = c.clonicStart + 36 + r.nextInt(21);
				c.kickPeak = Math.min(1.0F, 0.85F + 0.15F * r.nextFloat() + 0.1F * frantic);
				c.kickRate = 0.8F + 0.25F * r.nextFloat();
				gasps = 2 + r.nextInt(3);
				gaspFrom = c.clonicEnd - 16 + r.nextInt(10);
				c.heartEnd = c.clonicEnd;
				c.voiceEnd = land + 20;
			}
			default -> throw new IllegalStateException();
		}

		// A frantic animal kicks harder; a stoic one lies quieter.
		c.kickPeak = Mth.clamp(c.kickPeak * (0.85F + 0.3F * frantic), 0.0F, 1.0F);
		if (horse(c)) {
			// A horse goes hardest of all: the most violent paddling of any animal, longer, and a conscious one screams.
			c.kickPeak = Math.min(1.0F, c.kickPeak * 1.3F + 0.1F);
			c.kickRate += 0.15F;
			c.clonicEnd += (c.clonicEnd - c.clonicStart) / 4;
			if (c.voiceEnd > 0) {
				c.voiceEnd += 10;
			}

			if (gasps > 0) {
				gasps++;
			}
		}

		c.gasps = new int[gasps];
		int at = gaspFrom;
		float gap = 9.0F + r.nextInt(6);
		for (int i = 0; i < gasps; i++) {
			c.gasps[i] = at;
			at += Math.round(gap);
			gap *= 1.3F + 0.3F * r.nextFloat();
		}

		int last = gasps > 0 ? c.gasps[gasps - 1] : 0;
		c.fasciculationEnd = Math.max(c.clonicEnd, last) + 12 + r.nextInt(13);
		int end = c.fasciculationEnd;
		if (end > MAX_THROES) {
			// Squeezed into five seconds, keeping the proportions.
			float k = MAX_THROES / (float) end;
			c.tonicEnd = Math.round(c.tonicEnd * k);
			c.clonicStart = Math.round(c.clonicStart * k);
			c.clonicEnd = Math.round(c.clonicEnd * k);
			c.heartEnd = Math.round(c.heartEnd * k);
			c.voiceEnd = Math.round(c.voiceEnd * k);
			for (int i = 0; i < gasps; i++) {
				c.gasps[i] = Math.round(c.gasps[i] * k);
			}

			c.fasciculationEnd = MAX_THROES;
		}

		c.fasciculationEnd = Math.max(c.fasciculationEnd, MIN_THROES);
		c.throeTicks = c.fasciculationEnd;
		c.nextBeat = 0;
		c.burstUntil = c.collapseAt + c.clonicStart;
	}

	/** The moment of death: an opened throat or neck starts to jet with the heartbeat. */
	void begin(Corpse c, int color, boolean gore) {
		if (!gore || !GoreConfig.get().arterialSpurts || (c.kind != Corpse.Kind.EXSANGUINATION && c.kind != Corpse.Kind.DECAPITATION)) {
			return;
		}

		float yawRad = c.body.yaw * Mth.DEG_TO_RAD;
		float side = c.random.nextBoolean() ? 1.0F : -1.0F;
		Vec3 out = new Vec3(Mth.cos(yawRad) * side, 0.4, Mth.sin(yawRad) * side);
		Supplier<Vec3> wound = c.kind == Corpse.Kind.DECAPITATION ? () -> Suffering.neck(c) : () -> bleedPoint(c);
		int life = c.collapseAt + c.heartEnd;
		BloodWorld blood = GoreSystems.blood();
		blood.spurts().start(wound, out, color, life, (c.kind == Corpse.Kind.DECAPITATION ? 1.0F : 0.8F) * (horse(c) ? 1.25F : 1.0F));
		blood.spurts().ooze(wound, c::position, c.entity.getBbWidth() * 0.5, color, life + 20, 0.7F);
	}

	/** One tick of dying, from the moment of death until the body is still. */
	void tick(ClientLevel level, Corpse c, Posture p, int color, boolean gore) {
		RandomSource r = c.random;
		Ragdoll body = c.body;
		int ground = c.groundTicks();
		p.legSeed = c.legSeed;
		p.hindLimp = c.hindLimp;
		if (ground < 0) {
			// The legs give way, all at once, and it goes over.
			float limp = c.kind == Corpse.Kind.DECAPITATION || c.kind == Corpse.Kind.TRAUMA ? 1.0F : ramp(c.age, 0, c.collapseAt);
			fold(c, p, limp, Math.max(limp, ramp(c.age, 0, c.collapseAt - 1)));
			p.tremble = 0.2F;
			return;
		}

		// The folded legs straighten out as it rolls onto its side; a burnt body draws its limbs in with the heat.
		float folded = Math.max(0.0F, Mth.cos(body.roll));
		float drawn = c.kind == Corpse.Kind.BURN ? 0.55F * ramp(ground, 10, 50) : 0.0F;
		fold(c, p, Math.max(folded, drawn), Math.max(folded, drawn));

		if (c.lifeless()) {
			return;
		}

		// Tonic: rigid, legs locked out, head arched back, a fine quiver. A broken back keeps its forelegs rigid.
		float tonic = c.kind == Corpse.Kind.TRAUMA || c.kind == Corpse.Kind.BURN ? 0.0F
				: ramp(ground, 0, 5) * (1.0F - ramp(ground, c.tonicEnd, c.tonicEnd + 6));
		float strength = switch (c.kind) {
			case CONCUSSION -> 1.0F;
			case SPINAL -> 0.9F;
			case DECAPITATION -> 0.6F;
			default -> 0.5F;
		};
		p.stiff = tonic * strength;
		if (c.kind == Corpse.Kind.SPINAL) {
			p.stiff = Math.max(p.stiff, 0.6F * (1.0F - ramp(ground, c.clonicEnd - 6, c.clonicEnd + 6)));
		}

		if (ground < c.tonicEnd) {
			p.tremble = 0.2F;
		}

		// Clonic: paddling in bursts with pauses, each leg on its own, fading and slowing.
		float target = 0.0F;
		if (ground >= c.clonicStart && ground < c.clonicEnd) {
			float x = (ground - c.clonicStart) / (float) Math.max(1, c.clonicEnd - c.clonicStart);
			float envelope = (float) Math.pow(1.0F - x, 1.4) * ramp(ground, c.clonicStart, c.clonicStart + 3);
			if (c.age >= c.burstUntil) {
				c.burst = !c.burst;
				c.burstUntil = c.age + (c.burst ? 5 + r.nextInt(8) + Math.round(6 * (1.0F - x)) : 2 + r.nextInt(4) + Math.round(10 * x));
				if (c.burst) {
					// Each burst starts with a jerk of the whole body.
					body.spasm(r, 0.25F * envelope * c.kickPeak);
				}
			}

			target = c.kickPeak * envelope * (c.burst ? 0.75F + 0.25F * r.nextFloat() : 0.12F);
			p.kickRate = c.kickRate * (1.0F - 0.4F * x);
			p.thrash = c.kind == Corpse.Kind.BEATEN || c.kind == Corpse.Kind.DECAPITATION ? 0.15F * envelope : 0.0F;
		} else if (c.kind == Corpse.Kind.EXSANGUINATION && ground >= c.tonicEnd && ground < c.clonicStart) {
			// Lying there bleeding, weakening: a shiver and a feeble stir of the legs.
			p.tremble = 0.25F;
			target = 0.06F;
		}

		c.kickNow += (target - c.kickNow) * 0.4F;
		p.kick = c.kickNow;

		// Fasciculation: fine flickers of muscle after the paddling stops, the odd twitch, then nothing.
		if (ground >= c.clonicEnd) {
			float left = 1.0F - ramp(ground, c.clonicEnd, c.fasciculationEnd);
			p.tremble = Math.max(p.tremble, 0.15F * left);
			if (r.nextFloat() < 0.04F * left) {
				body.spasm(r, 0.08F);
			}
		}

		gasp(level, c, color, gore, ground);
		p.gaspAt = c.gaspAt;
		p.gasp = c.gaspStrength;
		heart(level, c, color, gore, ground);

		if (ground < c.voiceEnd && r.nextFloat() < 0.05F) {
			// Conscious for a moment yet: a weak, wet cry.
			screams.gurgle(Suffering.follow(c.entity), c.voice, 1.0F - ground / (float) c.voiceEnd);
		}

		if (!c.lastBreathTaken && c.gasps.length > 0 && ground >= c.throeTicks - 4) {
			c.lastBreathTaken = true;
			screams.lastBreath(Suffering.follow(c.entity), c.voice);
		}
	}

	/** An agonal gasp when one is due: the chest heaves, the head lifts. Lungs full of blood bring some up. */
	private void gasp(ClientLevel level, Corpse c, int color, boolean gore, int ground) {
		if (c.nextGasp >= c.gasps.length || ground < c.gasps[c.nextGasp]) {
			return;
		}

		float strength = Math.max(0.4F, 1.0F - 0.15F * c.nextGasp);
		c.nextGasp++;
		c.gaspAt = c.age;
		c.gaspStrength = strength;
		screams.gasp(Suffering.follow(c.entity), c.voice, strength * 0.7F);
		if (c.kind == Corpse.Kind.CHEST && gore) {
			Vec3 mouth = Suffering.neck(c);
			float yawRad = c.body.yaw * Mth.DEG_TO_RAD;
			Vec3 out = new Vec3(-Mth.sin(yawRad), -0.3, Mth.cos(yawRad)).normalize();
			BloodEmitter.cut(GoreSystems.blood(), level, mouth, out, color, 0.015F + 0.02F * strength, Math.round(3 + 5 * strength));
		}
	}

	/** A heartbeat: fast and strong at first, weaker and slower until it stops. Each one pushes blood out and down. */
	private void heart(ClientLevel level, Corpse c, int color, boolean gore, int ground) {
		if (!gore || ground >= c.heartEnd || c.age < c.nextBeat) {
			return;
		}

		float progress = ground / (float) Math.max(1, c.heartEnd);
		c.nextBeat = c.age + Math.round(7.0F + 16.0F * (float) Math.pow(progress, 1.2));
		float strength = 1.0F - 0.85F * progress;
		float flow = switch (c.kind) {
			case EXSANGUINATION, DECAPITATION -> 1.6F;
			case TRAUMA, BEATEN -> 1.0F;
			case CHEST -> 0.5F;
			default -> 0.6F;
		} * (horse(c) ? 1.5F : 1.0F);
		Vec3 from = bleedPoint(c);
		BloodWorld blood = GoreSystems.blood();
		BloodEmitter.burst(blood, level, from, color, (0.03F * strength + 0.008F) * flow, Math.round((3 + 5 * strength) * flow));
		// What runs down pools under the body, and the pool grows with every beat.
		BloodEmitter.drip(blood, new Vec3(from.x, c.body.y + 0.1, from.z), color, (0.05F * strength + 0.01F) * flow);
	}

	/** Where the blood comes from: the deepest wound, the neck of a beheaded body, or the mouth and nose. */
	private static Vec3 bleedPoint(Corpse c) {
		if (c.kind != Corpse.Kind.DECAPITATION && c.kind != Corpse.Kind.CHEST) {
			Vec3 wound = GoreSystems.dismemberment().wounds().deepest(c.entity.getId(), c.position(), c.yaw());
			if (wound != null) {
				// Lying on its side, the wound is near the ground.
				return new Vec3(wound.x, Math.min(wound.y, c.body.y + c.entity.getBbHeight() * 0.4), wound.z);
			}
		}

		return Suffering.neck(c);
	}

	/** Struck while it dies: a blow breaks the rigid phase and sets it kicking, and a conscious one cries out. */
	void struck(Corpse c) {
		if (c.lifeless()) {
			return;
		}

		int ground = c.groundTicks();
		if (ground >= 0 && ground < c.tonicEnd) {
			c.tonicEnd = ground;
			c.clonicStart = Math.min(c.clonicStart, ground);
		}

		if (ground >= c.clonicStart && ground < c.clonicEnd) {
			c.burst = true;
			c.burstUntil = c.age + 6 + c.random.nextInt(6);
		}

		c.body.spasm(c.random, 0.5F);
		if (ground < c.voiceEnd) {
			screams.gurgle(Suffering.follow(c.entity), c.voice, 1.0F);
		}
	}

	/**
	 * Folds the legs under the body: {@code front} and {@code hind} 0..1. The body sinks and pitches with them, so a
	 * kneeling cow goes nose down. Two-legged bodies sit straight down.
	 */
	static void fold(Corpse c, Posture p, float front, float hind) {
		ModelParts.Legs legs = c.legs;
		if (!legs.four()) {
			hind = Math.max(front, hind);
			front = 0.0F;
		}

		p.foldFront = front;
		p.foldHind = hind;
		float frontDown = legs.fold() * front;
		float rearDown = legs.fold() * hind;
		p.drop += (frontDown + rearDown) * 0.5F;
		if (legs.span() > 0.0F) {
			p.tiltX += (float) Math.atan2(frontDown - rearDown, legs.span()) * Mth.RAD_TO_DEG;
		}
	}

	/** 0 before {@code from}, 1 after {@code to}, linear between. */
	private static float ramp(float x, float from, float to) {
		return to <= from ? (x >= to ? 1.0F : 0.0F) : Mth.clamp((x - from) / (to - from), 0.0F, 1.0F);
	}
}
