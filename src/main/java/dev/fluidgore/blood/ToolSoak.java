package dev.fluidgore.blood;

import dev.fluidgore.net.GoreSync;
import dev.fluidgore.server.BloodyTools;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * How bloody this player's cleaning tool is (3.1). When the server runs Fluid Gore the stain is on the stack itself and
 * the server keeps it ({@link BloodyTools}); otherwise this game keeps its own reckoning, one per kind of tool, that
 * nobody else sees and the item does not show.
 */
public final class ToolSoak {
	private record Stain(float soak, int rgb) {
	}

	/** Kind of tool -> its stain, for a server without Fluid Gore. */
	private static final Map<Item, Stain> LOCAL = new HashMap<>();

	private ToolSoak() {
	}

	private static boolean serverStains() {
		return GoreSync.serverKeepsBodies();
	}

	public static float soak(ItemStack stack) {
		if (serverStains()) {
			return BloodyTools.soak(stack);
		}

		Stain local = LOCAL.get(stack.getItem());
		return local != null ? local.soak() : 0.0F;
	}

	/** The colour of the blood in the tool, {@code fallback} if it holds none. */
	public static int colour(ItemStack stack, int fallback) {
		if (serverStains()) {
			return BloodyTools.colour(stack, fallback);
		}

		Stain local = LOCAL.get(stack.getItem());
		return local != null && local.soak() > BloodyTools.CLEAN ? local.rgb() : fallback;
	}

	/** The tool took up {@code lifted} litres of {@code rgb} blood: kept here only if the server does not keep it. */
	public static void absorb(ItemStack stack, float lifted, int rgb) {
		if (!serverStains() && lifted > 0.0F) {
			add(stack, BloodyTools.absorbs(stack, lifted), rgb);
		}
	}

	/** A body wiped away with the tool. */
	public static void wipedBody(ItemStack stack, int rgb) {
		if (!serverStains()) {
			add(stack, BloodyTools.BODY, rgb);
		}
	}

	private static void add(ItemStack stack, float added, int rgb) {
		float before = soak(stack);
		LOCAL.put(stack.getItem(), new Stain(Math.min(1.0F, before + added), BloodyTools.mix(colour(stack, rgb), before, rgb, added)));
	}

	/** Rinsed once in water. */
	public static void rinse(ItemStack stack) {
		Stain local = LOCAL.get(stack.getItem());
		if (!serverStains() && local != null) {
			LOCAL.put(stack.getItem(), new Stain(Math.max(0.0F, local.soak() - BloodyTools.RINSE), local.rgb()));
		}
	}

	public static void clear() {
		LOCAL.clear();
	}
}
