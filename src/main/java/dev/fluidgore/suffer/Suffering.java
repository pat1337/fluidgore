package dev.fluidgore.suffer;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.GorePart;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.trigger.DamageTracker;
import dev.fluidgore.trigger.EntityFilter;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * Animal Suffering: screams, flinching, trembling, limping, herd panic and death throes for farm animals.
 *
 * <p>Everything here is client-side and visual. A client cannot change how an animal really moves on a server
 * (vanilla animals already run away when hit), so "acting insane" is done through the render pose: shaking, head
 * thrashing, leg kicking, sagging and tilting. The exception is the dead body: once the server removes the
 * entity, the body is ours, so a beheaded animal can keep running and a beaten one can keep kicking.
 */
public final class Suffering {
	private static final double ACTIVE_RANGE_SQ = 48.0 * 48.0;
	private static final int TERROR_MEMORY = 600;
	private static final double TERROR_RADIUS_SQ = 14.0 * 14.0;
	private static final int MAX_CORPSES = 12;
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
	private final RandomSource random = RandomSource.create();
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
		Personality self = state.voice.self();
		boolean personal = GoreConfig.get().personality;
		int ordeal = tick - state.firstHitTick;
		float blowShare = lastBlow / Math.max(1.0F, entity.getMaxHealth());
		Corpse.Kind kind;
		Corpse.Kind forced = forcedKind;
		boolean pops = forcing ? forcedPop : random.nextFloat() < Deaths.popChance();
		forcing = false;
		forcedKind = null;
		boolean burnt = GoreSystems.dismemberment().burns().burnt(entity);
		if (forced != null) {
			kind = forced;
		} else if (blast >= DamageTracker.TEAR_IMPACT) {
			// Torn open by a blast close by: dead before it lands. A few kicks of the legs, nothing more.
			kind = Corpse.Kind.QUICK;
		} else if (headless) {
			kind = Corpse.Kind.HEADLESS_RUN;
		} else if (burnt) {
			// Burnt alive: it writhes and screams to the end.
			kind = Corpse.Kind.BRUTAL;
		} else if (pops) {
			kind = classic(state, self, personal, ordeal, blowShare);
		} else {
			kind = deaths.choose(self, personal, weapon, zone, fall, state.hits, state.beatenHits, blowShare, state.dying, ordeal,
					ModelParts.legs(entity.getId()).four());
		}

		int run = 0;
		int throes;
		switch (kind) {
			case QUICK -> throes = 30 + random.nextInt(20);
			case NORMAL -> throes = 80 + random.nextInt(40);
			case BRUTAL -> throes = Math.min(240, 140 + 25 * state.hits + 40 * state.beatenHits + ordeal / 4);
			case BOLT -> {
				run = Math.max(8, Math.round((16 + random.nextInt(20) + 14 * self.frantic()) * intensity));
				throes = 70 + random.nextInt(40);
			}
			case COLLAPSE -> {
				run = Math.max(10, Math.round((36 + random.nextInt(30)) * intensity));
				throes = 70 + random.nextInt(30);
			}
			case CURL -> {
				run = Math.max(10, Math.round((40 + random.nextInt(20)) * intensity));
				throes = 90 + random.nextInt(40);
			}
			case HEADLESS_RUN -> {
				run = Math.min(140, Math.round((40 + random.nextInt(50)) * intensity));
				throes = 40;
			}
			default -> {
				run = deaths.run(kind, intensity);
				throes = deaths.throes(kind);
			}
		}

