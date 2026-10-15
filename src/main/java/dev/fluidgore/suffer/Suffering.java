package dev.fluidgore.suffer;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.dismember.GorePart;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.net.GoreSync;
import dev.fluidgore.trigger.EntityFilter;
import dev.fluidgore.trigger.Fate;
import dev.fluidgore.wound.Weapon;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Animal Suffering: screams, flinching, trembling, limping, herd panic and death throes for farm animals.
 *
 * <p>Everything here is client-side and visual. A client cannot change how an animal really moves on a server
 * (vanilla animals already run away when hit), so "acting insane" is done through the render pose: shaking, head
 * thrashing, leg kicking, sagging and tilting. The exception is the dead body: once the server removes the
 * entity, the body is ours, so it can go down and die out on the ground the way a real animal does (see Deaths).
 */
public final class Suffering {
	private static final double ACTIVE_RANGE_SQ = 48.0 * 48.0;
	private static final int TERROR_MEMORY = 600;
	private static final double TERROR_RADIUS_SQ = 14.0 * 14.0;
	private static final int MAX_CORPSES = 12;
	/** Ticks a dead body the server keeps is on screen with no corpse before it is taken for one already lying there. */
	private static final int ADOPT_AFTER = 5;
	/** How much longer a beaten or burnt body lies than an ordinary kill, on High and Extreme. */
	private static final float EVIDENCE_LINGER = 3.0F;
	/** Survivors notice a body, and a mother her young, from this far. */
	private static final double MOURN_RADIUS = 9.0;
	/** Predators smell a kill from this far. */
	private static final double SCENT_RADIUS = 18.0;
	/** Per tick, how fast an animal species forgets what it has seen; remembered dread falls to nothing in minutes. */
	private static final float DREAD_FADE = 0.00002F;
	private static final DustParticleOptions FLY = new DustParticleOptions(0x16130F, 0.3F);

	/** Per-animal memory: how it has been treated and how scared it is. */
	private static final class State {
		Voice voice;
		int hits;
		int beatenHits;
		int firstHitTick = -1;
		int lastSeenTick;
		float fear;
		int thrashTicks;
		int nextWhimper;
		int nextBellow;
		int nextCall;
		/** One more hit would finish it: the last health is draining and it is already on its way down. */
		boolean dying;
		/** Next grieving call, for an animal whose young or mother was killed. */
		int nextGrief;
		/** Its young is being hurt: where, and until when it stays alarmed. */
		@Nullable Vec3 alarmAt;
		int alarmUntil;
	}

	/** Something terrible happened here: makes nearby animals of the same kind panic for a while. */
	private record Terror(Vec3 pos, EntityType<?> type, float weight, int tick) {
	}

	private final ScreamManager screams = new ScreamManager();
	private final Int2ObjectOpenHashMap<State> states = new Int2ObjectOpenHashMap<>();
	private final Int2ObjectOpenHashMap<Corpse> corpses = new Int2ObjectOpenHashMap<>();
	private final Int2ObjectOpenHashMap<SufferPose> poses = new Int2ObjectOpenHashMap<>();
	private final List<Terror> terror = new ArrayList<>();
	/** What each species has seen in this world: raised by every killing, fading slowly. */
	private final Object2FloatOpenHashMap<EntityType<?>> dread = new Object2FloatOpenHashMap<>();
	private final Bereavement bereavement = new Bereavement();
	private final RandomSource random = Fate.stream("suffering");
	private final Deaths deaths = new Deaths(screams, random);
	private final Deaths.Posture posture = new Deaths.Posture();
	/** Set by tests and demos: how the next animal to die will die, and whether it bursts at the end. */
	private boolean forcing;
	private Corpse.@Nullable Kind forcedKind;
	private boolean forcedPop;
	private List<String> cachedAnimalSource;
	private final Set<Identifier> animals = new HashSet<>();
	private int tick;

	public ScreamManager screams() {
		return screams;
	}

	public Collection<Corpse> corpses() {
		return corpses.values();
	}

	public @Nullable Corpse corpse(int entityId) {
		return corpses.get(entityId);
	}

	/** True while this animal grieves for its killed young (or its killed mother). */
	public boolean grieving(int entityId) {
		return bereavement.of(entityId) != null;
	}

	public float fear(int entityId) {
		State state = states.get(entityId);
		return state == null ? 0.0F : state.fear;
	}

	/** Pose for this frame: the corpse's if it has died, otherwise the living animal's (null = untouched). */
	public @Nullable SufferPose pose(int entityId) {
		Corpse corpse = corpses.get(entityId);
		return corpse != null ? corpse.pose() : poses.get(entityId);
	}

	public boolean applies(LivingEntity entity) {
		GoreConfig config = GoreConfig.get();
		if (!config.sufferingLevel.enabled() || EntityFilter.isBloodless(entity)) {
			return false;
		}

		if (config.sufferingAnimals != cachedAnimalSource) {
			cachedAnimalSource = config.sufferingAnimals;
			animals.clear();
			for (String entry : config.sufferingAnimals) {
				Identifier id = Identifier.tryParse(entry.trim());
				if (id != null) {
					animals.add(id);
				}
			}
		}

		return animals.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
	}

