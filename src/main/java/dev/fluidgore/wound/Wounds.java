package dev.fluidgore.wound;

import dev.fluidgore.blood.BloodSupply;
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
import net.minecraft.client.player.LocalPlayer;
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

	/** A bone breaks once this much damage, relative to the body's health, has landed on it. */
	public static final float FRACTURE = 0.55F;
	/** And is smashed beyond saving (the head caved in, the limb torn off, the ribs stove right in) past this. */
	public static final float DESTROY = 1.6F;
	/** Severity healed per share of health regained: a third of the bar closes a wound to the bone back to nothing. */
	private static final float HEAL_RATE = 3.0F;
	/** Ticks a body has lain before its wounds bleed across the side it lies on. */
	private static final int LIE_TRACE = 30;
	/** Ticks a wound bleeds before its blood has run down the skin below it. */
	private static final int RUN_AFTER = 20;
	/** What a blow did to the bone under it. */
	public static final int INTACT = 0;
	public static final int BROKE = 1;
	public static final int SMASHED = 2;

	/**
	 * One blow resolved against the model.
	 *
	 * @param cut      true if a cutting blow went through a wound that could not get any deeper: the part comes off
	 * @param aimed    true if the spot is where the player really aimed; false if it was guessed for another attacker
	 * @param fracture what the blow did to the bone: {@link #INTACT}, {@link #BROKE} it just now, or {@link #SMASHED} it
	 */
	public record Strike(ModelHit.Result hit, float severity, boolean cut, boolean aimed, int fracture) {
	}

	private final Int2ObjectOpenHashMap<WoundSet> sets = new Int2ObjectOpenHashMap<>();
	private final Int2ObjectOpenHashMap<Aim> aims = new Int2ObjectOpenHashMap<>();
	private int tick;

	public @Nullable WoundSet get(int entityId) {
		return sets.get(entityId);
	}

	/** The client world's game time, what wounds and runs of blood are dated by; 0 with no world. */
	public static long now() {
		ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
		return level != null ? level.getGameTime() : 0L;
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
	 * A blow reported by the server. Uses the player's own aim if they swung at this entity (or their own view), then
	 * the path the server says the blow took ({@code rayFrom}/{@code rayDir}: a player's exact view, an arrow's flight;
	 * null on a server without Fluid Gore), then another player's swing along where the server says they are looking,
	 * otherwise a ray from the attacker to the middle of the victim.
	 */
	public @Nullable Strike onDamage(LivingEntity victim, @Nullable Entity attacker, boolean melee, Weapon weapon, float damage,
			@Nullable Vec3 rayFrom, @Nullable Vec3 rayDir) {
		if (attacker == null && (rayFrom == null || rayDir == null) || !applies(victim)) {
			return null;
		}

		Aim aim = aims.remove(victim.getId());
		if (aim != null && tick - aim.tick() <= AIM_TICKS) {
			Strike strike = open(victim, aim.model(), aim.hit(), aim.bodyYaw(), weapon, damage);
			if (strike != null) {
				return strike;
			}
		}

		boolean own = melee && attacker instanceof LocalPlayer;
		if (own) {
			// This player's swing with no click aimed (a command, a sweep): their view here is exact and current, the
			// server's a tick old.
			Strike seen = alongView(victim, (Player) attacker, weapon, damage);
			if (seen != null) {
				return seen;
			}
		}

		if (rayFrom != null && rayDir != null) {
			// The body is posed here a little differently than the server's box stood, so near misses are nudged in.
			double reach = rayFrom.distanceTo(victim.getBoundingBox().getCenter()) + victim.getBbWidth() + victim.getBbHeight() + 1.0;
			Strike told = alongRay(victim, rayFrom, (float) Math.toDegrees(-Math.asin(Math.clamp(rayDir.y, -1.0, 1.0))),
					(float) Math.toDegrees(Math.atan2(-rayDir.x, rayDir.z)), reach, weapon, damage);
			if (told != null) {
				return told;
			}
		}

		if (attacker == null) {
			return null;
		}

		if (melee && !own && attacker instanceof Player player) {
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
		return guessed == null ? null : new Strike(guessed.hit(), guessed.severity(), guessed.cut(), false, guessed.fracture());
	}

	/**
	 * Casts {@code player}'s swing along their synced view. Their rotation arrives rounded to 1.4 degrees and a tick or
	 * two late, so if the exact ray misses, nearby rays within a few degrees are tried before giving up.
	 */
	private @Nullable Strike alongView(LivingEntity victim, Player player, Weapon weapon, float damage) {
		return alongRay(victim, player.getEyePosition(), player.getViewXRot(1.0F), player.getViewYRot(1.0F), REACH, weapon, damage);
	}

	/** Casts a blow from {@code from} at pitch {@code xRot}, yaw {@code yRot}, trying nearby rays if it just misses. */
	private @Nullable Strike alongRay(LivingEntity victim, Vec3 from, float xRot, float yRot, double reach, Weapon weapon, float damage) {
		for (float[] nudge : VIEW_NUDGES) {
			Strike strike = strike(victim, from, Entity.calculateViewVector(xRot + nudge[0], yRot + nudge[1]), reach, weapon, damage);
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

		return deepest == null ? null : new Strike(deepest.hit(), deepest.severity(), false, false, deepest.fracture());
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

	/** Projects an approved body-box contact onto the existing model without random replacement rays. */
	public @Nullable Strike fragment(LivingEntity victim, dev.fluidgore.fragment.FragmentPath.Contact contact) {
		Vec3 local = dev.fluidgore.fragment.FragmentTrace.turn(contact.local(), victim.yBodyRot);
		Vec3 at = victim.position().add(local.x * victim.getBbWidth() * 0.5, local.y * victim.getBbHeight(), local.z * victim.getBbWidth() * 0.5);
		Vec3 direction = dev.fluidgore.fragment.FragmentTrace.turn(contact.direction(), victim.yBodyRot);
		double span = Math.max(1.0, victim.getBbWidth() + victim.getBbHeight());
		return strike(victim, at.subtract(direction.scale(span)), direction, span * 2, Weapon.PIERCE, contact.damage());
	}

	/** Opens a model wound; fragment box contacts are projected here without inventing random substitute hits. */
	private @Nullable Strike open(LivingEntity victim, Model<?> model, ModelHit.Result hit, float bodyYaw, Weapon weapon, float damage) {
		GoreLevel level = GoreConfig.get().goreLevel;
		if (!level.enabled() || !applies(victim)) {
			return null;
		}

		WoundSet set = sets.get(victim.getId());
		if (set == null || set.model != model) {
			set = new WoundSet(model, Flesh.of(victim), EntityFilter.bloodType(victim).rgb);
			WoundAnatomy.bind(set, victim, model);
			sets.put(victim.getId(), set);
		}

		// A blade to the head of a four-legged animal goes into its throat (see Sticking).
		ModelParts.Posed posed = ModelParts.pose(victim);
		boolean stick = false;
		int angle = 0;
		if (posed != null && posed.model() == model && Sticking.sticks(model, hit, weapon)) {
			Sticking.Spot spot = Sticking.at(victim, posed);
			if (spot != null) {
				hit = spot.hit();
				angle = spot.angle();
				stick = true;
			}
		}

		// Damage relative to how much the body can take: a sword on a chicken opens it up, on a horse it gashes.
		float relative = damage / Math.max(4.0F, victim.getMaxHealth());
		float amount = Math.max(0.12F, relative * 0.9F) * weapon.depth * level.woundRate;
		float cap = Math.min(weapon.cap, level.woundDepth);
		Vec3 offset = hit.point().subtract(victim.position());
		long now = now();
		WoundSet.Change change = set.add(hit, weapon, amount, cap, offset, bodyYaw, stick, angle, now, RANDOM);
		// What the blow has reached under the skin, if the body has an anatomy to reach into: the first time a wound gets to
		// an organ, the organ answers (cud from the rumen, froth from a lung, a spurt from a vessel).
		dev.fluidgore.anatomy.Anatomy.Kind reached = WoundAnatomy.reach(set, change.index());
		if (reached != null) {
			byte was = set.organ[change.index()];
			set.organ[change.index()] = (byte) (reached.ordinal() + 1);
			if (was != set.organ[change.index()]) {
				WoundAnatomy.answer(victim, set, change.index(), hit.point(), hit.normal(), reached);
			}
		}

		// Through a limb already cut as deep as it goes, it may come off; through the carcass, it splits (see Butchery).
		boolean through = weapon.cuts() && change.before() >= cap - 1.0E-3F;
		boolean cut = through && (hit.bodyPart() == null || RANDOM.nextFloat() < Math.min(1.0F, level.partChance * 2.0F));
		return new Strike(hit, change.after(), cut, true, bone(set, hit, weapon, relative * level.woundRate, offset, bodyYaw));
	}

	/**
	 * Adds a blow's crushing force to the bone under it (bodies of bone break the same way). Past {@link #FRACTURE} the
	 * bone breaks and shows through, crushed or stove in; past {@link #DESTROY} it is smashed. Returns which happened now.
	 */
	private static int bone(WoundSet set, ModelHit.Result hit, Weapon weapon, float force, Vec3 offset, float bodyYaw) {
		if (weapon.crush <= 0.0F || !GoreConfig.get().fractures) {
			return INTACT;
		}

		int index = hit.bodyPart() == null ? WoundSet.TORSO : hit.bodyPart().ordinal();
		int bit = 1 << index;
		float before = set.bone[index];
		float after = before + force * weapon.crush;
		set.bone[index] = after;
		if (before < DESTROY && after >= DESTROY && (set.smashed & bit) == 0) {
			set.smashed |= bit;
			set.broken |= bit;
			set.fracture(hit, 4.0F, offset, bodyYaw, RANDOM);
			return SMASHED;
		}

		if (before < FRACTURE && after >= FRACTURE && (set.broken & bit) == 0) {
			set.broken |= bit;
			set.fracture(hit, 2.8F, offset, bodyYaw, RANDOM);
			return BROKE;
		}

		return INTACT;
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

			// Its own blood, as much as it has left to give.
			BloodSupply.begin(corpse != null ? corpse.entity : (LivingEntity) entity);
			try {
				bleed(level, blood, set, corpse, entity, base, yaw, puddles);
			} finally {
				BloodSupply.end();
			}
		}
	}

	/**
	 * Lets the blood of a body's open wounds run down its skin. A wound runs once it has bled for {@link #RUN_AFTER}
	 * ticks, against the pose then (not mid-flinch from the blow), and again whenever a blow deepens it; once a body
	 * has come to lie, what still bleeds runs again across the side it lies on.
	 */
	private void runs(WoundSet set, @Nullable Corpse corpse, @Nullable Entity entity) {
		long now = now();
		boolean lying = corpse != null && !set.lyingTraced && corpse.groundTicks() >= LIE_TRACE;
		boolean due = false;
		for (int i = 0; i < set.count && !due; i++) {
			due = set.born[i] > set.tracedUpTo && now - set.born[i] >= RUN_AFTER;
		}

		if (!lying && !due) {
			return;
		}

		LivingEntity body = corpse != null ? corpse.entity : entity instanceof LivingEntity living ? living : null;
		ModelParts.Posed posed = body != null ? ModelParts.pose(body) : null;
		if (posed == null || !set.drawsOn(posed.model())) {
			return;
		}

		long upTo = set.tracedUpTo;
		for (int i = 0; i < set.count; i++) {
			boolean fresh = set.born[i] > set.tracedUpTo && now - set.born[i] >= RUN_AFTER;
			if (fresh || lying && (set.morph[i] == WoundSet.STICK || set.severity[i] >= 0.5F)) {
				Runs.trace(set, body, posed, i, now, RANDOM);
			}

			if (fresh) {
				upTo = Math.max(upTo, set.born[i]);
			}
		}

		set.tracedUpTo = upTo;
		set.lyingTraced |= lying;
	}

	/** One body's wounds bleeding for this step. */
	private void bleed(ClientLevel level, BloodWorld blood, WoundSet set, @Nullable Corpse corpse, @Nullable Entity entity, Vec3 base, float yaw, float puddles) {
		// The living clot: the drip thins out after the last blow and stops, so a wounded survivor does not pool forever.
		float flow = 1.0F;
		if (corpse == null) {
			set.quiet += 5;
			flow = 1.0F - set.quiet / (GoreConfig.get().woundClotSeconds * 20.0F);
			if (flow <= 0.0F) {
				return;
			}
		}

		if (GoreConfig.get().bloodRuns) {
			runs(set, corpse, entity);
		}

		if (set.trauma > 0.8F && RANDOM.nextFloat() < Math.min(0.6F, (set.trauma - 0.8F) * 0.35F) * flow) {
			// Beaten without the skin giving way: it bleeds inside, and the blood comes from the mouth and nose.
			Vec3 mouth = mouth(corpse != null ? corpse.entity : (LivingEntity) entity);
			if (mouth != null) {
				BloodEmitter.drip(blood, mouth, set.blood, 0.004F * Math.min(3.0F, set.trauma) * puddles);
			}
		}

		for (int i = 0; i < set.count; i++) {
			float severity = set.severity[i];
			if (severity < 0.25F || Float.isNaN(set.ox[i]) || RANDOM.nextFloat() > severity * 0.6F * flow) {
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

	/**
	 * Plucks a handful of feathers out of the spot of {@code entity}'s body the player's ray lands on, leaving bare skin.
	 * Returns where, or null if the ray missed the body.
	 */
	public @Nullable Vec3 pluck(LivingEntity entity, Vec3 eye, Vec3 dir, double reach) {
		ModelParts.Posed posed = ModelParts.pose(entity);
		ModelHit.Result hit = posed != null ? ModelHit.cast(entity, posed, eye, dir, reach) : null;
		if (hit == null) {
			return null;
		}

		WoundSet set = sets.get(entity.getId());
		if (set == null || set.model != posed.model()) {
			set = new WoundSet(posed.model(), Flesh.of(entity), EntityFilter.bloodType(entity).rgb);
			sets.put(entity.getId(), set);
		}

		set.pluck(hit, 2.6F, RANDOM);
		return hit.point();
	}

	/**
	 * Raw cut faces for a piece hacked out of a body, on each face of {@code cube} set in {@code rawFaces} (bit
	 * {@code axis * 2}, plus one for the positive side): raw meat across the whole face, {@code depth} deep (1 shows a
	 * knob of bone in it, less is meat through and through).
	 */
	public static WoundSet cutFaces(ModelPart piece, ModelPart.Cube cube, Flesh flesh, int blood, int rawFaces, float depth) {
		WoundSet set = new WoundSet(piece, flesh, blood);
		float[] min = {cube.minX, cube.minY, cube.minZ};
		float[] max = {cube.maxX, cube.maxY, cube.maxZ};
		for (int bit = 0; bit < 6; bit++) {
			if ((rawFaces & 1 << bit) == 0) {
				continue;
			}

			int axis = bit / 2;
			int sign = (bit & 1) != 0 ? 1 : -1;
			int b = (axis + 1) % 3;
			int c = (axis + 2) % 3;
			org.joml.Vector3f local = new org.joml.Vector3f();
			local.setComponent(axis, sign > 0 ? max[axis] : min[axis]);
			local.setComponent(b, (min[b] + max[b]) * 0.5F);
			local.setComponent(c, (min[c] + max[c]) * 0.5F);
			set.cutFace(new ModelHit.Result(piece, cube, axis, sign, local, null, Vec3.ZERO, Vec3.ZERO, 0.0), depth, RANDOM);
		}

		return set;
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
			if (!Float.isNaN(set.ox[i]) && set.morph[i] == WoundSet.STICK && set.severity[i] >= 0.25F) {
				// A cut throat is where it bleeds out, whatever else it has.
				best = i;
				break;
			}

			if (!Float.isNaN(set.ox[i]) && set.severity[i] >= deepest) {
				deepest = set.severity[i];
				best = i;
			}
		}

		return best < 0 ? null : base.add(new Vec3(set.ox[best], set.oy[best], set.oz[best]).yRot((set.yaw[best] - yaw) * Mth.DEG_TO_RAD));
	}

	/** The front underside of the head as posed now, or null if the model has no head (or it is gone). */
	public static @Nullable Vec3 mouth(LivingEntity entity) {
		ModelParts.Posed posed = ModelParts.pose(entity);
		Vec3 head = posed == null ? null : ModelHit.centre(entity, posed, BodyPart.HEAD);
		if (head == null || (GoreSystems.dismemberment().missingMask(entity.getId()) & BodyPart.HEAD.bit()) != 0) {
			return null;
		}

		float yaw = posed.state().bodyRot * Mth.DEG_TO_RAD;
		double reach = entity.getBbWidth() * 0.25;
		return head.add(-Mth.sin(yaw) * reach, -entity.getBbHeight() * 0.08, Mth.cos(yaw) * reach);
	}

	/**
	 * {@code entityId} got {@code fraction} of its health back (natural regeneration, food, potions): its wounds close
	 * that much, and a whole bar of health heals the deepest wound there is. Back to full health, nothing is left.
	 */
	public void heal(int entityId, float fraction, boolean full) {
		WoundSet set = sets.get(entityId);
		if (set == null || !GoreConfig.get().woundsHeal) {
			return;
		}

		if (set.heal(full ? 2.0F : fraction * HEAL_RATE)) {
			sets.remove(entityId);
		}
	}

	/** Puts the wounds a body had before this player was there on it (see {@code net/Evidence}). */
	public void restore(int entityId, WoundSet set) {
		sets.put(entityId, set);
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
