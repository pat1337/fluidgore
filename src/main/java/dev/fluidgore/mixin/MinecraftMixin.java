package dev.fluidgore.mixin;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.Cleaning;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes attack and use clicks to severed parts when the crosshair is on one (closer than any block or entity).
 * Only in that case is the vanilla action skipped, so normal fighting, mining and item use are unaffected. Normal
 * attacks on living entities only have their aim recorded, so the wound lands where the player was looking.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void fluidgore$punchPart(CallbackInfoReturnable<Boolean> cir) {
		Minecraft self = (Minecraft) (Object) this;
		if (GoreSystems.dismemberment().interaction().onAttack(self, GoreSystems.blood())) {
			cir.setReturnValue(false);
			return;
		}

		// A normal attack on a living thing: remember exactly where the player aimed, for the wound it opens.
		if (self.player != null && self.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity target) {
			GoreSystems.dismemberment().wounds().recordAim(target, self.player.getEyePosition(), self.player.getViewVector(1.0F));
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void fluidgore$grabPart(CallbackInfo ci) {
		Minecraft self = (Minecraft) (Object) this;
		if (GoreSystems.dismemberment().interaction().onUse(self) || Cleaning.onUse(self, GoreSystems.blood())) {
			ci.cancel();
		}
	}
}
