package dev.fluidgore.material;

import dev.fluidgore.anatomy.Anatomy;

/**
 * What a piece of a body is made of, the same for every system that owns one (a body, a severed limb, a butchered
 * joint, a blast fragment, an organ): how much water fresh tissue holds, how much of it will burn, how hard it is to
 * heat through, what heat makes of its colour, and how it lands. Separate from {@link Condition}, which is what has
 * happened to one piece of it.
 *
 * <p>The numbers are a game's approximation, rounded from food-science tables (lean meat about 75% water, fat tissue
 * 10-20%, fresh bone about 25%, viscera near 80%; fat burns with its own flame, lean meat hardly) and set so the
 * changes are seen at Minecraft's pace. They are not a validated tissue model.
 *
 * @param water       water share of fresh tissue, 0..1: how long it holds at the boil before it can dry and char
 * @param fuel        share that burns with a flame of its own once dry and hot, 0..1 (fat, mostly)
 * @param ignites     surface temperature (degrees C) from which a dry piece catches
 * @param diffusivity how fast heat gets in, relative to lean meat (fat is slower, bone slower still)
 * @param cooked      colour multiplier of the tissue cooked through, before it chars
 * @param restitution how much of its speed a fresh piece keeps bouncing
 * @param brittle     how brittle it gets charred: char at which a hard landing crumbles it
 * @param density     how heavy it is for its size against water: under 1 it floats (fat, a lung full of air), over 1 it
 *                    sinks, the more the faster (bone)
 */
public record Material(String name, float water, float fuel, float ignites, float diffusivity, int cooked, float restitution, float brittle, float density) {
	public static final Material MUSCLE = new Material("muscle", 0.75F, 0.15F, 390.0F, 1.0F, 0x9C8070, 0.1F, 0.55F, 1.06F);
	public static final Material FAT = new Material("fat", 0.15F, 0.9F, 300.0F, 0.7F, 0xE6C27A, 0.15F, 0.7F, 0.92F);
	public static final Material BONE = new Material("bone", 0.25F, 0.05F, 600.0F, 0.45F, 0xE8D8B8, 0.4F, 0.85F, 1.5F);
	public static final Material SKIN = new Material("skin", 0.65F, 0.25F, 370.0F, 0.9F, 0xB89070, 0.12F, 0.5F, 1.1F);
	public static final Material ORGAN = new Material("organ", 0.78F, 0.1F, 400.0F, 1.05F, 0x8A6A60, 0.08F, 0.5F, 1.05F);
	/** A lung: an organ, but full of air, so it floats (the hydrostatic test of a carcass inspector). */
	public static final Material LUNG = new Material("lung", 0.78F, 0.1F, 400.0F, 1.05F, 0x8A6A60, 0.08F, 0.5F, 0.45F);
	public static final Material GUT = new Material("gut", 0.82F, 0.12F, 400.0F, 1.1F, 0xC4A08A, 0.05F, 0.5F, 1.02F);
	/** Half-digested grass from a rumen: wet through, it steams a long time before it smoulders. */
	public static final Material CUD = new Material("cud", 0.85F, 0.3F, 330.0F, 0.8F, 0x6A5A30, 0.0F, 0.4F, 1.05F);
	/** Hoof, horn, claws: little water, slow to heat, chars rather than burns. */
	public static final Material KERATIN = new Material("keratin", 0.12F, 0.35F, 450.0F, 0.6F, 0xC8B090, 0.3F, 0.9F, 1.3F);

	/** What an anatomical piece is made of. */
	public static Material of(Anatomy.Kind kind) {
		return switch (kind) {
			case BONE, BONE_END, SKULL, TEETH, VERTEBRA, PHALANGES, CARTILAGE -> BONE;
			case HOOF, HORN -> KERATIN;
			case STOMACH, INTESTINE, COLON, RUMEN, RETICULUM, BLADDER, GALLBLADDER, TRACHEA, AORTA, VEIN -> GUT;
			case FAT, SUET, LARD -> FAT;
			case MUSCLE, MUTTON, PORK, BEEF, TONGUE -> MUSCLE;
			case LUNG, CUT_LUNG -> LUNG;
			default -> ORGAN;
		};
	}

	public boolean bone() {
		return this == BONE;
	}
}
