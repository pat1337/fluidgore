package dev.fluidgore.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen sits and what the mouse is over, for the sort buttons. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int fluidgore$leftPos();

	@Accessor("topPos")
	int fluidgore$topPos();

	@Accessor("imageWidth")
	int fluidgore$imageWidth();

	@Accessor("inventoryLabelY")
	int fluidgore$inventoryLabelY();

	@Accessor("hoveredSlot")
	@Nullable Slot fluidgore$hoveredSlot();
}
