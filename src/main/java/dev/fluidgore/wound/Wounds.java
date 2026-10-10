package dev.fluidgore.wound;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.trigger.EntityFilter;
import dev.fluidgore.trigger.Fate;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The hack model: blows land on the exact spot of the model they hit and open wounds there that deepen with every
 * blow on the same spot, on living animals mid-fight and on bodies after death. A blade through a wound that is
 * already as deep as the gore level allows takes that part off. Cosmetic and client-side only.
 */
public final class Wounds {
	public static final RenderStateDataKey<WoundSet> KEY = RenderStateDataKey.create(() -> "fluidgore:wounds");

	/** How long an attack click is remembered while waiting for the server to report the damage. */
	private static final int AIM_TICKS = 10;
	private static final double REACH = 8.0;
	private static final RandomSource RANDOM = Fate.stream("wounds");
	/** A maggot falling out of a rotting wound: a pale speck that drops to the ground. */
	private static final BlockParticleOption MAGGOT = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.BONE_BLOCK.defaultBlockState());
	/** Pitch and yaw offsets, in degrees, tried in order when another player's view ray misses the body. */
	private static final float[][] VIEW_NUDGES = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {4, 0}, {-4, 0}, {0, 4}, {0, -4}};

	/** Where a swing of the local player landed on the model, resolved against the pose the player saw. */
	private record Aim(Model<?> model, ModelHit.Result hit, float bodyYaw, int tick) {
	}

	/**
	 * One blow resolved against the model.
	 *
	 * @param cut   true if a cutting blow went through a wound that could not get any deeper: the part comes off
	 * @param aimed true if the spot is where the player really aimed; false if it was guessed for another attacker
	 */
	public record Strike(ModelHit.Result hit, float severity, boolean cut, boolean aimed) {
	}

	private final Int2ObjectOpenHashMap<WoundSet> sets = new Int2ObjectOpenHashMap<>();
	private final Int2ObjectOpenHashMap<Aim> aims = new Int2ObjectOpenHashMap<>();
	private int tick;

	public @Nullable WoundSet get(int entityId) {
		return sets.get(entityId);
	}

	/** True if this entity shows wounds under the current settings. */
	public static boolean applies(LivingEntity entity) {
		GoreConfig config = GoreConfig.get();
		if (!config.wounds || !EntityFilter.affects(entity)) {
			return false;
		}

		if (entity instanceof Player) {
			return config.woundPlayers;
		}

		return !(entity instanceof Enemy) || config.woundHostiles;
	}

	/**
	 * The local player swung at {@code target}. The spot is found now, against the pose the player is looking at:
	 * the damage arrives a tick or more later, by when knockback has moved the body and its legs have swung. The spot
	 * is kept in the struck part's own space, so it stays right however the body moves.
	 */
	public void recordAim(LivingEntity target, Vec3 eye, Vec3 dir) {
		ModelParts.Posed posed = GoreConfig.get().goreLevel.enabled() && applies(target) ? ModelParts.pose(target) : null;
		ModelHit.Result hit = posed != null ? ModelHit.cast(target, posed, eye, dir, REACH) : null;
		if (hit != null) {
			aims.put(target.getId(), new Aim(posed.model(), hit, posed.state().bodyRot, tick));
		}
	}

	/**
	 * A blow reported by the server. Uses the player's own aim if they swung at this entity, then another player's
	 * swing along where the server says they are looking, otherwise a ray from the attacker to the middle of the victim.
	 */
	public @Nullable Strike onDamage(LivingEntity victim, @Nullable Entity attacker, boolean melee, Weapon weapon, float damage) {
		if (attacker == null || !applies(victim)) {
			return null;
		}

		Aim aim = aims.remove(victim.getId());
		if (aim != null && tick - aim.tick() <= AIM_TICKS) {
			Strike strike = open(victim, aim.model(), aim.hit(), aim.bodyYaw(), weapon, damage);
			if (strike != null) {
				return strike;
			}
		}

		if (melee && attacker instanceof Player player) {
			// Another player's swing: the server sends everyone where each player looks, so it is cast along their
			// view the way the local player's own swing is, and lands on the same spot on every screen.
			Strike seen = alongView(victim, player, weapon, damage);
			if (seen != null) {
				return seen;
			}
		}

		// A mob, a projectile, or a swing whose view ray missed: the server never says where it landed, so a spot is guessed.
		Vec3 eye = attacker.getEyePosition();
		Vec3 target = victim.position().add(
				(RANDOM.nextDouble() - 0.5) * victim.getBbWidth() * 0.6,
				victim.getBbHeight() * (0.3 + RANDOM.nextDouble() * 0.5),
				(RANDOM.nextDouble() - 0.5) * victim.getBbWidth() * 0.6);
		Vec3 dir = target.subtract(eye);
		double length = dir.length();
		Strike guessed = length < 1.0E-4 ? null : strike(victim, eye, dir.scale(1.0 / length), length + 2.0, weapon, damage);
		return guessed == null ? null : new Strike(guessed.hit(), guessed.severity(), guessed.cut(), false);
	}

	/**
	 * Casts {@code player}'s swing along their synced view. Their rotation arrives rounded to 1.4 degrees and a tick or
	 * two late, so if the exact ray misses, nearby rays within a few degrees are tried before giving up.
	 */
	private @Nullable Strike alongView(LivingEntity victim, Player player, Weapon weapon, float damage) {
		Vec3 eye = player.getEyePosition();
		float xRot = player.getViewXRot(1.0F);
		float yRot = player.getViewYRot(1.0F);
		for (float[] nudge : VIEW_NUDGES) {
			Strike strike = strike(victim, eye, Entity.calculateViewVector(xRot + nudge[0], yRot + nudge[1]), REACH, weapon, damage);
			if (strike != null) {
				return strike;
			}
		}

		return null;
	}

	/**
	 * A blast at {@code from}: shrapnel and the blast itself tear several wounds on the side facing it. Returns the
	 * deepest, as a guessed spot.
	 */
	public @Nullable Strike onBlast(LivingEntity victim, Vec3 from, float damage) {
		if (!applies(victim)) {
			return null;
		}

		Strike deepest = null;
		int tears = 2 + RANDOM.nextInt(3);
		for (int k = 0; k < tears; k++) {
			Vec3 target = victim.position().add((RANDOM.nextDouble() - 0.5) * victim.getBbWidth(), victim.getBbHeight() * RANDOM.nextDouble(),
					(RANDOM.nextDouble() - 0.5) * victim.getBbWidth());
			Vec3 dir = target.subtract(from);
			double length = dir.length();
			if (length < 1.0E-4) {
				continue;
			}

			Strike strike = strike(victim, from, dir.scale(1.0 / length), length + 2.0, Weapon.BLAST, damage * 0.6F);
			if (strike != null && (deepest == null || strike.severity() > deepest.severity())) {
				deepest = strike;
			}
		}

		return deepest == null ? null : new Strike(deepest.hit(), deepest.severity(), false, false);
	}

	/** Casts the blow against the model as posed now and opens or deepens the wound it lands on. */
	public @Nullable Strike strike(LivingEntity victim, Vec3 from, Vec3 dir, double reach, Weapon weapon, float damage) {
		if (!GoreConfig.get().goreLevel.enabled() || !applies(victim)) {
			return null;
		}

		ModelParts.Posed posed = ModelParts.pose(victim);
		ModelHit.Result hit = posed != null ? ModelHit.cast(victim, posed, from, dir, reach) : null;
		return hit != null ? open(victim, posed.model(), hit, posed.state().bodyRot, weapon, damage) : null;
	}

	/** Opens or deepens the wound at {@code hit}, a spot on {@code model} found while the body yawed {@code bodyYaw}. */
	private @Nullable Strike open(LivingEntity victim, Model<?> model, ModelHit.Result hit, float bodyYaw, Weapon weapon, float damage) {
		GoreLevel level = GoreConfig.get().goreLevel;
		if (!level.enabled() || !applies(victim)) {
			return null;
		}

		WoundSet set = sets.get(victim.getId());
		if (set == null || set.model != model) {
			set = new WoundSet(model, Flesh.of(victim), EntityFilter.bloodType(victim).rgb);
			sets.put(victim.getId(), set);
		}

		// Damage relative to how much the body can take: a sword on a chicken opens it up, on a horse it gashes.
		float relative = damage / Math.max(4.0F, victim.getMaxHealth());
		float amount = Math.max(0.12F, relative * 0.9F) * weapon.depth * level.woundRate;
		float cap = Math.min(weapon.cap, level.woundDepth);
		Vec3 offset = hit.point().subtract(victim.position());
		WoundSet.Change change = set.add(hit, weapon, amount, cap, offset, bodyYaw, RANDOM);
		boolean cut = weapon.cuts() && hit.bodyPart() != null && change.before() >= cap - 1.0E-3F
				&& RANDOM.nextFloat() < Math.min(1.0F, level.partChance * 2.0F);
		return new Strike(hit, change.after(), cut, true);
	}

	/**
	 * A part was just cut off {@code entity} and {@code piece} is the loose copy that flies off. The wounds already on
	 * the part go with the piece, which also gets a raw cut face where it was joined; the body keeps a stump.
	 * Returns the piece's wounds, or null if wounds are off for this entity.
	 */
	public @Nullable WoundSet onSevered(LivingEntity entity, ModelParts.Posed posed, ModelPart severed, ModelPart piece, BodyPart bodyPart) {
		if (!applies(entity)) {
			return null;
		}

		Flesh flesh = Flesh.of(entity);
		int blood = EntityFilter.bloodType(entity).rgb;
		WoundSet pieceWounds = new WoundSet(piece, flesh, blood);
		WoundSet set = sets.get(entity.getId());
		if (set == null || set.model != posed.model()) {
			set = new WoundSet(posed.model(), flesh, blood);
			sets.put(entity.getId(), set);
		} else {
			set.transferTo(pieceWounds, ModelParts.correspondence(severed, piece));
			set.removePart(bodyPart);
		}

		ModelHit.Result face = ModelHit.cutFace(piece, bodyPart);
		if (face != null) {
			float[] pb = ModelParts.bounds(piece);
			pieceWounds.stump(face, Math.min(pb[3] - pb[0], pb[5] - pb[2]) * 16.0F * 0.5F, RANDOM);
		}

		ModelHit.Result at = ModelHit.stump(entity, posed, severed);
		if (at != null) {
			float[] b = ModelParts.bounds(severed);
			float size = Math.min(b[3] - b[0], b[5] - b[2]) * 16.0F * 0.5F;
			set.stump(at, size, RANDOM);
		}

		return pieceWounds;
	}

	/** Open wounds on living animals and fresh bodies drip, so a wounded animal leaves a trail. */
	public void tick(ClientLevel level, BloodWorld blood) {
		tick++;
		if (aims.size() > 64) {
			aims.int2ObjectEntrySet().removeIf(e -> tick - e.getValue().tick() > AIM_TICKS);
		}

		if (tick % 5 != 0 || sets.isEmpty()) {
			return;
		}

		float puddles = GoreConfig.get().goreLevel.puddles;
		for (Int2ObjectMap.Entry<WoundSet> entry : sets.int2ObjectEntrySet()) {
			WoundSet set = entry.getValue();
			if (!set.flesh.bleeds()) {
				continue;
			}

			Entity entity = level.getEntity(entry.getIntKey());
			Corpse corpse = GoreSystems.suffering().corpse(entry.getIntKey());
			Vec3 base;
			float yaw;
			if (corpse != null) {
				// Bodies bleed out: fresh ones drip, old ones have stopped.
				if (corpse.age > 600) {
					continue;
				}

				base = corpse.position();
				yaw = corpse.yaw();
			} else if (entity instanceof LivingEntity living && living.isAlive()) {
				base = living.position();
				yaw = living.yBodyRot;
			} else {
				continue;
			}

			if (set.trauma > 0.8F && RANDOM.nextFloat() < Math.min(0.6F, (set.trauma - 0.8F) * 0.35F)) {
				// Beaten without the skin giving way: it bleeds inside, and the blood comes from the mouth and nose.
				Vec3 mouth = mouth(corpse != null ? corpse.entity : (LivingEntity) entity);
				if (mouth != null) {
					BloodEmitter.drip(blood, mouth, set.blood, 0.004F * Math.min(3.0F, set.trauma) * puddles);
				}
			}

			for (int i = 0; i < set.count; i++) {
				float severity = set.severity[i];
				if (severity < 0.25F || Float.isNaN(set.ox[i]) || RANDOM.nextFloat() > severity * 0.6F) {
					continue;
				}

				Vec3 offset = new Vec3(set.ox[i], set.oy[i], set.oz[i]).yRot((set.yaw[i] - yaw) * Mth.DEG_TO_RAD);
				Vec3 at = base.add(offset);
				if (set.flesh.rots()) {
					// A rotting wound seeps slowly, and now and then a maggot drops out of a deep one.
					BloodEmitter.drip(blood, at, set.blood, 0.002F * severity * puddles);
					if (severity >= 0.5F && RANDOM.nextFloat() < 0.25F) {
						level.addParticle(MAGGOT, at.x, at.y, at.z, 0.0, 0.0, 0.0);
					}
				} else {
					BloodEmitter.drip(blood, at, set.blood, 0.004F * severity * puddles);
				}
			}
		}
	}

	/**
	 * Where the deepest open wound on this body is now, for a body whose feet are at {@code base} facing {@code yaw};
	 * null if it has none that bleeds (a bruise does not).
	 */
	public @Nullable Vec3 deepest(int entityId, Vec3 base, float yaw) {
		WoundSet set = sets.get(entityId);
		if (set == null) {
			return null;
		}

		int best = -1;
		float deepest = 0.25F;
		for (int i = 0; i < set.count; i++) {
			if (!Float.isNaN(set.ox[i]) && set.severity[i] >= deepest) {
				deepest = set.severity[i];
				best = i;
			}
		}

		return best < 0 ? null : base.add(new Vec3(set.ox[best], set.oy[best], set.oz[best]).yRot((set.yaw[best] - yaw) * Mth.DEG_TO_RAD));
	}

	/** The front underside of the head as posed now, or null if the model has no head (or it is gone). */
	private static @Nullable Vec3 mouth(LivingEntity entity) {
		ModelParts.Posed posed = ModelParts.pose(entity);
		Vec3 head = posed == null ? null : ModelHit.centre(entity, posed, BodyPart.HEAD);
		if (head == null || (GoreSystems.dismemberment().missingMask(entity.getId()) & BodyPart.HEAD.bit()) != 0) {
			return null;
		}

		float yaw = posed.state().bodyRot * Mth.DEG_TO_RAD;
		double reach = entity.getBbWidth() * 0.25;
		return head.add(-Mth.sin(yaw) * reach, -entity.getBbHeight() * 0.08, Mth.cos(yaw) * reach);
	}

	public void forget(int entityId) {
		sets.remove(entityId);
		aims.remove(entityId);
	}

	public void clear() {
		sets.clear();
		aims.clear();
	}
}