	static float intensity() {
		return GoreConfig.get().sufferingLevel.duration;
	}

	private State state(LivingEntity entity) {
		State state = states.get(entity.getId());
		if (state == null) {
			state = new State();
			state.voice = Voice.of(entity);
			state.nextWhimper = tick + 20 + random.nextInt(40);
			state.nextBellow = tick + random.nextInt(40);
			states.put(entity.getId(), state);
		}

		state.lastSeenTick = tick;
		return state;
	}

	/**
	 * Called for every hit on a suffering animal.
	 *
	 * @param bareHanded the attacker hit with an empty hand (beating an animal to death makes for the worst death)
	 * @param strike unit vector from the attacker towards the victim, or null if unknown; the body is thrown along it
	 * @param blast  how hard an explosion hit the body (0..1), 0 if it was not one
	 * @param weapon what struck it, null for nothing (a fall, fire)
	 * @param zone   where on the body the blow landed
	 * @param fall   it was hurt by falling
	 */
	public void onHurt(ClientLevel level, LivingEntity entity, float damage, boolean lethal, boolean bareHanded, @Nullable Vec3 strike,
			float blast, @Nullable Weapon weapon, Deaths.Zone zone, boolean fall) {
		State state = state(entity);
		state.hits++;
		if (bareHanded) {
			state.beatenHits++;
		}

		if (state.firstHitTick < 0) {
			state.firstHitTick = tick;
		}

		state.thrashTicks = 14;
		// Killing the young weighs on the herd far longer than killing a grown animal.
		boolean young = entity.isBaby();
		terror.add(new Terror(entity.position(), entity.getType(), lethal ? (young ? 5.0F : 3.0F) : 1.0F, tick));
		dread.put(entity.getType(), Math.min(1.0F, dread.getFloat(entity.getType()) + (lethal ? (young ? 0.25F : 0.1F) : 0.01F)));

		boolean headless = (GoreSystems.dismemberment().missingMask(entity.getId()) & BodyPart.HEAD.bit()) != 0;
		if (young && !headless && GoreConfig.get().ecology) {
			cryForMother(level, entity, state);
		}

		if (!lethal) {
			// The second-to-last blow: from here on it staggers and bleeds its way down.
			state.dying = entity.getHealth() <= Math.max(damage, entity.getMaxHealth() * 0.25F);
			if (!headless) {
				screams.pain(follow(entity), state.voice, intensity(), state.fear);
			}

			return;
		}

		startDeath(level, entity, state, damage, headless, strike, blast, weapon, zone, fall);
	}

	/** A young animal is hurt: it cries for its mother, and she answers, turns to it and is beside herself. */
	private void cryForMother(ClientLevel level, LivingEntity young, State state) {
		screams.call(follow(young), state.voice, true, 1.0F);
		LivingEntity mother = Bereavement.kinOf(level, young);
		if (mother != null) {
			State hers = state(mother);
			hers.fear = Math.max(hers.fear, 0.8F);
			hers.alarmAt = young.position();
			hers.alarmUntil = tick + 120;
			screams.call(follow(mother), hers.voice, false, 1.0F, 10 + random.nextInt(6));
		}
	}

