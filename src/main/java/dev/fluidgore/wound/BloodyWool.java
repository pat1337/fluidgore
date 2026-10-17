package dev.fluidgore.wound;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.suffer.Corpse;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Blood in a sheep's fleece or a chicken's feathers. Both drink it: every wound stains the coat round it (drawn by
 * {@link WoundRenderer} on the wool, or on the bird) and the whole coat goes from white through pink to a matted dark
 * red the more it bleeds, and lying in its own blood soaks it through. Animals packed in round the one being cut up
 * get it on them too, so a herd or a coop that is being slaughtered ends up blood-spattered all over. Rain and water
 * slowly wash it out, and leave a bird's feathers dark and slicked down until they dry.
 * <p>
 * Everything here follows from the hits and the weather (no dice), so every player sees the same coats.
 */
public final class BloodyWool {
	/** Tint multiplied into the wool layer, as an rgb int; null for clean wool. */
	public static final RenderStateDataKey<Integer> KEY = RenderStateDataKey.create(() -> "fluidgore:bloody_wool");
	/** A bird's plumage: how much of it is plucked and how wet it is, for its shape; null for no bird or a dry, full coat. */
	public static final RenderStateDataKey<Plumage> PLUMAGE = RenderStateDataKey.create(() -> "fluidgore:plumage");
	/** Animals this close to a blow get spattered, more the closer they stand. */
	private static final double SPATTER_RANGE = 3.0;
	/** Matted, drying blood in white wool. */
	private static final int SOAKED = 0x8E2424;
	/** How far the coat goes towards {@link #SOAKED} when completely soaked. */
	private static final float MAX_STAIN = 0.85F;
	/** Soak per tick for a dying animal lying in its own blood. */
	private static final float LYING = 0.004F;
	/** Soak washed out per tick in rain or water. */
	private static final float WASH = 0.0015F;
	/** Spatter alone stains a neighbour's coat no further than this: pink and blotched, not soaked through. */
	private static final float SPATTER_MAX = 0.35F;
	/** Wetting per check (every {@link #WET_EVERY} ticks) of a bird in rain or water, and drying per check out of it. */
	private static final float WETTING = 0.1F;
	private static final float DRYING = 0.004F;
	private static final int WET_EVERY = 4;
	/** Wet feathers go this much darker. */
	private static final float WET_DARK = 0.3F;
	private static final double WET_RANGE_SQ = 64.0 * 64.0;

	/** How plucked (0..1) and how wet (0..1) a bird's coat is. */
	public record Plumage(float plucked, float wet) {
	}

	private final Int2FloatOpenHashMap soak = new Int2FloatOpenHashMap();
	private final Int2FloatOpenHashMap wet = new Int2FloatOpenHashMap();
	private int tick;

	public static boolean woolly(Entity entity) {
		return entity instanceof Sheep sheep && !sheep.isSheared();
	}

	/** A coat that drinks blood, and the setting for it is on: an unsheared sheep's fleece, or a bird's feathers. */
	public static boolean soaks(Entity entity) {
		GoreConfig config = GoreConfig.get();
		return woolly(entity) ? config.bloodyWool : entity instanceof Chicken && config.bloodyFeathers;
	}

	/** How blood-soaked this animal's coat is, 0..1. */
	public float soak(int entityId) {
		return soak.get(entityId);
	}

	/** How wet this bird's feathers are, 0..1. */
	public float wet(int entityId) {
		return wet.get(entityId);
	}

	/** Tint for the wool layer of this sheep, or null if its wool is clean. */
	public @Nullable Integer tint(int entityId) {
		return stain(soak.get(entityId));
	}

	/**
	 * Tint for a bird whose feathers cover {@code 1 - plucked} of it: blood reddens them, water darkens them; the bare
	 * skin shows neither. Null if the coat is clean and dry.
	 */
	public @Nullable Integer featherTint(int entityId, float plucked) {
		float covered = 1.0F - plucked;
		// A bird bleeds into the feathers round the wound (drawn as stains there) before it reddens all over: the whole
		// coat takes on only half as much as a fleece, and only once it is properly soaked.
		float s = soak.get(entityId);
		Integer blood = stain(s * s * covered * 0.6F);
		float dark = WET_DARK * wet.get(entityId) * covered;
		if (dark <= 0.01F) {
			return blood;
		}

		int rgb = blood == null ? 0xFFFFFF : blood;
		float k = 1.0F - dark;
		return Math.round((rgb >> 16 & 0xFF) * k) << 16 | Math.round((rgb >> 8 & 0xFF) * k) << 8 | Math.round((rgb & 0xFF) * k);
	}

