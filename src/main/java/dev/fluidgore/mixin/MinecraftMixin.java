package dev.fluidgore.mixin;

import dev.fluidgore.GoreSystems;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes attack and use clicks to severed parts when the crosshair is on one (closer than any block or entity).
 * Only in that case is the vanilla action skipped, so normal fighting, mining and item use are unaffected.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void fluidgore$punchPart(CallbackInfoReturnable<Boolean> cir) {
		if (GoreSystems.dismemberment().interaction().onAttack((Minecraft) (Object) this, GoreSystems.blood())) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void fluidgore$grabPart(CallbackInfo ci) {
		if (GoreSystems.dismemberment().interaction().onUse((Minecraft) (Object) this)) {
			ci.cancel();
		}
	}
}
