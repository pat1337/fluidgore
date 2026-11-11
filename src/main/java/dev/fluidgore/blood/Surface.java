package dev.fluidgore.blood;

/**
 * What a floor does with blood, by what it is made of.
 *
 * <p>Blood stops spreading when the film is too thin for its weight to push it on against its own surface tension:
 * about 1.5 mm on glass or polished stone, 2.5 mm on plain stone, more on anything rough enough to hold it in its
 * texture. A porous floor drinks it instead. Dry sand takes whole blood down between its grains within seconds, so
 * a pool on sand hardly spreads and leaves a dark clump. Soil has pores too fine for the cells: the serum wicks in
 * slowly and the red stays on top, among the grass or as a crust on bare earth. Stone, glass and metal take nothing,
 * so the blood stays wet on them longest, and on the smoothest of them it is slippery underfoot.
 */
public enum Surface {
	/** Glass, ice, glazed and polished stone, metal: nothing soaks in, the film is thin and wide, and it is slippery. */
	POLISHED(1.6F, 0.0F, 0.0F, 1.0F, 1.0F, 0.15F),
	/** Plain stone, brick, concrete, terracotta. */
	STONE(2.5F, 0.002F, 0.3F, 0.7F, 0.35F, 0.6F),
	/** Cobbles and other rough stone: the film stands deeper in the joints and does not run as far. */
	ROUGH(3.5F, 0.004F, 0.6F, 0.5F, 0.15F, 1.8F),
	/** Planks and logs: a little soaks into the grain and stains it. */
	WOOD(2.6F, 0.02F, 2.0F, 0.5F, 0.3F, 0.6F),
	/** Bare earth: the serum wicks in, the red crusts on top. */
	SOIL(4.0F, 0.25F, 30.0F, 0.12F, 0.0F, 1.6F),
	/** Turf: the blades hold a deep film that hardly runs and soaks away slowly. */
	GRASS(6.0F, 0.12F, 30.0F, 0.08F, 0.0F, 2.5F),
	/** Sand and other dry grains: gone within seconds, into a dark clump. */
	SAND(5.0F, 2.5F, 60.0F, 0.0F, 0.0F, 1.2F),
	/** Gravel: runs down between the stones. */
	GRAVEL(5.0F, 4.0F, 80.0F, 0.0F, 0.0F, 2.5F),
	/** Wool, carpet, hay, moss, sponge: wicks it up. */
	FABRIC(4.0F, 1.2F, 40.0F, 0.0F, 0.0F, 1.0F),
	/** Snow: warm blood melts into it. */
	SNOW(5.0F, 1.5F, 50.0F, 0.0F, 0.0F, 1.0F),
	/** Mud and wet clay: already full of water, so little soaks in. */
	MUD(3.5F, 0.02F, 5.0F, 0.35F, 0.25F, 1.2F),
	/** Leaves: it drips through. */
	LEAVES(3.0F, 3.0F, 15.0F, 0.15F, 0.0F, 1.5F);

	/** Depth, mm, below which a film on it does not spread. */
	public final float hold;
	/** How fast a dry floor of it takes blood in, mm a second. */
	public final float soakRate;
	/** How much it takes in before it is full, mm. */
	public final float takes;
	/** How much a wet film on it mirrors the sky, 0 to 1. */
	public final float gloss;
	/** How slippery a wet film makes it, 0 to 1. */
	public final float slip;
	/**
	 * How uneven it is, mm: no floor is flat to the millimetre, and a film a few millimetres deep finds the low places
	 * first. It is what gives a pool its lobes and leaves dry islands in it.
	 */
	public final float relief;

	Surface(float hold, float soakRate, float takes, float gloss, float slip, float relief) {
		this.hold = hold;
		this.soakRate = soakRate;
		this.takes = takes;
		this.gloss = gloss;
		this.slip = slip;
		this.relief = relief;
	}

	/** True if blood soaks into it rather than standing on it. */
	public boolean porous() {
		return takes >= 5.0F;
	}

	/**
	 * The blood, mm, that covers a floor of it from edge to edge. With less than half of that over a place the floor
	 * there still reads as bare: the blood is in its texture, not over it, and is not drawn.
	 */
	public float cover() {
		return Math.clamp(hold * 0.6F, 0.9F, 1.5F);
	}
}
