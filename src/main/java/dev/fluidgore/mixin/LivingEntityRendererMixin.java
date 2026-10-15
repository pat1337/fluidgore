package dev.fluidgore.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.player.Injury;
import dev.fluidgore.player.InjuryPose;
import dev.fluidgore.suffer.BodyPose;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.Ragdoll;
import dev.fluidgore.suffer.SufferPose;
import dev.fluidgore.wound.BloodyWool;
import dev.fluidgore.wound.Burns;
import dev.fluidgore.wound.Wounds;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
	@Shadow
	protected EntityModel model;

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
		// Charring and plucking both tint the body; together they multiply.
		Integer burnt = GoreSystems.dismemberment().burns().tint(entity.getId());
		Integer plucked = GoreSystems.dismemberment().butchery().tint(entity.getId());
		Integer tint = burnt;
		if (plucked != null) {
			tint = burnt == null ? plucked : Integer.valueOf(Burns.apply(0xFF000000 | burnt, plucked) & 0xFFFFFF);
		}

		state.setData(Burns.KEY, tint);
		state.setData(BloodyWool.KEY, GoreSystems.dismemberment().wool().tint(entity.getId()));
		state.setData(ModelParts.ENTITY_ID, entity.getId());
		state.setData(Injury.KEY, GoreSystems.suffering().corpse(entity.getId()) == null ? Injury.of(entity) : null);

		Corpse corpse = GoreSystems.suffering().corpse(entity.getId());
		if (corpse != null) {
			Ragdoll body = corpse.body;
			// The ragdoll does its own falling over; vanilla's scripted tip-over stays off.
			state.deathTime = 0.0F;
			// Vanilla draws the flames at the entity's real spot, not where the ragdoll has the body: they hang in the air.
			// Burns puts flame particles on the body itself.
			state.displayFireAnimation = false;
			state.bodyRot = body.yaw(partialTicks);
			state.yRot = 0.0F;
			state.xRot = 0.0F;
			state.setData(BodyPose.KEY, new BodyPose(body.x(partialTicks) - state.x, body.y(partialTicks) - state.y,
					body.z(partialTicks) - state.z, body.roll(partialTicks), body.joints(partialTicks)));
			// It went down at once: no walking legs, only what the dying body does itself.
			state.walkAnimationSpeed = 0.0F;
			state.setData(SufferPose.CLOCK, corpse.age + partialTicks);
			// Vanilla keeps a dying mob red while it dies. Red Death does the same for the whole of the throes; after that
			// (and without it) only the first moments and fresh blows are red.
			state.hasRedOverlay = GoreConfig.get().redDeath && !corpse.lifeless() || corpse.age < 10 || corpse.flash > 0;
		} else {
			state.setData(BodyPose.KEY, null);
			state.setData(SufferPose.CLOCK, null);
			if (!entity.isAlive() && entity.deathTime > 20) {
				// A body the server keeps, with no corpse here (suffering is off): vanilla would keep it red for good.
				state.hasRedOverlay = entity.hurtTime > 0;
			}
		}
	}

	/**
	 * Skips bodies torn apart by a blast, and remembers the model this entity is drawn with. Subclasses pick it (baby or adult, per variant) right before
	 * calling this method, so here it is the right one for this entity.
	 */
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
	private void fluidgore$recordModel(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		Integer id = state.getData(ModelParts.ENTITY_ID);
		if (id != null && GoreSystems.dismemberment().vaporized(id)) {
			// Torn apart by a blast: there is no body left to draw.
			ci.cancel();
			return;
		}

		if (id != null) {
			ModelParts.recordDrawn(id, model);
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
			// Lift it by the half-width it swings below the pivot (pose scale is already applied here). Past the side, on
			// its way onto its back, the whole height of the body swings below the feet as well.
			float roll = body.roll();
			Integer id = state.getData(ModelParts.ENTITY_ID);
			float side = id != null ? ModelParts.sideLift(id, state.boundingBoxWidth, state.scale) : state.boundingBoxWidth * 0.5F;
			float lift = side * Mth.abs(Mth.sin(roll)) + state.boundingBoxHeight * Math.max(0.0F, -Mth.cos(roll)) * 0.7F;
			poseStack.translate(0.0F, lift / entityScale, 0.0F);
			poseStack.rotateDegrees(Axis.ZP, body.roll() * Mth.RAD_TO_DEG);
		}

		Injury injury = state.getData(Injury.KEY);
		if (injury != null && state instanceof HumanoidRenderState) {
			// A limp: the body drops onto the bad leg as it takes the weight.
			poseStack.rotateDegrees(Axis.ZP, InjuryPose.lean(injury, state));
		}

		SufferPose pose = state.getData(SufferPose.KEY);
		if (pose == null) {
			return;
		}

		Float clock = state.getData(SufferPose.CLOCK);
		float t = clock != null ? clock : state.ageInTicks;
		float gasp = pose.gaspAmount(t);
		if (gasp > 0.0F) {
			// An agonal gasp: the chest heaves out and sags back.
			poseStack.scale(1.0F + 0.07F * gasp, 1.0F + 0.03F * gasp, 1.0F + 0.02F * gasp);
		}

		float shiverZ = SufferPose.noise(t * 4.0F, 3.0F) * pose.tremble() * 3.0F;
		float shiverX = SufferPose.noise(t * 4.3F, 7.0F) * pose.tremble() * 2.0F;
		float convulse = SufferPose.noise(t * 1.1F, 11.0F) * pose.kick() * 4.0F;
		float wobble = Mth.sin(t * 0.35F) * pose.stagger();
		poseStack.translate(0.0F, -pose.drop(), 0.0F);
		poseStack.rotateDegrees(Axis.XP, pose.tiltX() + shiverX);
		poseStack.rotateDegrees(Axis.ZP, pose.tiltZ() + shiverZ + wobble + convulse);
	}
}
