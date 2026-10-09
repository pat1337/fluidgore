package dev.fluidgore.suffer;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.GorePart;
import dev.fluidgore.trigger.EntityFilter;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
	}

	/** Something terrible happened here: makes nearby animals of the same kind panic for a while. */
	private record Terror(Vec3 pos, EntityType<?> type, float weight, int tick) {
	}

	private final ScreamManager screams = new ScreamManager();
	private final Int2ObjectOpenHashMap<State> states = new Int2ObjectOpenHashMap<>();
	private final Int2ObjectOpenHashMap<Corpse> corpses = new Int2ObjectOpenHashMap<>();
	private final Int2ObjectOpenHashMap<SufferPose> poses = new Int2ObjectOpenHashMap<>();
	private final List<Terror> terror = new ArrayList<>();
	private final RandomSource random = RandomSource.create();
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

	private static float intensity() {
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
	 */
	public void onHurt(ClientLevel level, LivingEntity entity, float damage, boolean lethal, boolean bareHanded) {
		State state = state(entity);
		state.hits++;
		if (bareHanded) {
			state.beatenHits++;
		}

		if (state.firstHitTick < 0) {
			state.firstHitTick = tick;
		}

		state.thrashTicks = 14;
		terror.add(new Terror(entity.position(), entity.getType(), lethal ? 3.0F : 1.0F, tick));

		boolean headless = (GoreSystems.dismemberment().missingMask(entity.getId()) & BodyPart.HEAD.bit()) != 0;
		if (!lethal) {
			if (!headless) {
				screams.pain(follow(entity), state.voice, intensity(), state.fear);
			}

			return;
		}

		startDeath(entity, state, damage, headless);
	}

	private void startDeath(LivingEntity entity, State state, float lastBlow, boolean headless) {
		if (corpses.containsKey(entity.getId())) {
			return;
		}

		float intensity = intensity();
		int ordeal = tick - state.firstHitTick;
		float blowShare = lastBlow / Math.max(1.0F, entity.getMaxHealth());
		Corpse.Kind kind;
		if (headless) {
			kind = Corpse.Kind.HEADLESS_RUN;
		} else if (state.beatenHits >= 2 || state.hits >= 5 || ordeal > 100) {
			kind = Corpse.Kind.BRUTAL;
		} else if (state.hits <= 1 && blowShare >= 0.6F) {
			kind = Corpse.Kind.QUICK;
		} else {
			kind = Corpse.Kind.NORMAL;
		}

		int run = 0;
		int throes;
		switch (kind) {
			case QUICK -> throes = 30 + random.nextInt(20);
			case NORMAL -> throes = 80 + random.nextInt(40);
			case BRUTAL -> throes = Math.min(360, 140 + 25 * state.hits + 40 * state.beatenHits + ordeal / 4);
			default -> {
				run = Math.min(140, Math.round((40 + random.nextInt(50)) * intensity));
				throes = 40;
			}
		}

		throes = Math.max(20, Math.round(throes * intensity));
		Corpse corpse = new Corpse(entity, kind, state.voice, run, throes, 60, random.nextFloat() * 100.0F);
		corpse.burst = kind == Corpse.Kind.QUICK ? 1.0F : 0.6F;
		corpses.put(entity.getId(), corpse);
		evictOldCorpses();

		float length = switch (kind) {
			case QUICK -> 0.0F;
			case NORMAL -> 0.4F;
			case BRUTAL -> 1.0F;
			case HEADLESS_RUN -> -1.0F;
		};
		if (length >= 0.0F) {
			screams.death(follow(entity), state.voice, intensity, length);
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
		terror.removeIf(t -> tick - t.tick() > TERROR_MEMORY);
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
		float panic = config.herdPanic ? terrorAround(entity) : 0.0F;

		State state = states.get(entity.getId());
		if (state == null && !wounded && mask == 0 && panic < 0.01F) {
			return;
		}

		state = state(entity);
		float target = Math.max(panic, wounded ? 0.7F : 0.0F);
		state.fear += (target - state.fear) * 0.05F;
		if (state.thrashTicks > 0) {
			state.thrashTicks--;
		}

		// Pose: shaking from fear and pain, head thrown around after a hit, legs kicking.
		float thrash = state.thrashTicks / 14.0F + state.fear * 0.25F + (headless ? 0.5F : 0.0F);
		float tremble = Math.min(1.0F, state.fear * 0.6F + (wounded ? 0.5F : 0.0F) + (headless ? 1.0F : 0.0F)) * Math.min(1.5F, intensity);
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

		SufferPose pose = new SufferPose(tremble, Math.min(1.0F, thrash), kick, 0.9F, tiltX, tiltZ, drop, stagger);
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

		if (wounded && tick >= state.nextWhimper) {
			screams.whimper(follow(entity), state.voice);
			state.nextWhimper = tick + Math.round((40 + random.nextInt(60)) / intensity);
		}

		if (state.fear > 0.25F && config.herdPanic && tick >= state.nextBellow) {
			screams.bellow(follow(entity), state.voice, state.fear);
			state.nextBellow = tick + Math.round((60 + random.nextInt(120)) / (state.fear * intensity));
		}
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
		c.bodyRotO = c.bodyRot;
		c.walkPosO = c.walkPos;
		LivingEntity entity = c.entity;
		if (c.unloaded) {
			entity.xo = entity.getX();
			entity.yo = entity.getY();
			entity.zo = entity.getZ();
		}

		GoreConfig config = GoreConfig.get();
		boolean gore = config.goreLevel.enabled();
		int color = EntityFilter.bloodColor(entity);

		if (c.running()) {
			runBlind(level, c);
			float pressure = 1.0F - c.age / (float) Math.max(1, c.runTicks);
			if (gore && c.age % 4 == 0) {
				BloodEmitter.burst(GoreSystems.blood(), level, neck(entity), color, 0.05F * pressure + 0.01F, 4);
			}

			c.pose = new SufferPose(0.4F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 10.0F);
			return;
		}

		c.walkSpeed *= 0.7F;
		int ground = c.groundTicks();
		if (ground < 0) {
			return;
		}

		// Agonal throes: kicking that dies away, broken by sudden spasms that get rarer as life runs out.
		float life = 1.0F - Math.min(1.0F, ground / (float) c.throeTicks);
		float envelope = (float) Math.exp(-ground / Math.max(1.0, c.throeTicks / 3.0));
		if (life > 0.0F && random.nextFloat() < 0.07F * life) {
			c.burst = 0.6F + random.nextFloat() * 0.4F;
		}

		c.burst *= 0.82F;
		float kick = life <= 0.0F ? 0.0F : Mth.clamp(envelope * 0.55F + c.burst * 0.8F, 0.0F, 1.0F);
		float thrash = life <= 0.0F ? 0.0F : Math.min(1.0F, c.burst * 0.7F + envelope * 0.3F);
		float tremble = life <= 0.0F ? 0.0F : envelope * 0.8F;
		c.pose = new SufferPose(tremble, thrash, kick, 0.8F + c.burst * 0.9F, 0.0F, 0.0F, 0.0F, 0.0F);

		if (life > 0.0F && c.kind != Corpse.Kind.HEADLESS_RUN && c.kind != Corpse.Kind.QUICK && random.nextFloat() < 0.045F * envelope + 0.01F * life) {
			screams.gurgle(follow(entity), c.voice, envelope);
		}

		if (gore && c.kind == Corpse.Kind.HEADLESS_RUN && life > 0.0F && c.age % 7 == 0) {
			BloodEmitter.drip(GoreSystems.blood(), neck(entity), color, 0.01F);
		}
	}

	/**
	 * Headless reflex run: forward at a jog, veering at random and turning away from walls, with full leg
	 * movement. Uses vanilla's collision routine against block shapes so it does not pass through walls.
	 */
	private void runBlind(ClientLevel level, Corpse c) {
		LivingEntity entity = c.entity;
		c.bodyRot += SufferPose.noise(c.age * 0.35F, c.seed) * 14.0F;
		float yaw = c.bodyRot * Mth.DEG_TO_RAD;
		double speed = 0.16 + 0.04 * Math.sin(c.age * 0.5);
		c.fallSpeed = entity.onGround() ? 0.0 : c.fallSpeed - 0.08;
		Vec3 wanted = new Vec3(-Mth.sin(yaw) * speed, c.fallSpeed, Mth.cos(yaw) * speed);
		Vec3 moved = Entity.collideBoundingBox(CollisionContext.empty(), wanted, entity.getBoundingBox(), level, List.of());
		if (moved.x != wanted.x || moved.z != wanted.z) {
			// Blind collision with a wall: lurch away.
			c.bodyRot += 120.0F + random.nextFloat() * 120.0F;
		}

		if (moved.y != wanted.y && wanted.y < 0.0) {
			c.fallSpeed = 0.0;
			entity.setOnGround(true);
		} else if (moved.y < 0.0) {
			entity.setOnGround(false);
		}

		entity.setPos(entity.getX() + moved.x, entity.getY() + moved.y, entity.getZ() + moved.z);
		c.walkSpeed = 1.0F;
		c.walkPos += 1.3F;
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
				for (int i = 0; i < 12; i++) {
					level.addParticle(ParticleTypes.POOF,
							entity.getRandomX(1.0), entity.getRandomY(), entity.getRandomZ(1.0),
							random.nextGaussian() * 0.02, random.nextGaussian() * 0.02, random.nextGaussian() * 0.02);
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

	private static Supplier<Vec3> follow(LivingEntity entity) {
		return () -> new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() * 0.6, entity.getZ());
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
	}
}
