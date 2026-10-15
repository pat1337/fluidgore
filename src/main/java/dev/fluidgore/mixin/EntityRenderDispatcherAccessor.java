package dev.fluidgore.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the equipment assets, which say which textures a piece of armour is drawn with. */
@Mixin(EntityRenderDispatcher.class)
public interface EntityRenderDispatcherAccessor {
	@Accessor("equipmentAssets")
	EquipmentAssetManager fluidgore$getEquipmentAssets();
}
