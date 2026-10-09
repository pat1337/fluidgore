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
		c.walkPosO = c.walkPos;
		Ragdoll body = c.body;
		body.beginTick();
		LivingEntity entity = c.entity;
		GoreConfig config = GoreConfig.get();
		boolean gore = config.goreLevel.enabled();
		int color = EntityFilter.bloodColor(entity);

		// The collision box follows the body, not the entity: until the server removes it, the entity still sits
		// wherever the server last put it.
		AABB box = entity.getBoundingBox().move(body.x - entity.getX(), body.y - entity.getY(), body.z - entity.getZ());
		boolean running = c.running();
		body.move(level, box, running ? steer(level, c, box) : 0.0);

		if (!running && !c.toppled) {
			c.toppled = true;
			topple(c);
		}

		if (c.toppled && body.tip() > 0.0F && gore && c.kind != Corpse.Kind.QUICK) {
			BloodEmitter.drip(GoreSystems.blood(), neck(c), color, 0.02F);
		}

		// Legs follow distance actually covered (as vanilla does), so feet never slide; none while down.
		if (running && body.onGround) {
			double travelled = Math.hypot(body.x - body.px, body.z - body.pz);
			c.walkSpeed += (Math.min(1.0F, (float) travelled * 4.0F) - c.walkSpeed) * 0.4F;
		} else {
			c.walkSpeed = 0.0F;
		}

		c.walkPos += c.walkSpeed;

		float tremble = 0.0F;
		int ground = c.groundTicks();
		if (running) {
			float pressure = 1.0F - c.age / (float) Math.max(1, c.runTicks);
			if (gore && c.age % 4 == 0) {
				BloodEmitter.burst(GoreSystems.blood(), level, neck(c), color, 0.05F * pressure + 0.01F, 4);
			}

			tremble = 0.4F;
		} else if (ground >= 0) {
			// Agonal throes: sudden convulsions that get rarer as life runs out, flinging the joints and body.
			float life = 1.0F - Math.min(1.0F, ground / (float) c.throeTicks);
			float envelope = (float) Math.exp(-ground / Math.max(1.0, c.throeTicks / 3.0));
			if (life > 0.0F && random.nextFloat() < 0.07F * life) {
				body.spasm(random, 0.5F + random.nextFloat() * 0.5F);
			}

			tremble = life <= 0.0F ? 0.0F : envelope * 0.8F;
			if (life > 0.0F && c.kind != Corpse.Kind.HEADLESS_RUN && c.kind != Corpse.Kind.QUICK && random.nextFloat() < 0.045F * envelope + 0.01F * life) {
				screams.gurgle(follow(entity), c.voice, envelope);
			}

			if (gore && c.kind == Corpse.Kind.HEADLESS_RUN && life > 0.0F && c.age % 7 == 0) {
				BloodEmitter.drip(GoreSystems.blood(), neck(c), color, 0.01F);
			}
		}

		body.stepJoints();
		c.pose = new SufferPose(tremble, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
		if (c.unloaded) {
			// Keep the entity (culling, shadow, bounding box) with the body; no offset is then needed to draw it.
			entity.xo = body.px;
			entity.yo = body.py;
			entity.zo = body.pz;
			entity.setPos(body.x, body.y, body.z);
			entity.setOnGround(body.onGround);
		}
	}

	/** Starts the fall: the push comes from the body's own momentum, so a hit knocks it over away from the blow. */
	private void topple(Corpse c) {
		Ragdoll body = c.body;
		float yawRad = body.yaw * Mth.DEG_TO_RAD;
		// Positive lean tips towards (cos yaw, sin yaw): the body's own right-hand side.
		double lateral = body.vx * Mth.cos(yawRad) + body.vz * Mth.sin(yawRad);
		float side = Math.abs(lateral) > 0.01 ? (float) Math.signum(lateral) : (random.nextBoolean() ? 1.0F : -1.0F);
		float forward = (float) Math.hypot(body.vx, body.vz);
		body.topple(side, Math.min(0.2F, forward * 0.5F + (float) Math.abs(lateral)));
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
		return (0.15 + 0.03 * Math.sin(c.age * 0.4)) * Math.min(1.0, c.age / 6.0);
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

	/** The neck stump of a ragdolled body: high up when upright, near the ground once it lies on its side. */
	private static Vec3 neck(Corpse c) {
		Ragdoll b = c.body;
		float yawRad = b.yaw * Mth.DEG_TO_RAD;
		double forward = c.entity.getBbWidth() * 0.45;
		double height = c.entity.getBbHeight() * (0.2 + 0.6 * Mth.cos(b.roll));
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
	}
}
