package dev.fluidgore.mixin;

import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.suffer.SufferPose;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adjusts an entity's model right before it is drawn: hides severed parts and adds suffering motion (head
 * thrashing, legs kicking, shivering). Models are shared between all entities of a type and drawn later than
 * they are submitted, so the only safe moment is here: after {@code setupAnim} has posed the model for this
 * entity and immediately around {@code renderToBuffer}. Everything is restored right after drawing so other
 * entities using the same model are unaffected. Armour and other layers reuse the entity's render state, so a
 * severed head takes its helmet with it.
 */
@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureRendererMixin {
	@Unique
	private final List<ModelPart> fluidgore$hidden = new ArrayList<>();
	@Unique
	private final List<ModelPart> fluidgore$moved = new ArrayList<>();
	@Unique
	private final List<float[]> fluidgore$savedRotations = new ArrayList<>();

	@Inject(
			method = "prepareModel",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V")
	)
	private void fluidgore$beforeDraw(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		fluidgore$restore();
		if (!(submit.state() instanceof LivingEntityRenderState state)) {
			return;
		}

		Integer mask = state.getData(Dismemberment.MISSING_PARTS);
		SufferPose pose = state.getData(SufferPose.KEY);
		if ((mask == null || mask == 0) && pose == null) {
			return;
		}

		Model<?> model = submit.model();
		ModelPart[] parts = ModelParts.index(model);
		if (mask != null && mask != 0) {
			for (BodyPart part : BodyPart.values()) {
				ModelPart modelPart = parts[part.ordinal()];
				if ((mask & part.bit()) != 0 && modelPart != null && modelPart.visible) {
					modelPart.visible = false;
					fluidgore$hidden.add(modelPart);
				}
			}
		}

		if (pose != null) {
			fluidgore$animate(parts, pose, state.ageInTicks);
		}
	}

	@Inject(
			method = "prepareModel",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", shift = At.Shift.AFTER)
	)
	private void fluidgore$afterDraw(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		fluidgore$restore();
	}

	/**
	 * Legs kick out of phase with each other (paddling), the head is thrown around and everything shivers.
	 * Amplitudes come from the pose; the motion itself is computed from time so it stays smooth every frame.
	 */
	@Unique
	private void fluidgore$animate(ModelPart[] parts, SufferPose pose, float t) {
		ModelPart head = parts[BodyPart.HEAD.ordinal()];
		if (head != null && (pose.thrash() > 0.01F || pose.tremble() > 0.01F)) {
			fluidgore$save(head);
			float shiver = pose.tremble() * 0.06F;
			head.xRot += Mth.sin(t * 0.83F) * pose.thrash() * 0.6F + SufferPose.noise(t * 5.0F, 1.0F) * shiver;
			head.yRot += Mth.sin(t * 1.37F + 1.0F) * pose.thrash() * 0.7F + SufferPose.noise(t * 5.5F, 2.0F) * shiver;
			head.zRot += Mth.sin(t * 2.1F) * pose.thrash() * 0.3F;
		}

		if (pose.kick() > 0.01F) {
			BodyPart[] legs = {BodyPart.RIGHT_ARM, BodyPart.LEFT_ARM, BodyPart.RIGHT_LEG, BodyPart.LEFT_LEG};
			for (int i = 0; i < legs.length; i++) {
				ModelPart leg = parts[legs[i].ordinal()];
				if (leg == null) {
					continue;
				}

				fluidgore$save(leg);
				float phase = i * 1.9F;
				leg.xRot += Mth.sin(t * pose.kickRate() + phase) * pose.kick() * 0.9F;
				leg.zRot += SufferPose.noise(t * 2.0F, phase) * pose.kick() * 0.2F;
			}
		}
	}

	@Unique
	private void fluidgore$save(ModelPart part) {
		fluidgore$moved.add(part);
		fluidgore$savedRotations.add(new float[]{part.xRot, part.yRot, part.zRot});
	}

	@Unique
	private void fluidgore$restore() {
		for (ModelPart part : fluidgore$hidden) {
			part.visible = true;
		}

		fluidgore$hidden.clear();
		for (int i = 0; i < fluidgore$moved.size(); i++) {
			ModelPart part = fluidgore$moved.get(i);
			float[] saved = fluidgore$savedRotations.get(i);
			part.xRot = saved[0];
			part.yRot = saved[1];
			part.zRot = saved[2];
		}

		fluidgore$moved.clear();
		fluidgore$savedRotations.clear();
	}
}
