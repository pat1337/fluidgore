package dev.fluidgore.wound;

import dev.fluidgore.trigger.EntityFilter;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

/**
 * How a blow opens a body. Fists and clubs bruise and split skin but never reach bone; blades open meat; axes chop
 * deep and wide; arrows and tridents punch small, deep holes.
 *
 * @param depth  wound severity added per unit of relative damage
 * @param cap    deepest a wound from this weapon alone can get (1 = bone)
 * @param radius starting wound radius, in model pixels
 */
public enum Weapon {
	BLUNT(0.5F, 0.45F, 2.5F),
	BLADE(1.0F, 1.0F, 2.0F),
	CHOP(1.45F, 1.0F, 2.6F),
	PIERCE(1.2F, 0.8F, 1.1F);

	public final float depth;
	public final float cap;
	public final float radius;

	Weapon(float depth, float cap, float radius) {
		this.depth = depth;
		this.cap = cap;
		this.radius = radius;
	}

	public boolean cuts() {
		return this == BLADE || this == CHOP;
	}

	public static Weapon of(ItemStack held) {
		if (!EntityFilter.isBladed(held)) {
			return BLUNT;
		}

		return held.is(ItemTags.AXES) ? CHOP : BLADE;
	}
}