	private void startDeath(ClientLevel level, LivingEntity entity, State state, float lastBlow, boolean headless, @Nullable Vec3 strike,
			float blast, @Nullable Weapon weapon, Deaths.Zone zone, boolean fall) {
		if (corpses.containsKey(entity.getId())) {
			return;
		}

		if (GoreSystems.dismemberment().vaporized(entity.getId())) {
			// Torn apart at once: no body, no throes, no cry. Only those left behind.
			mourn(level, entity);
			return;
		}

		float intensity = intensity();
		float blowShare = lastBlow / Math.max(1.0F, entity.getMaxHealth());
		Corpse.Kind forced = forcedKind;
		boolean pops = forcing ? forcedPop : random.nextFloat() < Deaths.popChance();
		forcing = false;
		forcedKind = null;
		boolean burnt = GoreSystems.dismemberment().burns().burnt(entity);
		Corpse.Kind kind = forced != null ? forced
				: deaths.choose(weapon, zone, fall, headless, burnt, blast, state.hits, state.beatenHits, blowShare, ModelParts.legs(entity.getId()).four());

		// On High and Extreme a body that was beaten or burnt to death lies there a good while: the evidence of what was
		// done to it. A small body is left lying longer too.
		float evidence = kind == Corpse.Kind.BEATEN || burnt ? EVIDENCE_LINGER : 1.0F;
		// A small body, and a horse, are left lying longer.
		float size = entity.isBaby() || entity.getType() == EntityTypes.HORSE ? 1.5F : 1.0F;
		int linger = Math.round(GoreConfig.get().corpseLingerSeconds * 20 * size * GoreConfig.get().goreLevel.linger
				* (GoreConfig.get().goreLevel.linger > 1.0F ? evidence : 1.0F));
		// The vanilla death goes over at once; the death throes give the legs a moment first.
		boolean vanilla = !GoreConfig.get().deathThroes;
		Corpse corpse = new Corpse(entity, kind, state.voice, vanilla ? 0 : deaths.collapse(kind), linger, random.nextFloat() * 100.0F);
		corpse.vanilla = vanilla;
		corpse.held = GoreSync.serverKeepsBodies();
		corpse.pops = pops;
		deaths.plan(corpse);
		if (vanilla) {
			Deaths.vanillaFall(corpse, weapon, strike);
			// Red until it is down, as in vanilla; an axe holds it on its feet a moment longer.
			corpse.throeTicks = Corpse.VANILLA_TICKS + corpse.fallDelay;
		}

		corpses.put(entity.getId(), corpse);
		evictOldCorpses();
		boolean gore = GoreConfig.get().goreLevel.enabled() && !EntityFilter.isBloodless(entity);
		deaths.begin(corpse, EntityFilter.bloodColor(entity), gore);

		// The killing blow throws the body the way it was struck, harder the bigger the share of its health.
		int launch = GoreConfig.get().deathLaunchPercent;
		if (launch > 0 && strike != null) {
			float share = Math.min(1.0F, blowShare);
			// A blast throws a body far harder than any blow: up and out, over walls and out of pits.
			double speed = blast > 0.0F ? 0.15 + 0.85 * blast : 0.1 + 0.3 * share;
			corpse.body.launch(strike, speed * launch / 100.0);
		}

		float cry = Deaths.cry(kind);
		if (Deaths.horse(corpse) && cry >= 0.0F) {
			// A horse screams.
			cry = Math.max(cry, 0.5F);
		}

		if (cry >= 0.0F) {
			screams.death(follow(entity), state.voice, intensity, cry);
		}

		mourn(level, entity);
	}

	/**
	 * A body the server kept, seen here for the first time (it died out of sight, or this player came back to it):
	 * already lying on its side, still. What was done to it before is not known here.
	 */
	private void adopt(LivingEntity entity) {
		Corpse corpse = new Corpse(entity, Corpse.Kind.EXSANGUINATION, state(entity).voice, 0, 0, random.nextFloat() * 100.0F);
		corpse.vanilla = true;
		corpse.held = true;
		deaths.plan(corpse);
		corpse.throeTicks = 0;
		corpse.heartEnd = 0;
		corpse.voiceEnd = 0;
		corpse.toppled = true;
		corpse.landed = true;
		corpse.body.roll = corpse.body.proll = Mth.HALF_PI;
		// Long dead: past the red of dying and the first moments.
		corpse.age = Corpse.KEEP_GRACE;
		corpses.put(entity.getId(), corpse);
	}

	/** For tests and demos: the next animal to die dies this way (null: as it would), and bursts at the end or not. */
	public void forceNextDeath(Corpse.@Nullable Kind kind, boolean pop) {
		forcing = true;
		forcedKind = kind;
		forcedPop = pop;
	}

	/** A body was struck while it dies: the death answers in its own way. */
	public void struck(ClientLevel level, Corpse corpse, Vec3 look) {
		deaths.struck(corpse);
	}

	/** {@code entity} has been killed: its mother (or its young) starts to grieve. */
	private void mourn(ClientLevel level, LivingEntity entity) {
		GoreConfig config = GoreConfig.get();
		if (config.ecology) {
			int grief = Math.round(config.griefSeconds * 20 * config.sufferingLevel.duration);
			LivingEntity kin = bereavement.onDeath(level, entity, tick, grief);
			if (kin != null) {
				// A moment of silence, then the first call.
				state(kin).nextGrief = tick + 30 + random.nextInt(20);
			}
		}
	}

	/** Bodies only this client keeps are capped; the ones the server keeps are capped by the server. */
	private void evictOldCorpses() {
		while (true) {
			int loose = 0;
			int oldest = -1;
			int oldestAge = -1;
			for (Int2ObjectMap.Entry<Corpse> entry : corpses.int2ObjectEntrySet()) {
				if (entry.getValue().kept()) {
					continue;
				}

				loose++;
				if (entry.getValue().age > oldestAge) {
					oldestAge = entry.getValue().age;
					oldest = entry.getIntKey();
				}
			}

			if (loose <= MAX_CORPSES) {
				return;
			}

			finishCorpse(corpses.remove(oldest));
		}
	}

