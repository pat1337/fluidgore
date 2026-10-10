package dev.fluidgore.wound;

import dev.fluidgore.trigger.EntityFilter;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * How a blow opens a body. Fists and clubs bruise and crush but barely break the skin, so a beating bleeds little
 * outside and a lot inside; a flint is a sharp rock that scrapes ragged, shallow cuts; blades open meat; axes chop
 * deep and wide; pickaxes dig narrow holes and knock bits out like mining; arrows, spears and teeth punch holes;
 * a blast tears everywhere at once.
 *
 * @param depth  wound severity added per unit of relative damage
 * @param cap    deepest a wound from this weapon alone can get (1 = bone)
 * @param radius starting wound radius, in model pixels
 * @param spread how much a wound widens with each further blow (bruises swell and spread)
 * @param bleed  how much blood leaves the body per unit of damage, relative to a clean blade cut
 * @param severs a blow through a wound that cannot get any deeper takes the part off
 * @param chips  bits of flesh and bone knocked out of the wound per deep blow
 */
public enum Weapon {
	BLUNT(0.5F, 0.45F, 2.5F, 0.5F, 0.1F, false, 0),
	FLINT(0.75F, 0.7F, 1.4F, 0.25F, 0.55F, false, 0),
	BLADE(1.0F, 1.0F, 2.0F, 0.2F, 1.0F, true, 0),
	CHOP(1.45F, 1.0F, 2.6F, 0.2F, 1.2F, true, 2),
	PICK(1.3F, 1.0F, 1.3F, 0.15F, 0.8F, true, 4),
	PIERCE(1.2F, 0.8F, 1.1F, 0.1F, 0.6F, false, 0),
	BITE(0.9F, 0.75F, 1.8F, 0.25F, 0.7F, false, 0),
	BLAST(1.0F, 1.0F, 2.2F, 0.3F, 1.4F, false, 3);

	public final float depth;
	public final float cap;
	public final float radius;
	public final float spread;
	public final float bleed;
	public final boolean severs;
	public final int chips;

	Weapon(float depth, float cap, float radius, float spread, float bleed, boolean severs, int chips) {
		this.depth = depth;
		this.cap = cap;
		this.radius = radius;
		this.spread = spread;
		this.bleed = bleed;
		this.severs = severs;
		this.chips = chips;
	}

	/** A cutting edge: swords, axes and the pick's point. */
	public boolean cuts() {
		return severs;
	}

	/** What the held item does to flesh. Spears pierce; anything not configured as a blade (or a pick) is blunt. */
	public static Weapon of(ItemStack held) {
		if (held.is(Items.FLINT)) {
			return FLINT;
		}

		if (held.is(ItemTags.PICKAXES)) {
			return PICK;
		}

		if (held.is(ItemTags.SPEARS)) {
			return PIERCE;
		}

		if (!EntityFilter.isBladed(held)) {
			return BLUNT;
		}

		return held.is(ItemTags.AXES) ? CHOP : BLADE;
	}
}
