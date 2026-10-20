package dev.fluidgore.trigger;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.net.Evidence;
import dev.fluidgore.net.GoreSync;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodSupply;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.demo.DemoPlayer;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.player.PlayerInjuries;
import dev.fluidgore.suffer.Deaths;
import dev.fluidgore.suffer.Suffering;
import dev.fluidgore.wound.HitFeedback;
import dev.fluidgore.wound.Weapon;
import dev.fluidgore.wound.Wounds;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Detects damage purely from what a vanilla server already sends to every client:
 * <ul>
 * <li>the damage event packet (who hit whom, see {@code LivingEntityMixin}), and</li>
 * <li>the synced health value of every living entity.</li>
 * </ul>
 * The health drop gives the amount; the damage event, if one arrived in the last few ticks, gives the attacker.
 * This works on vanilla servers with no server-side mod.
 */
public final class DamageTracker {
	/** Ticks to wait for a health update after a damage event before treating it as a light hit. */
	private static final int PENDING_TIMEOUT = 4;
	private static final double EFFECT_RANGE_SQ = 96.0 * 96.0;
	private static final RandomSource RANDOM = Fate.stream("tracker");

	/**
	 * @param explosion the hit was an explosion
	 * @param sourceAt  where the damage came from, if the packet says (often not for TNT: the TNT entity is gone)
	 */
	private record PendingHit(int attackerId, boolean melee, boolean explosion, boolean fire, boolean fall, @Nullable Vec3 sourceAt, int tick) {
	}

	/**
	 * An explosion the server told us about: where and how big (TNT is 4, a creeper 3, a charged one 6), and the
	 * pressure vessel it burst, if it was one of a vessel's TNT.
	 */
	private record Blast(Vec3 center, float radius, int tick, PressureVessel.@Nullable Vessel vessel) {
	}

	/** Blast radius assumed when no explosion packet matches (TNT). */
	private static final float DEFAULT_BLAST_RADIUS = 4.0F;
	/** Blast impact (0..1, vanilla's measure) at which a body is shredded outright, and at which it is torn open. */
	private static final float SHRED_IMPACT = 0.72F;
	public static final float TEAR_IMPACT = 0.45F;
	/** With brutal PvP on: how much more one player's blade bleeds another, and how much deeper it cuts. */
	private static final float PVP_BLEED = 1.6F;
	private static final float PVP_DEPTH = 1.5F;

	private final Int2FloatOpenHashMap lastHealth = new Int2FloatOpenHashMap();
	private final Int2ObjectOpenHashMap<PendingHit> pending = new Int2ObjectOpenHashMap<>();
	private final List<Blast> blasts = new ArrayList<>();
	private final PressureVessel vessels = new PressureVessel();
	private int tick;

	public DamageTracker() {
		lastHealth.defaultReturnValue(Float.NaN);
	}

	/** Called on the client thread when a damage event packet arrives for {@code victim}. */
	public void onDamageEvent(LivingEntity victim, DamageSource source) {
		Entity attacker = source.getEntity();
		boolean melee = attacker != null && source.getDirectEntity() == attacker;
		pending.put(victim.getId(), new PendingHit(attacker == null ? -1 : attacker.getId(), melee, source.is(DamageTypeTags.IS_EXPLOSION), source.is(DamageTypeTags.IS_FIRE),
				source.is(DamageTypeTags.IS_FALL),
				source.getSourcePosition(), tick));
	}

	/** Called on the client thread when an explosion packet arrives. */
	public void onExplosion(Vec3 center, float radius) {
		boolean[] burst = {false};
		PressureVessel.Vessel vessel = vessels.explosion(center, burst);
		// The rest of a vessel's TNT is the same burst: every body is graded from the vessel's centre.
		blasts.add(new Blast(vessel != null ? vessel.centre : center, radius, tick, vessel));
		ClientLevel level = Minecraft.getInstance().level;
		if (burst[0] && level != null && GoreConfig.get().goreLevel.enabled()) {
			PressureVessel.burst(level, GoreSystems.blood(), GoreSystems.dismemberment(), vessel);
		}
	}

	public PressureVessel vessels() {
		return vessels;
	}

