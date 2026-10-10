package dev.fluidgore.suffer;

import dev.fluidgore.config.GoreConfig;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * Who this animal is. Derived from the species and the entity's UUID, so it is the same on every client, every
 * session, and no two cows are alike: a stoic old cow with a deep slow bellow and a frantic young one with a
 * thin, long scream.
 *
 * @param role      how the species relates to killing around it: prey flee, herd animals also call to the herd,
 *                  predators take interest in fresh blood instead of panicking
 * @param pitch     multiplier on every scream pitch
 * @param loudness  multiplier on every scream volume
 * @param frantic   0 stoic (goes down slowly, quietly), 1 frantic (bolts, thrashes, screams)
 * @param shake     multiplier on trembling and flinching
 * @param panic     how quickly fear builds in this animal
 * @param endurance multiplier on how long its cries and its death last
 */
public record Personality(Role role, float pitch, float loudness, float frantic, float shake, float panic, float endurance) {
	public enum Role {
		PREY, HERD, PREDATOR
	}

	public static final Personality NEUTRAL = new Personality(Role.PREY, 1.0F, 1.0F, 0.5F, 1.0F, 1.0F, 1.0F);

	private record Trait(Role role, float pitch, float loudness, float frantic, float endurance) {
	}

	private static final Trait DEFAULT = new Trait(Role.PREY, 1.0F, 1.0F, 0.5F, 1.0F);
	private static final Map<String, Trait> TRAITS = Map.ofEntries(
			// Cattle: a low bellow that carries and calls the herd.
			Map.entry("cow", new Trait(Role.HERD, 0.85F, 1.05F, 0.35F, 1.2F)),
			Map.entry("mooshroom", new Trait(Role.HERD, 0.85F, 1.05F, 0.35F, 1.2F)),
			// Pigs: loud, sustained, uncomfortably human.
			Map.entry("pig", new Trait(Role.PREY, 1.1F, 1.35F, 0.7F, 1.4F)),
			// Sheep: bleats that crack and then simply stop.
			Map.entry("sheep", new Trait(Role.HERD, 1.05F, 0.8F, 0.5F, 0.6F)),
			Map.entry("goat", new Trait(Role.HERD, 1.0F, 1.0F, 0.6F, 0.9F)),
			// Birds and small things: flapping, then sudden silence.
			Map.entry("chicken", new Trait(Role.PREY, 1.25F, 0.9F, 0.95F, 0.4F)),
			Map.entry("rabbit", new Trait(Role.PREY, 1.4F, 0.6F, 0.9F, 0.4F)),
			// Horses: panicked whinnies and rearing.
			Map.entry("horse", new Trait(Role.PREY, 1.0F, 1.1F, 0.8F, 1.0F)),
			Map.entry("donkey", new Trait(Role.PREY, 0.95F, 1.15F, 0.7F, 1.1F)),
			Map.entry("mule", new Trait(Role.PREY, 0.95F, 1.1F, 0.7F, 1.1F)),
			Map.entry("llama", new Trait(Role.HERD, 0.95F, 0.9F, 0.4F, 1.0F)),
			Map.entry("trader_llama", new Trait(Role.HERD, 0.95F, 0.9F, 0.4F, 1.0F)),
			Map.entry("camel", new Trait(Role.HERD, 0.75F, 1.1F, 0.3F, 1.3F)),
			Map.entry("wolf", new Trait(Role.PREDATOR, 1.0F, 1.0F, 0.6F, 1.0F)),
			Map.entry("fox", new Trait(Role.PREDATOR, 1.15F, 0.8F, 0.8F, 0.7F)),
			Map.entry("cat", new Trait(Role.PREDATOR, 1.1F, 0.9F, 0.9F, 0.6F))
	);

	/** The animal's personality, or {@link #NEUTRAL} species-blind behaviour if personalities are switched off. */
	public static Personality of(LivingEntity entity) {
		if (!GoreConfig.get().personality) {
			return NEUTRAL;
		}

		Trait trait = TRAITS.getOrDefault(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(), DEFAULT);
		UUID id = entity.getUUID();
		RandomSource random = RandomSource.create(id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 21));
		float young = entity.isBaby() ? 1.35F : 1.0F;
		return new Personality(
				trait.role(),
				Mth.clamp(trait.pitch() * (0.88F + 0.24F * random.nextFloat()) * young, 0.5F, 1.5F),
				trait.loudness() * (0.8F + 0.4F * random.nextFloat()),
				Mth.clamp(trait.frantic() + (random.nextFloat() - 0.5F) * 0.7F, 0.0F, 1.0F),
				0.6F + 0.9F * random.nextFloat(),
				0.6F + 1.0F * random.nextFloat(),
				trait.endurance() * (0.75F + 0.5F * random.nextFloat()));
	}
}
