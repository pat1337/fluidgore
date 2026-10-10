package dev.fluidgore.ui;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The 1.7.10 sorting buttons: a tiny button in the top right of a chest (or barrel, ender chest, shulker box) and one
 * next to the player's "Inventory" label, and a middle click (the pick-block button) anywhere over either part sorts that part. See
 * {@link InventorySorter}.
 */
public final class SortButtons {
	private static final int SIZE = 9;

	private SortButtons() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!GoreConfig.get().inventorySorting || !(screen instanceof AbstractContainerScreen<?> container) || !sortable(screen)) {
				return;
			}

			AbstractContainerScreenAccessor at = (AbstractContainerScreenAccessor) screen;
			SortButton chest = hasContainer(screen) ? new SortButton(container, InventorySorter.Region.CONTAINER) : null;
			SortButton mine = new SortButton(container, InventorySorter.Region.PLAYER);
			if (chest != null) {
				Screens.getWidgets(screen).add(chest);
			}

			Screens.getWidgets(screen).add(mine);
			// The recipe book slides the player's inventory sideways, so the buttons follow the screen every frame.
			ScreenEvents.beforeExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				int right = at.fluidgore$leftPos() + at.fluidgore$imageWidth() - SIZE - 6;
				if (chest != null) {
					chest.setPosition(right, at.fluidgore$topPos() + 5);
				}

				mine.setPosition(right, at.fluidgore$topPos() + at.fluidgore$inventoryLabelY() - 1);
			});
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
				if (!client.options.keyPickItem.matchesMouse(event)) {
					return true;
				}

				Slot slot = at.fluidgore$hoveredSlot();
				if (slot == null) {
					return true;
				}

				InventorySorter.Region region = slot.container instanceof Inventory ? InventorySorter.Region.PLAYER : InventorySorter.Region.CONTAINER;
				return !InventorySorter.sort(client, container.getMenu(), region);
			});
		});
	}

	/** Chests, barrels, ender chests, shulker boxes and the player's own inventory (not the creative one). */
	private static boolean sortable(Screen screen) {
		return hasContainer(screen) || screen instanceof InventoryScreen && !(screen instanceof CreativeModeInventoryScreen);
	}

	private static boolean hasContainer(Screen screen) {
		return screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen;
	}

	/** A tiny vanilla-look button with a sort mark: three bars, longest on top. */
	private static final class SortButton extends AbstractButton {
		private final AbstractContainerScreen<?> screen;
		private final InventorySorter.Region region;

		SortButton(AbstractContainerScreen<?> screen, InventorySorter.Region region) {
			super(0, 0, SIZE, SIZE, Component.translatable("fluidgore.sort.button"));
			this.screen = screen;
			this.region = region;
			setTooltip(Tooltip.create(Component.translatable(region == InventorySorter.Region.CONTAINER ? "fluidgore.sort.chest" : "fluidgore.sort.inventory")));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			InventorySorter.sort(Minecraft.getInstance(), screen.getMenu(), region);
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			extractDefaultSprite(graphics);
			int x = getX() + 2;
			int y = getY() + 2;
			int color = 0xFF404040;
			graphics.fill(x, y, x + 5, y + 1, color);
			graphics.fill(x, y + 2, x + 4, y + 3, color);
			graphics.fill(x, y + 4, x + 2, y + 5, color);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}
