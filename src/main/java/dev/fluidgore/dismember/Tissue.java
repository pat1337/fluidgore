package dev.fluidgore.dismember;

import dev.fluidgore.material.Material;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

/**
 * What a body is made of, by share of live weight. The shares are rounded slaughter-yield figures for cattle: about
 * 60% of live weight is carcass, which is roughly 60% muscle, 25% fat and 15% bone; the hide is about 7%, blood 4%,
 * the digestive tract with its contents (mostly the rumen) about 18%, and the heart, liver, lungs and kidneys about
 * 4%. Head and feet are counted as bone and hide. A blast tears a body into pieces in these proportions; blood goes
 * out as droplets, not pieces.
 *
 * @param share       fraction of live weight
 * @param rgb         base colour (hide pieces use the animal's own skin texture instead)
 * @param minPx       smallest piece edge, in model pixels (1/16 block)
 * @param maxPx       largest piece edge, in model pixels
 * @param wet         blood (or cud) a fresh piece has to leave on the ground where it lands, 0 for none
 * @param material    what it is made of: how it bounces, dries, cooks and burns (see {@link Material})
 */
public enum Tissue {
	MUSCLE(0.36F, 0x9B2226, 1, 3, 0.012F, Material.MUSCLE),
	FAT(0.15F, 0xEADFB4, 1, 2, 0.0F, Material.FAT),
	BONE(0.13F, 0xE8E2D0, 1, 2, 0.0F, Material.BONE),
	HIDE(0.10F, 0xFFFFFF, 1, 3, 0.004F, Material.SKIN),
	GUT(0.08F, 0xC98A83, 1, 2, 0.01F, Material.GUT),
	/** Rumen contents: half-digested grass. */
	CUD(0.10F, 0x6E6B2F, 1, 1, 0.02F, Material.CUD),
	ORGAN(0.04F, 0x5C1A1B, 2, 3, 0.02F, Material.ORGAN),
	BLOOD(0.04F, 0x8A0303, 0, 0, 0.0F, Material.ORGAN);

	public final float share;
	public final int rgb;
	public final int minPx;
	public final int maxPx;
	public final float wet;
	public final Material material;

	Tissue(float share, int rgb, int minPx, int maxPx, float wet, Material material) {
		this.share = share;
		this.rgb = rgb;
		this.minPx = minPx;
		this.maxPx = maxPx;
		this.wet = wet;
		this.material = material;
	}

	/**
	 * True if {@code type} chews the cud: cattle, sheep and goats with their four-chambered stomachs, and camels and
	 * llamas with their three. Only their bellies spill {@link #CUD}.
	 */
	public static boolean chewsCud(EntityType<?> type) {
		return type == EntityTypes.COW || type == EntityTypes.MOOSHROOM || type == EntityTypes.SHEEP || type == EntityTypes.GOAT
				|| type == EntityTypes.CAMEL || type == EntityTypes.LLAMA || type == EntityTypes.TRADER_LLAMA;
	}

	/** Picks a tissue by share of weight from a uniform number in [0, 1), leaving blood out. */
	public static Tissue pick(float roll) {
		return pick(roll, MUSCLE, FAT, BONE, HIDE, GUT, CUD, ORGAN);
	}

	/** The soft flesh of a body whose bones and organs went as themselves: muscle, fat, hide, and the cud. */
	public static Tissue pickFlesh(float roll) {
		return pick(roll, MUSCLE, FAT, HIDE, CUD);
	}

	/** What spills out of a belly ripped open: gut, rumen contents and organs, with some of the muscle and fat around them. */
	public static Tissue pickInnards(float roll) {
		return pick(roll, GUT, CUD, ORGAN, GUT, MUSCLE, FAT);
	}

	/** Picks one of {@code from} by share of weight (an entry listed twice counts twice). */
	private static Tissue pick(float roll, Tissue... from) {
		float total = 0.0F;
		for (Tissue tissue : from) {
			total += tissue.share;
		}

		float at = roll * total;
		for (Tissue tissue : from) {
			at -= tissue.share;
			if (at < 0.0F) {
				return tissue;
			}
		}

		return from[0];
	}
}
