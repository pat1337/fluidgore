package dev.fluidgore.mixin;

import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the armour models a humanoid's armour layer draws with. */
@Mixin(HumanoidArmorLayer.class)
public interface HumanoidArmorLayerAccessor {
	@Accessor("modelSet")
	ArmorModelSet<?> fluidgore$getModelSet();

	@Accessor("babyModelSet")
	ArmorModelSet<?> fluidgore$getBabyModelSet();
}
