package dev.fluidgore.anatomy;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodTextures;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.SurfaceProbe;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.demo.DemoPlayer;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.trigger.EntityFilter;
import dev.fluidgore.wound.BurntBone;
import dev.fluidgore.wound.Cremation;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.animal.sheep.SheepFurModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * People burning alive, drawn layer by layer by {@link MeltRenderer}. Every tick a player spends on fire the fire gets
 * a little further into them, four times as fast in lava, and how far it can get goes with how near death the burns
 * have brought them (their health) and the gore level: a healthy player on fire reddens, blisters and chars, one about
 * to die of it has the skin burnt through to fat running and dripping, and on Extreme the muscle under it. Burns heal
 * as health comes back.
 *
 * <p>Dying on fire, the body stays where it fell and goes on burning on its own fat ({@link Cremation} takes over from
 * how far it got here, see {@link #handOver}): the skin and fat go, the muscle cooks off, the organs shrivel, and it
 * ends a charred, then white skeleton. Black smoke pours off a burning person, and fat drips from them and spatters
 * the ground.
 *
 * <p>Everything follows from the synced fire flag and health, and the holes from the body's UUID, so every player sees
 * the same burns.
 */
public final class Melting {
	/** How far the fire gets per tick on fire: into the fat in about fifteen seconds of burning. */
	private static final float RATE = 0.0015F;
	/** A healthy body burns this share of the way its gore level lets a dying one. */
	private static final float HEALTHY = 0.35F;
	/** Healed: this much less burnt per share of full health regained. */
	private static final float HEALING = 0.6F;
	/** Ticks at full health before the last of the burns fades, and how fast it does then. */
	private static final int SCARRED = 600;
	private static final float FADE = 0.0005F;
	private static final double RANGE_SQ = 64.0 * 64.0;
	private static final double FX_SQ = 32.0 * 32.0;
	/** The fat on the ground: greasy yellow-brown. */
	private static final int GREASE = 0x7A5A1E;
	/** A rotting body's: dark brown, rendered out of adipocere and the fluids of decay. */
	private static final int PUTRID_GREASE = 0x4A3C1E;
	/** Smoke and drips: this screen's own. */
	private static final RandomSource FX = RandomSource.create();

	private static final class Body {
		final long seed;
		float bare;
		float health;
		int cold;
		/** Opened for the {@code resolution} demo: raw, unburnt, held where it is, drawn at {@link #detail}. */
		boolean sample;
		Anatomy.Detail detail = Anatomy.Detail.MIXED;

		Body(long seed, float health) {
			this.seed = seed;
			this.health = health;
		}
	}

	private final Int2ObjectOpenHashMap<Body> bodies = new Int2ObjectOpenHashMap<>();

	/**
	 * True if {@code entity} burns layer by layer: a player, a walking corpse (a zombie or a drowned, burning in the sun
	 * or set alight, rotten all through), or any other person with Melting Mobs, drawn as a humanoid; or, with Burning
	 * Animals, a sheep, a pig or a cow.
	 */
	public static boolean applies(LivingEntity entity) {
		GoreConfig config = GoreConfig.get();
		if (!config.melting || !config.goreLevel.enabled() || !EntityFilter.affects(entity) || EntityFilter.isBone(entity)) {
			return false;
		}

		if (Anatomy.Beast.of(entity.getType()) != null) {
			return config.meltingAnimals && ModelParts.drawn(entity.getId()) instanceof QuadrupedModel<?> && !(ModelParts.drawn(entity.getId()) instanceof SheepFurModel);
		}

		return entity instanceof Player || (config.meltingMobs || DemoPlayer.playing() || EntityFilter.isRotten(entity))
				&& ModelParts.drawn(entity.getId()) instanceof HumanoidModel<?>;
	}

	/** True if {@code entity} burns through its own anatomy and is a person, not a beast. */
	public static boolean person(LivingEntity entity) {
		return applies(entity) && Anatomy.Beast.of(entity.getType()) == null;
	}

	/** A sheep still in its wool. */
	private static boolean fleece(LivingEntity entity) {
		return entity instanceof Sheep sheep && !sheep.isSheared();
	}

	/** The body's own dice for where the fire gets through: from its UUID, the same as {@link Cremation}'s. */
	public static long seed(LivingEntity entity) {
		return entity.getUUID().getMostSignificantBits() ^ entity.getUUID().getLeastSignificantBits() ^ 0x5DEECE66DL;
	}

	/** How far the fire has got into a living body, 0 if it never burnt. */
	public float bare(int entityId) {
		Body body = bodies.get(entityId);
		return body == null ? 0.0F : body.bare;
	}

	/** The furthest a living body can burn at a gore level, at the point of death. */
	public static float cap(GoreLevel level) {
		return switch (level) {
			case OFF -> 0.0F;
			case LOW -> 0.12F;
			case MEDIUM -> 0.22F;
			case HIGH -> 0.34F;
			case EXTREME -> 0.5F;
		};
	}

	/**
	 * How {@code entity} looks burnt, or null if it does not burn this way or is not burnt. A body burns on in
	 * {@link Cremation}; a living one as far as it has got here.
	 */
	public @Nullable Melt look(LivingEntity entity) {
		if (!applies(entity)) {
			return null;
		}

		int id = entity.getId();
		Body body = bodies.get(id);
		float alive = body == null ? 0.0F : body.bare;
		Corpse corpse = GoreSystems.suffering().corpse(id);
		if (corpse != null) {
			Cremation cremation = GoreSystems.dismemberment().cremation();
			float bare = Math.max(cremation.bare(id), alive);
			return bare <= 0.004F ? null : new Melt(bare, Math.max(cremation.burn(id), alive),
					Math.max(cremation.bone(id), BurntBone.ownFire(alive)), cremation.burning(id), true, seed(entity), EntityFilter.isRotten(entity),
					Anatomy.Beast.of(entity.getType()), fleece(entity), alive > 0.0F || cremation.burntAlive(id), Anatomy.Detail.MIXED,
					Anatomy.male(entity.getUUID()), entity.isBaby());
		}

		if (body != null && body.sample) {
			return new Melt(alive, 0.0F, 0.0F, false, false, body.seed, EntityFilter.isRotten(entity), Anatomy.Beast.of(entity.getType()), fleece(entity), false,
					body.detail, Anatomy.male(entity.getUUID()), entity.isBaby());
		}

		return alive <= 0.004F ? null : new Melt(alive, alive, BurntBone.ownFire(alive), entity.isOnFire(), false, body.seed, EntityFilter.isRotten(entity),
				Anatomy.Beast.of(entity.getType()), fleece(entity), true, Anatomy.Detail.MIXED, Anatomy.male(entity.getUUID()), entity.isBaby());
	}

	/** For tests and demos: {@code entity} has burnt this far, whatever its health. */
	public void set(LivingEntity entity, float bare) {
		Body body = bodies.computeIfAbsent(entity.getId(), id -> new Body(seed(entity), entity.getHealth()));
		body.bare = bare;
	}

	/**
	 * For the {@code resolution} demo: {@code entity} opened {@code bare} deep, raw (no fire has cooked it) and held so, with what
	 * lies under its muscle drawn at {@code detail}. It looks only: nothing else about the body changes.
	 */
	public void sample(LivingEntity entity, float bare, Anatomy.Detail detail) {
		set(entity, bare);
		Body body = bodies.get(entity.getId());
		body.sample = true;
		body.detail = detail;
		body.cold = 0;
	}

	/**
	 * A body that was burning alive is a corpse now: how far it had got (0 if it never burnt), for the fire to burn on
	 * from there. It is no longer tracked here.
	 */
	public float handOver(int entityId) {
		Body body = bodies.remove(entityId);
		return body == null ? 0.0F : body.bare;
	}

	public void tick(ClientLevel level, BloodWorld blood) {
		GoreConfig config = GoreConfig.get();
		if (!config.melting) {
			bodies.clear();
			return;
		}

		Entity camera = Minecraft.getInstance().getCameraEntity();
		if (camera == null) {
			return;
		}

		float cap = cap(config.goreLevel);
		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof LivingEntity living) || !living.isAlive() || camera.distanceToSqr(living) > RANGE_SQ || !applies(living)) {
				continue;
			}

			int id = living.getId();
			boolean burning = living.isOnFire();
			Body body = bodies.get(id);
			if (body == null) {
				if (!burning) {
					continue;
				}

				body = new Body(seed(living), living.getHealth());
				bodies.put(id, body);
			}

			float health = living.getHealth();
			float max = Math.max(1.0F, living.getMaxHealth());
			if (burning) {
				float hurt = Mth.clamp(1.0F - health / max, 0.0F, 1.0F);
				float limit = cap * (HEALTHY + (1.0F - HEALTHY) * hurt);
				if (body.bare < limit) {
					body.bare = Math.min(limit, body.bare + RATE * (living.isInLava() ? 4.0F : 1.0F));
				}

				body.cold = 0;
			} else {
				if (body.sample) {
					continue;
				}

				body.cold++;
				if (health > body.health) {
					body.bare = Math.max(0.0F, body.bare - HEALING * (health - body.health) / max);
				}

				if (health >= max && body.cold > SCARRED) {
					body.bare = Math.max(0.0F, body.bare - FADE);
				}
			}

			body.health = health;
			if (burning && camera.distanceToSqr(living) < FX_SQ) {
				effects(level, blood, living, body.bare, config);
			}
		}

		// A body is kept while it has burns or a corpse here; it goes once healed, or once its entity is gone.
		bodies.int2ObjectEntrySet().removeIf(entry -> {
			int id = entry.getIntKey();
			Entity entity = level.getEntity(id);
			boolean dead = entity instanceof LivingEntity living && !living.isAlive();
			return entry.getValue().bare <= 0.0F && !dead || entity == null && GoreSystems.suffering().corpse(id) == null;
		});
	}

	/**
	 * A person on fire: thick greasy smoke pouring up off them, and once the skin is through, fat running out and
	 * dripping, burning, onto the ground, with a sputter now and then.
	 */
	private static void effects(ClientLevel level, BloodWorld blood, LivingEntity living, float bare, GoreConfig config) {
		float particles = config.goreLevel.particles;
		double w = living.getBbWidth();
		double h = living.getBbHeight();
		if (fleece(living)) {
			// Wool hardly burns: it is slow to catch and puts itself out, so a fleece on fire scorches and smoulders,
			// curling into brittle black beads under a thick white, stinking smoke, long before the skin under it goes.
			float wool = 1.0F - Melt.ramp(bare, 0.08F, 0.24F);
			if (wool > 0.0F && FX.nextFloat() < 0.8F * wool * particles) {
				level.addParticle(FX.nextFloat() < 0.75F ? ParticleTypes.WHITE_SMOKE : ParticleTypes.SMOKE, living.getX() + (FX.nextDouble() - 0.5) * w,
						living.getY() + h * (0.5 + FX.nextDouble() * 0.5), living.getZ() + (FX.nextDouble() - 0.5) * w, 0.0, 0.025 + FX.nextDouble() * 0.02, 0.0);
				if (config.goreSounds && FX.nextFloat() < 0.04F * wool) {
					level.playLocalSound(living.getX(), living.getY() + h, living.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.1F,
							1.3F + FX.nextFloat() * 0.3F, false);
				}
			}
		}

		float hair = 1.0F - Melt.ramp(bare, 0.03F, 0.12F);
		if (hair > 0.0F && FX.nextFloat() < 0.6F * hair * particles) {
			// The hair goes first: it frizzles and smokes white off the scalp in the first seconds, before the skin goes.
			level.addParticle(FX.nextFloat() < 0.7F ? ParticleTypes.WHITE_SMOKE : ParticleTypes.SMOKE, living.getX() + (FX.nextDouble() - 0.5) * w * 0.5,
					living.getY() + h + 0.02, living.getZ() + (FX.nextDouble() - 0.5) * w * 0.5, 0.0, 0.04 + FX.nextDouble() * 0.03, 0.0);
			if (config.goreSounds && FX.nextFloat() < 0.03F * hair) {
				level.playLocalSound(living.getX(), living.getY() + h, living.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.08F,
						1.6F + FX.nextFloat() * 0.3F, false);
			}
		}

		if (FX.nextFloat() < (0.2F + bare * 1.2F) * particles) {
			level.addParticle(ParticleTypes.SMOKE, living.getX() + (FX.nextDouble() - 0.5) * w, living.getY() + h * (0.5 + FX.nextDouble() * 0.5),
					living.getZ() + (FX.nextDouble() - 0.5) * w, 0.0, 0.06 + FX.nextDouble() * 0.04, 0.0);
		}

		if (bare > 0.15F && FX.nextFloat() < 0.03F * particles) {
			// A column of it, seen from far off.
			level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, living.getX(), living.getY() + h, living.getZ(), 0.0, 0.06, 0.0);
		}

		float fat = Melt.ramp(bare, 0.18F, 0.4F);
		if (fat > 0.0F && FX.nextFloat() < 0.35F * fat * particles) {
			double x = living.getX() + (FX.nextDouble() - 0.5) * w * 1.1;
			double z = living.getZ() + (FX.nextDouble() - 0.5) * w * 1.1;
			level.addParticle(FX.nextBoolean() ? ParticleTypes.FALLING_HONEY : ParticleTypes.FALLING_LAVA, x, living.getY() + h * (0.2 + FX.nextDouble() * 0.5),
					z, 0.0, 0.0, 0.0);
			if (FX.nextFloat() < 0.08F) {
				double surface = SurfaceProbe.surfaceBelow(level, x, living.getY() + 0.5, living.getY() - 1.5, z);
				if (!Double.isNaN(surface)) {
					blood.decals().addFloor(level, x, surface, z, 0.08F + FX.nextFloat() * 0.1F, BloodTextures.splat(FX), FX.nextInt(8),
							EntityFilter.isRotten(living) ? PUTRID_GREASE : GREASE, false,
							blood.decalLifetime());
				}
			}

			if (config.goreSounds && FX.nextFloat() < 0.02F * fat) {
				level.playLocalSound(living.getX(), living.getY() + h * 0.5, living.getZ(), SoundEvents.LAVA_POP, SoundSource.NEUTRAL, 0.2F,
						0.9F + FX.nextFloat() * 0.3F, false);
			}
		}
	}

	/** The body has a new id (it lies on after its player respawned under the old one). */
	public void rekey(int from, int to) {
		Body body = bodies.remove(from);
		if (body != null) {
			bodies.put(to, body);
		}
	}

	/** How many living bodies are burnt or opened here. */
	public int count() {
		return bodies.size();
	}

	public void forget(int entityId) {
		bodies.remove(entityId);
	}

	public void clear() {
		bodies.clear();
	}
}
