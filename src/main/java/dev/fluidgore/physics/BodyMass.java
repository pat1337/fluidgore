package dev.fluidgore.physics;

import dev.fluidgore.config.GoreConfig;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * What a body weighs, and so how long it burns: common to the server (a body that died burning burns on there, see
 * {@code server/BurningBodies}) and the clients (the fire on it, see {@code wound/Cremation}), so they agree.
 */
public final class BodyMass {
	/** Seconds to burn to the bone per cube root of the body's kilograms, at a speed of 100%. */
	public static final float SECONDS = 12.0F;
	/**
	 * What animals weigh, roughly, grown: what decides how long they burn and how much smoke they make. A mob not
	 * listed is weighed by its size.
	 */
	private static final Map<String, Float> KILOGRAMS = Map.ofEntries(
			Map.entry("chicken", 2.5F), Map.entry("rabbit", 2.0F), Map.entry("parrot", 0.4F), Map.entry("bat", 0.05F), Map.entry("frog", 0.5F),
			Map.entry("axolotl", 0.3F), Map.entry("cat", 4.5F), Map.entry("ocelot", 12.0F), Map.entry("fox", 7.0F), Map.entry("armadillo", 5.0F),
			Map.entry("wolf", 40.0F), Map.entry("goat", 60.0F), Map.entry("sheep", 70.0F), Map.entry("pig", 110.0F), Map.entry("panda", 110.0F),
			Map.entry("turtle", 150.0F), Map.entry("llama", 150.0F), Map.entry("trader_llama", 150.0F), Map.entry("dolphin", 150.0F),
			Map.entry("donkey", 250.0F), Map.entry("hoglin", 250.0F), Map.entry("mule", 350.0F), Map.entry("polar_bear", 400.0F),
			Map.entry("zombie_horse", 450.0F), Map.entry("horse", 500.0F), Map.entry("camel", 550.0F), Map.entry("cow", 650.0F),
			Map.entry("mooshroom", 650.0F), Map.entry("sniffer", 900.0F), Map.entry("villager", 70.0F), Map.entry("wandering_trader", 70.0F),
			Map.entry("player", 75.0F), Map.entry("zombie", 70.0F), Map.entry("husk", 60.0F), Map.entry("drowned", 70.0F),
			Map.entry("pillager", 75.0F), Map.entry("vindicator", 80.0F), Map.entry("evoker", 70.0F), Map.entry("witch", 65.0F),
			Map.entry("piglin", 80.0F), Map.entry("spider", 30.0F), Map.entry("cave_spider", 8.0F), Map.entry("squid", 20.0F)
	);

	private BodyMass() {
	}

	/** What a body weighs, in kilograms: its own kind's weight, a fraction of it for the young. */
	public static float kilograms(LivingEntity entity) {
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
		Float kg = KILOGRAMS.get(id);
		if (kg == null) {
			// Mostly water, and an animal fills only part of its box.
			float width = entity.getBbWidth();
			kg = Mth.clamp(width * width * entity.getBbHeight() * 350.0F, 0.05F, 2000.0F);
		} else if (entity.isBaby()) {
			kg *= 0.15F;
		}

		return kg;
	}

	/** Seconds {@code entity} takes to burn to the bone in a fire, at the speed set. */
	public static float seconds(LivingEntity entity) {
		return SECONDS * (float) Math.cbrt(kilograms(entity)) * 100.0F / GoreConfig.get().cremationSpeedPercent;
	}
}
