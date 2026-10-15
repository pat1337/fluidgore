package dev.fluidgore.mixin;

import dev.fluidgore.server.KeptBodies;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server side: a slaughtered animal's body stays instead of vanishing after vanilla's one second (see {@link KeptBodies}). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDeathMixin {
	@Inject(method = "tickDeath", at = @At("HEAD"), cancellable = true)
	private void fluidgore$keepBody(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!self.level().isClientSide() && KeptBodies.keep(self)) {
			ci.cancel();
		}
	}
}
