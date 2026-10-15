package dev.fluidgore.wound;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodTextures;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.SurfaceProbe;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.trigger.EntityFilter;
import dev.fluidgore.trigger.Fate;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Burnt flesh. Every tick an animal spends on fire chars it a little more, from seared through roasted brown to
 * black, and it keeps that colour: alive, dying and dead. Burning bodies give off greasy smoke and ash and sizzle;
 * once the fire is out they smoulder for a while. A body that burnt to death leaves a black scorch mark where it
 * lies, and the pieces it pops into at the end are charred too.
 */
public final class Burns {
	/** Tint for the model: the char colour multiplied into the entity's texture, as an rgb int. */
	public static final RenderStateDataKey<Integer> KEY = RenderStateDataKey.create(() -> "fluidgore:char");
	/** Char per tick on fire: fully black after about seven seconds of burning, and four times as fast in lava. */
	private static final float RATE = 0.007F;
	/** Ticks a body keeps smouldering after the fire on it has gone out. */
	private static final int SMOULDER = 600;
	private static final double RANGE_SQ = 64.0 * 64.0;
	private static final RandomSource RANDOM = Fate.stream("burns");

	private final Int2FloatOpenHashMap charOf = new Int2FloatOpenHashMap();
	/** Ticks since the entity was last on fire. */
	private final Int2IntOpenHashMap cold = new Int2IntOpenHashMap();
	private final IntOpenHashSet scorched = new IntOpenHashSet();

	/** How burnt the entity is, 0 (not at all) to 1 (charred black). */
	public float charOf(int entityId) {
		return charOf.get(entityId);
	}

	/** True if the entity is on fire now or burnt badly enough that it counts as burnt to death. */
	public boolean burnt(LivingEntity entity) {
		return entity.isOnFire() || charOf.get(entity.getId()) > 0.3F;
	}

	/** Render tint for the entity, or null if it is not burnt. */
	public @Nullable Integer tint(int entityId) {
		float c = charOf.get(entityId);
		return c <= 0.0F ? null : tint(c);
	}

	/**
	 * White (untouched) through roasted brown at 0.4 to charred black at 1. A multiplier for the texture, so the
	 * animal's own markings still show through at first and are gone at the end.
	 */
	public static int tint(float c) {
		int roast = 0x9A6A48;
		int black = 0x2A201B;
		return c < 0.4F ? lerp(0xFFFFFF, roast, c / 0.4F) : lerp(roast, black, (c - 0.4F) / 0.6F);
	}

	/** Multiplies an argb colour by a tint. */
	public static int apply(int argb, int tint) {
		int r = (argb >> 16 & 0xFF) * (tint >> 16 & 0xFF) / 255;
		int g = (argb >> 8 & 0xFF) * (tint >> 8 & 0xFF) / 255;
		int b = (argb & 0xFF) * (tint & 0xFF) / 255;
		return argb & 0xFF000000 | r << 16 | g << 8 | b;
	}

