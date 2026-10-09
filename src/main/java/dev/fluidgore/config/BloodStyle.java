package dev.fluidgore.config;

import java.util.Locale;

/** Look of blood splats, puddles and droplets. */
public enum BloodStyle {
	/** Blocky pixel-art splats and chunky drops, like redstone dust. Matches Minecraft's look. */
	REDSTONE,
	/** Smooth, round splats. */
	REALISTIC,
	/** A random mix of both for every splat. */
	MIXED;

	public String translationKey() {
		return "fluidgore.style." + name().toLowerCase(Locale.ROOT);
	}
}
