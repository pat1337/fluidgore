package dev.fluidgore.wound;

import net.minecraft.util.Mth;

/**
 * What heat does to bone, for every renderer and for the debris of a burnt body. How burnt the bones are is not how
 * much flesh has burnt off them. Really it depends on how hot the fire got, for how long, how much air reached the
 * bone and what lay over it; this is a game's approximation of that. Here the flesh burning away chars the bone it lays
 * bare, brown and then charcoal, and a body burning on its own fat goes no further than that. Bone goes grey and at
 * last chalk white (calcined: its carbon burnt out, cracked and shrunk) in a fire that goes on after the flesh is gone,
 * a campfire or a pyre under it or lava. So the remains of an ordinary fire are a dark, mottled skeleton, as a
 * firefighter mostly finds them, and white bone means long, hot burning.
 *
 * <p>The heat is 0..1: {@link #SCORCH} brown, {@link #CHAR} dark brown going to charcoal by {@link #GREY}, then grey,
 * past {@link #CALCINED} white. The charred range is a gradient, not one black, so bones at different heats in it look
 * different.
 */
public final class BurntBone {
	/** The heat the flesh burning off gives the bones, at most here: charred, short of grey (a game's rule, see above). */
	public static final float OWN_FIRE = 0.55F;
	/** Scorched brown from here. */
	public static final float SCORCH = 0.3F;
	/** Charred from here: dark brown, going to charcoal. */
	public static final float CHAR = 0.45F;
	/** Charcoal going grey as the carbon burns out of it, from here. */
	public static final float GREY = 0.75F;
	/** Calcined from here: chalky, cracked and shrunk. */
	public static final float CALCINED = 0.9F;
	/**
	 * How far one bone, or one patch of bone, is from the body's heat: thin and exposed bone burns ahead, bone lying in
	 * the shelter of the rest behind. It is what keeps a skeleton mottled at every stage: round {@link #OWN_FIRE} it runs
	 * from scorched brown in the sheltered places to charcoal on the most exposed.
	 */
	private static final float SPREAD = 0.4F;

	/** Fresh bone in the fire, greasy yellow-brown, and each stage after it, as colours of their own. */
	private static final int RAW = 0xB09068;
	private static final int SCORCHED = 0x7A5A3C;
	private static final int CHARRED = 0x4A3424;
	private static final int CHARCOAL = 0x262220;
	private static final int GREYED = 0x6E737A;
	private static final int CHALK = 0xCFC9BC;
	/** The same stages as multipliers on the ivory of a bone texture. */
	private static final int SCORCHED_TINT = 0x9A7450;
	private static final int CHARRED_TINT = 0x5E4430;
	private static final int CHARCOAL_TINT = 0x302A26;
	private static final int GREY_TINT = 0x8E949C;

	private BurntBone() {
	}

	/** How burnt the bones are from the flesh burning off them alone, {@code burn} 0..1 of the flesh gone. */
	public static float ownFire(float burn) {
		return OWN_FIRE * ramp(burn, 0.25F, 0.85F);
	}

	/**
	 * One bone's (or one patch's) own heat: the body's {@code heat} moved by how exposed it is, {@code exposure} in [0, 1)
	 * from the body's dice (0.5 is the body's own).
	 */
	public static float local(float heat, float exposure) {
		return Mth.clamp(heat + (exposure - 0.5F) * SPREAD, 0.0F, 1.0F);
	}

	/** The colour of bone at {@code heat}. */
	public static int color(float heat) {
		if (heat < SCORCH) {
			return lerp(RAW, SCORCHED, heat / SCORCH);
		}

		if (heat < CHAR) {
			return lerp(SCORCHED, CHARRED, (heat - SCORCH) / (CHAR - SCORCH));
		}

		if (heat < GREY) {
			return lerp(CHARRED, CHARCOAL, (heat - CHAR) / (GREY - CHAR));
		}

		return heat < CALCINED ? lerp(CHARCOAL, GREYED, (heat - GREY) / (CALCINED - GREY)) : lerp(GREYED, CHALK, (heat - CALCINED) / (1.0F - CALCINED));
	}

	/** The same as a multiplier on a bone texture; past {@link #CALCINED} draw the calcined tile with {@link #chalk}. */
	public static int tint(float heat) {
		if (heat < SCORCH) {
			return lerp(0xFFFFFF, SCORCHED_TINT, ramp(heat, SCORCH * 0.5F, SCORCH));
		}

		if (heat < CHAR) {
			return lerp(SCORCHED_TINT, CHARRED_TINT, (heat - SCORCH) / (CHAR - SCORCH));
		}

		return heat < GREY ? lerp(CHARRED_TINT, CHARCOAL_TINT, (heat - CHAR) / (GREY - CHAR)) : lerp(CHARCOAL_TINT, GREY_TINT, (heat - GREY) / (CALCINED - GREY));
	}

	/** The multiplier on the calcined tile: grey where it has only just calcined, dull chalk at the end, never snow. */
	public static int chalk(float heat) {
		return lerp(0xA9ADB2, 0xEEE9DF, ramp(heat, CALCINED, 1.0F));
	}

	/** How much a calcined bone has shrunk, 0..0.12: it has lost its water and collagen. */
	public static float shrink(float heat) {
		return 0.12F * ramp(heat, CALCINED, 1.0F);
	}

	static float ramp(float x, float from, float to) {
		float t = Mth.clamp((x - from) / (to - from), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	static int lerp(int from, int to, float t) {
		t = Mth.clamp(t, 0.0F, 1.0F);
		int r = Math.round(Mth.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF));
		int g = Math.round(Mth.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF));
		int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
		return r << 16 | g << 8 | b;
	}
}
