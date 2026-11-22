package dev.fluidgore.config;

/**
 * How much of the gore this player's screen draws. A level never changes what happens to a body: how deep a blow goes,
 * what it reaches, whether a part comes off, how much blood leaves and how the animal dies are the same at every level
 * but Off, so players on different levels see the same body. A level only scales how many particles and scraps are
 * drawn, how long blood, pieces and bodies stay, and how far into a wound the screen draws.
 * <p>
 * The top level, {@link #EXTREME}, draws everything that is there; the Gore Level slider calls it Full. Animal
 * Suffering has its own level of the same five steps and reads only {@link #duration} from it.
 */
public enum GoreLevel {
	OFF(0.0F, 0.0F, 0.0F, 1.0F, 0.0F),
	LOW(0.4F, 0.5F, 0.5F, 1.0F, 0.45F),
	MEDIUM(1.0F, 1.0F, 1.0F, 1.0F, 0.7F),
	HIGH(1.0F, 1.2F, 1.6F, 1.5F, 1.0F),
	EXTREME(1.0F, 1.3F, 2.5F, 2.5F, 1.0F);

	/** Multiplier for how many droplets, mist particles and loose scraps are drawn: 1 draws them all. */
	public final float particles;
	/** Multiplier for how long parts and flesh pieces last. */
	public final float duration;
	/**
	 * Multiplier for how long splats and puddles stay before fading. Higher than {@link #duration} at the top, so a
	 * slaughter leaves its evidence on the ground long after the flesh is gone.
	 */
	public final float bloodDuration;
	/** Multiplier for how long a body lies after its death throes. */
	public final float linger;
	/**
	 * The deepest a wound is drawn, as a wound severity: Low draws gashes, Medium open meat, High and the top level the
	 * bone and the organs. The wound itself is as deep as the blows made it (see {@link #drawn}).
	 */
	public final float woundDrawn;

	GoreLevel(float particles, float duration, float bloodDuration, float linger, float woundDrawn) {
		this.particles = particles;
		this.duration = duration;
		this.bloodDuration = bloodDuration;
		this.linger = linger;
		this.woundDrawn = woundDrawn;
	}

	public boolean enabled() {
		return this != OFF;
	}

	/** True at the top level, which draws everything: the only one that draws rot. */
	public boolean full() {
		return this == EXTREME;
	}

	/** How deep a wound of {@code severity} is drawn at this level. */
	public float drawn(float severity) {
		return Math.min(severity, woundDrawn);
	}

	/**
	 * How many of {@code count} particles are drawn at this level. Never none of some: a spray that carries blood keeps
	 * a drop to carry it, so the litres are the same at every level.
	 */
	public int drawn(int count) {
		return count <= 0 || !enabled() ? 0 : Math.max(1, Math.round(count * particles));
	}

	public String translationKey() {
		return "fluidgore.level." + name().toLowerCase(java.util.Locale.ROOT);
	}

	/** The name on the Gore Level slider, where the top level is Full. */
	public String goreKey() {
		return full() ? "fluidgore.level.full" : translationKey();
	}
}
