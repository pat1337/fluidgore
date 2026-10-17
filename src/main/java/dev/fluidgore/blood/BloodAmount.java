package dev.fluidgore.blood;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * How much blood each kind of mob carries, on top of how big it is: a cow is a bucket of it, a chicken a few drops.
 * 1 is an average body. On Extreme the differences are exaggerated, so the big bleeders gush and the small ones stay small.
 * Override any mob with {@code bloodAmounts} in the config ({@code "minecraft:cow": 2.0}).
 */
public final class BloodAmount {
	private static final Map<String, Float> DEFAULTS = Map.ofEntries(
			Map.entry("cow", 1.7F), Map.entry("mooshroom", 1.7F), Map.entry("pig", 1.4F), Map.entry("sheep", 1.2F),
			Map.entry("goat", 1.0F), Map.entry("horse", 2.6F), Map.entry("donkey", 1.6F), Map.entry("mule", 1.6F),
			Map.entry("llama", 1.3F), Map.entry("trader_llama", 1.3F), Map.entry("camel", 1.7F), Map.entry("polar_bear", 1.6F),
			Map.entry("panda", 1.4F), Map.entry("ravager", 1.9F), Map.entry("hoglin", 1.6F), Map.entry("zoglin", 1.5F),
			Map.entry("player", 1.2F), Map.entry("villager", 1.2F), Map.entry("pillager", 1.2F), Map.entry("vindicator", 1.2F),
			Map.entry("evoker", 1.2F), Map.entry("wolf", 0.9F), Map.entry("fox", 0.7F), Map.entry("cat", 0.6F),
			Map.entry("ocelot", 0.6F), Map.entry("chicken", 0.45F), Map.entry("rabbit", 0.4F), Map.entry("parrot", 0.1F),
			Map.entry("bat", 0.2F), Map.entry("turtle", 0.6F), Map.entry("cod", 0.1F), Map.entry("salmon", 0.1F),
			Map.entry("tropical_fish", 0.2F), Map.entry("pufferfish", 0.2F), Map.entry("squid", 0.9F), Map.entry("spider", 0.7F),
			Map.entry("cave_spider", 0.4F), Map.entry("bee", 0.05F), Map.entry("silverfish", 0.1F), Map.entry("endermite", 0.1F)
	);

	private BloodAmount() {
	}

	/** The mob's own measure of blood, 1 for an average body. */
	public static float measure(LivingEntity entity) {
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		Float custom = GoreConfig.get().bloodAmounts.get(id);
		float base = custom != null ? custom : DEFAULTS.getOrDefault(id.substring(id.indexOf(':') + 1), 1.0F);
		// Extreme pulls the mobs further apart: a cow gushes, a rabbit still only trickles.
		return GoreConfig.get().goreLevel == GoreLevel.EXTREME ? (float) Math.pow(base, 1.3) : base;
	}

	/** {@link #measure} as a multiplier for a number of droplets, spurts or trickles, which may not grow as fast as volume. */
	public static float countFactor(float measure) {
		return Mth.clamp(measure, 0.15F, 3.0F);
	}
}
