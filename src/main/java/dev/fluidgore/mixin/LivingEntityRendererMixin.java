package dev.fluidgore.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.fluidgore.GoreSystems;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.suffer.BodyPose;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.Ragdoll;
import dev.fluidgore.suffer.SufferPose;
import dev.fluidgore.wound.Wounds;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies per-entity gore state into the render state (rendering only sees render states, never the entity):
 * the missing-part mask and wounds for {@link ModelFeatureRendererMixin}, the suffering pose, and for dying animals the
 * corpse's own death animation, which replaces vanilla's fixed one-second tip-over.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL")
	)
	private void fluidgore$attachGoreState(LivingEntity entity, LivingEntityRenderState state, float partialTicks, CallbackInfo ci) {
		int mask = GoreSystems.dismemberment().missingMask(entity.getId());
		// Always write, so a reused render state never keeps stale data from another entity.
		state.setData(Dismemberment.MISSING_PARTS, mask == 0 ? null : mask);
		state.setData(SufferPose.KEY, GoreSystems.suffering().pose(entity.getId()));
		state.setData(Wounds.KEY, GoreSystems.dismemberment().wounds().get(entity.getId()));

		Corpse corpse = GoreSystems.suffering().corpse(entity.getId());
		if (corpse != null) {
			Ragdoll body = corpse.body;
			// The ragdoll does its own falling over; vanilla's scripted tip-over stays off.
			state.deathTime = 0.0F;
			state.bodyRot = body.yaw(partialTicks);
			state.yRot = 0.0F;
			state.xRot = 0.0F;
			state.setData(BodyPose.KEY, new BodyPose(body.x(partialTicks) - state.x, body.y(partialTicks) - state.y,
					body.z(partialTicks) - state.z, body.roll(partialTicks), body.joints(partialTicks)));
			state.walkAnimationPos = corpse.walkPos(partialTicks);
			state.walkAnimationSpeed = corpse.walkSpeed();
			// Vanilla tints dying mobs red; over a long death that just looks wrong.
			state.hasRedOverlay = corpse.age < 10;
		} else {
			state.setData(BodyPose.KEY, null);
		}
	}

	/** Moves a ragdolled body from where vanilla would draw the entity to where the physics put it. */
	@Inject(method = "setupRotations", at = @At("HEAD"))
	private void fluidgore$place(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float entityScale, CallbackInfo ci) {
		BodyPose body = state.getData(BodyPose.KEY);
		if (body != null) {
			poseStack.translate(body.dx(), body.dy(), body.dz());
		}
	}

	/**
	 * Whole-body motion, applied after vanilla has turned the body to face its direction (and tipped it over if
	 * dead): shivering, sagging and tilting towards missing legs, a staggering wobble, and convulsions.
	 */
	@Inject(method = "setupRotations", at = @At("TAIL"))
	private void fluidgore$suffer(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float entityScale, CallbackInfo ci) {
		BodyPose body = state.getData(BodyPose.KEY);
		if (body != null) {
			// The lean pivots on the feet, so a body lying on its side would sink half its width into the floor.
			// Lift it by the half-width it swings below the pivot (pose scale is already applied here).
			poseStack.translate(0.0F, state.boundingBoxWidth * 0.5F / entityScale * Mth.abs(Mth.sin(body.roll())), 0.0F);
			poseStack.rotateDegrees(Axis.ZP, body.roll() * Mth.RAD_TO_DEG);
		}

		SufferPose pose = state.getData(SufferPose.KEY);
		if (pose == null) {
			return;
		}

		float t = state.ageInTicks;
		float shiverZ = SufferPose.noise(t * 4.0F, 3.0F) * pose.tremble() * 3.0F;
		float shiverX = SufferPose.noise(t * 4.3F, 7.0F) * pose.tremble() * 2.0F;
		float convulse = SufferPose.noise(t * 1.1F, 11.0F) * pose.kick() * 4.0F;
		float wobble = Mth.sin(t * 0.35F) * pose.stagger();
		poseStack.translate(0.0F, -pose.drop(), 0.0F);
		poseStack.rotateDegrees(Axis.XP, pose.tiltX() + shiverX);
		poseStack.rotateDegrees(Axis.ZP, pose.tiltZ() + shiverZ + wobble + convulse);
	}
}