	public void tick(ClientLevel level) {
		tick++;
		screams.tick();
		bereavement.tick(tick);
		terror.removeIf(t -> tick - t.tick() > TERROR_MEMORY);
		dread.replaceAll((type, value) -> Math.max(0.0F, value - DREAD_FADE));
		poses.clear();

		Entity camera = Minecraft.getInstance().getCameraEntity();
		boolean kept = GoreSync.serverKeepsBodies();
		if (camera != null) {
			for (Entity entity : level.entitiesForRendering()) {
				if (!(entity instanceof LivingEntity living) || corpses.containsKey(living.getId())) {
					continue;
				}

				if (living.isAlive()) {
					if (camera.distanceToSqr(living) < ACTIVE_RANGE_SQ && applies(living)) {
						tickLiving(level, living);
					}
				} else if (kept && living.deathTime >= ADOPT_AFTER && !living.isRemoved() && applies(living)
						&& !GoreSystems.dismemberment().vaporized(living.getId())) {
					adopt(living);
				}
			}
		}

		Iterator<Corpse> it = corpses.values().iterator();
		while (it.hasNext()) {
			Corpse corpse = it.next();
			tickCorpse(level, corpse);
			if (corpse.done()) {
				it.remove();
				finishCorpse(corpse);
			}
		}

		tickSeveredHeads();
		states.int2ObjectEntrySet().removeIf(e -> tick - e.getValue().lastSeenTick > 200 && !corpses.containsKey(e.getIntKey()));
	}

