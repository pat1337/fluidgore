package dev.fluidgore.server;

import dev.fluidgore.config.GoreConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bloody cleaning tools (3.1): a sponge or brush soaks up the blood it lifts and must be washed in water before it
 * cleans well again. How bloody it is lives on the stack itself ({@code custom_model_data}: float 0 the soak, 0..1,
 * colour 0 the blood's colour), so everyone sees the stain (see {@code assets/minecraft/items/sponge.json}) and it
 * stays with the item. Only the server writes it; on a server without Fluid Gore each game keeps its own (see
 * {@code blood/ToolSoak}). The stack's other custom model data is left as it was.
 */
public final class BloodyTools {
	/** Raw blood a stack can hold before it is soaked through (a puddle's worth is about 0.3). Brushes hold less. */
	private static final float SPONGE_HOLDS = 0.6F;
	private static final float BRUSH_HOLDS = 0.25F;
	/** One rinse takes this much of the soak out: a few dunks wash a soaked sponge. */
	public static final float RINSE = 0.4F;
	/** Below this it counts as clean. */
	public static final float CLEAN = 0.02F;
	/** A body wiped away with the tool soaks it this much. */
	public static final float BODY = 0.35F;
	private static final double WASH_REACH = 5.0;

	private BloodyTools() {
	}

	/** True if {@code stack} is one of the configured cleaning items. */
	public static boolean isTool(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}

		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		for (String entry : GoreConfig.get().cleaningItems) {
			if (entry.trim().equals(id)) {
				return true;
			}
		}

		return false;
	}

	/** How much blood {@code stack} holds, 0 (clean) to 1 (soaked through). */
	public static float soak(ItemStack stack) {
		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		Float soak = data != null ? data.getFloat(0) : null;
		return soak != null && Float.isFinite(soak) ? Math.clamp(soak, 0.0F, 1.0F) : 0.0F;
	}

	/** The colour of the blood in {@code stack}, or {@code fallback} if it holds none. */
	public static int colour(ItemStack stack, int fallback) {
		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		Integer rgb = data != null ? data.getColor(0) : null;
		return rgb != null ? rgb & 0xFFFFFF : fallback;
	}

	/** The soak {@code lifted} litres of blood add to a tool like {@code stack} (all of a stack shares it). */
	public static float absorbs(ItemStack stack, float lifted) {
		float holds = stack.is(Items.BRUSH) ? BRUSH_HOLDS : SPONGE_HOLDS;
		return lifted / (holds * Math.max(1, stack.getCount()));
	}

	/** The colour after {@code added} soak of {@code rgb} joins {@code before} soak of {@code old}: mixed by amount. */
	public static int mix(int old, float before, int rgb, float added) {
		if (before <= CLEAN) {
			return rgb;
		}

		float t = added / Math.max(1.0E-4F, before + added);
		int r = Math.round(((old >> 16) & 0xFF) * (1.0F - t) + ((rgb >> 16) & 0xFF) * t);
		int g = Math.round(((old >> 8) & 0xFF) * (1.0F - t) + ((rgb >> 8) & 0xFF) * t);
		int b = Math.round((old & 0xFF) * (1.0F - t) + (rgb & 0xFF) * t);
		return r << 16 | g << 8 | b;
	}

	/** Sets how bloody {@code stack} is; at {@link #CLEAN} or less the stain comes off it altogether. */
	public static void set(ItemStack stack, float soak, int rgb) {
		CustomModelData data = stack.getOrDefault(DataComponents.CUSTOM_MODEL_DATA, CustomModelData.EMPTY);
		List<Float> floats = new ArrayList<>(data.floats());
		List<Integer> colors = new ArrayList<>(data.colors());
		if (floats.isEmpty()) {
			floats.add(0.0F);
		}

		if (colors.isEmpty()) {
			colors.add(0);
		}

		boolean clean = soak <= CLEAN;
		floats.set(0, clean ? 0.0F : Math.min(1.0F, soak));
		colors.set(0, clean ? 0 : rgb & 0xFFFFFF);
		if (clean && floats.size() == 1 && colors.size() == 1 && data.flags().isEmpty() && data.strings().isEmpty()) {
			// Only ever ours: a washed tool stacks with a new one again.
			stack.remove(DataComponents.CUSTOM_MODEL_DATA);
		} else {
			stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(floats, data.flags(), data.strings(), colors));
		}
	}

	/**
	 * {@code player}'s tool took up {@code lifted} litres of blood of colour {@code rgb} (their game measured it; at most
	 * a puddle's worth a stroke is believed).
	 */
	static void absorb(ServerPlayer player, float lifted, int rgb) {
		ItemStack stack = player.getMainHandItem();
		if (!isTool(stack) || !(lifted > 0.0F)) {
			return;
		}

		float before = soak(stack);
		float added = absorbs(stack, Math.min(lifted, 0.5F));
		set(stack, before + added, mix(colour(stack, rgb), before, rgb, added));
	}

	/** A body wiped away with {@code player}'s tool. */
	static void wipedBody(ServerPlayer player, int rgb) {
		ItemStack stack = player.getMainHandItem();
		if (isTool(stack)) {
			float before = soak(stack);
			set(stack, before + BODY, mix(colour(stack, rgb), before, rgb, BODY));
		}
	}

	/**
	 * {@code player} washes their tool: true if there is water to wash it in (they stand in it, or look at water or a
	 * water cauldron within reach). A sponge that comes out clean comes out wet.
	 */
	static boolean wash(ServerPlayer player) {
		ItemStack stack = player.getMainHandItem();
		if (!isTool(stack) || soak(stack) <= CLEAN || !nearWater(player)) {
			return false;
		}

		float left = soak(stack) - RINSE;
		set(stack, left, colour(stack, 0));
		if (left <= CLEAN && stack.is(Items.SPONGE)) {
			player.setItemInHand(InteractionHand.MAIN_HAND, stack.transmuteCopy(Items.WET_SPONGE));
		}

		return true;
	}

	/** In water, or looking at water (or a water cauldron) within reach. */
	public static boolean nearWater(net.minecraft.world.entity.player.Player player) {
		if (player.isInWater()) {
			return true;
		}

		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(WASH_REACH));
		BlockHitResult hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.WATER, player));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return false;
		}

		FluidState fluid = player.level().getFluidState(hit.getBlockPos());
		return !fluid.isEmpty() || player.level().getBlockState(hit.getBlockPos()).is(Blocks.WATER_CAULDRON);
	}
}
