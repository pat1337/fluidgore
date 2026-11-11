package dev.fluidgore.blood;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which {@link Surface} a block is. Read from what the game already says about the block (its tags, the sound it makes
 * underfoot, how slippery it is, its name), so blocks from other mods are sorted too.
 */
public final class Surfaces {
	private static final Map<Block, Surface> KNOWN = new IdentityHashMap<>();
	/** Words in a block's name that mean a worked, closed face. */
	private static final String[] SMOOTH = {"polished", "smooth", "glass", "quartz", "glazed", "tiles", "chiseled", "obsidian", "purpur",
			"prismarine_brick", "dark_prismarine", "iron_block", "gold_block", "diamond_block", "emerald_block", "netherite_block", "lapis_block",
			"redstone_block", "copper", "amethyst_block", "sea_lantern", "beacon", "calcite", "resin_brick"};
	private static final String[] COARSE = {"cobble", "netherrack", "end_stone", "tuff", "basalt", "dripstone", "magma", "blackstone", "bedrock",
			"_ore", "mossy", "cracked", "infested", "pumice", "scoria"};

	private Surfaces() {
	}

	public static Surface of(BlockState state) {
		Block block = state.getBlock();
		Surface known = KNOWN.get(block);
		if (known == null) {
			known = sort(state);
			KNOWN.put(block, known);
		}

		return known;
	}

	private static Surface sort(BlockState state) {
		String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		SoundType sound = state.getSoundType();
		if (state.is(BlockTags.ICE) || state.is(BlockTags.IMPERMEABLE) || state.is(BlockTags.GLAZED_TERRACOTTA) || state.getBlock().getFriction() > 0.7F
				|| sound == SoundType.GLASS) {
			return Surface.POLISHED;
		}

		if (state.is(BlockTags.SNOW) || sound == SoundType.SNOW || sound == SoundType.POWDER_SNOW) {
			return Surface.SNOW;
		}

		if (state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS) || state.is(BlockTags.MOSS_BLOCKS) || sound == SoundType.WOOL
				|| sound == SoundType.MOSS || sound == SoundType.MOSS_CARPET || sound == SoundType.SPONGE || sound == SoundType.WET_SPONGE
				|| name.contains("hay") || name.contains("straw")) {
			return Surface.FABRIC;
		}

		if (state.is(BlockTags.LEAVES)) {
			return Surface.LEAVES;
		}

		if (state.is(BlockTags.SAND) || state.is(BlockTags.CONCRETE_POWDERS) || sound == SoundType.SAND || sound == SoundType.SOUL_SAND
				|| sound == SoundType.SOUL_SOIL || sound == SoundType.SUSPICIOUS_SAND) {
			return Surface.SAND;
		}

		if (name.contains("gravel")) {
			return Surface.GRAVEL;
		}

		if (state.is(BlockTags.MUD) || sound == SoundType.MUD || sound == SoundType.MUDDY_MANGROVE_ROOTS || name.equals("clay")) {
			return Surface.MUD;
		}

		if (state.is(BlockTags.GRASS_BLOCKS)) {
			return Surface.GRASS;
		}

		if (state.is(BlockTags.DIRT) || name.contains("farmland") || name.contains("dirt") || name.contains("podzol") || state.is(BlockTags.NYLIUM)
				|| sound == SoundType.ROOTED_DIRT) {
			return Surface.SOIL;
		}

		if (state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS) || state.is(BlockTags.WOODEN_SLABS) || state.is(BlockTags.WOODEN_STAIRS)
				|| sound == SoundType.WOOD || sound == SoundType.BAMBOO_WOOD || sound == SoundType.NETHER_WOOD || sound == SoundType.CHERRY_WOOD
				|| sound == SoundType.STEM) {
			return Surface.WOOD;
		}

		for (String word : SMOOTH) {
			if (name.contains(word)) {
				return Surface.POLISHED;
			}
		}

		if (sound == SoundType.METAL || sound == SoundType.NETHERITE_BLOCK || sound == SoundType.COPPER || sound == SoundType.IRON
				|| sound == SoundType.ANVIL || name.startsWith("cut_")) {
			return Surface.POLISHED;
		}

		for (String word : COARSE) {
			if (name.contains(word)) {
				return Surface.ROUGH;
			}
		}

		if (sound == SoundType.GRASS) {
			return Surface.GRASS;
		}

		if (sound == SoundType.GRAVEL || state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
			return Surface.SOIL;
		}

		return Surface.STONE;
	}
}
