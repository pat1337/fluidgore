package dev.fluidgore.mixin;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to a model part's geometry, needed to clone severed parts. */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
	@Accessor("cubes")
	List<ModelPart.Cube> fluidgore$getCubes();

	@Accessor("children")
	Map<String, ModelPart> fluidgore$getChildren();
}
