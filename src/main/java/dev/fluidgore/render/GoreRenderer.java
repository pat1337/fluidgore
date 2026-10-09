package dev.fluidgore.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodCell;
import dev.fluidgore.blood.BloodTextures;
import dev.fluidgore.blood.Decal;
import dev.fluidgore.blood.DropletSystem;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.GorePart;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.SufferPose;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import java.util.Collection;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws all gore through Fabric's {@link LevelRenderEvents#COLLECT_SUBMITS} hook, using only vanilla submit calls
 * and vanilla render types. Sodium and Iris see ordinary entity-style geometry; nothing of theirs is patched.
 *
 * <p>Decals are lifted slightly off their surface (a tiny per-decal amount so overlapping decals do not
 * z-fight), and are depth tested like any other geometry, so they never show through blocks.
 */
public final class GoreRenderer {
	private static final double MAX_DISTANCE_SQ = 96.0 * 96.0;
	private static final float CELL_LIFT = 0.0025F;
	private static final float DECAL_LIFT = 0.004F;
	private static final float DECAL_LIFT_STEP = 0.0004F;
	private static final int OVERLAY = OverlayTexture.NO_OVERLAY;

	private GoreRenderer() {
	}

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(GoreRenderer::collect);
	}

	private static void collect(LevelRenderContext context) {
		if (Minecraft.getInstance().level == null) {
			return;
		}

		CameraRenderState camera = context.levelState().cameraRenderState;
		Vec3 cam = camera.pos;
		Frustum frustum = camera.cullFrustum;
		float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		SubmitNodeCollector collector = context.submitNodeCollector();
		PoseStack poseStack = context.poseStack();

		submitCorpses(camera, collector, poseStack, frustum, partialTick);
		if (!GoreConfig.get().goreLevel.enabled()) {
			return;
		}

		List<Decal> decals = GoreSystems.blood().decals().decals();
		Collection<BloodCell> cells = GoreSystems.blood().fluid().cells();
		List<DropletSystem.Droplet> droplets = GoreSystems.blood().droplets().droplets();
		if (!decals.isEmpty() || !cells.isEmpty() || !droplets.isEmpty()) {
			RenderType type = RenderTypes.entityTranslucent(BloodTextures.ATLAS);
			Quaternionf orientation = new Quaternionf(camera.orientation);
			// Geometry is emitted in camera-relative coordinates (computed in double precision) with an identity
			// pose, matching how vanilla positions entities relative to the camera.
			collector.submitCustomGeometry(poseStack, type, (pose, buffer) -> {
				for (BloodCell cell : cells) {
					emitCell(pose, buffer, cell, cam, frustum);
				}

				for (Decal decal : decals) {
					emitDecal(pose, buffer, decal, cam, frustum);
				}

				emitDroplets(pose, buffer, droplets, cam, orientation, partialTick);
			});
		}

		for (GorePart part : GoreSystems.dismemberment().parts().parts()) {
			submitPart(collector, poseStack, part, cam, frustum, partialTick);
		}
	}

	/**
	 * Bodies the server has already removed but that are still going through their death throes. They are drawn
	 * with their own vanilla renderer, exactly like a live entity, so they keep their model, texture and armour.
	 * (Until removal, vanilla draws them itself and the throes come in through the render-state mixin.)
	 */
	private static void submitCorpses(CameraRenderState camera, SubmitNodeCollector collector, PoseStack poseStack, Frustum frustum,
			float partialTick) {
		if (GoreSystems.suffering().corpses().isEmpty()) {
			return;
		}

		EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
		Vec3 cam = camera.pos;
		for (Corpse corpse : GoreSystems.suffering().corpses()) {
			if (!corpse.unloaded) {
				continue;
			}

			LivingEntity entity = corpse.entity;
			if (cam.distanceToSqr(entity.position()) > MAX_DISTANCE_SQ || !frustum.isVisible(entity.getBoundingBox().inflate(1.0))) {
				continue;
			}

			EntityRenderState state = dispatcher.extractEntity(entity, partialTick);
			dispatcher.submit(state, camera, state.x - cam.x, state.y - cam.y, state.z - cam.z, poseStack, collector);
		}
	}

	private static void emitDecal(PoseStack.Pose pose, VertexConsumer buffer, Decal d, Vec3 cam, Frustum frustum) {
		double reach = d.half + 0.6;
		boolean floor = d.face == Direction.UP;
		double wx = floor ? d.centerA : (d.face.getAxis() == Direction.Axis.X ? d.plane : d.centerA);
		double wy = floor ? d.plane : d.centerB;
		double wz = floor ? d.centerB : (d.face.getAxis() == Direction.Axis.X ? d.centerA : d.plane);
		if (cam.distanceToSqr(wx, wy, wz) > MAX_DISTANCE_SQ
				|| !frustum.isVisible(new AABB(wx - reach, wy - reach, wz - reach, wx + reach, wy + reach, wz + reach))) {
			return;
		}

		float alpha = d.alpha() * 0.94F;
		if (alpha <= 0.01F) {
			return;
		}

		int color = argb(dry(d.rgb, d.dryness()), alpha);
		float lift = DECAL_LIFT + (d.serial % 24) * DECAL_LIFT_STEP;
		float size = d.half * 2.0F;
		for (int i = 0; i < d.pieces.length; i += Decal.PIECE_STRIDE) {
			float a0 = d.pieces[i];
			float b0 = d.pieces[i + 1];
			float a1 = d.pieces[i + 2];
			float b1 = d.pieces[i + 3];
			float h = d.pieces[i + 4];
			emitPlanarQuad(pose, buffer, d.face, d.centerA, d.centerB, d.plane + h + lift * sign(d.face), a0, b0, a1, b1,
					d.half, size, d.tile, d.orient, color, d.light, cam);
		}
	}

	private static void emitCell(PoseStack.Pose pose, VertexConsumer buffer, BloodCell cell, Vec3 cam, Frustum frustum) {
		double cx = cell.bx + 0.5;
		double cz = cell.bz + 0.5;
		if (cam.distanceToSqr(cx, cell.surfaceY, cz) > MAX_DISTANCE_SQ
				|| !frustum.isVisible(new AABB(cell.bx, cell.surfaceY - 0.6, cell.bz, cell.bx + 1, cell.surfaceY + 0.6, cell.bz + 1))) {
			return;
		}

		float alpha = cell.alpha();
		if (alpha <= 0.01F) {
			return;
		}

		boolean pool = cell.pooled;
		float half = pool ? 0.5F : cell.halfSize();
		int tile = pool ? BloodTextures.POOL : BloodTextures.puddle(cell.serial);
		int orient = (int) (cell.serial & 7);
		int color = argb(dry(cell.rgb, cell.dryness()), alpha);

		// Draw per half-block quadrant, so a puddle on stairs sits on each step.
		for (int q = 0; q < 4; q++) {
			float h = cell.quadrantHeights[q];
			if (Float.isNaN(h)) {
				continue;
			}

			float qa0 = (q & 1) == 0 ? -0.5F : 0.0F;
			float qb0 = (q & 2) == 0 ? -0.5F : 0.0F;
			float a0 = Math.max(qa0, -half);
			float a1 = Math.min(qa0 + 0.5F, half);
			float b0 = Math.max(qb0, -half);
			float b1 = Math.min(qb0 + 0.5F, half);
			if (a1 - a0 < 0.005F || b1 - b0 < 0.005F) {
				continue;
			}

			emitPlanarQuad(pose, buffer, Direction.UP, cx, cz, cell.surfaceY + h + CELL_LIFT, a0, b0, a1, b1,
					half, half * 2.0F, tile, orient, color, cell.light, cam);
		}
	}

	/**
	 * Emits one rectangle (a0..a1, b0..b1, relative to the decal centre) on a block-aligned plane. UVs are taken
	 * from the rectangle's position inside the full decal square, so clipped pieces still line up seamlessly.
	 */
	private static void emitPlanarQuad(PoseStack.Pose pose, VertexConsumer buffer, Direction face, double centerA, double centerB,
			double plane, float a0, float b0, float a1, float b1, float half, float size, int tile, int orient, int color,
			int light, Vec3 cam) {
		float nx = face.getStepX();
		float ny = face.getStepY();
		float nz = face.getStepZ();
		float[] as = {a0, a0, a1, a1};
		float[] bs = {b0, b1, b1, b0};
		for (int k = 0; k < 4; k++) {
			float u = (as[k] + half) / size;
			float v = (bs[k] + half) / size;
			if (face != Direction.UP) {
				// On walls b is height: the top of the texture should be at the top of the wall.
				v = 1.0F - v;
			}

			if ((orient & 4) != 0) {
				float t = u;
				u = v;
				v = t;
			}

			if ((orient & 1) != 0) {
				u = 1.0F - u;
			}

			if ((orient & 2) != 0) {
				v = 1.0F - v;
			}

			double wa = centerA + as[k];
			double wb = centerB + bs[k];
			double x;
			double y;
			double z;
			if (face == Direction.UP) {
				x = wa;
				y = plane;
				z = wb;
			} else if (face.getAxis() == Direction.Axis.X) {
				x = plane;
				y = wb;
				z = wa;
			} else {
				x = wa;
				y = wb;
				z = plane;
			}

			buffer.addVertex(pose, (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z))
					.setColor(color)
					.setUv(BloodTextures.u0(tile) + u * BloodTextures.span(), BloodTextures.v0(tile) + v * BloodTextures.span())
					.setOverlay(OVERLAY)
					.setLight(light)
					.setNormal(pose, nx, ny, nz);
		}
	}

	private static void emitDroplets(PoseStack.Pose pose, VertexConsumer buffer, List<DropletSystem.Droplet> droplets, Vec3 cam,
			Quaternionf orientation, float partialTick) {
		if (droplets.isEmpty()) {
			return;
		}

		Vector3f right = orientation.transform(new Vector3f(1.0F, 0.0F, 0.0F));
		Vector3f up = orientation.transform(new Vector3f(0.0F, 1.0F, 0.0F));
		Vector3f normal = orientation.transform(new Vector3f(0.0F, 0.0F, 1.0F));
		int dropletTile = BloodTextures.droplet();
		float u0 = BloodTextures.u0(dropletTile);
		float v0 = BloodTextures.v0(dropletTile);
		float span = BloodTextures.span();
		for (DropletSystem.Droplet d : droplets) {
			double x = d.prevX + (d.x - d.prevX) * partialTick - cam.x;
			double y = d.prevY + (d.y - d.prevY) * partialTick - cam.y;
			double z = d.prevZ + (d.z - d.prevZ) * partialTick - cam.z;
			if (x * x + y * y + z * z > MAX_DISTANCE_SQ) {
				continue;
			}

			float s = d.size;
			int color = argb(d.rgb, 0.92F);
			emitBillboardVertex(pose, buffer, x, y, z, right, up, -s, -s, u0, v0 + span, color, d.light, normal);
			emitBillboardVertex(pose, buffer, x, y, z, right, up, -s, s, u0, v0, color, d.light, normal);
			emitBillboardVertex(pose, buffer, x, y, z, right, up, s, s, u0 + span, v0, color, d.light, normal);
			emitBillboardVertex(pose, buffer, x, y, z, right, up, s, -s, u0 + span, v0 + span, color, d.light, normal);
		}
	}

	private static void emitBillboardVertex(PoseStack.Pose pose, VertexConsumer buffer, double x, double y, double z, Vector3f right,
			Vector3f up, float dr, float du, float u, float v, int color, int light, Vector3f normal) {
		buffer.addVertex(pose, (float) (x + right.x * dr + up.x * du), (float) (y + right.y * dr + up.y * du), (float) (z + right.z * dr + up.z * du))
				.setColor(color)
				.setUv(u, v)
				.setOverlay(OVERLAY)
				.setLight(light)
				.setNormal(pose, normal.x, normal.y, normal.z);
	}

	private static void submitPart(SubmitNodeCollector collector, PoseStack poseStack, GorePart part, Vec3 cam, Frustum frustum,
			float partialTick) {
		double x = part.prevX + (part.x - part.prevX) * partialTick;
		double y = part.prevY + (part.y - part.prevY) * partialTick;
		double z = part.prevZ + (part.z - part.prevZ) * partialTick;
		if (cam.distanceToSqr(x, y, z) > MAX_DISTANCE_SQ || !frustum.isVisible(new AABB(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1))) {
			return;
		}

		float alpha = part.alpha();
		if (alpha <= 0.01F) {
			return;
		}

		poseStack.pushPose();
		poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
		if (part.squash < 1.0F) {
			// Stomped flat: squashed vertically, spread out a little.
			float spread = 1.0F + (1.0F - part.squash) * 0.4F;
			poseStack.scale(spread, part.squash, spread);
		}

		poseStack.rotate(new Quaternionf(part.prevRotation).slerp(part.rotation, partialTick));
		if (part.twitchTicks > part.age) {
			// Severed head: twitches that fade out as the last nerve activity dies away.
			float t = part.age + partialTick;
			float strength = 1.0F - t / part.twitchTicks;
			float jolt = SufferPose.noise(t * 2.5F, part.lifetime) > 0.55F ? 0.25F : 0.05F;
			poseStack.rotate(new Quaternionf().rotationXYZ(
					SufferPose.noise(t * 3.0F, 1.0F) * jolt * strength,
					SufferPose.noise(t * 2.7F, 2.0F) * jolt * strength,
					SufferPose.noise(t * 3.3F, 3.0F) * jolt * strength));
		}

		poseStack.scale(part.scale, part.scale, part.scale);
		// Same flip vanilla applies to entity models, then centre the part on its pivot for tumbling.
		poseStack.scale(-1.0F, -1.0F, 1.0F);
		poseStack.translate(-part.localCenter.x, -part.localCenter.y, -part.localCenter.z);
		collector.submitModelPart(part.model, poseStack, RenderTypes.entityTranslucent(part.texture), part.light, OVERLAY, null,
				argb(0xFFFFFF, alpha));
		poseStack.popPose();
	}

	private static float sign(Direction face) {
		return face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : -1.0F;
	}

	/** Drying darkens blood and shifts it towards brown. */
	private static int dry(int rgb, float dryness) {
		float r = ((rgb >> 16) & 0xFF) * (1.0F - 0.35F * dryness);
		float g = ((rgb >> 8) & 0xFF) * (1.0F - 0.55F * dryness);
		float b = (rgb & 0xFF) * (1.0F - 0.6F * dryness);
		return ((int) r << 16) | ((int) g << 8) | (int) b;
	}

	private static int argb(int rgb, float alpha) {
		int a = Math.max(0, Math.min(255, (int) (alpha * 255.0F)));
		return (a << 24) | (rgb & 0xFFFFFF);
	}
}
