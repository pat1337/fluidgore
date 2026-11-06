package dev.fluidgore.player;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.wound.WoundSet;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * How badly a living body is hurt, read off its wounds, for the way it carries itself: each limb 0 (sound) to 1 (broken,
 * or cut to the bone), the belly the same, and {@code weak}, how near a player is to dying (0 above a third of their
 * health, 1 at none). Everything comes from the wounds and the synced health, so every client sees the same limp.
 */
public record Injury(float rightLeg, float leftLeg, float rightArm, float leftArm, float belly, float weak) {
	public static final RenderStateDataKey<Injury> KEY = RenderStateDataKey.create(() -> "fluidgore:injury");
	/** Below this share of their health a player starts to fail. */
	public static final float WEAK_BELOW = 0.34F;

	/** The injury of {@code entity}, or null if it is unhurt or limping is off. */
	public static @Nullable Injury of(LivingEntity entity) {
		if (!GoreConfig.get().limp || !entity.isAlive()) {
			return null;
		}

		if (entity instanceof Player player && PlayerInjuries.spared(player)) {
			// Creative or spectator: unhurt (see PlayerInjuries.spared).
			return null;
		}

		WoundSet set = GoreSystems.dismemberment().wounds().get(entity.getId());
		float weak = entity instanceof Player ? weak(entity.getHealth() / Math.max(1.0F, entity.getMaxHealth())) : 0.0F;
		if (set == null && weak <= 0.0F) {
			return null;
		}

		Injury injury = set == null ? new Injury(0, 0, 0, 0, 0, weak)
				: new Injury(limb(set, BodyPart.RIGHT_LEG), limb(set, BodyPart.LEFT_LEG), limb(set, BodyPart.RIGHT_ARM), limb(set, BodyPart.LEFT_ARM),
						set.ribsBroken() ? 1.0F : hurt(set.torsoSeverity()), weak);
		return injury.isNone() ? null : injury;
	}

	/** How near death a share {@code health} of full health is, 0..1. */
	public static float weak(float health) {
		return Mth.clamp(1.0F - health / WEAK_BELOW, 0.0F, 1.0F);
	}

	/** A bruise does nothing; a gash starts to tell; meat to the bone, or a broken bone, cripples. */
	private static float limb(WoundSet set, BodyPart part) {
		return set.broken(part) ? 1.0F : hurt(set.severity(part));
	}

	private static float hurt(float severity) {
		return Mth.clamp((severity - 0.2F) / 0.55F, 0.0F, 1.0F);
	}

	public float leg(boolean right) {
		return right ? rightLeg : leftLeg;
	}

	public float arm(boolean right) {
		return right ? rightArm : leftArm;
	}

	public boolean isNone() {
		return rightLeg <= 0.0F && leftLeg <= 0.0F && rightArm <= 0.0F && leftArm <= 0.0F && belly <= 0.0F && weak <= 0.0F;
	}
}
