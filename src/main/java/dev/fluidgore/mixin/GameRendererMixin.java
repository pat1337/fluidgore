package dev.fluidgore.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.fluidgore.GoreSystems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The local player's injuries in their own view (see {@code PlayerInjuries}): a heavy blow knocks the view aside and a
 * failing body sways, after vanilla's hurt tilt; a crippled leg hitches the step, after vanilla's view bobbing (so it
 * follows the View Bobbing option). Both the world and the held item get it, as vanilla's own do.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "bobHurt", at = @At("TAIL"))
	private void fluidgore$stagger(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.getCameraEntity() == mc.player && !cameraState.entityRenderState.isDeadOrDying) {
			GoreSystems.injuries().staggerView(poseStack, mc.player, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		}
	}

	@Inject(method = "bobView", at = @At("TAIL"))
	private void fluidgore$limp(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && Minecraft.getInstance().getCameraEntity() == player && cameraState.entityRenderState.isPlayer) {
			GoreSystems.injuries().limpView(poseStack, player, cameraState.entityRenderState.backwardsInterpolatedWalkDistance,
					cameraState.entityRenderState.bob);
		}
	}
}
