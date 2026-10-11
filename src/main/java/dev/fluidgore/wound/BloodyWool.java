package dev.fluidgore.wound;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.suffer.Corpse;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Blood in a sheep's fleece. Wool drinks it: every wound stains the fleece round it (drawn by {@link WoundRenderer} on
 * the wool) and the whole coat goes from white through pink to a matted dark red the more it bleeds, and lying in its
 * own blood soaks it through. Sheep packed in round the one being cut up get it on them too, so a herd that is being
 * slaughtered ends up blood-spattered all over. Rain and water slowly wash it out.
 * <p>
 * Everything here follows from the hits themselves (no dice), so every player sees the same coats.
 */
public final class BloodyWool {
	/** Tint multiplied into the wool layer, as an rgb int; null for clean wool. */
	public static final RenderStateDataKey<Integer> KEY = RenderStateDataKey.create(() -> "fluidgore:bloody_wool");
	/** Sheep this close to a blow get spattered, more the closer they stand. */
	private static final double SPATTER_RANGE = 3.0;
	/** Matted, drying blood in white wool. */
	private static final int SOAKED = 0x8E2424;
	/** How far the coat goes towards {@link #SOAKED} when completely soaked. */
	private static final float MAX_STAIN = 0.85F;
	/** Soak per tick for a dying sheep lying in its own blood. */
	private static final float LYING = 0.004F;
	/** Soak washed out per tick in rain or water. */
	private static final float WASH = 0.0015F;
	/** Spatter alone stains a neighbour's coat no further than this: pink and blotched, not soaked through. */
	private static final float SPATTER_MAX = 0.35F;

	private final Int2FloatOpenHashMap soak = new Int2FloatOpenHashMap();

	public static boolean woolly(Entity entity) {
		return entity instanceof Sheep sheep && !sheep.isSheared();
	}

	/** How blood-soaked this sheep's fleece is, 0..1. */
	public float soak(int entityId) {
		return soak.get(entityId);
	}

	/** Tint for the wool layer of this sheep, or null if its wool is clean. */
	public @Nullable Integer tint(int entityId) {
		float s = soak.get(entityId);
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
	 * A blow that opened a wound {@code depth} deep (0..1) on {@code victim} at {@code at}: blood into its own fleece if
	 * it is a sheep, and spatter over the fleece of every sheep close by.
	 */
	public void hit(ClientLevel level, LivingEntity victim, Vec3 at, float depth) {
		GoreConfig config = GoreConfig.get();
		if (!config.bloodyWool || !config.goreLevel.enabled() || !Flesh.of(victim).bleeds()) {
			return;
		}

		float bleed = config.goreLevel.puddles;
		if (woolly(victim)) {
			add(victim.getId(), (0.08F + 0.3F * depth) * bleed);
		}

		AABB around = new AABB(at, at).inflate(SPATTER_RANGE);
		for (Sheep sheep : level.getEntitiesOfClass(Sheep.class, around, s -> s != victim && !s.isSheared())) {
			double distance = sheep.getBoundingBox().distanceToSqr(at);
			float near = 1.0F - (float) Math.sqrt(distance) / (float) SPATTER_RANGE;
			if (near > 0.0F) {
				spatter(sheep.getId(), (0.03F + 0.12F * depth) * near * bleed);
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
		if (!GoreConfig.get().bloodyWool) {
			soak.clear();
			return;
		}

		// A sheep dying on the ground lies in its own blood, and the fleece drinks it up.
		for (Corpse corpse : GoreSystems.suffering().corpses()) {
			if (corpse.entity.getType() == EntityTypes.SHEEP && woolly(corpse.entity) && !corpse.lifeless()) {
				add(corpse.entity.getId(), LYING * GoreConfig.get().goreLevel.puddles);
			}
		}

		for (Int2FloatMap.Entry entry : soak.int2FloatEntrySet()) {
			Entity entity = level.getEntity(entry.getIntKey());
			if (entity != null && entity.isInWaterOrRain()) {
				entry.setValue(Math.max(0.0F, entry.getFloatValue() - WASH));
			}
		}

		soak.values().removeIf(v -> v <= 0.0F);
	}

	public void forget(int entityId) {
		soak.remove(entityId);
	}

	public void clear() {
		soak.clear();
	}
}
