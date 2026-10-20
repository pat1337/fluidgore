package dev.fluidgore.mixin;

import dev.fluidgore.GoreSystems;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Deafness after a blast (see {@code PlayerInjuries.hearing}): every sound's volume, as it starts and each tick for
 * sounds that move, is turned down by how much of the hearing is gone. Only this player's ears; nothing is sent.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	@Inject(method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F", at = @At("RETURN"), cancellable = true)
	private void fluidgore$deafened(float volume, SoundSource source, CallbackInfoReturnable<Float> cir) {
		float hearing = GoreSystems.injuries().hearing(source);
		if (hearing < 1.0F) {
			cir.setReturnValue(cir.getReturnValueF() * hearing);
		}
	}
}