		throes = Math.max(20, Math.round(throes * intensity * self.endurance()));
		// A small body is left lying longer, and on High and Extreme a body that was beaten or burnt to death lies there
		// a good while: the evidence of what was done to it.
		float evidence = kind == Corpse.Kind.BRUTAL || burnt ? EVIDENCE_LINGER : 1.0F;
		int linger = Math.round(GoreConfig.get().corpseLingerSeconds * 20 * (entity.isBaby() ? 1.5F : 1.0F) * GoreConfig.get().goreLevel.linger
				* (GoreConfig.get().goreLevel.linger > 1.0F ? evidence : 1.0F));
		Corpse corpse = new Corpse(entity, kind, state.voice, run, throes, linger, random.nextFloat() * 100.0F);
		corpse.pops = pops;
		corpses.put(entity.getId(), corpse);
		evictOldCorpses();
		boolean gore = GoreConfig.get().goreLevel.enabled() && !EntityFilter.isBloodless(entity);
		if (kind.scripted()) {
			deaths.begin(level, corpse, EntityFilter.bloodColor(entity), gore);
		}

		// The killing blow throws the body the way it was struck, harder the bigger the share of its health.
		int launch = GoreConfig.get().deathLaunchPercent;
		if (launch > 0 && strike != null) {
			float share = Math.min(1.0F, lastBlow / Math.max(1.0F, entity.getMaxHealth()));
			// A blast throws a body far harder than any blow: up and out, over walls and out of pits.
			double speed = blast > 0.0F ? 0.15 + 0.85 * blast : 0.1 + 0.3 * share;
			corpse.body.launch(strike, speed * launch / 100.0);
		}

		float length = switch (kind) {
			case QUICK -> 0.0F;
			case NORMAL -> 0.4F;
			case BRUTAL -> 1.0F;
			case HEADLESS_RUN -> -1.0F;
			case BOLT -> 0.7F;
			case COLLAPSE -> 0.1F;
			case CURL -> 0.2F;
			default -> Deaths.cry(kind);
		};
		if (length >= 0.0F) {
			screams.death(follow(entity), state.voice, intensity, length);
		}