	private static @Nullable Integer stain(float s) {
		if (s <= 0.01F) {
			return null;
		}

		float t = Math.min(1.0F, s) * MAX_STAIN;
		int r = Math.round(Mth.lerp(t, 255, SOAKED >> 16 & 0xFF));
		int g = Math.round(Mth.lerp(t, 255, SOAKED >> 8 & 0xFF));
		int b = Math.round(Mth.lerp(t, 255, SOAKED & 0xFF));
		return r << 16 | g << 8 | b;
	}

	/**
	 * A blow that opened a wound {@code depth} deep (0..1) on {@code victim} at {@code at}: blood into its own coat if it
	 * has one, and spatter over the coats of every sheep and bird close by.
	 */
	public void hit(ClientLevel level, LivingEntity victim, Vec3 at, float depth) {
		GoreConfig config = GoreConfig.get();
		if (!config.goreLevel.enabled() || !Flesh.of(victim).bleeds()) {
			return;
		}

		float bleed = config.goreLevel.puddles;
		if (soaks(victim)) {
			add(victim.getId(), (0.08F + 0.3F * depth) * bleed);
		}

		AABB around = new AABB(at, at).inflate(SPATTER_RANGE);
		for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, around, e -> e != victim && soaks(e))) {
			double distance = other.getBoundingBox().distanceToSqr(at);
			float near = 1.0F - (float) Math.sqrt(distance) / (float) SPATTER_RANGE;
			if (near > 0.0F) {
				spatter(other.getId(), (0.03F + 0.12F * depth) * near * bleed);
			}
		}
	}

	private void add(int entityId, float amount) {
		soak.put(entityId, Math.min(1.0F, soak.get(entityId) + amount));
	}

	private void spatter(int entityId, float amount) {
		float now = soak.get(entityId);
		if (now < SPATTER_MAX) {
			soak.put(entityId, Math.min(SPATTER_MAX, now + amount));
		}
	}

	public void tick(ClientLevel level) {
		GoreConfig config = GoreConfig.get();
		if (!config.bloodyWool && !config.bloodyFeathers) {
			soak.clear();
			wet.clear();
			return;
		}

		tick++;
		// An animal dying on the ground lies in its own blood, and the coat drinks it up.
		for (Corpse corpse : GoreSystems.suffering().corpses()) {
			if (soaks(corpse.entity) && !corpse.lifeless()) {
				add(corpse.entity.getId(), LYING * config.goreLevel.puddles);
			}
		}

		for (Int2FloatMap.Entry entry : soak.int2FloatEntrySet()) {
			Entity entity = level.getEntity(entry.getIntKey());
			if (entity != null && entity.isInWaterOrRain()) {
				entry.setValue(Math.max(0.0F, entry.getFloatValue() - WASH));
			}
		}

		soak.values().removeIf(v -> v <= 0.0F);
		if (config.bloodyFeathers && tick % WET_EVERY == 0) {
			wetFeathers(level);
		}
	}

	/** Birds out in the rain or in water get wet through; out of it they slowly dry. */
	private void wetFeathers(ClientLevel level) {
		Entity viewer = Minecraft.getInstance().getCameraEntity();
		if (viewer == null) {
			return;
		}

		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof Chicken && entity.isInWaterOrRain() && entity.distanceToSqr(viewer) < WET_RANGE_SQ) {
				wet.put(entity.getId(), Math.min(1.0F, wet.get(entity.getId()) + WETTING));
			}
		}

		for (Int2FloatMap.Entry entry : wet.int2FloatEntrySet()) {
			Entity entity = level.getEntity(entry.getIntKey());
			if (entity == null || !entity.isInWaterOrRain()) {
				entry.setValue(Math.max(0.0F, entry.getFloatValue() - DRYING));
			}
		}

		wet.values().removeIf(v -> v <= 0.0F);
	}

	public void forget(int entityId) {
		soak.remove(entityId);
		wet.remove(entityId);
	}

	public void clear() {
		soak.clear();
		wet.clear();
	}
}
