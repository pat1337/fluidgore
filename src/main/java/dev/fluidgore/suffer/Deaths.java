package dev.fluidgore.suffer;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.wound.Weapon;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * How animals really die. Nine deaths, each what a stockman or a hunter would recognise, chosen by where and with
 * what the killing blow landed and by who the animal is, with enough chance in it that a herd killed the same way
 * still dies a dozen different ways. The tenth, the body that bursts when it finally goes, is rare
 * ({@link #popChance}).
 *
 * <p>This class plays out the part of each death before the body goes over onto its side: standing, staggering,
 * kneeling, lying on its chest, dragging itself, circling. The throes on the ground are shared and live in
 * {@link Suffering}; {@link #ground} only adds what is particular to a death. See {@link Corpse} for the list.
 */
public final class Deaths {
	/** Where the killing blow landed on the body. */
	public enum Zone {
		HEAD, BODY, LEG, UNKNOWN
	}

	/** The pose being built up for one body this tick; each death sets only what it needs. */
	static final class Posture {
		float tremble, thrash, kick, kickRate, tiltX, tiltZ, drop, stagger, headDip, lookYaw, foldFront, foldHind, stiff, headTilt;

		Posture reset() {
			tremble = thrash = kick = tiltX = tiltZ = drop = stagger = headDip = lookYaw = foldFront = foldHind = stiff = headTilt = 0.0F;
			kickRate = 0.6F;
			return this;
		}

		SufferPose build() {
			return new SufferPose(tremble, thrash, kick, kickRate, tiltX, tiltZ, drop, stagger, headDip, lookYaw, foldFront, foldHind, stiff,
					headTilt);
		}
	}

	/** Length of one rise attempt of a downer, in ticks. */
	private static final int ATTEMPT_TICKS = 26;

	private final ScreamManager screams;
	private final RandomSource random;

	Deaths(ScreamManager screams, RandomSource random) {
		this.screams = screams;
		this.random = random;
	}

	/** Chance that a death ends with the body bursting apart, 0..1. */
	static float popChance() {
		GoreConfig config = GoreConfig.get();
		return config.deathPop ? config.deathPopPercent / 100.0F : 0.0F;
	}

	/**
	 * Picks one of the nine deaths. Every one has a small base weight so any of them can happen; the blow and the
	 * animal make some far more likely.
	 *
	 * @param weapon    what struck the killing blow, null for none (a fall, fire)
	 * @param fall      it died of a fall
	 * @param four      it stands on four legs (a broken back or a downer needs a hind end to fail)
	 */
	Corpse.Kind choose(Personality self, boolean personal, @Nullable Weapon weapon, Zone zone, boolean fall, int hits, int beatenHits,
			float blowShare, boolean dying, int ordeal, boolean four) {
		if (beatenHits >= 2) {
			// Beaten to death: the worst of them, and no chance about it.
			return Corpse.Kind.BRUTAL;
		}

		float frantic = personal ? self.frantic() : 0.5F;
		boolean heavy = weapon == Weapon.BLUNT || weapon == Weapon.CHOP || weapon == Weapon.PICK;
		boolean edge = weapon == Weapon.BLADE || weapon == Weapon.CHOP || weapon == Weapon.FLINT || weapon == Weapon.BITE;
		boolean oneBlow = hits <= 1 && blowShare >= 0.6F;
		Corpse.Kind[] kinds = {Corpse.Kind.STUNNED, Corpse.Kind.BLEED_OUT, Corpse.Kind.BOLT, Corpse.Kind.DOWNER, Corpse.Kind.BROKEN_BACK,
				Corpse.Kind.CHOKING, Corpse.Kind.CURL, Corpse.Kind.BRUTAL, Corpse.Kind.CIRCLING};
		float[] weight = {
				1.0F + (oneBlow ? 5.0F : 0.0F) + (zone == Zone.HEAD && heavy ? 4.0F : 0.0F),
				1.0F + (edge ? 4.0F : 0.0F) + (zone == Zone.LEG ? 1.0F : 0.0F),
				1.0F + (weapon == Weapon.PIERCE ? 2.0F : 0.0F) + 6.0F * Math.max(0.0F, frantic - 0.5F),
				four ? 1.0F + 6.0F * Math.max(0.0F, 0.5F - frantic) + (dying ? 2.0F : 0.0F) + (ordeal > 60 ? 1.0F : 0.0F) : 0.0F,
				four ? 0.8F + (fall ? 8.0F : 0.0F) + (zone == Zone.BODY && heavy ? 2.0F : 0.0F) : 0.0F,
				1.0F + (weapon == Weapon.PIERCE ? 4.0F : 0.0F) + (zone == Zone.BODY && edge ? 2.0F : 0.0F),
				0.8F + (hits >= 3 ? 2.0F : 0.0F) + (zone == Zone.BODY ? 1.5F : 0.0F),
				0.6F + (hits >= 5 ? 4.0F : 0.0F) + (ordeal > 100 ? 3.0F : 0.0F),
				0.8F + (zone == Zone.HEAD && !edge ? 4.0F : 0.0F)
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

		return Corpse.Kind.BLEED_OUT;
	}

	/** Ticks a scripted death spends before it goes over onto its side. */
	int run(Corpse.Kind kind, float intensity) {
		return switch (kind) {
			case STUNNED -> 5;
			case BLEED_OUT -> Math.round((100 + random.nextInt(60)) * intensity);
			case DOWNER -> Math.round((170 + random.nextInt(90)) * intensity);
			case BROKEN_BACK -> Math.round((130 + random.nextInt(60)) * intensity);
			case CHOKING -> Math.round((120 + random.nextInt(50)) * intensity);
			case CIRCLING -> Math.round((70 + random.nextInt(40)) * intensity);
			default -> 0;
		};
	}

	/** Ticks of throes on the ground for a scripted death, before intensity and endurance. */
	int throes(Corpse.Kind kind) {
		return switch (kind) {
			case STUNNED -> 110 + random.nextInt(40);
			case BLEED_OUT -> 50 + random.nextInt(30);
			case DOWNER -> 50 + random.nextInt(20);
			case BROKEN_BACK -> 60 + random.nextInt(30);
			case CHOKING -> 60 + random.nextInt(30);
			case CIRCLING -> 130 + random.nextInt(40);
			default -> 60;
		};
	}

	/** Length of the death cry, 0..1, or negative for none. */
	static float cry(Corpse.Kind kind) {
		return switch (kind) {
			case STUNNED -> -1.0F;
			case BLEED_OUT -> 0.3F;
			case DOWNER -> 0.2F;
			case BROKEN_BACK -> 0.6F;
			case CHOKING -> 0.05F;
			case CIRCLING -> 0.15F;
			default -> 0.4F;
		};
	}

	/** The moment of death. */
	void begin(ClientLevel level, Corpse c, int color, boolean gore) {
		c.circleDir = random.nextBoolean() ? 1.0F : -1.0F;
		switch (c.kind) {
			case STUNNED -> c.phaseAt = Math.round((35 + random.nextInt(25)) * Suffering.intensity());
			case BLEED_OUT -> {
				if (gore && GoreConfig.get().arterialSpurts) {
					// The opened artery jets with each beat, and blood runs down the body to the feet.
					float yawRad = c.body.yaw * Mth.DEG_TO_RAD;
					Vec3 side = new Vec3(Mth.cos(yawRad) * c.circleDir, 0.4, Mth.sin(yawRad) * c.circleDir);
					BloodWorld blood = GoreSystems.blood();
					blood.spurts().start(() -> Suffering.neck(c), side, color, c.runTicks, 0.9F);
					blood.spurts().ooze(() -> Suffering.neck(c), () -> c.position(), c.entity.getBbWidth() * 0.5, color, c.runTicks + c.throeTicks, 0.8F);
				}

				c.nextBeat = 0;
			}
			case DOWNER -> c.attemptAt = -100;
			case CHOKING -> c.nextCough = 4;
			default -> {
			}
		}
	}

	/**
	 * Wanted speed along the heading while the body is still up; some deaths also steer. Zero for those that do not
	 * go anywhere.
	 */
	double drive(Corpse c) {
		Ragdoll body = c.body;
		float progress = c.prefall();
		return switch (c.kind) {
			case CIRCLING -> {
				// Tight circles one way, as a brain-hurt animal does, wandering a little.
				float wanted = c.circleDir * (7.0F + 3.0F * SufferPose.noise(c.age * 0.1F, c.seed));
				body.yawV += (wanted - body.yawV) * 0.2F;
				body.yaw += body.yawV;
				yield 0.085 * (1.0 - 0.5 * progress);
			}
			case BROKEN_BACK -> {
				// Hauling itself on its front legs, slower as it tires; a little swing of the forequarters with each pull.
				body.yaw += SufferPose.noise(c.age * 0.07F, c.seed) * 1.5F;
				yield progress < 0.85F ? 0.05 * (1.0 - 0.6 * progress) * (0.6 + 0.4 * Math.max(0.0F, Mth.sin(c.age * 0.5F))) : 0.0;
			}
			case BLEED_OUT -> progress < 0.5F ? 0.01 * Math.max(0.0F, SufferPose.noise(c.age * 0.05F, c.seed)) : 0.0;
			default -> 0.0;
		};
	}

	/** One tick of a scripted death while the body is still up (on its feet, knees or chest). */
	void upright(ClientLevel level, Corpse c, Posture p, int color, boolean gore) {
		float progress = c.prefall();
		int age = c.age;
		BloodWorld blood = GoreSystems.blood();
		switch (c.kind) {
			case STUNNED -> {
				// Dropped like a sack: every leg goes at once.
				fold(c, p, ramp(age, 0, 4), ramp(age, 1, 5));
				p.tremble = 0.2F;
			}
			case BLEED_OUT -> {
				bleedOut(level, c, p, progress, color, gore, blood);
				// On its feet it sways and its head sinks; then the front knees, the hind end, and it lies on its chest.
				float front = ramp(progress, 0.55F, 0.68F);
				float hind = ramp(progress, 0.68F, 0.8F);
				fold(c, p, front, hind);
				p.stagger = 2.0F + 8.0F * Math.min(progress / 0.55F, 1.0F) * (1.0F - front);
				p.tremble = 0.3F + 0.4F * progress;
				p.headDip = 0.5F * progress + 0.5F * ramp(progress, 0.8F, 1.0F);
				if (age % 22 == 11) {
					screams.breath(Suffering.follow(c.entity), c.voice, 1.0F - progress);
				}
			}
			case DOWNER -> downer(c, p, progress);
			case BROKEN_BACK -> {
				// The hind end drops at once and stays down; it hauls itself along until the front gives out too.
				float front = ramp(progress, 0.85F, 1.0F);
				fold(c, p, front, ramp(age, 0, 6));
				p.tremble = 0.5F;
				p.thrash = 0.3F * (1.0F - front);
				p.headDip = -0.2F + 0.8F * front;
				if (age % 30 == 7 && front < 0.5F) {
					screams.bellow(Suffering.follow(c.entity), c.voice, 1.0F);
				}

				if (gore && age % 3 == 0 && c.body.onGround) {
					// Smeared behind it as it drags.
					BloodEmitter.drip(blood, behind(c), color, 0.03F);
				}
			}
			case CHOKING -> {
				coughing(level, c, p, progress, color, gore, blood, 1.0F);
				float front = ramp(progress, 0.6F, 0.7F);
				fold(c, p, front, ramp(progress, 0.7F, 0.8F));
				// Neck stretched out low, mouth open, fighting for air.
				p.headDip += 0.45F + 0.35F * front;
				p.tiltX += 4.0F * (1.0F - front);
				p.stagger = 2.0F * (1.0F - front);
				p.tremble = 0.4F;
			}
			case CIRCLING -> {
				p.headTilt = 0.5F * c.circleDir;
				p.headDip = 0.25F;
				p.stagger = 5.0F + 6.0F * progress;
				p.tremble = 0.3F;
				if (age % 40 == 13) {
					screams.bellow(Suffering.follow(c.entity), c.voice, 0.3F);
				}
			}
			default -> {
			}
		}
	}

	/**
	 * Adds what is particular to a scripted death once the body is on the ground; {@code life} runs from 1 at the
	 * start of the throes to 0 at their end. Returns false if the shared throes should stay still (no spasms).
	 */
	boolean ground(ClientLevel level, Corpse c, Posture p, int ground, float life, int color, boolean gore) {
		Ragdoll body = c.body;
		if (c.kind != Corpse.Kind.CIRCLING) {
			// It went over from its chest: the folded legs straighten out as it rolls onto its side.
			float folded = Math.max(0.0F, Mth.cos(body.roll));
			fold(c, p, folded, folded);
		}

		switch (c.kind) {
			case STUNNED -> {
				if (ground < c.phaseAt) {
					// The rigid phase: legs locked straight out, head arched back, a fine quiver and nothing else.
					p.stiff = 1.0F;
					p.tremble = 0.25F;
					return false;
				}

				// Then the legs paddle on their own, steady at first, slowing and stopping.
				float left = life <= 0.0F ? 0.0F : Math.min(1.0F, (c.throeTicks - ground) / (float) Math.max(1, c.throeTicks - c.phaseAt));
				p.stiff = Math.max(0.0F, 1.0F - (ground - c.phaseAt) / 6.0F);
				p.kick = Math.max(p.kick, 0.9F * left * left);
				p.kickRate = 0.5F + 0.4F * left;
				if (life > 0.0F && random.nextFloat() < 0.012F) {
					screams.gasp(Suffering.follow(c.entity), c.voice, left);
				}

				return false;
			}
			case BLEED_OUT -> {
				if (gore && life > 0.0F && ground % Math.round(14.0F + 20.0F * (1.0F - life)) == 0) {
					BloodEmitter.drip(GoreSystems.blood(), Suffering.neck(c), color, 0.03F * life + 0.01F);
				}
			}
			case CHOKING -> {
				if (life > 0.5F) {
					coughing(level, c, p, 1.0F, color, gore, GoreSystems.blood(), (life - 0.5F) * 2.0F);
				}
			}
			case CIRCLING -> {
				if (ground > 15 && body.rollLimit < Mth.PI) {
					// It rolls on over onto its back.
					body.rollLimit = Mth.PI;
					body.rollV += Math.signum(body.roll) * 0.06F;
				}

				// Legs up, kicking at the sky, then locking stiff there.
				float kicking = ramp(ground, 15, 25) * (1.0F - ramp(ground, c.throeTicks * 0.5F, c.throeTicks * 0.75F));
				p.kick = Math.max(p.kick, kicking);
				p.kickRate = 0.8F;
				p.stiff = ramp(ground, c.throeTicks * 0.55F, c.throeTicks * 0.85F);
			}
			default -> {
			}
		}

		return true;
	}

	/** The voice still works on the ground. */
	static boolean voiced(Corpse c) {
		return c.kind != Corpse.Kind.STUNNED;
	}

	/** Struck while it dies: each death answers a blow in its own way, on top of the knock every body takes. */
	void struck(ClientLevel level, Corpse c, Vec3 look, int color) {
		boolean gore = GoreConfig.get().goreLevel.enabled();
		BloodWorld blood = GoreSystems.blood();
		switch (c.kind) {
			case STUNNED -> {
				// A blow while it lies rigid sets the legs paddling at once.
				int ground = c.age - c.collapseAt;
				if (ground >= 0 && ground < c.phaseAt) {
					c.phaseAt = ground;
				}

				c.body.spasm(random, 0.8F);
			}
			case BLEED_OUT -> {
				if (gore) {
					BloodEmitter.burst(blood, level, Suffering.neck(c), color, 0.08F, 10);
				}

				if (c.upright()) {
					screams.pain(Suffering.follow(c.entity), c.voice, 0.5F, 1.0F);
				}
			}
			case DOWNER -> {
				// A desperate heave to get up, away from the blows.
				if (c.upright() && c.age - c.attemptAt > ATTEMPT_TICKS) {
					c.attemptAt = c.age;
					screams.strain(Suffering.follow(c.entity), c.voice, 1.0F);
				}
			}
			case BROKEN_BACK -> {
				if (c.upright()) {
					// It hauls itself away from whoever is hitting it.
					float away = (float) (Mth.atan2(-look.x, look.z) * Mth.RAD_TO_DEG);
					c.body.yaw += Mth.wrapDegrees(away - c.body.yaw) * 0.5F;
					c.body.vx += look.x * 0.06;
					c.body.vz += look.z * 0.06;
					screams.pain(Suffering.follow(c.entity), c.voice, 1.0F, 1.0F);
				}
			}
			case CHOKING -> c.nextCough = c.age;
			case CIRCLING -> {
				if (c.upright()) {
					c.circleDir = -c.circleDir;
					c.body.yawV = c.circleDir * 20.0F;
				}
			}
			default -> {
				if (!c.lifeless() && c.kind != Corpse.Kind.HEADLESS_RUN && c.kind != Corpse.Kind.QUICK) {
					screams.gurgle(Suffering.follow(c.entity), c.voice, 1.0F);
				}
			}
		}
	}

	/** The heart pumping the body empty: strong, fast gushes at first, then weaker and further apart. */
	private void bleedOut(ClientLevel level, Corpse c, Posture p, float progress, int color, boolean gore, BloodWorld blood) {
		if (c.age < c.nextBeat) {
			return;
		}

		c.nextBeat = c.age + Math.round(8.0F + 22.0F * progress);
		if (gore) {
			float strength = 1.0F - 0.8F * progress;
			Vec3 neck = Suffering.neck(c);
			BloodEmitter.burst(blood, level, neck, color, 0.05F * strength + 0.01F, Math.round(4 + 6 * strength));
			// What runs straight down: the pool under it grows with every beat.
			BloodEmitter.drip(blood, new Vec3(neck.x, c.body.y + 0.15, neck.z), color, 0.08F * strength + 0.02F);
		}

		p.tremble += 0.2F;
	}

	/** Down on its chest, trying again and again to get its hind end up, until it gives up and rolls over. */
	private void downer(Corpse c, Posture p, float progress) {
		int age = c.age;
		float front = ramp(age, 0, 15);
		float hind = ramp(age, 12, 30);
		boolean down = age > 40;
		if (down && age - c.attemptAt > ATTEMPT_TICKS && age < c.runTicks - 40 && random.nextFloat() < 0.025F) {
			c.attemptAt = age;
			c.attempts++;
			screams.strain(Suffering.follow(c.entity), c.voice, 1.0F / c.attempts);
		}

		float heave = 0.0F;
		int into = age - c.attemptAt;
		if (into >= 0 && into < ATTEMPT_TICKS) {
			heave = Mth.sin(Mth.PI * into / ATTEMPT_TICKS) * Math.max(0.35F, 1.0F - 0.15F * c.attempts);
			if (into == ATTEMPT_TICKS - 4) {
				// Back down, hard.
				c.body.spasm(random, 0.3F);
				Vec3 at = c.position();
				if (random.nextInt(4) == 0) {
					c.entity.level().playLocalSound(at.x, at.y, at.z, SoundEvents.GENERIC_SMALL_FALL, SoundSource.NEUTRAL, 0.6F, 0.7F, false);
				}
			}
		}

		fold(c, p, front, hind * (1.0F - 0.8F * heave));
		// Between attempts the head lies turned back against its flank.
		float rest = down ? ramp(age, 40, 60) * (1.0F - heave) : 0.0F;
		p.lookYaw = 1.3F * c.circleDir * rest;
		p.headDip = 0.35F * rest - 0.2F * heave;
		p.tremble = 0.25F + 0.6F * heave;
		p.kick = 0.25F * heave;
		if (down && age % 30 == 0 && heave == 0.0F) {
			screams.breath(Suffering.follow(c.entity), c.voice, 1.0F - progress);
		}
	}

	/** Coughing up blood: a heave, a jerk of the head and a spray of froth out of the mouth, then gasping. */
	private void coughing(ClientLevel level, Corpse c, Posture p, float progress, int color, boolean gore, BloodWorld blood, float strength) {
		int since = c.age - c.phaseAt;
		if (c.age >= c.nextCough) {
			c.phaseAt = c.age;
			since = 0;
			c.nextCough = c.age + 12 + random.nextInt(20) + Math.round(progress * 15.0F);
			screams.cough(Suffering.follow(c.entity), c.voice, strength);
			if (gore) {
				Vec3 mouth = Suffering.neck(c);
				float yawRad = c.body.yaw * Mth.DEG_TO_RAD;
				Vec3 out = new Vec3(-Mth.sin(yawRad), -0.3, Mth.cos(yawRad));
				BloodEmitter.cut(blood, level, mouth, out.normalize(), color, 0.02F + 0.03F * strength, Math.round(5 + 7 * strength));
			}
		} else if (since == 8) {
			screams.gasp(Suffering.follow(c.entity), c.voice, strength);
		}

		p.headDip -= 0.35F * (float) Math.exp(-since / 3.0);
		p.thrash = Math.max(p.thrash, 0.3F * (float) Math.exp(-since / 4.0));
	}

	/**
	 * Folds the legs under the body: {@code front} and {@code hind} 0..1. The body sinks and pitches with them, so a
	 * kneeling cow goes nose down and a broken-backed one sits like a dog. Two-legged bodies sit straight down.
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

	/** Under the hind end of a body. */
	private static Vec3 behind(Corpse c) {
		Ragdoll b = c.body;
		float yawRad = b.yaw * Mth.DEG_TO_RAD;
		double back = c.entity.getBbWidth() * 0.5;
		return new Vec3(b.x + Mth.sin(yawRad) * back, b.y + 0.1, b.z - Mth.cos(yawRad) * back);
	}

	/** 0 before {@code from}, 1 after {@code to}, linear between. */
	private static float ramp(float x, float from, float to) {
		return Mth.clamp((x - from) / (to - from), 0.0F, 1.0F);
	}
}
