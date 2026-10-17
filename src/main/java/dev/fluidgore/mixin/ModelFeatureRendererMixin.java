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
import net.minecraft.client.model.animal.chicken.AdultChickenModel;
import net.minecraft.client.model.animal.chicken.ChickenModel;
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
		BloodyWool.Plumage plumage = submit.model() instanceof AdultChickenModel ? state.getData(BloodyWool.PLUMAGE) : null;
		if ((mask == null || mask == 0) && pose == null && body == null && (wounds == null || wounds.brokenMask() == 0) && injury == null && rot == null
				&& plumage == null) {
			return;
		}

		Model<?> model = submit.model();
		ModelPart[] parts = ModelParts.index(model);
		if (plumage != null) {
			fluidgore$skinny(model, parts, plumage);
		}

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
			fluidgore$animate(parts, pose, clock != null ? clock : state.ageInTicks, model instanceof ChickenModel);
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
	 * A dying bird's wings: they beat in frantic bursts, up and out, never quite together, and the head on a broken (or
	 * cut) neck hangs and swings loose with every beat.
	 */
	@Unique
	private void fluidgore$flap(ModelPart[] parts, ModelPart head, SufferPose pose, float t) {
		float flap = pose.flap();
		if (flap > 0.01F) {
			for (int i = 0; i < 2; i++) {
				ModelPart wing = parts[i == 0 ? BodyPart.RIGHT_ARM.ordinal() : BodyPart.LEFT_ARM.ordinal()];
				if (wing == null) {
					continue;
				}

				fluidgore$save(wing);
				// Each wing a little out of step with the other; vanilla's own flap opens the right wing at +z, the left at -z.
				float phase = t * pose.flapRate() + i * 0.7F + SufferPose.noise(t * 0.5F, pose.legSeed() + i) * 0.8F;
				float open = (Mth.sin(phase) + 1.0F) * 0.5F;
				float angle = flap * (0.25F + 1.15F * open);
				wing.zRot += i == 0 ? angle : -angle;
				wing.xRot += Mth.sin(phase * 0.5F) * 0.25F * flap;
			}
		}

		float limp = pose.neckLimp();
		if (head != null && limp > 0.0F) {
			fluidgore$save(head);
			float swing = flap * Mth.sin(t * pose.flapRate() * 1.3F);
			head.xRot += limp * (1.2F + 0.35F * swing);
			head.zRot += limp * (0.6F * Mth.sin(pose.legSeed()) + 0.4F * swing);
		}
	}

	/**
	 * A chicken is mostly feathers: plucked, the bird under them is a skinny thing, the body narrow and the wings bony;
	 * soaked in the rain, the feathers lie flat and slick and it looks half the size it did.
	 */
	@Unique
	private void fluidgore$skinny(Model<?> model, ModelPart[] parts, BloodyWool.Plumage plumage) {
		float thin = Math.min(0.4F, 0.32F * plumage.plucked() + 0.14F * plumage.wet());
		if (thin <= 0.0F || !model.root().hasChild("body")) {
			return;
		}

		ModelPart body = model.root().getChild("body");
		fluidgore$save(body);
		// The body is turned on its side in the model: x is its width, z its depth, y its length.
		body.xScale *= 1.0F - thin;
		body.zScale *= 1.0F - 0.8F * thin;
		body.yScale *= 1.0F - 0.25F * thin;
		// The wings close in on the narrower body and sit lower on its back, thin and bony without their feathers.
		float bony = 0.35F * plumage.plucked();
		for (BodyPart wing : new BodyPart[] {BodyPart.RIGHT_ARM, BodyPart.LEFT_ARM}) {
			ModelPart part = parts[wing.ordinal()];
			if (part == null) {
				continue;
			}

			fluidgore$save(part);
			part.x -= Math.signum(part.x) * 3.0F * thin;
			part.y += 2.4F * thin;
			part.yScale *= 1.0F - bony;
			part.zScale *= 1.0F - 0.7F * bony;
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
				if (submit.model() instanceof AdultChickenModel && GoreConfig.get().bloodyFeathers) {
					// The feathers round each wound are matted with its blood (under the wound itself).
					WoundRenderer.drawStains(wounds, submit.model(), false, poseStack, getVertexBuilder(WoundRenderer.renderType()), submit.lightCoords(),
							submit.overlayCoords());
				}

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
	private void fluidgore$animate(ModelPart[] parts, SufferPose pose, float t, boolean bird) {
		ModelPart head = parts[BodyPart.HEAD.ordinal()];
		if (bird && (pose.flap() > 0.01F || pose.neckLimp() > 0.0F)) {
			fluidgore$flap(parts, head, pose, t);
		}

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
				// A bird's "front legs" are its wings: they beat (see fluidgore$flap), they do not paddle.
				if (leg == null || bird && i < 2) {
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
		fluidgore$savedRotations.add(new float[]{part.xRot, part.yRot, part.zRot, part.xScale, part.yScale, part.zScale, part.x, part.y, part.z});
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
			part.x = saved[6];
			part.y = saved[7];
			part.z = saved[8];
		}

		fluidgore$moved.clear();
		fluidgore$savedRotations.clear();
	}
}
