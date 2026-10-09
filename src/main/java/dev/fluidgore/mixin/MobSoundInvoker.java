package dev.fluidgore.mixin;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The idle "moo/oink/baa" sound, used (pitched up) for whimpers and herd panic bellows. */
@Mixin(Mob.class)
public interface MobSoundInvoker {
	@Invoker("getAmbientSound")
	@Nullable SoundEvent fluidgore$getAmbientSound();
}
