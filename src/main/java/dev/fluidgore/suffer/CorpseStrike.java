package dev.fluidgore.suffer;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.trigger.EntityFilter;
import dev.fluidgore.trigger.Fate;
import dev.fluidgore.wound.HitFeedback;
import dev.fluidgore.wound.Weapon;
import dev.fluidgore.wound.Wounds;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Hitting a body that is already down or still going through its death throes. The server no longer knows about
 * these bodies, so nothing can hit them in the normal way; the attack click is caught here, aimed against the
 * body's own box, and answered with blood, a shove into the ragdoll and, with a blade, a chance to cut a part off.
 */
public final class CorpseStrike {
	private static final double REACH = 4.5;
	private static final int COOLDOWN = 6;
	private static final RandomSource RANDOM = Fate.stream("corpseStrike");

	private static int lastStrike = -100;

	private CorpseStrike() {
	}

	/** Attack key pressed. Returns true if it landed on a body (the vanilla attack is then skipped). */
	public static boolean onAttack(Minecraft mc, LocalPlayer player, ClientLevel level, BloodWorld blood) {
		GoreConfig config = GoreConfig.get();
		Collection<Corpse> corpses = GoreSystems.suffering().corpses();
		if (!config.goreLevel.enabled() || corpses.isEmpty()) {
			return false;
		}

		Corpse best = aimed(mc, player);
		if (best == null) {
			return false;
		}

		Vec3 eye = player.getEyePosition();

		player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		if (level.getGameTime() - lastStrike >= COOLDOWN) {
			lastStrike = (int) level.getGameTime();
			strike(level, blood, best, eye, player.getViewVector(1.0F), Weapon.of(player.getMainHandItem()),
					(float) player.getAttributeValue(Attributes.ATTACK_DAMAGE));
		}

		return true;
	}

