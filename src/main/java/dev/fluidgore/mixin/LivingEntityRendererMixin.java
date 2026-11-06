package dev.fluidgore.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.Pallor;
import dev.fluidgore.anatomy.Melt;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.player.Injury;
import dev.fluidgore.player.InjuryPose;
import dev.fluidgore.ragdoll.RigPose;
import dev.fluidgore.rot.Rot;
import dev.fluidgore.suffer.BodyPose;
import dev.fluidgore.suffer.CorpseStrike;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.Ragdoll;
import dev.fluidgore.suffer.SufferPose;
import dev.fluidgore.wound.BloodyWool;
import dev.fluidgore.wound.BodyFlames;
import dev.fluidgore.wound.Burns;
import dev.fluidgore.wound.Cremation;
import dev.fluidgore.wound.Wounds;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
	@Unique
	private static final float[] NO_JOINTS = new float[Ragdoll.JOINTS];

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

		// A bird's feathers redden with its blood and darken in the rain (not the bare skin where it is plucked).
		BloodyWool.Plumage plumage = null;
		if (entity instanceof Chicken && GoreConfig.get().bloodyFeathers) {
			float bare = GoreSystems.dismemberment().butchery().plucked(entity.getId());
			Integer feathers = GoreSystems.dismemberment().wool().featherTint(entity.getId(), bare);
			if (feathers != null) {
				tint = tint == null ? feathers : Integer.valueOf(Burns.apply(0xFF000000 | tint, feathers) & 0xFFFFFF);
			}

			float wet = GoreSystems.dismemberment().wool().wet(entity.getId());
			if (bare > 0.0F || wet > 0.0F) {
				plumage = new BloodyWool.Plumage(bare, wet);
			}
		}

		// Burning: the feathers singe off first, leaving the bird skinny and bare as a plucked one.
		Cremation.Charred charred = GoreSystems.dismemberment().cremation().look(entity.getId());
		state.setData(Cremation.KEY, charred);
		// A person burns down through their own anatomy instead (see MeltRenderer).
		Melt melt = GoreSystems.dismemberment().melting().look(entity);
		state.setData(Melt.KEY, melt);
		if (melt != null) {
			state.setData(Cremation.KEY, null);
		}

		if (charred != null && entity instanceof Chicken) {
			float singed = Math.min(1.0F, charred.bare() * 6.0F);
			plumage = new BloodyWool.Plumage(Math.max(singed, plumage == null ? 0.0F : plumage.plucked()), plumage == null ? 0.0F : plumage.wet());
		}

		state.setData(BloodyWool.PLUMAGE, plumage);

		// Rot discolours it on top of that.
		Rot rot = GoreSystems.decay().of(entity.getId());
		state.setData(Rot.KEY, rot);
		if (rot != null) {
			tint = tint == null ? rot.tint() : Integer.valueOf(Burns.apply(0xFF000000 | tint, rot.tint()) & 0xFFFFFF);
		}

		state.setData(Burns.KEY, tint);
		// Bled white: on the body's own skin only, not its wool, armour or saddle.
		state.setData(Pallor.KEY, Pallor.look(entity));
		state.setData(BloodyWool.KEY, GoreSystems.dismemberment().wool().tint(entity.getId()));
		state.setData(BloodyWool.COAT, GoreSystems.dismemberment().wool().drawn(entity));
		state.setData(ModelParts.ENTITY_ID, entity.getId());
		state.setData(Injury.KEY, GoreSystems.suffering().corpse(entity.getId()) == null ? Injury.of(entity) : null);

		Corpse corpse = GoreSystems.suffering().corpse(entity.getId());
		state.setData(RigPose.KEY, null);
		state.setData(BodyFlames.KEY, null);
		if (corpse != null) {
			Ragdoll body = corpse.body;
			// The ragdoll does its own falling over; vanilla's scripted tip-over stays off.
			state.deathTime = 0.0F;
			state.yRot = 0.0F;
			state.xRot = 0.0F;
			if (corpse.rig != null) {
				// Articulated: drawn unturned from the rig's own origin, every part posed from its segment.
				RigPose rig = corpse.rig.pose(partialTicks, state.x, state.y, state.z);
				state.setData(RigPose.KEY, rig);
				state.bodyRot = 180.0F;
				state.setData(BodyPose.KEY, new BodyPose(0.0, 0.0, 0.0, 0.0F, NO_JOINTS));
			} else {
				state.bodyRot = body.yaw(partialTicks);
				state.setData(BodyPose.KEY, new BodyPose(body.x(partialTicks) - state.x, body.y(partialTicks) - state.y,
						body.z(partialTicks) - state.z, body.roll(partialTicks), body.joints(partialTicks)));
			}
			fluidgore$flames(entity, corpse, state, partialTicks);
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
	 * A burning body wears vanilla's flames, the same sheet of fire a living mob on fire has, but on the body where it
	 * lies (vanilla would draw them at the entity's own spot, hanging in the air) and as big as the fire on it: low and
	 * long over a body on its side, tall while its fat burns, small as it catches and on the bones at the end. It stands
	 * on {@link CorpseStrike#middle}, the same spot the fire's own particles rise from, ragdoll or rig.
	 */
	@Unique
	private static void fluidgore$flames(LivingEntity entity, Corpse corpse, LivingEntityRenderState state, float partialTicks) {
		state.displayFireAnimation = false;
		int id = entity.getId();
		if (GoreSystems.dismemberment().vaporized(id)) {
			return;
		}

		// Cremation's fire, when it has one for this body, says how big the flames are: it already counts the server's
		// on-fire flag as heat, and it knows when the body is out, doused or down to bare bone. The flag alone (cremation
		// off) gets vanilla's full sheet. Taking the bigger of the two made the sheet jump to full size and back as the
		// server's fire ticks ran out and were lit again.
		Cremation cremation = GoreSystems.dismemberment().cremation();
		float fierce = cremation.tracks(id) ? cremation.flames(id) : !corpse.unloaded && entity.isOnFire() ? 1.0F : 0.0F;
		if (fierce <= 0.05F) {
			return;
		}

		float w = state.boundingBoxWidth;
		float h = state.boundingBoxHeight;
		Vec3 middle = CorpseStrike.middle(corpse, partialTicks);
		double dx = middle.x - state.x;
		double dy = middle.y - state.y;
		double dz = middle.z - state.z;
		float lying = Mth.abs(Mth.sin(corpse.body.roll(partialTicks)));
		float width = Mth.lerp(lying, w, Math.max(w, h)) * (0.7F + 0.3F * fierce);
		float height = Mth.lerp(lying, h, w) * (0.5F + 0.5F * fierce);
		state.displayFireAnimation = true;
		// From low on the body, so the flames wrap it and rise off its top rather than standing on its middle.
		state.setData(BodyFlames.KEY, new BodyFlames(dx, dy - height * 0.35, dz, width, height));
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
			Rot rot = state.getData(Rot.KEY);
			if (rot != null) {
				// A bloated body is wider: lift it by what it has swollen.
				side *= 1.0F + 0.28F * Math.max(0.0F, rot.swell());
			}

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
		if (pose == null || state.getData(RigPose.KEY) != null) {
			// An articulated body moves its own segments: no whole-body shivering on top.
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
