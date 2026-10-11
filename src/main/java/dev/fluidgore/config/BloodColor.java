package dev.fluidgore.config;

import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Selectable blood tints. Values are opaque RGB; alpha is applied by the renderer. Each colour also names a
 * vanilla block whose break particles are used as flying gore chunks (redstone block crumbs for red blood).
 */
public enum BloodColor {
	RED(0x8A0303, () -> Blocks.REDSTONE_BLOCK),
	DARK_RED(0x5C0000, () -> Blocks.NETHER_WART_BLOCK),
	GREEN(0x3F7A12, () -> Blocks.SLIME_BLOCK),
	YELLOW(0xB59A12, () -> Blocks.HONEY_BLOCK),
	BLUE(0x1E3F9C, () -> Blocks.LAPIS_BLOCK),
	PURPLE(0x5B1A7A, () -> Blocks.AMETHYST_BLOCK),
	BLACK(0x1A1414, () -> Blocks.COAL_BLOCK),
	/** What is left in a walking corpse: dark, brown and clotted. */
	ROTTEN(0x3A2414, () -> Blocks.MUD),
	/**
	 * An enderman's: violet, and it crumbles like purpur. Its hue is its own (blue a good two thirds over red, hardly any
	 * green), so {@link #glows} can tell it apart at any brightness.
	 */
	ENDER(0x7616C4, () -> Blocks.PURPUR_BLOCK);

	public final int rgb;
	private final Supplier<Block> chunkBlock;

	BloodColor(int rgb, Supplier<Block> chunkBlock) {
		this.rgb = rgb;
		this.chunkBlock = chunkBlock;
	}

	public Block chunkBlock() {
		return chunkBlock.get();
	}

	/**
	 * True for ender blood, however light or dark it came out (blood varies only in brightness), while Glowing Ender
	 * Blood is on: it is drawn at full brightness, so it glows in the dark and shader packs bloom it.
	 */
	public static boolean glows(int rgb) {
		int r = rgb >> 16 & 0xFF;
		int g = rgb >> 8 & 0xFF;
		int b = rgb & 0xFF;
		return GoreConfig.get().glowingEnderBlood && r >= 8 && b >= r * 1.55F && b <= r * 1.8F && g <= r * 0.3F;
	}

	public String translationKey() {
		return "fluidgore.color." + name().toLowerCase(Locale.ROOT);
	}
}