	private void tickLiving(ClientLevel level, LivingEntity entity) {
		GoreConfig config = GoreConfig.get();
		float intensity = intensity();
		int mask = GoreSystems.dismemberment().missingMask(entity.getId());
		boolean headless = (mask & BodyPart.HEAD.bit()) != 0;
		boolean wounded = entity.getHealth() < entity.getMaxHealth() * 0.3F;
		boolean ecology = config.ecology;
		float panic = config.herdPanic ? terrorAround(entity) : 0.0F;
		float memory = ecology ? dread.getFloat(entity.getType()) : 0.0F;
		Corpse near = ecology && !corpses.isEmpty() ? nearestCorpse(entity) : null;

		State state = states.get(entity.getId());
		Bereavement.Grief grief = ecology ? bereavement.of(entity.getId()) : null;
		if (state == null && !wounded && mask == 0 && panic < 0.01F && memory < 0.12F && near == null && grief == null) {
			return;
		}

		state = state(entity);
		if (state.dying && entity.getHealth() > entity.getMaxHealth() * 0.5F) {
			// It pulled through: healed past half its health, it stops staggering and bleeding its way down.
			state.dying = false;
		}

		Personality self = state.voice.self();
		boolean predator = ecology && self.role() == Personality.Role.PREDATOR;
		boolean kin = near != null && near.entity.getType() == entity.getType() && near.entity.isBaby() != entity.isBaby();

		// Predators are drawn to a kill, not frightened by it; a mother whose young lies dead is beside herself.
		float target = predator ? 0.0F : Math.max(panic, memory * 0.55F);
		float sorrow = grief != null ? grief.strength(tick) : 0.0F;
		boolean alarmed = state.alarmAt != null && tick < state.alarmUntil;
		if (grief != null) {
			target = Math.max(target, 0.35F + 0.5F * sorrow);
		}

		if (alarmed) {
			target = Math.max(target, 0.8F);
		}

		if (kin && !entity.isBaby()) {
			target = Math.max(target, 0.8F);
		}

		if (wounded) {
			target = Math.max(target, 0.7F);
		}

		state.fear += (target - state.fear) * Math.min(0.5F, 0.05F * self.panic() * (1.0F + memory));
		if (state.thrashTicks > 0) {
			state.thrashTicks--;
		}

		// Pose: shaking from fear and pain, head thrown around after a hit, legs kicking.
		float thrash = state.thrashTicks / 14.0F + state.fear * 0.25F + (headless ? 0.5F : 0.0F);
		float tremble = Math.min(1.0F, (state.fear * 0.6F + (wounded ? 0.5F : 0.0F) + (headless ? 1.0F : 0.0F)) * self.shake()) * Math.min(1.5F, intensity);
		float kick = Math.min(1.0F, state.thrashTicks / 14.0F * 0.7F + (headless ? 0.5F : 0.0F));

		// Missing legs: the body sags and tips towards the missing side and staggers as it moves.
		float tiltX = 0.0F;
		float tiltZ = 0.0F;
		int legsMissing = 0;
		if ((mask & BodyPart.RIGHT_ARM.bit()) != 0) { tiltX += 14.0F; tiltZ += 9.0F; legsMissing++; }
		if ((mask & BodyPart.LEFT_ARM.bit()) != 0) { tiltX += 14.0F; tiltZ -= 9.0F; legsMissing++; }
		if ((mask & BodyPart.RIGHT_LEG.bit()) != 0) { tiltX -= 10.0F; tiltZ += 9.0F; legsMissing++; }
		if ((mask & BodyPart.LEFT_LEG.bit()) != 0) { tiltX -= 10.0F; tiltZ -= 9.0F; legsMissing++; }
		float drop = Math.min(0.3F, legsMissing * 0.1F);
		float stagger = legsMissing > 0 ? 6.0F : (wounded ? 2.0F : 0.0F);

		// Dying: the nearer to death, the more it sags, staggers and shakes, and the more it bleeds as it goes.
		float dyingness = wounded ? Mth.clamp(1.0F - entity.getHealth() / (entity.getMaxHealth() * 0.3F), 0.0F, 1.0F) : 0.0F;
		if (state.dying) {
			dyingness = Math.max(dyingness, 0.7F);
		}

		if (dyingness > 0.0F) {
			drop += 0.14F * dyingness;
			tiltX += 8.0F * dyingness;
			stagger = Math.max(stagger, 2.0F + 6.0F * dyingness);
			tremble = Math.min(1.0F, tremble + 0.4F * dyingness);
			// A body dying of burns is seared shut and does not drip.
			if (dyingness > 0.4F && tick % 6 == 0 && config.goreLevel.enabled() && !headless && !GoreSystems.dismemberment().burns().burnt(entity)) {
				BloodEmitter.drip(GoreSystems.blood(), new Vec3(entity.getX(), entity.getY() + 0.3, entity.getZ()), EntityFilter.bloodColor(entity), 0.02F);
			}
		}

		// Where it looks: survivors cower with their heads down and stand over the body; a predator lifts its head
		// and turns towards the blood.
		float headDip = 0.0F;
		float lookYaw = 0.0F;
		if (predator) {
			Vec3 smell = near == null ? nearestTerror(entity) : null;
			if (near != null || smell != null) {
				lookYaw = near != null ? lookToward(entity, near.body.x, near.body.z) : lookToward(entity, smell.x, smell.z);
				headDip = -0.3F;
				tiltX += 4.0F;
				drop += 0.04F;
			}
		} else if (ecology) {
			headDip = 0.5F * state.fear;
			tiltX -= 4.0F * state.fear;
			if (near != null && near.groundTicks() > 20) {
				double dist = Math.sqrt(entity.distanceToSqr(near.body.x, near.body.y, near.body.z));
				lookYaw = lookToward(entity, near.body.x, near.body.z);
				headDip = Math.max(headDip, 0.6F * (float) (1.0 - dist / MOURN_RADIUS));
				tremble = Math.max(tremble, 0.15F * self.shake());
			}
		}

		// Its young being hurt: head up, turned to the cries. Grieving: head hung, turned to where it happened.
		Vec3 toward = alarmed ? state.alarmAt : grief != null ? grief.where() : null;
		if (toward != null && !predator && entity.distanceToSqr(toward) < 40.0 * 40.0 && near == null) {
			lookYaw = lookToward(entity, toward.x, toward.z);
			headDip = alarmed ? -0.25F : Math.max(headDip, 0.6F * sorrow);
			tremble = Math.max(tremble, (alarmed ? 0.5F : 0.3F * sorrow) * self.shake());
		}

		SufferPose pose = new SufferPose(tremble, Math.min(1.0F, thrash), kick, 0.9F, tiltX, tiltZ, drop, stagger, headDip, lookYaw);
		if (!pose.isIdle()) {
			poses.put(entity.getId(), pose);
		}

		if (headless) {
			// No head, no screams. The neck pumps blood with every heartbeat.
			if (tick % 6 == 0 && config.goreLevel.enabled()) {
				BloodEmitter.burst(GoreSystems.blood(), level, neck(entity), EntityFilter.bloodColor(entity), 0.03F, 3);
			}

			return;
		}

		if ((wounded || state.dying) && tick >= state.nextWhimper) {
			screams.whimper(follow(entity), state.voice);
			state.nextWhimper = tick + Math.round((40 + random.nextInt(60)) / intensity);
		}

		if (grief != null && tick >= state.nextGrief && state.nextGrief > 0) {
			// Calls to the place it happened, again and again, further apart and quieter as the grief wears on.
			screams.grieve(follow(entity), state.voice, sorrow, grief.orphan());
			state.nextGrief = tick + Math.round((70 + random.nextInt(90)) / Math.max(0.12F, sorrow));
		} else if (grief == null && kin && near.groundTicks() > 20 && tick >= state.nextCall) {
			screams.call(follow(entity), state.voice, entity.isBaby(), state.fear);
			state.nextCall = tick + 50 + random.nextInt(50);
		}

		if (state.fear > 0.25F && config.herdPanic && !predator && tick >= state.nextBellow) {
			screams.bellow(follow(entity), state.voice, state.fear);
			float herd = self.role() == Personality.Role.HERD ? 0.7F : 1.0F;
			state.nextBellow = tick + Math.round(herd * (60 + random.nextInt(120)) / (state.fear * intensity));
		}
	}

	/** The nearest body of a dead animal that has finished falling, within {@link #MOURN_RADIUS}, or null. */
	private @Nullable Corpse nearestCorpse(LivingEntity entity) {
		Corpse best = null;
		double bestDist = MOURN_RADIUS * MOURN_RADIUS;
		for (Corpse corpse : corpses.values()) {
			double dist = entity.distanceToSqr(corpse.body.x, corpse.body.y, corpse.body.z);
			if (corpse.entity != entity && corpse.groundTicks() >= 0 && dist < bestDist) {
				bestDist = dist;
				best = corpse;
			}
		}

		return best;
	}

