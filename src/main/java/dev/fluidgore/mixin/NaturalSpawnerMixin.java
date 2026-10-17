package dev.fluidgore.mixin;

import com.google.common.collect.Iterables;
import dev.fluidgore.server.KeptBodies;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Server side: vanilla counts every mob in the level against the mob cap, the dead too. A kept body is a carcass, not an
 * animal, so it is left out of the count: a field of slaughtered cows does not stop new ones from spawning.
 * <p>
 * The list is filtered once it is stored, not where it is fetched: Lithium redirects that call
 * ({@code minimal_nonvanilla.spawning}), and two redirects of one call cannot both apply.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
	@ModifyVariable(method = "createState", at = @At("STORE"), ordinal = 0)
	private static Iterable<Entity> fluidgore$skipKeptBodies(Iterable<Entity> entities) {
		return Iterables.filter(entities, entity -> !KeptBodies.isKept(entity));
	}
}