	public void tick(ClientLevel level, BloodWorld blood, Dismemberment dismemberment, Suffering suffering) {
		tick++;
		vessels.tick(level);
		Entity camera = Minecraft.getInstance().getCameraEntity();
		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living)) {
				continue;
			}

			int id = living.getId();
			float health = living.getHealth();
			float previous = lastHealth.put(id, health);
			if (Float.isNaN(previous)) {
				continue;
			}

			if (previous <= 0.0F && health > 0.0F) {
				// Respawned (or revived by a totem): the body is whole again.
				dismemberment.restore(id);
				BloodSupply.refill(id, 1.0F, true);
			} else if (previous > 0.0F && health > previous + 0.01F) {
				// Healing: the wounds close as the health comes back.
				float max = Math.max(1.0F, living.getMaxHealth());
				dismemberment.wounds().heal(id, (health - previous) / max, health >= max - 0.01F);
				BloodSupply.refill(id, (health - previous) / max, health >= max - 0.01F);
			}

			PendingHit hit = pending.get(id);
			float damage;
			if (health < previous - 0.01F) {
				damage = previous - health;
			} else if (hit != null && tick - hit.tick() > PENDING_TIMEOUT) {
				// A hit with no visible health change (absorption hearts, armour soaking it all): a small bleed.
				damage = health > 0.0F ? 1.0F : 0.0F;
			} else {
				continue;
			}

			pending.remove(id);
			if (damage <= 0.0F || camera == null || camera.distanceToSqr(living) > EFFECT_RANGE_SQ) {
				continue;
			}

			// Seeded from what every client is told the same way, so everyone with the mod sees the same outcome.
			Fate.begin(living, hit != null && hit.attackerId() >= 0 ? level.getEntity(hit.attackerId()) : null);
			// This player's own hit: the parts it cuts off are this game's to move (see PieceSync).
			GoreSync.local(hit != null && Minecraft.getInstance().player != null && hit.attackerId() == Minecraft.getInstance().player.getId());
			// What comes out of it is its own blood: no more than the body really holds (see BloodSupply).
			BloodSupply.begin(living);
			try {
				hurt(level, blood, dismemberment, suffering, living, damage, health <= 0.0F, hit);
			} finally {
				BloodSupply.end();
				GoreSync.local(false);
				Fate.end();
			}

			if (hit != null && Minecraft.getInstance().player != null && hit.attackerId() == Minecraft.getInstance().player.getId()) {
				// This player's own hit: the server keeps the picture of what it did (see Evidence).
				Evidence.touched(living.getId());
			}
		}

		pending.int2ObjectEntrySet().removeIf(e -> tick - e.getValue().tick() > 100);
		blasts.removeIf(b -> tick - b.tick() > 100);
	}

	private void hurt(ClientLevel level, BloodWorld blood, Dismemberment dismemberment, Suffering suffering, LivingEntity victim,
			float damage, boolean lethal, @Nullable PendingHit hit) {
		if (victim instanceof LocalPlayer self && self == Minecraft.getInstance().player) {
			// What the player feels of it themselves: blood on the screen from that side, the view knocked about.
			Entity source = hit != null && hit.attackerId() >= 0 ? level.getEntity(hit.attackerId()) : null;
			Vec3 from = hit != null && hit.sourceAt() != null ? hit.sourceAt() : source != null ? source.getEyePosition() : null;
			PlayerInjuries.Cause cause = hit == null || hit.fire() ? PlayerInjuries.Cause.OTHER
					: hit.explosion() ? PlayerInjuries.Cause.BLAST
					: hit.fall() ? PlayerInjuries.Cause.FALL
					: from != null ? PlayerInjuries.Cause.STRUCK : PlayerInjuries.Cause.OTHER;
			GoreSystems.injuries().onHurt(self, damage, from, cause);
		}

		boolean gore = EntityFilter.affects(victim);
		boolean suffers = suffering.applies(victim);
		if (!gore && !suffers) {
			return;
		}

		Entity attacker = hit != null && hit.attackerId() >= 0 ? level.getEntity(hit.attackerId()) : null;
		LivingEntity meleeAttacker = attacker instanceof LivingEntity living && hit.melee() ? living : null;
		// Where the blast went off, and how hard it hit this body, 0..1: vanilla's own measure (distance over twice
		// the radius, times how much of the body the blast could see). Null and 0 for anything but a blast.
		Vec3 blastAt = null;
		float impact = 0.0F;
		if (hit != null && hit.explosion()) {
			Blast known = blastFor(victim, hit.sourceAt());
			if (known != null || hit.sourceAt() != null) {
				blastAt = known != null ? known.center() : hit.sourceAt();
				float radius = known != null ? known.radius() : DEFAULT_BLAST_RADIUS;
				if (known != null && known.vessel() != null) {
					// A pressure vessel: graded by the real overpressure on the body, not by vanilla's TNT.
					impact = PressureVessel.impact(PressureVessel.overpressure(known.vessel(), victim), TEAR_IMPACT, SHRED_IMPACT);
				} else {
					double dist = Math.sqrt(victim.distanceToSqr(blastAt)) / (radius * 2.0);
					impact = (float) Math.max(0.0, (1.0 - dist) * ServerExplosion.getSeenPercent(blastAt, victim));
				}
			} else {
				// No idea where it went off (the explosion packet never came): a blast, but not a close one.
				blastAt = victim.position();
				impact = 0.3F;
			}
		}

		Vec3 away = null;
		Vec3 strike = null;
		if (blastAt != null) {
			Vec3 out = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0).subtract(blastAt);
			if (out.lengthSqr() > 1.0E-6) {
				// A blast throws up as well as out, even from level ground.
				strike = out.normalize().add(0.0, 0.5, 0.0).normalize();
				away = new Vec3(strike.x, 0.0, strike.z).lengthSqr() > 1.0E-6 ? new Vec3(strike.x, 0.0, strike.z).normalize() : null;
			}
		} else if (attacker != null) {
			Vec3 delta = new Vec3(victim.getX() - attacker.getX(), 0.0, victim.getZ() - attacker.getZ());
			if (delta.lengthSqr() > 1.0E-6) {
				away = delta.normalize();
			}

			// Where the blow was going: from the attacker's eyes to the middle of the victim.
			Vec3 aim = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0).subtract(attacker.getEyePosition());
			if (aim.lengthSqr() > 1.0E-6) {
				strike = aim.normalize();
			}
		}

		GoreConfig config = GoreConfig.get();
		boolean torn = false;
		// Players too: a creeper that kills you throws your limbs about for everyone watching.
		if (gore && blastAt != null && lethal && config.blastGore && config.goreLevel.ordinal() >= GoreLevel.MEDIUM.ordinal()) {
			if (impact >= SHRED_IMPACT) {
				// Right next to the charge: the body is torn to pieces and there is nothing left to die slowly.
				dismemberment.vaporize(blood, level, victim, blastAt);
				gore = false;
			} else if (impact >= TEAR_IMPACT) {
				// Close: limbs and head torn off and thrown, the belly opened. The rest is thrown like a doll.
				dismemberment.tear(blood, level, victim, blastAt, (impact - TEAR_IMPACT) / (SHRED_IMPACT - TEAR_IMPACT));
				torn = true;
			}
		}

		Weapon weapon = weapon(attacker, meleeAttacker, blastAt);
		// One player's blade on another: deeper cuts, harder bleeding, and a kill takes more than one limb.
		boolean pvp = config.brutalPvp && victim instanceof Player && meleeAttacker instanceof Player && weapon != null && weapon.cuts();
		Deaths.Zone zone = Deaths.Zone.UNKNOWN;
		if (gore) {
			// No attacker and no blast: a fall, fire, drowning. Bruising and a little bleeding.
			// Fire sears the flesh shut instead: it chars (see Burns) but does not bleed.
			if (hit == null || !hit.fire()) {
				// What is in a walking corpse is old and thick: it oozes rather than sprays.
				float flow = (EntityFilter.isRotten(victim) ? 0.55F : 1.0F) * (pvp ? PVP_BLEED : 1.0F);
				BloodEmitter.bleed(blood, level, victim, damage, lethal, away, (weapon != null ? weapon.bleed : 0.25F) * flow);
			}

			Wounds.Strike wound = weapon == null ? null : blastAt != null
					? dismemberment.wounds().onBlast(victim, blastAt, damage)
					: dismemberment.wounds().onDamage(victim, attacker, meleeAttacker != null, weapon, damage * (pvp ? PVP_DEPTH : 1.0F));
			if (wound != null) {
				BodyPart part = wound.hit().bodyPart();
				zone = part == null ? Deaths.Zone.BODY : part == BodyPart.HEAD ? Deaths.Zone.HEAD : Deaths.Zone.LEG;
			}

			if (wound != null) {
				Vec3 from = blastAt != null ? blastAt : attacker.getEyePosition();
				Vec3 blow = wound.hit().point().subtract(from);
				if (blow.lengthSqr() > 1.0E-6) {
					HitFeedback.hit(level, blood, victim, wound, weapon, blow.normalize());
				}
			}

			if (wound != null && wound.fracture() != Wounds.INTACT) {
				HitFeedback.fracture(level, victim, wound);
				BodyPart broke = wound.hit().bodyPart();
				// Smashed: the head caves in, the limb is torn off. Players keep their parts until they die.
				if (wound.fracture() == Wounds.SMASHED && broke != null && (lethal || !(victim instanceof Player))) {
					dismemberment.smash(blood, level, victim, broke, victim::position, Float.NaN, 0.0F, away);
				}
			}

			if (wound != null && wound.aimed()) {
				// The player aimed this blow: only the part that was struck can come off.
				BodyPart struck = wound.hit().bodyPart();
				boolean heavy = damage >= config.heavyHitDamage && !(victim instanceof Player);
				boolean chop = weapon.cuts() && (lethal || heavy) && RANDOM.nextFloat() < config.goreLevel.partChance;
				if (struck != null && (wound.cut() || chop)) {
					dismemberment.severPart(blood, level, victim, struck, victim::position, Float.NaN, 0.0F, away);
				}

				if (pvp && lethal && config.goreLevel.ordinal() >= GoreLevel.HIGH.ordinal()) {
					// Hacked down: more of the body comes apart than the limb that took the blow.
					dismemberment.onBladedHit(blood, level, victim, true, away);
				}
			} else if (torn) {
				// Already torn apart above.
			} else if (weapon != Weapon.BLAST && EntityFilter.isBone(victim) && (meleeAttacker != null || lethal)
					|| meleeAttacker != null && EntityFilter.isBladed(DemoPlayer.held(meleeAttacker)) || weapon == Weapon.BLAST && lethal) {
				// Spot unknown or guessed. Bone does not need a blade: any hard blow can snap a bone off.
				// Players only lose parts on death; mobs can also lose one to a heavy hit and keep walking.
				boolean heavy = damage >= config.heavyHitDamage && !(victim instanceof Player);
				if (lethal || heavy) {
					dismemberment.onBladedHit(blood, level, victim, lethal, away);
				}
			}
		}

		// After dismemberment, so a beheading on this very hit is known to the death throes.
		if (suffers) {
			boolean bareHanded = meleeAttacker != null && DemoPlayer.held(meleeAttacker).isEmpty();
			suffering.onHurt(level, victim, damage, lethal, bareHanded, strike, impact, weapon, zone, hit != null && hit.fall());
		}
	}

	/**
	 * What the blow was: the held item for a melee hit (an empty-handed zombie or player punches; an animal bites), a
	 * projectile, or a blast. Null if nothing struck it (falling, burning, drowning).
	 */
	private static @Nullable Weapon weapon(@Nullable Entity attacker, @Nullable LivingEntity melee, @Nullable Vec3 blastAt) {
		if (blastAt != null) {
			return Weapon.BLAST;
		}

		if (melee != null) {
			if (DemoPlayer.held(melee).isEmpty() && !(melee instanceof Player) && !(melee instanceof Zombie)) {
				return Weapon.BITE;
			}

			return Weapon.of(DemoPlayer.held(melee));
		}

		return attacker != null ? Weapon.PIERCE : null;
	}

	/**
	 * The explosion from the last few ticks that hurt {@code victim}: the one nearest the damage source if the packet
	 * gave one, otherwise the nearest one whose reach (twice its radius) covers the victim. Null if none.
	 */
	private @Nullable Blast blastFor(LivingEntity victim, @Nullable Vec3 sourceAt) {
		Blast best = null;
		double bestSq = Double.MAX_VALUE;
		for (Blast b : blasts) {
			if (tick - b.tick() > 10) {
				continue;
			}

			double d = sourceAt != null ? b.center().distanceToSqr(sourceAt) : victim.distanceToSqr(b.center());
			// A vessel's blasts are centred on the vessel, up to a block and a half from the TNT that did the damage.
			double limit = sourceAt != null ? (b.vessel() != null ? 4.0 : 2.5) : b.radius() * 2.0;
			if (d < limit * limit && d < bestSq) {
				best = b;
				bestSq = d;
			}
		}

		return best;
	}

	public void forget(int entityId) {
		lastHealth.remove(entityId);
		pending.remove(entityId);
	}

	public void clear() {
		lastHealth.clear();
		pending.clear();
		blasts.clear();
		vessels.clear();
	}
}