	public void tick(ClientLevel level, BloodWorld blood) {
		GoreConfig config = GoreConfig.get();
		if (!config.burns) {
			clear();
			return;
		}

		Entity camera = Minecraft.getInstance().getCameraEntity();
		if (camera == null) {
			return;
		}

		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof LivingEntity living) || !living.isOnFire() || camera.distanceToSqr(living) > RANGE_SQ || !EntityFilter.affects(living)
					|| EntityFilter.isBone(living)) {
				continue;
			}

			int id = living.getId();
			float before = charOf.get(id);
			charOf.put(id, Math.min(1.0F, before + RATE * (living.isInLava() ? 4.0F : 1.0F)));
			cold.put(id, 0);
		}

		for (Int2FloatMap.Entry entry : charOf.int2FloatEntrySet()) {
			int id = entry.getIntKey();
			float c = entry.getFloatValue();
			int since = cold.addTo(id, 1);
			LivingEntity entity = entityFor(level, id);
			if (entity == null) {
				continue;
			}

			Vec3 at = where(entity);
			if (since > 0 && since < SMOULDER && RANDOM.nextFloat() < c * 0.25F * (1.0F - since / (float) SMOULDER)) {
				// Smouldering: thin smoke rising off the hide long after the flames are gone.
				level.addParticle(ParticleTypes.SMOKE, at.x + (RANDOM.nextDouble() - 0.5) * entity.getBbWidth(), at.y,
						at.z + (RANDOM.nextDouble() - 0.5) * entity.getBbWidth(), 0.0, 0.03, 0.0);
			}

			if (since == 0) {
				// A corpse's vanilla flames are hidden (they would stay where the entity was), so burn on the body itself.
				if (entity.isOnFire() && GoreSystems.suffering().corpse(id) != null && RANDOM.nextFloat() < 0.6F) {
					level.addParticle(ParticleTypes.FLAME, at.x + (RANDOM.nextDouble() - 0.5) * entity.getBbWidth(),
							at.y - entity.getBbHeight() * 0.3 + RANDOM.nextDouble() * entity.getBbHeight() * 0.6,
							at.z + (RANDOM.nextDouble() - 0.5) * entity.getBbWidth(), 0.0, 0.02, 0.0);
				}

				// Burning: greasy black smoke, flakes of ash coming off, the fat sizzling.
				if (RANDOM.nextFloat() < 0.3F + c * 0.4F) {
					level.addParticle(ParticleTypes.LARGE_SMOKE, at.x + (RANDOM.nextDouble() - 0.5) * entity.getBbWidth(), at.y,
							at.z + (RANDOM.nextDouble() - 0.5) * entity.getBbWidth(), 0.0, 0.04, 0.0);
				}

				if (RANDOM.nextFloat() < c * 0.5F) {
					level.addParticle(ParticleTypes.ASH, at.x + (RANDOM.nextDouble() - 0.5) * entity.getBbWidth(), at.y,
							at.z + (RANDOM.nextDouble() - 0.5) * entity.getBbWidth(), 0.0, 0.0, 0.0);
				}

				if (config.goreSounds && RANDOM.nextFloat() < 0.008F + c * 0.01F) {
					// A rare, soft sizzle: many sharp high hisses a second were the clicking.
					level.playLocalSound(at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.12F + c * 0.12F,
							0.8F + RANDOM.nextFloat() * 0.2F, false);
				}
			}

			if (!entity.isAlive() && c > 0.3F && scorched.add(id)) {
				scorch(level, blood, entity, c);
			}
		}
	}

	/** A black, greasy scorch mark under a body that burnt to death. */
	private static void scorch(ClientLevel level, BloodWorld blood, LivingEntity entity, float c) {
		Vec3 at = where(entity);
		double surface = SurfaceProbe.surfaceBelow(level, at.x, entity.getY() + 0.5, entity.getY() - 1.5, at.z);
		if (Double.isNaN(surface)) {
			return;
		}

		float half = Math.max(0.3F, entity.getBbWidth() * (0.6F + 0.4F * c));
		blood.decals().addFloor(level, at.x, surface, at.z, half, BloodTextures.splat(RANDOM), RANDOM.nextInt(8), 0x1A1512, false,
				blood.decalLifetime());
	}

	/** The body's middle: where the ragdoll has it if it is a corpse. */
	private static Vec3 where(LivingEntity entity) {
		Corpse corpse = GoreSystems.suffering().corpse(entity.getId());
		Vec3 feet = corpse != null ? corpse.position() : entity.position();
		return feet.add(0.0, entity.getBbHeight() * (corpse != null && corpse.unloaded ? 0.25 : 0.5), 0.0);
	}

	private static @Nullable LivingEntity entityFor(ClientLevel level, int id) {
		Corpse corpse = GoreSystems.suffering().corpse(id);
		if (corpse != null) {
			return corpse.entity;
		}

		return level.getEntity(id) instanceof LivingEntity living ? living : null;
	}

	private static int lerp(int from, int to, float t) {
		t = Mth.clamp(t, 0.0F, 1.0F);
		int r = Math.round(Mth.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF));
		int g = Math.round(Mth.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF));
		int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
		return r << 16 | g << 8 | b;
	}

	/** The entity respawned or is gone for good. */
	/** Restores how charred a body is, for evidence (see {@code net/Evidence}). */
	public void restore(int entityId, float charred) {
		if (charred > 0.0F) {
			charOf.put(entityId, Math.max(charOf.get(entityId), charred));
		}
	}

	public void forget(int entityId) {
		charOf.remove(entityId);
		cold.remove(entityId);
		scorched.remove(entityId);
	}

	public void clear() {
		charOf.clear();
		cold.clear();
		scorched.clear();
	}
}
