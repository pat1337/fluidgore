package dev.fluidgore.mixin;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Asks an entity for its own hurt/death sounds, so screams are built from the right variant (a cold cow, a
 * screaming goat, a donkey...) without hardcoding sound ids.
 */
@Mixin(LivingEntity.class)
public interface LivingEntitySoundInvoker {
	@Invoker("getHurtSound")
	@Nullable SoundEvent fluidgore$getHurtSound(DamageSource source);

	@Invoker("getDeathSound")
	@Nullable SoundEvent fluidgore$getDeathSound();
}
