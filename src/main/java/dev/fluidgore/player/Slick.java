package dev.fluidgore.player;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Wet blood underfoot. On a smooth floor (glass, ice, polished stone, metal) a film of fresh blood is as slippery as
 * ice; plain stone and planks are a little slippery; turf, sand and anything else that drinks it are not, and blood
 * that has set is tacky, not slick. Only this player's own footing is changed, in their own game, as walking through
 * a pile of bodies slows only them (see {@link GoreConfig#slipperyBlood}).
 */
public final class Slick {
	/** How slippery ice is: the most blood makes a floor. */
	private static final float ICE = 0.98F;
	/** True once the game has asked for this player's footing: the hook is in place (for the game test). */
	private static boolean asked;

	private Slick() {
	}

	/** True once the game has asked for the local player's footing. */
	public static boolean asked() {
		return asked;
	}

	/** The game asks for the floor's friction under {@code entity} as it walks (from {@code LivingEntityFrictionMixin}). */
	public static float walking(LivingEntity entity, float friction) {
		if (entity instanceof LocalPlayer) {
			asked = true;
		}

		return friction(entity, friction);
	}

	/** The friction of the floor under {@code entity}, given the block's own {@code friction}. */
	public static float friction(LivingEntity entity, float friction) {
		if (!(entity instanceof LocalPlayer player) || friction >= ICE) {
			return friction;
		}

		GoreConfig config = GoreConfig.get();
		if (config.slipperyBlood <= 0 || !config.goreLevel.enabled() || !player.onGround()) {
			return friction;
		}

		float slick = GoreSystems.blood().fluid().slickAt(player.getX(), player.getY(), player.getZ());
		return slick <= 0.0F ? friction : friction + (ICE - friction) * slick * config.slipperyBlood / 100.0F;
	}
}
