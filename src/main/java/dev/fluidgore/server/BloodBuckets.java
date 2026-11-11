package dev.fluidgore.server;

import dev.fluidgore.config.GoreConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Blood in a bucket. An ordinary bucket dipped in blood holds it, by the litre: a bucket takes what the game's
 * buckets take, a whole block of liquid, which is a thousand litres (see {@link GoreConfig#bucketLitres}), so it is
 * rarely full. What it holds lives on the stack itself ({@code custom_model_data}: float 0 how full it is, 0 to 1,
 * float 1 the litres, colour 0 the blood's colour), with a name and a line saying how much, so everyone sees it (see
 * {@code assets/minecraft/items/bucket.json}) and it stays with the item. It is still the game's own bucket: no item
 * is registered, and emptied it is a plain bucket again. Only the server writes it; on a server without Fluid Gore
 * each game keeps its own reckoning (see {@code blood/Buckets}).
 */
public final class BloodBuckets {
	/** Litres in a US gallon. */
	private static final float GALLON = 3.785F;

	private BloodBuckets() {
	}

	/** What a bucket holds: the litres of blood and its colour. */
	public record Held(float litres, int rgb) {
		public static final Held NONE = new Held(0.0F, 0);
	}

	/** Litres a bucket takes. */
	public static float capacity() {
		return GoreConfig.get().bucketLitres;
	}

	/** True if {@code stack} is a bucket blood can go into: the game's empty bucket, with blood in it or not. */
	public static boolean isBucket(ItemStack stack) {
		return stack.is(Items.BUCKET);
	}

	/** Litres of blood in {@code stack}. */
	public static float litres(ItemStack stack) {
		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		Float litres = data != null ? data.getFloat(1) : null;
		return litres != null && Float.isFinite(litres) ? Math.clamp(litres, 0.0F, 1000.0F) : 0.0F;
	}

	/** The colour of the blood in {@code stack}, or {@code fallback} if it holds none. */
	public static int colour(ItemStack stack, int fallback) {
		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		Integer rgb = data != null ? data.getColor(0) : null;
		return rgb != null && litres(stack) > 0.0F ? rgb & 0xFFFFFF : fallback;
	}

	/** "37.4" for a number of litres or gallons; a whole number once it is in the hundreds. */
	public static String amount(float value) {
		return value >= 100.0F ? String.format(Locale.ROOT, "%.0f", value) : String.format(Locale.ROOT, "%.1f", value);
	}

	public static float gallons(float litres) {
		return litres / GALLON;
	}

	/** Writes {@code litres} of {@code rgb} blood onto a bucket that is to hold some. */
	private static void fill(ItemStack stack, float litres, int rgb) {
		CustomModelData data = stack.getOrDefault(DataComponents.CUSTOM_MODEL_DATA, CustomModelData.EMPTY);
		List<Float> floats = new ArrayList<>(data.floats());
		List<Integer> colors = new ArrayList<>(data.colors());
		while (floats.size() < 2) {
			floats.add(0.0F);
		}

		if (colors.isEmpty()) {
			colors.add(0);
		}

		floats.set(0, Math.clamp(litres / capacity(), 0.001F, 1.0F));
		floats.set(1, litres);
		colors.set(0, rgb & 0xFFFFFF);
		stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(floats, data.flags(), data.strings(), colors));
		// A game without the mod shows the plain words.
		stack.set(DataComponents.ITEM_NAME, Component.translatableWithFallback("item.fluidgore.bucket_of_blood", "Bucket of Blood"));
		Component line = Component.translatableWithFallback("item.fluidgore.bucket_of_blood.amount", "%s litres (%s gallons)", amount(litres),
				amount(gallons(litres))).withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false));
		stack.set(DataComponents.LORE, new ItemLore(List.of(line)));
		// Two buckets with different amounts in them are not one stack.
		stack.set(DataComponents.MAX_STACK_SIZE, 1);
	}

	/**
	 * {@code player} dipped the bucket they hold into {@code litres} of {@code rgb} blood (their game measured it; no
	 * more is believed than the bucket has room for). From a stack of empty buckets one is filled and goes into the
	 * inventory, as with water. Returns the litres it took.
	 */
	static float scoop(ServerPlayer player, float litres, int rgb) {
		ItemStack held = player.getMainHandItem();
		if (!isBucket(held) || !(litres > 0.0F)) {
			return 0.0F;
		}

		float before = litres(held);
		float taken = Math.min(litres, capacity() - before);
		if (taken <= 0.0F) {
			return 0.0F;
		}

		int mixed = before <= 0.0F ? rgb : BloodyTools.mix(colour(held, rgb), before, rgb, taken);
		if (held.getCount() > 1) {
			ItemStack one = held.split(1);
			fill(one, before + taken, mixed);
			if (!player.getInventory().add(one)) {
				player.drop(one, false, Prediction.SERVER_ONLY);
			}
		} else {
			fill(held, before + taken, mixed);
		}

		return taken;
	}

	/** {@code player} tips out the bucket they hold: it is a plain bucket again. Returns what was in it. */
	static Held pour(ServerPlayer player) {
		ItemStack held = player.getMainHandItem();
		float litres = isBucket(held) ? litres(held) : 0.0F;
		if (litres <= 0.0F) {
			return Held.NONE;
		}

		int rgb = colour(held, 0x8A0303);
		ItemStack empty = new ItemStack(Items.BUCKET);
		Component named = held.get(DataComponents.CUSTOM_NAME);
		if (named != null) {
			empty.set(DataComponents.CUSTOM_NAME, named);
		}

		player.setItemInHand(InteractionHand.MAIN_HAND, empty);
		return new Held(litres, rgb);
	}
}
