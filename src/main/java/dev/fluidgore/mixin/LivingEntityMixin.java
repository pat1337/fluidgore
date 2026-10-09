package dev.fluidgore.mixin;

import dev.fluidgore.GoreSystems;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client receives a damage event packet for every hurt entity it can see; this is the only place the
 * attacker is known client-side. Fabric API has no client-side damage event, hence the mixin.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void fluidgore$onDamageEvent(DamageSource source, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide()) {
			GoreSystems.tracker().onDamageEvent(self, source);
		}
	}

	/**
	 * Vanilla makes a dead mob vanish in a puff of smoke after one second. While Fluid Gore keeps the body around
	 * for its death throes, the puff is held back and played when the body finally disappears.
	 */
	@Inject(method = "makePoofParticles", at = @At("HEAD"), cancellable = true)
	private void fluidgore$holdPoof(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide() && GoreSystems.suffering().holdsBody(self.getId())) {
			ci.cancel();
		}
	}
}