		mourn(level, entity);
	}

	/**
	 * The death throes as they were before the realistic deaths, kept for the rare body that bursts at the end: chosen
	 * by how it was killed and its temperament alone.
	 */
	private static Corpse.Kind classic(State state, Personality self, boolean personal, int ordeal, float blowShare) {
		Corpse.Kind kind;
		if (state.beatenHits >= 2 || state.hits >= 5 || ordeal > 100) {
			kind = Corpse.Kind.BRUTAL;
		} else if (state.hits <= 1 && blowShare >= 0.6F) {
			kind = Corpse.Kind.QUICK;
		} else if (personal && self.frantic() > 0.68F) {
			kind = Corpse.Kind.BOLT;
		} else if (personal && self.frantic() < 0.32F) {
			kind = Corpse.Kind.COLLAPSE;
		} else if (personal && state.hits >= 3) {
			kind = Corpse.Kind.CURL;
		} else {
			kind = Corpse.Kind.NORMAL;
		}

		if (state.dying && (kind == Corpse.Kind.QUICK || kind == Corpse.Kind.NORMAL)) {
			// It was already on its way down: the legs give out instead of an instant fall.
			kind = Corpse.Kind.COLLAPSE;
		}

		return kind;
	}

	/** For tests and demos: the next animal to die dies this way (null: as it would), and bursts at the end or not. */
	public void forceNextDeath(Corpse.@Nullable Kind kind, boolean pop) {
		forcing = true;
		forcedKind = kind;
		forcedPop = pop;
	}

	/** A body was struck while it dies: the death answers in its own way. */
	public void struck(ClientLevel level, Corpse corpse, Vec3 look) {
		deaths.struck(level, corpse, look, EntityFilter.bloodColor(corpse.entity));
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

	private void evictOldCorpses() {
		while (corpses.size() > MAX_CORPSES) {
			int oldest = -1;
			int oldestAge = -1;
			for (Int2ObjectMap.Entry<Corpse> entry : corpses.int2ObjectEntrySet()) {
				if (entry.getValue().age > oldestAge) {
					oldestAge = entry.getValue().age;
					oldest = entry.getIntKey();
				}
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
		if (camera != null) {
			for (Entity entity : level.entitiesForRendering()) {
				if (entity instanceof LivingEntity living && living.isAlive() && !corpses.containsKey(living.getId())
						&& camera.distanceToSqr(living) < ACTIVE_RANGE_SQ && applies(living)) {
					tickLiving(level, living);
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

		c.walkPosO = c.walkPos;
		Ragdoll body = c.body;
		body.beginTick();
		LivingEntity entity = c.entity;
		GoreConfig config = GoreConfig.get();
		boolean gore = config.goreLevel.enabled();
		int color = EntityFilter.bloodColor(entity);
		Personality self = c.self();

		// The collision box follows the body, not the entity: until the server removes it, the entity still sits
		// wherever the server last put it.
		AABB box = entity.getBoundingBox().move(body.x - entity.getX(), body.y - entity.getY(), body.z - entity.getZ());
		boolean running = c.running();
		boolean scripted = c.kind.scripted() && c.upright();
		body.move(level, box, running ? steer(level, c, box) : scripted ? deaths.drive(c) : 0.0);

		if (c.age >= c.collapseAt && !c.toppled) {
			c.toppled = true;
			topple(c);
		}

		shove(c, box);
		float landing = c.toppled ? body.tip() : 0.0F;
		if (landing > 0.0F) {
			slam(level, c, landing, color, gore);
		}

		// Legs follow distance actually covered (as vanilla does), so feet never slide; none while down.
		if ((running || scripted) && body.onGround) {
			double travelled = Math.hypot(body.x - body.px, body.z - body.pz);
			c.walkSpeed += (Math.min(1.0F, (float) travelled * 4.0F) - c.walkSpeed) * 0.4F;
		} else {
			c.walkSpeed = 0.0F;
		}

		c.walkPos += c.walkSpeed;

		Deaths.Posture p = posture.reset();
		boolean curl = c.kind == Corpse.Kind.CURL;
		// How far the legs have given out. The posture is kept through the first moments of the fall and fades as
		// the body rolls onto its side, so there is no pop when the buckling ends.
		float buckle = 0.0F;
		if (c.kind == Corpse.Kind.COLLAPSE || curl) {
			buckle = c.buckling() ? c.prefall() : Math.max(0.0F, Mth.cos(body.roll));
		}

		int ground = c.groundTicks();
		if (scripted) {
			deaths.upright(level, c, p, color, gore);
		} else if (running) {
			float pressure = 1.0F - c.age / (float) Math.max(1, c.runTicks);
			if (c.kind == Corpse.Kind.HEADLESS_RUN) {
				if (gore && c.age % 4 == 0) {
					BloodEmitter.burst(GoreSystems.blood(), level, neck(c), color, 0.05F * pressure + 0.01F, 4);
				}
			} else {
				// A bolting animal screams as it goes and leaves a trail, and at the end its front legs fold under it and it
				// goes down nose first.
				if (gore && c.age % 5 == 0) {
					BloodEmitter.drip(GoreSystems.blood(), centre(c), color, 0.02F);
				}

				if (c.age % 9 == 3) {
					screams.pain(follow(entity), c.voice, 0.0F, 1.0F);
				}

				p.thrash = 0.5F;
				Deaths.fold(c, p, Mth.clamp((c.age - c.runTicks + 6) / 6.0F, 0.0F, 1.0F), 0.0F);
			}

			p.tremble = 0.4F;
		} else if (c.buckling()) {
			// Legs going out from under it. A collapsing animal breathes hard; a curled one whimpers over the wound.
			p.tremble = (curl ? 0.6F + 0.3F * buckle : 0.5F);
			p.kick = 0.3F * buckle;
			if (curl && c.age % 18 == 4) {
				screams.whimper(follow(entity), c.voice);
			} else if (!curl && c.age % 14 == 5) {
				screams.breath(follow(entity), c.voice, buckle);
			}

			if (gore && c.age % (curl ? 6 : 8) == 0) {
				BloodEmitter.drip(GoreSystems.blood(), centre(c), color, 0.02F);
			}
		} else if (ground >= 0) {
			// Agonal throes: sudden convulsions that get rarer as life runs out, flinging the joints and body.
			float life = 1.0F - Math.min(1.0F, ground / (float) c.throeTicks);
			float envelope = (float) Math.exp(-ground / Math.max(1.0, c.throeTicks / 3.0));
			boolean convulses = !c.kind.scripted() || deaths.ground(level, c, p, ground, life, color, gore);
			if (c.kind == Corpse.Kind.BOLT) {
				Deaths.fold(c, p, Math.max(0.0F, Mth.cos(body.roll)), 0.0F);
			}

			float spasmRate = 0.07F * (0.4F + 1.2F * self.frantic()) * (c.kind == Corpse.Kind.COLLAPSE ? 0.5F : 1.0F);
			if (convulses && life > 0.0F && random.nextFloat() < spasmRate * life) {
				body.spasm(random, 0.5F + random.nextFloat() * 0.5F);
			}

			if (convulses) {
				p.tremble = life <= 0.0F ? 0.0F : Math.min(1.0F, envelope * 0.8F * self.shake());
			}

			boolean voiced = c.kind != Corpse.Kind.HEADLESS_RUN && c.kind != Corpse.Kind.QUICK && Deaths.voiced(c);
			boolean breathes = voiced && (c.kind != Corpse.Kind.BRUTAL || ground > c.throeTicks / 2);
			if (life > 0.0F && voiced && c.kind != Corpse.Kind.COLLAPSE && random.nextFloat() < 0.045F * envelope + 0.01F * life) {
				if (entity.isBaby() && config.ecology) {
					// A dying young animal still calls for its mother, weaker each time.
					if (random.nextBoolean()) {
						screams.weakCall(follow(entity), c.voice, envelope);
					}
				} else {
					screams.gurgle(follow(entity), c.voice, envelope);
				}
			}

			// Breaths get further apart, then there is one last long one.
			if (breathes && ground >= c.nextBreath && ground < c.throeTicks + c.stillTicks / 2) {
				screams.breath(follow(entity), c.voice, envelope);
				c.nextBreath = ground + Math.round(16.0F + 8.0F * self.endurance() + ground * 0.08F);
			}

			if (voiced && !c.lastBreathTaken && ground >= c.throeTicks) {
				c.lastBreathTaken = true;
				screams.lastBreath(follow(entity), c.voice);
			}

			if (gore && c.kind == Corpse.Kind.HEADLESS_RUN && life > 0.0F && c.age % 7 == 0) {
				BloodEmitter.drip(GoreSystems.blood(), neck(c), color, 0.01F);
			}

			if (config.ecology && ground > c.throeTicks) {
				attractFlies(level, c, ground - c.throeTicks);
			}
		}

		body.stepJoints();
		if (buckle > 0.0F) {
			p.tiltX += (curl ? 26.0F : 6.0F) * buckle;
			p.drop += entity.getBbHeight() * (curl ? 0.12F : 0.25F) * buckle * (curl ? 1.0F : buckle);
			p.stagger += curl ? 0.0F : 3.0F * buckle;
		}

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
		if (random.nextFloat() < density * 0.5F) {
			double angle = random.nextDouble() * Mth.TWO_PI;
			double radius = 0.3 + random.nextDouble() * 0.5;
			level.addParticle(FLY, middle.x + Math.cos(angle) * radius, middle.y + random.nextDouble() * 0.5, middle.z + Math.sin(angle) * radius,
					-Math.sin(angle) * 0.15, 0.0, Math.cos(angle) * 0.15);
		}

		if (c.age >= c.nextFly) {
			screams.flies(() -> middle, density);
			c.nextFly = c.age + 50 + random.nextInt(30);
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
		level.playLocalSound(middle.x, middle.y, middle.z, SoundEvents.GENERIC_BIG_FALL, SoundSource.NEUTRAL,
				Math.min(1.0F, 0.4F + speed * 4.0F), 0.75F + 0.25F / heavy + random.nextFloat() * 0.1F, false);
		if (gore && !EntityFilter.isBloodless(c.entity)) {
			BloodEmitter.burst(GoreSystems.blood(), level, middle, color, 0.02F + speed * 0.2F, 4 + Math.round(speed * 40.0F));
			BloodEmitter.drip(GoreSystems.blood(), neck(c), color, 0.02F);
		}

		c.body.spasm(random, 0.4F);
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
		if (random.nextFloat() < 0.3F) {
			body.spasm(random, 0.2F);
		}

		c.keepFor(40);
	}

	/** Starts the fall: the push comes from the body's own momentum, so a hit knocks it over away from the blow. */
	private void topple(Corpse c) {
		Ragdoll body = c.body;
		float yawRad = body.yaw * Mth.DEG_TO_RAD;
		// Positive lean tips towards (cos yaw, sin yaw): the body's own right-hand side.
		double lateral = body.vx * Mth.cos(yawRad) + body.vz * Mth.sin(yawRad);
		float side = Math.abs(lateral) > 0.01 ? (float) Math.signum(lateral) : (random.nextBoolean() ? 1.0F : -1.0F);
		float forward = (float) Math.hypot(body.vx, body.vz);
		// A firm push, so it keels over about as fast as vanilla's tip-over instead of teetering.
		body.topple(side, 0.05F + Math.min(0.2F, forward * 0.5F + (float) Math.abs(lateral)));
		body.spasm(random, c.kind == Corpse.Kind.QUICK ? 1.0F : 0.6F);
	}

	/**
	 * Headless reflex run: a jog with a smoothly wandering heading. Blocked by a wall it hops a one-block step if
	 * there is room, otherwise it turns away over several ticks (no snapping), so it never passes through blocks.
	 * Returns the wanted speed.
	 */
	private double steer(ClientLevel level, Corpse c, AABB box) {
		Ragdoll body = c.body;
		if (body.blocked && body.onGround && c.turnTicks == 0) {
			float yawRad = body.yaw * Mth.DEG_TO_RAD;
			AABB raised = box.move(0.0, 1.0, 0.0);
			Vec3 ahead = new Vec3(-Mth.sin(yawRad) * 0.4, 0.0, Mth.cos(yawRad) * 0.4);
			Vec3 reach = Entity.collideBoundingBox(CollisionContext.empty(), ahead, raised, level, List.of());
			if (reach.horizontalDistanceSqr() > ahead.horizontalDistanceSqr() - 1.0E-6) {
				body.vy = 0.42;
			} else {
				c.turnDir = random.nextBoolean() ? 1.0F : -1.0F;
				c.turnTicks = 12 + random.nextInt(10);
			}
		}

		float wanted = SufferPose.noise(c.age * 0.12F, c.seed) * 5.0F;
		if (c.turnTicks > 0) {
			c.turnTicks--;
			wanted = c.turnDir * 14.0F;
		}

		body.yawV += (wanted - body.yawV) * 0.2F;
		body.yaw += body.yawV;
		return ((c.kind == Corpse.Kind.BOLT ? 0.22 : 0.15) + 0.03 * Math.sin(c.age * 0.4)) * Math.min(1.0, c.age / 6.0);
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
			// The vanilla "poof" was held back while the body lay there; play it now that it disappears.
			if (entity.level() instanceof ClientLevel level) {
				for (int i = 0; i < 20; i++) {
					level.addParticle(ParticleTypes.POOF,
							entity.getRandomX(1.0), entity.getRandomY(), entity.getRandomZ(1.0),
							random.nextGaussian() * 0.02, random.nextGaussian() * 0.02, random.nextGaussian() * 0.02);
				}

				GoreConfig config = GoreConfig.get();
				if (corpse.pops && config.goreLevel.enabled() && !EntityFilter.isBloodless(entity)) {
					Ragdoll body = corpse.body;
					GoreSystems.dismemberment().pop(GoreSystems.blood(), level, entity, new Vec3(body.x, body.y, body.z), body.yaw, body.roll);
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

		corpse.unloaded = true;
		return true;
	}

	public boolean holdsBody(int entityId) {
		return corpses.containsKey(entityId);
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
