package dev.fluidgore.suffer;

import dev.fluidgore.mixin.LivingEntitySoundInvoker;
import dev.fluidgore.mixin.MobSoundInvoker;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * The raw material for an animal's screams: its own vanilla hurt, death and idle sounds, and the personality that
 * bends them. Captured while the entity is alive, because a dead or removed entity may no longer report them.
 */
public record Voice(SoundEvent hurt, SoundEvent death, SoundEvent ambient, Personality self) {
	public static Voice of(LivingEntity entity) {
		SoundEvent hurt = ((LivingEntitySoundInvoker) entity).fluidgore$getHurtSound(entity.damageSources().generic());
		SoundEvent death = ((LivingEntitySoundInvoker) entity).fluidgore$getDeathSound();
		SoundEvent ambient = entity instanceof Mob mob ? ((MobSoundInvoker) mob).fluidgore$getAmbientSound() : null;
		if (hurt == null) hurt = SoundEvents.GENERIC_HURT;
		if (death == null) death = hurt;
		if (ambient == null) ambient = hurt;
		return new Voice(hurt, death, ambient, Personality.of(entity));
	}
}