	/** Where the nearest recent slaughter happened, or null if none is close. */
	private @Nullable Vec3 nearestTerror(LivingEntity entity) {
		Vec3 best = null;
		double bestDist = SCENT_RADIUS * SCENT_RADIUS;
		for (Terror t : terror) {
			double dist = t.pos().distanceToSqr(entity.position());
			if (dist < bestDist) {
				bestDist = dist;
				best = t.pos();
			}
		}

		return best;
	}

	/** Head yaw offset in radians that turns an animal's head from its body heading to a point, within what a neck allows. */
	private static float lookToward(LivingEntity entity, double x, double z) {
		float wanted = (float) (Mth.atan2(entity.getX() - x, z - entity.getZ()) * Mth.RAD_TO_DEG);
		return Mth.clamp(Mth.wrapDegrees(wanted - entity.yBodyRot) * Mth.DEG_TO_RAD, -1.1F, 1.1F);
	}

	/** Fear from recent slaughter nearby: same species counts fully, other animals half. */
	private float terrorAround(LivingEntity entity) {
		float sum = 0.0F;
		for (Terror t : terror) {
			if (t.pos().distanceToSqr(entity.position()) > TERROR_RADIUS_SQ) {
				continue;
			}

			float freshness = 1.0F - (tick - t.tick()) / (float) TERROR_MEMORY;
			sum += t.weight() * freshness * (t.type() == entity.getType() ? 1.0F : 0.5F);
		}

		return Math.min(1.0F, sum / 3.0F);
	}

	private void tickCorpse(ClientLevel level, Corpse c) {
		c.age++;
		if (c.flash > 0) {
			c.flash--;
		}

		Ragdoll body = c.body;
		body.beginTick();
		LivingEntity entity = c.entity;
		GoreConfig config = GoreConfig.get();
		boolean gore = config.goreLevel.enabled() && !EntityFilter.isBloodless(entity);
		int color = EntityFilter.bloodColor(entity);

		// The collision box follows the body, not the entity: until the server removes it, the entity still sits
		// wherever the server last put it.
		AABB box = entity.getBoundingBox().move(body.x - entity.getX(), body.y - entity.getY(), body.z - entity.getZ());
		body.move(level, box, 0.0);
		if (c.age >= c.collapseAt && !c.toppled) {
			c.toppled = true;
			if (!c.vanilla) {
				topple(c);
			}
		}

		shove(c, box);
		float landing = !c.toppled ? 0.0F : c.vanilla ? vanillaTip(c) : body.tip();
		if (landing > 0.0F) {
			slam(level, c, landing, color, gore);
		}

		Deaths.Posture p = posture.reset();
		deaths.tick(level, c, p, color, gore);
		if (config.ecology && c.lifeless()) {
			attractFlies(level, c, c.groundTicks() - c.throeTicks);
		}

		body.stepJoints();
		c.pose = p.build();
		if (c.unloaded) {
			// Keep the entity (culling, shadow, bounding box) with the body; no offset is then needed to draw it.
			entity.xo = body.px;
			entity.yo = body.py;
			entity.zo = body.pz;
			entity.setPos(body.x, body.y, body.z);
			entity.setOnGround(body.onGround);
		}
	}

	/**
	 * Flies gather over a body that has finished dying and keep coming the longer it lies there, and the more bodies
	 * lie near it. The drone is the scavengers' cue.
	 */
	private void attractFlies(ClientLevel level, Corpse c, int restTicks) {
		int neighbours = 0;
		for (Corpse other : corpses.values()) {
			if (other != c && other.groundTicks() >= 0 && other.body.distanceToSqr(c.body) < 36.0) {
				neighbours++;
			}
		}

		float density = Mth.clamp(0.1F + restTicks / 20.0F * 0.02F + neighbours * 0.1F, 0.0F, 1.0F);
		Vec3 middle = centre(c);
		if (c.random.nextFloat() < density * 0.5F) {
			double angle = c.random.nextDouble() * Mth.TWO_PI;
			double radius = 0.3 + c.random.nextDouble() * 0.5;
			level.addParticle(FLY, middle.x + Math.cos(angle) * radius, middle.y + c.random.nextDouble() * 0.5, middle.z + Math.sin(angle) * radius,
					-Math.sin(angle) * 0.15, 0.0, Math.cos(angle) * 0.15);
		}

		if (c.age >= c.nextFly) {
			screams.flies(() -> middle, density);
			c.nextFly = c.age + 50 + c.random.nextInt(30);
		}
	}

	/** The middle of a ragdolled body: at half height when upright, near the ground once it lies on its side. */
	static Vec3 centre(Corpse c) {
		Ragdoll b = c.body;
		return new Vec3(b.x, b.y + c.entity.getBbHeight() * (0.15 + 0.4 * Math.abs(Mth.cos(b.roll))), b.z);
	}

