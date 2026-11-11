package dev.fluidgore.config;

import java.util.Locale;

/**
 * How finely blood is drawn. The game's own textures have 16 pixels to a block; blood at 16 matches them pixel for
 * pixel. Higher numbers draw the same blood with smaller pixels, and Smooth with none at all: clean edges, soft
 * shading and the sun's glint.
 */
public enum BloodResolution {
	/** 16 pixels to a block, like the game's own textures. */
	X16(16),
	X32(32),
	X64(64),
	/** No pixels: outlines and shading as fine as the screen. */
	SMOOTH(0);

	/** Pixels to a block; 0 for {@link #SMOOTH}. */
	public final int texels;

	BloodResolution(int texels) {
		this.texels = texels;
	}

	/** True if splats and drops are drawn as chunky pixels rather than smooth shapes. */
	public boolean pixelArt() {
		return this == X16;
	}

	public String translationKey() {
		return "fluidgore.resolution." + name().toLowerCase(Locale.ROOT);
	}
}
