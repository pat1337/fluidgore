package dev.fluidgore.config;

/**
 * Global intensity preset. Every effect multiplies its base amount by the factors of the active level,
 * so a single slider scales particles, puddles, dismemberment and effect duration together.
 */
public enum GoreLevel {
	OFF(0.0F, 0.0F, 0.0F, 0, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F),
	LOW(0.4F, 0.5F, 0.15F, 1, 0.5F, 0.6F, 0.45F, 0.5F, 1.0F),
	MEDIUM(1.0F, 1.0F, 0.35F, 2, 1.0F, 1.0F, 0.7F, 1.0F, 1.0F),
	HIGH(1.7F, 1.0F, 0.6F, 3, 1.2F, 1.3F, 1.0F, 1.6F, 1.5F),
	EXTREME(2.6F, 1.0F, 1.0F, 5, 1.3F, 1.8F, 1.0F, 2.5F, 2.5F);

	/** Multiplier for droplet and mist particle counts. */
	public final float particles;
	/** Multiplier for the blood volume that lands on the ground (puddle size and amount). */
	public final float puddles;
	/** Probability that a qualifying bladed hit separates at least one body part. */
	public final float partChance;
	/** Maximum number of parts that may separate from one entity in one hit. */
	public final int maxPartsPerHit;
	/** Multiplier for how long parts, flesh pieces and spurts last. */
	public final float duration;
	/** Multiplier for how fast blows deepen wounds. */
	public final float woundRate;
	/**
	 * Deepest a wound may get: Low stops at gashes, Medium at open meat, High and Extreme reach the bone. Parts come
	 * off when a blade goes through a wound already this deep.
	 */
	public final float woundDepth;
	/**
	 * Multiplier for how long splats and puddles stay before fading. Higher than {@link #duration} at the top, so a
	 * slaughter leaves its evidence on the ground long after the flesh is gone.
	 */
	public final float bloodDuration;
	/** Multiplier for how long a body lies after its death throes. */
	public final float linger;

	GoreLevel(float particles, float puddles, float partChance, int maxPartsPerHit, float duration, float woundRate, float woundDepth, float bloodDuration, float linger) {
		this.particles = particles;
		this.puddles = puddles;
		this.partChance = partChance;
		this.maxPartsPerHit = maxPartsPerHit;
		this.duration = duration;
		this.woundRate = woundRate;
		this.woundDepth = woundDepth;
		this.bloodDuration = bloodDuration;
		this.linger = linger;
	}

	public boolean enabled() {
		return this != OFF;
	}

	public String translationKey() {
		return "fluidgore.level." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
