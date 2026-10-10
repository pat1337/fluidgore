package dev.fluidgore.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Sorts a chest or the player's inventory the way the old 1.7.10 sorters did: partial stacks merged, then ordered by
 * item id (blocks first, then tools, food and the rest, as the old numeric ids ran), then by damage, then by name.
 *
 * <p>Works on a vanilla server: every move is an ordinary inventory click, the same ones a player makes by hand, sent
 * all at once. The client's own copy of the container updates with each click, so each step reads where things really
 * are. Nothing is sorted if the player is holding an item on the cursor.
 */
public final class InventorySorter {
	/** Which part of a screen to sort. */
	public enum Region {
		/** The chest, barrel, shulker box or ender chest itself. */
		CONTAINER,
		/** The player's main inventory, without the hotbar, armour or offhand. */
		PLAYER
	}

	private static final Comparator<ItemStack> ORDER = Comparator.<ItemStack>comparingInt(s -> BuiltInRegistries.ITEM.getId(s.getItem()))
			.thenComparingInt(ItemStack::getDamageValue)
			.thenComparing(s -> s.getHoverName().getString())
			.thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed());

	private InventorySorter() {
	}

	/** The slots of {@code region} in {@code menu}, in slot order. */
	public static List<Slot> slots(AbstractContainerMenu menu, Region region) {
		List<Slot> out = new ArrayList<>();
		for (Slot slot : menu.slots) {
			boolean player = slot.container instanceof Inventory;
			if (region == Region.CONTAINER ? !player : player && slot.getContainerSlot() >= Inventory.SELECTION_SIZE
					&& slot.getContainerSlot() < Inventory.INVENTORY_SIZE) {
				out.add(slot);
			}
		}

		return out;
	}

	/** Sorts {@code region} of the open container. Returns false if there was nothing it could do. */
	public static boolean sort(Minecraft mc, AbstractContainerMenu menu, Region region) {
		Player player = mc.player;
		if (player == null || mc.gameMode == null || !menu.getCarried().isEmpty()) {
			return false;
		}

		List<Slot> slots = slots(menu, region);
		if (slots.size() < 2) {
			return false;
		}

		Clicker click = new Clicker(mc, menu, player);
		merge(slots, click, menu);
		order(slots, click, menu);
		if (!menu.getCarried().isEmpty()) {
			// Never leave anything on the cursor: back into the first free slot.
			for (Slot slot : slots) {
				if (!slot.hasItem()) {
					click.at(slot);
					break;
				}
			}
		}

		return true;
	}

	/** Tops up each partial stack from later partial stacks of the same item. */
	private static void merge(List<Slot> slots, Clicker click, AbstractContainerMenu menu) {
		for (int a = 0; a < slots.size(); a++) {
			Slot into = slots.get(a);
			for (int b = a + 1; b < slots.size(); b++) {
				ItemStack target = into.getItem();
				if (target.isEmpty() || target.getCount() >= max(into, target)) {
					break;
				}

				Slot from = slots.get(b);
				if (!from.hasItem() || !ItemStack.isSameItemSameComponents(target, from.getItem()) || !from.mayPickup(click.player)) {
					continue;
				}

				click.at(from);
				click.at(into);
				if (!menu.getCarried().isEmpty()) {
					click.at(from);
				}
			}
		}
	}

	/**
	 * Puts every stack in its sorted place. Slot by slot, the stack that belongs there is found further on and swapped
	 * in: pick it up, click it down (taking up what was there, or topping up a partial stack of the same item), and put
	 * whatever is left on the cursor where it came from.
	 */
	private static void order(List<Slot> slots, Clicker click, AbstractContainerMenu menu) {
		List<ItemStack> wanted = new ArrayList<>();
		for (Slot slot : slots) {
			if (slot.hasItem()) {
				wanted.add(slot.getItem().copy());
			}
		}

		wanted.sort(ORDER);
		for (int i = 0; i < wanted.size(); i++) {
			ItemStack want = wanted.get(i);
			Slot here = slots.get(i);
			if (same(here.getItem(), want)) {
				continue;
			}

			for (int j = i + 1; j < slots.size(); j++) {
				Slot there = slots.get(j);
				if (same(there.getItem(), want)) {
					click.at(there);
					click.at(here);
					if (!menu.getCarried().isEmpty()) {
						click.at(there);
					}

					break;
				}
			}
		}
	}

	private static boolean same(ItemStack a, ItemStack b) {
		return a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b);
	}

	private static int max(Slot slot, ItemStack stack) {
		return Math.min(stack.getMaxStackSize(), slot.getMaxStackSize(stack));
	}

	/** A plain left click on a slot, as the player would make it. */
	private record Clicker(Minecraft mc, AbstractContainerMenu menu, Player player) {
		void at(Slot slot) {
			mc.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.PICKUP, player);
		}
	}
}
