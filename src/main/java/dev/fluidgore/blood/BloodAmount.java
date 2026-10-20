package dev.fluidgore.blood;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * How hard each kind of mob bleeds from a wound, 1 for a person. It follows the mob's real blood volume
 * ({@link BloodSupply#litres}) to the two-thirds power: vessels and wounds grow with the size of the body, not its
 * volume, so a cow's cut runs about four times a person's and a chicken's a tenth. How much can come out in all is
 * {@link BloodSupply}'s business. Override any mob's litres with {@code bloodAmounts} in the config
 * ({@code "minecraft:cow": 37.0}).
 */
public final class BloodAmount {
	/** Litres of blood in a 75 kg person. */
	private static final float PERSON_LITRES = 5.25F;

	private BloodAmount() {
	}

	/** The mob's measure of bleeding, 1 for a person. */
	public static float measure(LivingEntity entity) {
		return (float) Math.pow(BloodSupply.litres(entity) / PERSON_LITRES, 2.0 / 3.0);
	}

	/** {@link #measure} as a multiplier for a number of droplets, spurts or trickles, which may not grow as fast as volume. */
	public static float countFactor(float measure) {
		return Mth.clamp(measure, 0.15F, 3.0F);
	}
}
