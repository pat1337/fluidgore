package dev.fluidgore.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.player.Injury;
import dev.fluidgore.player.InjuryPose;
import dev.fluidgore.rot.Rot;
import dev.fluidgore.rot.RotRenderer;
import dev.fluidgore.suffer.BodyPose;
import dev.fluidgore.suffer.SufferPose;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.wound.BloodyWool;
import dev.fluidgore.wound.Burns;
import dev.fluidgore.wound.WoundRenderer;
import dev.fluidgore.wound.WoundSet;
import dev.fluidgore.wound.Wounds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.animal.sheep.SheepFurModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
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
	/** A folded leg lies flat under the body. */
	@Unique
	private static final float FOLD = 1.45F;
	/** How far a stiff leg is thrust out from straight down. */
	@Unique
	private static final float STIFF = 0.75F;
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
		WoundSet wounds = state.getData(Wounds.KEY);
		Injury injury = submit.model() instanceof HumanoidModel<?> ? state.getData(Injury.KEY) : null;
		Rot rot = state.getData(Rot.KEY);
		if ((mask == null || mask == 0) && pose == null && body == null && (wounds == null || wounds.brokenMask() == 0) && injury == null && rot == null) {
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
			Float clock = state.getData(SufferPose.CLOCK);
			fluidgore$animate(parts, pose, clock != null ? clock : state.ageInTicks);
		}

		if (injury != null) {
			InjuryPose.apply(parts, injury, state, this::fluidgore$save);
		}

		if (body != null) {
			// The head and each limb are free-swinging springs.
			ModelParts.applyJoints(parts, body.joints(), this::fluidgore$save);
		}

		if (wounds != null && wounds.brokenMask() != 0 && wounds.drawsOn(model)) {
			ModelParts.applyFractures(model, parts, wounds, this::fluidgore$save);
		}

		if (rot != null) {
			fluidgore$rot(model, parts, rot);
		}
	}

	/**
	 * A rotting body: rigor mortis arches the head back and locks the legs; the gas of the bloat swells the trunk and
	 * pushes the legs out stiff, until it bursts and the trunk sinks in. Wool, saddles and armour swell with it.
	 */
	@Unique
	private void fluidgore$rot(Model<?> model, ModelPart[] parts, Rot rot) {
		float swell = rot.swell();
		float rigor = rot.rigor();
		ModelPart torso = ModelParts.torso(model);
		if (torso != null && swell != 0.0F) {
			fluidgore$save(torso);
			float girth = 1.0F + 0.28F * swell;
			torso.xScale *= girth;
			torso.zScale *= girth;
			torso.yScale *= 1.0F + 0.1F * swell;
		}

		float out = Math.max(rigor * 0.3F, Math.max(0.0F, swell) * 0.7F);
		if (out > 0.0F) {
			for (int i = 0; i < ModelParts.LIMBS.length; i++) {
				ModelPart leg = parts[ModelParts.LIMBS[i].ordinal()];
				if (leg != null) {
					fluidgore$save(leg);
					leg.xRot += i < 2 ? -out : out;
				}
			}
		}

		ModelPart head = parts[BodyPart.HEAD.ordinal()];
		if (head != null && rigor > 0.0F) {
			fluidgore$save(head);
			head.xRot -= 0.35F * rigor;
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
			Rot rot = state.getData(Rot.KEY);
			Integer id = state.getData(ModelParts.ENTITY_ID);
			if (rot != null && RotRenderer.shows(rot.days) && id != null && submit.model() == ModelParts.drawn(id)) {
				// The rot goes under the wounds.
				RotRenderer.draw(submit.model().root(), rot.days, rot.arid, rot.seed, poseStack, getVertexBuilder(WoundRenderer.renderType()),
						submit.lightCoords(), submit.overlayCoords());
			}

			WoundSet wounds = state.getData(Wounds.KEY);
			if (wounds != null && wounds.count() > 0 && wounds.drawsOn(submit.model())) {
				WoundRenderer.draw(wounds, poseStack, getVertexBuilder(WoundRenderer.renderType()), submit.lightCoords(), submit.overlayCoords());
				if (submit.model() instanceof PlayerModel && GoreConfig.get().bloodyClothes) {
					// Blood soaks through the jacket, sleeves and trousers over the wounds.
					WoundRenderer.drawStains(wounds, submit.model(), true, poseStack, getVertexBuilder(WoundRenderer.renderType()), submit.lightCoords(),
							submit.overlayCoords());
				}
			} else if (wounds != null && wounds.count() > 0 && submit.model() instanceof SheepFurModel && GoreConfig.get().bloodyWool) {
				// The fleece over the wounds is soaked with their blood.
				WoundRenderer.drawStains(wounds, submit.model(), false, poseStack, getVertexBuilder(WoundRenderer.renderType()), submit.lightCoords(),
						submit.overlayCoords());
			} else if (wounds != null && wounds.count() > 0 && submit.model() instanceof HumanoidModel<?> && state instanceof AvatarRenderState
					&& GoreConfig.get().bloodyClothes) {
				// A player's armour is smeared with the blood of the wounds under it.
				WoundRenderer.drawStains(wounds, submit.model(), false, poseStack, getVertexBuilder(WoundRenderer.renderType()), submit.lightCoords(),
						submit.overlayCoords());
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
			Integer soaked = submit.model() instanceof SheepFurModel ? state.getData(BloodyWool.KEY) : null;
			if (soaked != null) {
				// Blood in the wool: the whole fleece reddens with it.
				color = Burns.apply(color, soaked);
			}

			Integer tint = state.getData(Burns.KEY);
			if (tint != null) {
				return Burns.apply(color, tint);
			}
		}

		return color;
	}

	/**
	 * Legs kick out of phase with each other (paddling), the head is thrown around and everything shivers.
	 * Amplitudes come from the pose; the motion itself is computed from time so it stays smooth every frame. Folded
	 * legs are tucked under the body (front ones back, hind ones forward, as a cow lies down); stiff legs are locked
	 * straight out with the head arched back. Each leg kicks with its own phase and strength, a dead hind end hangs
	 * limp, and an agonal gasp lifts the head.
	 */
	@Unique
	private void fluidgore$animate(ModelPart[] parts, SufferPose pose, float t) {
		ModelPart head = parts[BodyPart.HEAD.ordinal()];
		float gasp = pose.gaspAmount(t);
		if (head != null && (pose.thrash() > 0.01F || pose.tremble() > 0.01F || pose.headDip() != 0.0F || pose.lookYaw() != 0.0F
				|| pose.stiff() > 0.0F || pose.headTilt() != 0.0F || gasp > 0.0F)) {
			fluidgore$save(head);
			float shiver = pose.tremble() * 0.06F;
			head.xRot += Mth.sin(t * 0.83F) * pose.thrash() * 0.6F + SufferPose.noise(t * 5.0F, 1.0F) * shiver + pose.headDip() - 0.8F * pose.stiff()
					- 0.45F * gasp;
			head.yRot += Mth.sin(t * 1.37F + 1.0F) * pose.thrash() * 0.7F + SufferPose.noise(t * 5.5F, 2.0F) * shiver + pose.lookYaw();
			head.zRot += Mth.sin(t * 2.1F) * pose.thrash() * 0.3F + pose.headTilt();
		}

		if (pose.kick() > 0.01F || pose.foldFront() > 0.0F || pose.foldHind() > 0.0F || pose.stiff() > 0.0F) {
			BodyPart[] legs = {BodyPart.RIGHT_ARM, BodyPart.LEFT_ARM, BodyPart.RIGHT_LEG, BodyPart.LEFT_LEG};
			for (int i = 0; i < legs.length; i++) {
				ModelPart leg = parts[legs[i].ordinal()];
				if (leg == null) {
					continue;
				}

				fluidgore$save(leg);
				boolean front = i < 2;
				float fold = front ? pose.foldFront() : pose.foldHind();
				float alive = front ? 1.0F : 1.0F - pose.hindLimp();
				leg.xRot = Mth.lerp(fold, leg.xRot, front ? FOLD : -FOLD) + (front ? -STIFF : STIFF) * pose.stiff() * alive;
				float phase = pose.legPhase(i);
				float kick = pose.kick() * (1.0F - 0.7F * fold) * pose.legStrength(i) * alive;
				leg.xRot += Mth.sin(t * pose.kickRate() + phase) * kick * 0.9F;
				leg.zRot += SufferPose.noise(t * 2.0F, phase) * kick * 0.2F;
			}
		}
	}

	@Unique
	private void fluidgore$save(ModelPart part) {
		fluidgore$moved.add(part);
		fluidgore$savedRotations.add(new float[]{part.xRot, part.yRot, part.zRot, part.xScale, part.yScale, part.zScale});
	}

	@Unique
	private void fluidgore$restore() {
		for (ModelPart part : fluidgore$hidden) {
			part.visible = true;
		}

		fluidgore$hidden.clear();
		// Newest first, so a part saved twice ends up as it was before either change.
		for (int i = fluidgore$moved.size() - 1; i >= 0; i--) {
			ModelPart part = fluidgore$moved.get(i);
			float[] saved = fluidgore$savedRotations.get(i);
			part.xRot = saved[0];
			part.yRot = saved[1];
			part.zRot = saved[2];
			part.xScale = saved[3];
			part.yScale = saved[4];
			part.zScale = saved[5];
		}

		fluidgore$moved.clear();
		fluidgore$savedRotations.clear();
	}
}
