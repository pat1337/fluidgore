package dev.fluidgore.trigger;

import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.suffer.Suffering;
import dev.fluidgore.wound.Weapon;
import dev.fluidgore.wound.Wounds;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
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
	private static final RandomSource RANDOM = RandomSource.create();

	private record PendingHit(int attackerId, boolean melee, int tick) {
	}

	private final Int2FloatOpenHashMap lastHealth = new Int2FloatOpenHashMap();
	private final Int2ObjectOpenHashMap<PendingHit> pending = new Int2ObjectOpenHashMap<>();
	private int tick;

	public DamageTracker() {
		lastHealth.defaultReturnValue(Float.NaN);
	}

	/** Called on the client thread when a damage event packet arrives for {@code victim}. */
	public void onDamageEvent(LivingEntity victim, DamageSource source) {
		Entity attacker = source.getEntity();
		boolean melee = attacker != null && source.getDirectEntity() == attacker;
		pending.put(victim.getId(), new PendingHit(attacker == null ? -1 : attacker.getId(), melee, tick));
	}

	public void tick(ClientLevel level, BloodWorld blood, Dismemberment dismemberment, Suffering suffering) {
		tick++;
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

			hurt(level, blood, dismemberment, suffering, living, damage, health <= 0.0F, hit);
		}

		pending.int2ObjectEntrySet().removeIf(e -> tick - e.getValue().tick() > 100);
	}

	private void hurt(ClientLevel level, BloodWorld blood, Dismemberment dismemberment, Suffering suffering, LivingEntity victim,
			float damage, boolean lethal, @Nullable PendingHit hit) {
		boolean gore = EntityFilter.affects(victim);
		boolean suffers = suffering.applies(victim);
		if (!gore && !suffers) {
			return;
		}

		Entity attacker = hit != null && hit.attackerId() >= 0 ? level.getEntity(hit.attackerId()) : null;
		LivingEntity meleeAttacker = attacker instanceof LivingEntity living && hit.melee() ? living : null;
		Vec3 away = null;
		Vec3 strike = null;
		if (attacker != null) {
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

		if (gore) {
			BloodEmitter.bleed(blood, level, victim, damage, lethal, away);
			Weapon weapon = meleeAttacker != null ? Weapon.of(meleeAttacker.getMainHandItem()) : Weapon.PIERCE;
			Wounds.Strike wound = dismemberment.wounds().onDamage(victim, attacker, weapon, damage);
			if (wound != null && wound.aimed()) {
				// The player aimed this blow: only the part that was struck can come off.
				BodyPart struck = wound.hit().bodyPart();
				boolean heavy = damage >= GoreConfig.get().heavyHitDamage && !(victim instanceof Player);
				boolean chop = weapon.cuts() && (lethal || heavy) && RANDOM.nextFloat() < GoreConfig.get().goreLevel.partChance;
				if (struck != null && (wound.cut() || chop)) {
					dismemberment.severPart(blood, level, victim, struck, victim::position, Float.NaN, 0.0F, away);
				}
			} else if (EntityFilter.isBone(victim) && (meleeAttacker != null || lethal)
					|| meleeAttacker != null && EntityFilter.isBladed(meleeAttacker.getMainHandItem())) {
				// Spot unknown or guessed. Bone does not need a blade: any hard blow can snap a bone off.
				// Players only lose parts on death; mobs can also lose one to a heavy hit and keep walking.
				boolean heavy = damage >= GoreConfig.get().heavyHitDamage && !(victim instanceof Player);
				if (lethal || heavy) {
					dismemberment.onBladedHit(blood, level, victim, lethal, away);
				}
			}
		}

		// After dismemberment, so a beheading on this very hit is known to the death throes.
		if (suffers) {
			boolean bareHanded = meleeAttacker != null && meleeAttacker.getMainHandItem().isEmpty();
			suffering.onHurt(level, victim, damage, lethal, bareHanded, strike);
		}
	}

	public void forget(int entityId) {
		lastHealth.remove(entityId);
		pending.remove(entityId);
	}

	public void clear() {
		lastHealth.clear();
		pending.clear();
	}
}
