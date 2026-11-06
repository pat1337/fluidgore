package dev.fluidgore.mixin;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.dismember.BodyPart;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What a body holds in its hands. Vanilla's held-item layer places the item by the arm's standing pose, before a dead
 * body is laid down (see {@code ModelFeatureRendererMixin}), so on a falling or lying body a bow or a sword hung in the
 * air where the hand had been, jumping as the pose was reapplied. A dying body lets go of what it held, and a hand that
 * is cut off takes its item with it. Every armed mob and every player fills its hands here, after the rest of its
 * render state.
 */
@Mixin(ArmedEntityRenderState.class)
public abstract class ArmedEntityRenderStateMixin {
	@Inject(method = "extractArmedEntityRenderState", at = @At("TAIL"))
	private static void fluidgore$emptyHands(LivingEntity entity, ArmedEntityRenderState state, ItemModelResolver resolver, float partialTicks,
			CallbackInfo ci) {
		int id = entity.getId();
		boolean dead = GoreSystems.suffering().corpse(id) != null;
		int missing = GoreSystems.dismemberment().missingMask(id);
		if (dead || (missing & BodyPart.RIGHT_ARM.bit()) != 0) {
			state.rightHandItemState.clear();
			state.rightHandItemStack = ItemStack.EMPTY;
		}

		if (dead || (missing & BodyPart.LEFT_ARM.bit()) != 0) {
			state.leftHandItemState.clear();
			state.leftHandItemStack = ItemStack.EMPTY;
		}
	}
}
