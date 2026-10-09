package dev.fluidgore.config;

import java.util.Locale;

/** Selectable blood tints. Values are opaque RGB; alpha is applied by the renderer. */
public enum BloodColor {
	RED(0x8A0303),
	DARK_RED(0x5C0000),
	GREEN(0x3F7A12),
	YELLOW(0xB59A12),
	BLUE(0x1E3F9C),
	PURPLE(0x5B1A7A),
	BLACK(0x1A1414);

	public final int rgb;

	BloodColor(int rgb) {
		this.rgb = rgb;
	}

	public String translationKey() {
		return "fluidgore.color." + name().toLowerCase(Locale.ROOT);
	}
}
