package dev.fluidgore.mixin;

import dev.fluidgore.player.Slick;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The floor's friction as a walking body reads it each tick, so wet blood on a smooth floor can make it slippery for
 * this game's own player (see {@link Slick}). Nobody else's movement is touched: everything but the local player gets
 * the block's own friction back. Not required: if another mod has changed this method so that the floor's friction is
 * no longer where it was, blood is simply not slippery, and the game starts as usual.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFrictionMixin {
	@ModifyVariable(method = "travelInAir", at = @At("STORE"), ordinal = 0, require = 0)
	private float fluidgore$slick(float blockFriction) {
		return Slick.walking((LivingEntity) (Object) this, blockFriction);
	}
}
