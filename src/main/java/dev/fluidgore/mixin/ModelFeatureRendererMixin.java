package dev.fluidgore.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.suffer.BodyPose;
import dev.fluidgore.suffer.SufferPose;
import dev.fluidgore.wound.Burns;
import dev.fluidgore.wound.WoundRenderer;
import dev.fluidgore.wound.WoundSet;
import dev.fluidgore.wound.Wounds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adjusts an entity's model right before it is drawn: hides severed parts and adds suffering motion (head
 * thrashing, legs kicking, shivering). Models are shared between all entities of a type and drawn later than
 * they are submitted, so the only safe moment is here: after {@code setupAnim} has posed the model for this
 * entity and immediately around {@code renderToBuffer}. Wounds are drawn right after the model, in the same part
 * transforms, so they follow the animation. Everything is restored right after drawing so other
 * entities using the same model are unaffected. Armour and other layers reuse the entity's render state, so a
 * severed head takes its helmet with it.
 */
@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureRendererMixin extends RenderTypeFeatureRenderer<ModelFeatureRenderer.Submit<?>> {
	@Shadow
	@Final
	private PoseStack poseStack;
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
		BodyPose body = state.getData(BodyPose.KEY);
		if ((mask == null || mask == 0) && pose == null && body == null) {
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

		if (body != null) {
			// The head and each limb are free-swinging springs.
			ModelParts.applyJoints(parts, body.joints(), this::fluidgore$save);
		}
	}

	@Inject(
			method = "prepareModel",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", shift = At.Shift.AFTER)
	)
	private void fluidgore$afterDraw(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		// Wounds go on the body model only (not armour sharing its state), not on ghostly see-through bodies or the
		// block-breaking overlay, and while the parts are still posed and severed parts still hidden.
		if (submit.state() instanceof LivingEntityRenderState state && submit.sheetedDecalPose() == null
				&& submit.tintedColor() >>> 24 == 0xFF) {
			WoundSet wounds = state.getData(Wounds.KEY);
			if (wounds != null && wounds.count() > 0 && wounds.drawsOn(submit.model())) {
				WoundRenderer.draw(wounds, poseStack, getVertexBuilder(WoundRenderer.renderType()), submit.lightCoords(), submit.overlayCoords());
			}
		}

		fluidgore$restore();
	}

	/** Burnt flesh: the body and anything sharing its state (saddle, wool) darkens; not the block-breaking overlay. */
	@ModifyArg(
			method = "prepareModel",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"),
			index = 4
	)
	private int fluidgore$char(int color, @Local(argsOnly = true) ModelFeatureRenderer.Submit<?> submit) {
		if (submit.sheetedDecalPose() == null && submit.state() instanceof LivingEntityRenderState state) {
			Integer tint = state.getData(Burns.KEY);
			if (tint != null) {
				return Burns.apply(color, tint);
			}
		}

		return color;
	}

	/**
	 * Legs kick out of phase with each other (paddling), the head is thrown around and everything shivers.
	 * Amplitudes come from the pose; the motion itself is computed from time so it stays smooth every frame.
	 */
	@Unique
	private void fluidgore$animate(ModelPart[] parts, SufferPose pose, float t) {
		ModelPart head = parts[BodyPart.HEAD.ordinal()];
		if (head != null && (pose.thrash() > 0.01F || pose.tremble() > 0.01F || pose.headDip() != 0.0F || pose.lookYaw() != 0.0F)) {
			fluidgore$save(head);
			float shiver = pose.tremble() * 0.06F;
			head.xRot += Mth.sin(t * 0.83F) * pose.thrash() * 0.6F + SufferPose.noise(t * 5.0F, 1.0F) * shiver + pose.headDip();
			head.yRot += Mth.sin(t * 1.37F + 1.0F) * pose.thrash() * 0.7F + SufferPose.noise(t * 5.5F, 2.0F) * shiver + pose.lookYaw();
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