	/** The body under the crosshair within reach, nearer than any block, or null. */
	public static @Nullable Corpse aimed(Minecraft mc, LocalPlayer player) {
		Collection<Corpse> corpses = GoreSystems.suffering().corpses();
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(REACH));
		// Blocks in the way hide a body. The dying entity itself may still be what the crosshair is on; that is fine.
		double limit = REACH * REACH;
		HitResult hit = mc.hitResult;
		if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
			limit = Math.min(limit, hit.getLocation().distanceToSqr(eye));
		}

		Corpse best = null;
		for (Corpse corpse : corpses) {
			if (GoreSystems.dismemberment().vaporized(corpse.entity.getId())) {
				continue;
			}

			Optional<Vec3> clip = box(corpse).clip(eye, end);
			if (clip.isPresent() && clip.get().distanceToSqr(eye) < limit) {
				limit = clip.get().distanceToSqr(eye);
				best = corpse;
			}
		}

		return best;
	}

	/**
	 * One blow on a body from {@code eye} along {@code look}. It lands on the spot of the model it hits and opens or
	 * deepens the wound there; blood and a sound, a shove through the ragdoll and a jolt in the struck limb; a blade
	 * through a wound that is already as deep as it can get takes that part off.
	 */
	public static void strike(ClientLevel level, BloodWorld blood, Corpse corpse, Vec3 eye, Vec3 look, Weapon weapon, float damage) {
		LivingEntity entity = corpse.entity;
		Ragdoll body = corpse.body;
		boolean blade = weapon.cuts();
		Wounds.Strike wound = GoreSystems.dismemberment().wounds().strike(entity, eye, look, REACH + 2.0, weapon, damage);
		Vec3 point = wound != null ? wound.hit().point() : box(corpse).clip(eye, eye.add(look.scale(REACH + 2.0))).orElse(centre(corpse));
		int rgb = EntityFilter.bloodColor(entity);
		level.playLocalSound(point.x, point.y, point.z, blade ? SoundEvents.PLAYER_ATTACK_SWEEP : SoundEvents.PLAYER_ATTACK_WEAK,
				SoundSource.PLAYERS, 0.8F, 0.8F, false);
		if (wound != null) {
			HitFeedback.hit(level, blood, entity, wound, weapon, look);
			if (wound.fracture() != Wounds.INTACT) {
				HitFeedback.fracture(level, entity, wound);
				BodyPart broke = wound.hit().bodyPart();
				if (wound.fracture() == Wounds.SMASHED && broke != null) {
					GoreSystems.dismemberment().smash(blood, level, entity, broke, () -> new Vec3(body.x, body.y, body.z), body.yaw, body.roll, null);
				}
			}
		} else {
			BloodEmitter.burst(blood, level, point, rgb, blade ? 0.07F : 0.025F, blade ? 14 : 5);
			BloodEmitter.slash(level, point, blade ? 0.9F : 0.5F);
		}

		// The body takes the blow like a struck mob takes knockback: flashes red, skids along the swing and hops, harder
		// the harder the hit, and rolls if hit off-centre. Each blow keeps it around a little longer. A blade cuts rather
		// than bats, so a carcass being butchered is worked where it lies, and barely moves once it has stopped moving.
		float knock = !blade ? 1.0F : corpse.lifeless() ? 0.05F : 0.12F;
		double push = (blade ? 0.25 : 0.32) * (0.8 + Math.min(damage, 10.0F) * 0.05) * body.heft * knock;
		body.vx = Mth.clamp(body.vx + look.x * push, -0.8, 0.8);
		body.vz = Mth.clamp(body.vz + look.z * push, -0.8, 0.8);
		body.vy = Math.min(0.5, body.vy + (body.onGround ? 0.18 : 0.08) * body.heft * knock);
		body.onGround = false;
		float yawRad = body.yaw * Mth.DEG_TO_RAD;
		double lateral = look.x * Mth.cos(yawRad) + look.z * Mth.sin(yawRad);
		body.rollV += (float) Math.max(-0.18, Math.min(0.18, lateral * 0.2)) * knock;
		corpse.flash = 6;
		corpse.keepFor(60);
		GoreSystems.suffering().struck(level, corpse, look);
		BodyPart struck = wound != null ? wound.hit().bodyPart() : null;
		if (struck != null) {
			body.strikeJoint(struck == BodyPart.HEAD ? -1 : List.of(ModelParts.LIMBS).indexOf(struck), blade ? 0.6F : 0.45F, RANDOM);
		} else {
			body.spasm(RANDOM, blade ? 0.7F : 0.5F);
		}

		Vec3 flat = new Vec3(look.x, 0.0, look.z);
		Vec3 away = flat.lengthSqr() > 1.0E-6 ? flat.normalize() : null;
		if (wound != null) {
			if (struck != null && wound.cut()) {
				GoreSystems.dismemberment().severPart(blood, level, entity, struck, () -> new Vec3(body.x, body.y, body.z), body.yaw, body.roll, away);
			} else if (struck == null && wound.cut() && blade && GoreConfig.get().butchery) {
				// Through the carcass, already cut to the bone: a few chops through the backbone and it splits.
				GoreSystems.dismemberment().butchery().chopThrough(blood, level, corpse, wound.hit().part(), look);
			}
		} else if (blade && RANDOM.nextFloat() < 0.4F * GoreConfig.get().goreLevel.partChance + 0.15F) {
			// Wounds are off for this body, so the spot is unknown: a random part.
			GoreSystems.dismemberment().severOnBody(blood, level, entity, () -> new Vec3(body.x, body.y, body.z), body.yaw, body.roll, away);
		}
	}

	/** The body's box: the entity's own while it is upright, lying flat once it has tipped over. */
	private static AABB box(Corpse corpse) {
		Ragdoll body = corpse.body;
		LivingEntity entity = corpse.entity;
		float lying = Math.abs(Mth.sin(body.roll));
		double width = entity.getBbWidth();
		double height = entity.getBbHeight();
		double half = Mth.lerp(lying, width, Math.max(width, height)) * 0.5;
		double top = Mth.lerp(lying, height, width);
		return new AABB(body.x - half, body.y, body.z - half, body.x + half, body.y + top, body.z + half).inflate(0.1);
	}

	/** For tests: the point at the middle of a body. */
	public static Vec3 centre(Corpse corpse) {
		return box(corpse).getCenter();
	}
}
