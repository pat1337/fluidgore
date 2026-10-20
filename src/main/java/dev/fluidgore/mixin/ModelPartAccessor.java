package dev.fluidgore.mixin;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to a model part's geometry, needed to clone severed parts, and to swap it for the length of one draw when a
 * ragdoll bends a part at a joint the model does not have (see {@code ragdoll/Poser}).
 */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
	@Accessor("cubes")
	List<ModelPart.Cube> fluidgore$getCubes();

	@Accessor("children")
	Map<String, ModelPart> fluidgore$getChildren();

	@Mutable
	@Accessor("cubes")
	void fluidgore$setCubes(List<ModelPart.Cube> cubes);

	@Mutable
	@Accessor("children")
	void fluidgore$setChildren(Map<String, ModelPart> children);
}