	/** The body hits the ground on its side: a heavy thud, a jolt of blood out of it, and the legs kick with it. */
	private void slam(ClientLevel level, Corpse c, float speed, int color, boolean gore) {
		Vec3 middle = centre(c);
		float heavy = 1.0F / c.body.heft;
		float volume = Math.min(1.0F, 0.4F + speed * 4.0F);
		if (c.random.nextInt(4) == 0 || Deaths.horse(c)) {
			// The hard crunch of a fall, one fall in four; every time for half a ton of horse.
			level.playLocalSound(middle.x, middle.y, middle.z, SoundEvents.GENERIC_BIG_FALL, SoundSource.NEUTRAL, volume * 0.8F,
					0.75F + 0.25F / heavy + c.random.nextFloat() * 0.1F, false);
		} else {
			// Otherwise a dull, heavy thud: meat and bone going down on the ground.
			level.playLocalSound(middle.x, middle.y, middle.z, SoundEvents.MUD_BREAK, SoundSource.NEUTRAL, volume * 0.7F,
					0.45F + 0.2F / heavy + c.random.nextFloat() * 0.1F, false);
			level.playLocalSound(middle.x, middle.y, middle.z, SoundEvents.WOOL_FALL, SoundSource.NEUTRAL, volume * 0.6F,
					0.6F + c.random.nextFloat() * 0.1F, false);
		}

		if (gore && !EntityFilter.isBloodless(c.entity)) {
			BloodEmitter.burst(GoreSystems.blood(), level, middle, color, 0.02F + speed * 0.2F, 4 + Math.round(speed * 40.0F));
			BloodEmitter.drip(GoreSystems.blood(), neck(c), color, 0.02F);
		}

		c.body.spasm(c.random, 0.4F);
	}

