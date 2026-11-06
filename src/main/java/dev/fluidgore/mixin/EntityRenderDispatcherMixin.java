package dev.fluidgore.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.fluidgore.wound.BodyFlames;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Draws a burning body's flames on the body where it lies (see {@link BodyFlames}), not at the entity's own spot. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Redirect(
			method = "submit",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitFlame(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lorg/joml/Quaternionf;)V"
			)
	)
	private void fluidgore$bodyFlames(SubmitNodeCollector collector, PoseStack poseStack, EntityRenderState state, Quaternionf rotation) {
		BodyFlames flames = state.getData(BodyFlames.KEY);
		if (flames == null) {
			collector.submitFlame(poseStack, state, rotation);
			return;
		}

		poseStack.pushPose();
		poseStack.translate(flames.dx(), flames.dy(), flames.dz());
		// Vanilla's sheet is never less than about twice as tall as it is wide (it was made for a mob standing up): one
		// layer of it, squashed down to the height of the fire on a body lying on the ground.
		poseStack.scale(1.0F, flames.height() / BodyFlames.sheetHeight(flames.width()), 1.0F);
		collector.submitFlame(poseStack, flames.state(state), rotation);
		poseStack.popPose();
	}
}
