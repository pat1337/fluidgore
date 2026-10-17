package dev.fluidgore.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fluidgore.dismember.Feathers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws loose feathers as flat, two-sided quads with {@code feather.png}: lying flat once down, rocking along the quill
 * as they drift. A dry feather is drawn from the top row of the texture, a soaked one from the bottom row (its vanes
 * clumped into strands). Camera-relative coordinates, like the rest of the gore.
 */
final class FeatherRenderer {
	static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("fluidgore", "textures/misc/feather.png");
	private static final double MAX_DISTANCE_SQ = 48.0 * 48.0;
	private static final float[][] CORNERS = {{-0.5F, -0.5F}, {0.5F, -0.5F}, {0.5F, 0.5F}, {-0.5F, 0.5F}};

	private static final Quaternionf ROTATION = new Quaternionf();
	private static final Vector3f CORNER = new Vector3f();
	private static final Vector3f NORMAL = new Vector3f();

	private FeatherRenderer() {
	}

	static void submit(Feathers f, SubmitNodeCollector collector, PoseStack poseStack, Vec3 cam, Frustum frustum, float partialTick) {
		if (f.count() == 0) {
			return;
		}

		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TEXTURE), (pose, buffer) -> {
			for (int i = 0; i < f.count(); i++) {
				emit(f, i, pose, buffer, cam, frustum, partialTick);
			}
		});
	}

	private static void emit(Feathers f, int i, PoseStack.Pose pose, VertexConsumer buffer, Vec3 cam, Frustum frustum, float t) {
		double x = Mth.lerp(t, f.px[i], f.x[i]);
		double y = Mth.lerp(t, f.py[i], f.y[i]);
		double z = Mth.lerp(t, f.pz[i], f.z[i]);
		if (cam.distanceToSqr(x, y, z) > MAX_DISTANCE_SQ || !frustum.isVisible(new AABB(x - 0.15, y - 0.15, z - 0.15, x + 0.15, y + 0.15, z + 0.15))) {
			return;
		}

		float size = f.size[i] * f.scale(i);
		if (size <= 0.0F) {
			return;
		}

		// The quad lies in the feather's own x-z plane, the quill along z; it turns to its heading and rocks about the quill.
		ROTATION.rotationY(Mth.lerp(t, f.pyaw[i], f.yaw[i])).rotateZ(Mth.lerp(t, f.ptilt[i], f.tilt[i]));
		ROTATION.transform(NORMAL.set(0.0F, 1.0F, 0.0F));
		float u0 = f.variant[i] * 0.25F;
		float v0 = f.clumped(i) ? 0.5F : 0.0F;
		float rx = (float) (x - cam.x);
		float ry = (float) (y - cam.y);
		float rz = (float) (z - cam.z);
		int color = 0xFF000000 | f.color(i);
		for (float[] c : CORNERS) {
			ROTATION.transform(CORNER.set(c[0] * size, 0.0F, c[1] * size));
			buffer.addVertex(pose, rx + CORNER.x, ry + CORNER.y, rz + CORNER.z)
					.setColor(color)
					.setUv(u0 + (c[0] + 0.5F) * 0.25F, v0 + (0.5F - c[1]) * 0.5F)
					.setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(f.light[i])
					.setNormal(pose, NORMAL.x, NORMAL.y, NORMAL.z);
		}
	}
}