	/** Walking into a body that is down shoves it along and jostles its limbs. */
	private void shove(Corpse c, AABB box) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || !c.toppled || !player.getBoundingBox().intersects(box)) {
			return;
		}

		Vec3 motion = player.getDeltaMovement();
		if (motion.horizontalDistanceSqr() < 0.0025) {
			return;
		}

		Ragdoll body = c.body;
		body.vx += motion.x * 0.5 * body.heft;
		body.vz += motion.z * 0.5 * body.heft;
		if (c.random.nextFloat() < 0.3F) {
			body.spasm(c.random, 0.2F);
		}

		c.keepFor(40);
	}

	/**
	 * Vanilla's tip-over, to the frame: fast at first, flat on the ground after about 13 ticks, onto the side away from
	 * the blow, after the axe's hold and turning with the sword's swing (see {@link Deaths#vanillaFall}). Blows do not
	 * roll it after that; it lies as it fell. Returns the landing speed on the tick it lands, else 0.
	 */
	private static float vanillaTip(Corpse c) {
		Ragdoll body = c.body;
		float f = vanillaCurve(c.age - c.fallDelay);
		body.yaw += c.fallTwist * (f - vanillaCurve(c.age - 1 - c.fallDelay));
		body.roll = c.fallSide * f * Mth.HALF_PI;
		body.rollV = 0.0F;
		if (f < 1.0F || c.landed) {
			return 0.0F;
		}

		c.landed = true;
		return 0.25F;
	}

	/** How far over vanilla's dying mob is, 0..1, {@code ticks} after it died. */
	private static float vanillaCurve(int ticks) {
		return Math.min(1.0F, Mth.sqrt(Math.max(0.0F, ticks - 1.0F) / Corpse.VANILLA_TICKS * 1.6F));
	}

	/** Starts the fall: the push comes from the body's own momentum, so a hit knocks it over away from the blow. */
	private void topple(Corpse c) {
		Ragdoll body = c.body;
		float yawRad = body.yaw * Mth.DEG_TO_RAD;
		// Positive lean tips towards (cos yaw, sin yaw): the body's own right-hand side.
		double lateral = body.vx * Mth.cos(yawRad) + body.vz * Mth.sin(yawRad);
		float side = Math.abs(lateral) > 0.01 ? (float) Math.signum(lateral) : (c.random.nextBoolean() ? 1.0F : -1.0F);
		float forward = (float) Math.hypot(body.vx, body.vz);
		// A firm push, so it keels over about as fast as vanilla's tip-over instead of teetering.
		body.topple(side, 0.05F + Math.min(0.2F, forward * 0.5F + (float) Math.abs(lateral)));
		body.spasm(c.random, c.kind == Corpse.Kind.TRAUMA ? 0.2F : 0.4F);
	}

	/** Severed heads twitch (rendered by GoreRenderer) and let out a few weak moans. */
	private void tickSeveredHeads() {
		for (GorePart part : GoreSystems.dismemberment().parts().parts()) {
			if (part.voice == null || part.age >= part.twitchTicks) {
				continue;
			}

			float strength = 1.0F - part.age / (float) part.twitchTicks;
			if (random.nextFloat() < 0.012F * strength + 0.002F) {
				screams.moan(() -> new Vec3(part.x, part.y, part.z), part.voice, strength);
			}
		}
	}

	private void finishCorpse(Corpse corpse) {
		if (corpse == null) {
			return;
		}

		LivingEntity entity = corpse.entity;
		if (corpse.unloaded) {
			// The vanilla "poof" was held back while the body lay there; play it now that it disappears. A body already torn or
			// butchered apart has nothing left to go up in smoke.
			if (entity.level() instanceof ClientLevel level && !GoreSystems.dismemberment().vaporized(entity.getId())) {
				for (int i = 0; i < 20; i++) {
					level.addParticle(ParticleTypes.POOF,
							entity.getRandomX(1.0), entity.getRandomY(), entity.getRandomZ(1.0),
							random.nextGaussian() * 0.02, random.nextGaussian() * 0.02, random.nextGaussian() * 0.02);
				}

				GoreConfig config = GoreConfig.get();
				if (corpse.pops && !corpse.kept() && config.goreLevel.enabled() && !EntityFilter.isBloodless(entity)) {
					Ragdoll body = corpse.body;
					// The body's own dice: it bursts the same way on every client.
					Fate.begin(corpse.random.nextLong());
					try {
						GoreSystems.dismemberment().pop(GoreSystems.blood(), level, entity, new Vec3(body.x, body.y, body.z), body.yaw, body.roll);
					} finally {
						Fate.end();
					}
				}
			}

			GoreSystems.dismemberment().onEntityUnload(entity.getId());
		}
	}

	/** The server removed this entity. Returns true if a corpse keeps it on screen a while longer. */
	public boolean onEntityUnload(int entityId) {
		Corpse corpse = corpses.get(entityId);
		if (corpse == null) {
			states.remove(entityId);
			return false;
		}

		if (!corpse.unloaded) {
			corpse.unloadedAt = corpse.age;
		}

		corpse.unloaded = true;
		return true;
	}

	public boolean holdsBody(int entityId) {
		return corpses.containsKey(entityId);
	}

	/**
	 * The body is cleaned away (a player used a cleaning item on it): a puff of smoke, as vanilla's death ends, and it
	 * is gone from this screen at once; the server removes the entity after. Returns false if there was no body.
	 */
	public boolean clean(ClientLevel level, int entityId) {
		Corpse corpse = corpses.remove(entityId);
		Dismemberment dismemberment = GoreSystems.dismemberment();
		boolean gone = dismemberment.vaporized(entityId);
		if (corpse == null) {
			dismemberment.hide(entityId);
			return !gone && level.getEntity(entityId) instanceof LivingEntity body && !body.isAlive();
		}

		if (!gone) {
			Vec3 at = centre(corpse);
			for (int i = 0; i < 20; i++) {
				level.addParticle(ParticleTypes.POOF, at.x + random.nextGaussian() * 0.3, at.y + random.nextDouble() * 0.4, at.z + random.nextGaussian() * 0.3,
						random.nextGaussian() * 0.02, random.nextGaussian() * 0.02, random.nextGaussian() * 0.02);
			}

			level.playLocalSound(at.x, at.y, at.z, SoundEvents.SPONGE_ABSORB, SoundSource.PLAYERS, 0.8F, 0.9F + random.nextFloat() * 0.2F, false);
		}

		if (corpse.unloaded) {
			dismemberment.onEntityUnload(entityId);
		} else {
			dismemberment.hide(entityId);
		}

		return true;
	}

	static Supplier<Vec3> follow(LivingEntity entity) {
		return () -> new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() * 0.6, entity.getZ());
	}

	/** The neck stump of a ragdolled body: high up when upright, near the ground once it lies on its side. */
	static Vec3 neck(Corpse c) {
		Ragdoll b = c.body;
		float yawRad = b.yaw * Mth.DEG_TO_RAD;
		double forward = c.entity.getBbWidth() * 0.45;
		double height = c.entity.getBbHeight() * (0.2 + 0.6 * Math.abs(Mth.cos(b.roll)));
		return new Vec3(b.x - Mth.sin(yawRad) * forward, b.y + height, b.z + Mth.cos(yawRad) * forward);
	}

	private static Vec3 neck(LivingEntity entity) {
		float yaw = entity.yBodyRot * Mth.DEG_TO_RAD;
		double forward = entity.getBbWidth() * 0.45;
		return new Vec3(entity.getX() - Mth.sin(yaw) * forward, entity.getY() + entity.getBbHeight() * 0.8, entity.getZ() + Mth.cos(yaw) * forward);
	}

	public void clear() {
		screams.clear();
		for (Corpse corpse : corpses.values()) {
			if (corpse.unloaded) {
				GoreSystems.dismemberment().onEntityUnload(corpse.entity.getId());
			}
		}

		corpses.clear();
		states.clear();
		poses.clear();
		terror.clear();
		dread.clear();
		bereavement.clear();
	}
}
