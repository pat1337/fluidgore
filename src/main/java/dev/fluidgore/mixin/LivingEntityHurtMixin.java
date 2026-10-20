package dev.fluidgore.mixin;

import dev.fluidgore.server.HitReports;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Server side: every hit that lands is reported to the players who see the body (see {@link HitReports}). Fabric's
 * after-damage event skips the killing blow, the one that matters most, hence the mixin.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtMixin {
	@Unique
	private float fluidgore$healthBefore = Float.NaN;

	@Inject(method = "hurtServer", at = @At("HEAD"))
	private void fluidgore$before(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
		fluidgore$healthBefore = ((LivingEntity) (Object) this).getHealth();
	}

	@Inject(method = "hurtServer", at = @At("RETURN"))
	private void fluidgore$after(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
		float before = fluidgore$healthBefore;
		fluidgore$healthBefore = Float.NaN;
		if (cir.getReturnValueZ() && !Float.isNaN(before)) {
			HitReports.dealt((LivingEntity) (Object) this, level, source, before);
		}
	}
}
