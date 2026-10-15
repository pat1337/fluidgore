package dev.fluidgore.player;

import dev.fluidgore.dismember.BodyPart;
import java.util.function.Consumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

/**
 * How a hurt humanoid carries itself, laid over vanilla's walk once {@code setupAnim} has posed the model. A crippled
 * leg barely swings and drags out to the side; a hurt arm hangs and swings little; a deep belly wound, or a player near
 * death, is held with the free hand pressed across it, and their head hangs. Applied to every humanoid model drawn with
 * the body's render state, so armour stays on the limbs it covers. Arms busy with an item or a swing are left alone.
 */
public final class InjuryPose {
	/** A hand pressed to the belly: the arm brought forward and across the body. */
	private static final float CLUTCH_PITCH = -0.8F;
	private static final float CLUTCH_YAW = 0.6F;

	private InjuryPose() {
	}

	public static void apply(ModelPart[] parts, Injury injury, LivingEntityRenderState state, Consumer<ModelPart> save) {
		for (boolean right : new boolean[]{true, false}) {
			float h = injury.leg(right);
			ModelPart leg = parts[(right ? BodyPart.RIGHT_LEG : BodyPart.LEFT_LEG).ordinal()];
			if (h > 0.0F && leg != null) {
				save.accept(leg);
				// It is kept nearly straight and swung out round the hip rather than bent through a stride.
				leg.xRot *= 1.0F - 0.65F * h;
				leg.zRot += (right ? 1.0F : -1.0F) * 0.12F * h;
			}
		}

		ArmedEntityRenderState armed = state instanceof ArmedEntityRenderState a ? a : null;
		boolean rightMain = armed == null || armed.mainArm == HumanoidArm.RIGHT;
		// The free hand goes to the wound: the off hand, unless that arm is the one that is hurt worse.
		boolean clutchRight = rightMain ? injury.rightArm() > injury.leftArm() : injury.leftArm() <= injury.rightArm();
		float clutch = Math.max(Mth.clamp((injury.belly() - 0.35F) / 0.4F, 0.0F, 1.0F), injury.weak() * 0.85F);
		for (boolean right : new boolean[]{true, false}) {
			ModelPart arm = parts[(right ? BodyPart.RIGHT_ARM : BodyPart.LEFT_ARM).ordinal()];
			if (arm == null || busy(armed, right)) {
				continue;
			}

			float side = right ? 1.0F : -1.0F;
			float h = injury.arm(right);
			if (right == clutchRight && clutch > 0.0F) {
				save.accept(arm);
				arm.xRot = Mth.lerp(clutch, arm.xRot, CLUTCH_PITCH);
				arm.yRot = Mth.lerp(clutch, arm.yRot, -side * CLUTCH_YAW);
				arm.zRot = Mth.lerp(clutch, arm.zRot, side * 0.1F);
			} else if (h > 0.0F) {
				save.accept(arm);
				// Hanging: it swings a little with the body and no more.
				arm.xRot = Mth.lerp(0.8F * h, arm.xRot, arm.xRot * 0.15F + 0.08F);
				arm.zRot = Mth.lerp(0.8F * h, arm.zRot, side * 0.04F);
			}
		}

		ModelPart head = parts[BodyPart.HEAD.ordinal()];
		if (injury.weak() > 0.0F && head != null) {
			save.accept(head);
			head.xRot += 0.35F * injury.weak();
		}
	}

	/** True if the arm is doing something (holding an item up, using it, swinging): the injury does not override it. */
	private static boolean busy(ArmedEntityRenderState armed, boolean right) {
		if (armed == null) {
			return false;
		}

		HumanoidArm arm = right ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
		if (armed.swingAnimation > 0.0F && armed.currentSwing != null && armed.currentSwing.hand().asArm(armed.mainArm) == arm) {
			return true;
		}

		HumanoidModel.ArmPose pose = armed.getArmPose(arm);
		return pose != HumanoidModel.ArmPose.EMPTY && pose != HumanoidModel.ArmPose.ITEM;
	}

	/**
	 * Sideways lean, in degrees, of a limping body at this point in its stride: it drops onto the bad leg as it takes the
	 * weight and pushes off it quickly. 0 standing still.
	 */
	public static float lean(Injury injury, LivingEntityRenderState state) {
		float speed = Math.min(1.0F, state.walkAnimationSpeed);
		if (speed <= 0.01F) {
			return 0.0F;
		}

		float c = Mth.cos(state.walkAnimationPos * 0.6662F);
		// Vanilla swings the right leg forward while cos > 0, so the left one carries the body then.
		return (injury.leftLeg() * Math.max(0.0F, c) - injury.rightLeg() * Math.max(0.0F, -c)) * speed * 7.0F;
	}
}
