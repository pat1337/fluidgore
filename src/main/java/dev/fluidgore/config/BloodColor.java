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
	/** An enderman's: bright violet, and it crumbles like purpur and chorus. */
	ENDER(0x9A2AD8, () -> Blocks.PURPUR_BLOCK);

	public final int rgb;
	private final Supplier<Block> chunkBlock;

	BloodColor(int rgb, Supplier<Block> chunkBlock) {
		this.rgb = rgb;
		this.chunkBlock = chunkBlock;
	}

	public Block chunkBlock() {
		return chunkBlock.get();
	}

	public String translationKey() {
		return "fluidgore.color." + name().toLowerCase(Locale.ROOT);
	}
}
