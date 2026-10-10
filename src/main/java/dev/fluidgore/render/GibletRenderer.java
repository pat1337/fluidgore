package dev.fluidgore.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fluidgore.dismember.Giblets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
import org.jspecify.annotations.Nullable;

/**
 * Draws giblets as small tumbling boxes, one batch per texture: the shared flesh texture tinted per tissue, and each
 * animal's own skin for pieces of hide. Camera-relative coordinates, like the rest of the gore.
 */
final class GibletRenderer {
	static final Identifier FLESH = Identifier.fromNamespaceAndPath("fluidgore", "textures/misc/flesh.png");
	private static final double MAX_DISTANCE_SQ = 64.0 * 64.0;
	private static final int OVERLAY = OverlayTexture.NO_OVERLAY;
	/** Corner signs of a box, and the four corners of each face (outward winding) with the face normal axis. */
	private static final int[][] FACES = {
			{0, 1, 3, 2, 0, -1}, {4, 6, 7, 5, 0, 1},
			{0, 4, 5, 1, 1, -1}, {2, 3, 7, 6, 1, 1},
			{0, 2, 6, 4, 2, -1}, {1, 5, 7, 3, 2, 1}};
	private static final float[][] FACE_UV = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};

	private static final Quaternionf ROTATION = new Quaternionf();
	private static final Vector3f[] CORNERS = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(),
			new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
	private static final Vector3f NORMAL = new Vector3f();

	private GibletRenderer() {
	}

	static void submit(Giblets g, SubmitNodeCollector collector, PoseStack poseStack, Vec3 cam, Frustum frustum, float partialTick) {
		int n = g.count();
		if (n == 0) {
			return;
		}

		List<@Nullable Identifier> textures = new ArrayList<>();
		textures.add(null);
		for (int i = 0; i < n; i++) {
			if (g.texture[i] != null && !textures.contains(g.texture[i])) {
				textures.add(g.texture[i]);
			}
		}

		for (Identifier texture : textures) {
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture == null ? FLESH : texture), (pose, buffer) -> {
				for (int i = 0; i < g.count(); i++) {
					if (Objects.equals(g.texture[i], texture)) {
						emit(g, i, pose, buffer, cam, frustum, partialTick);
					}
				}
			});
		}
	}

	private static void emit(Giblets g, int i, PoseStack.Pose pose, VertexConsumer buffer, Vec3 cam, Frustum frustum, float t) {
		double x = Mth.lerp(t, g.px[i], g.x[i]);
		double y = Mth.lerp(t, g.py[i], g.y[i]);
		double z = Mth.lerp(t, g.pz[i], g.z[i]);
		if (cam.distanceToSqr(x, y, z) > MAX_DISTANCE_SQ || !frustum.isVisible(new AABB(x - 0.2, y - 0.2, z - 0.2, x + 0.2, y + 0.2, z + 0.2))) {
			return;
		}

		float scale = g.scale(i);
		if (scale <= 0.0F) {
			return;
		}

		ROTATION.rotationYXZ(Mth.lerp(t, g.pry[i], g.ry[i]), Mth.lerp(t, g.prx[i], g.rx[i]), 0.0F);
		float sx = g.hx[i] * scale;
		float sy = g.hy[i] * scale;
		float sz = g.hz[i] * scale;
		for (int c = 0; c < 8; c++) {
			CORNERS[c].set((c & 4) != 0 ? sx : -sx, (c & 2) != 0 ? sy : -sy, (c & 1) != 0 ? sz : -sz);
			ROTATION.transform(CORNERS[c]);
		}

		float rx = (float) (x - cam.x);
		float ry = (float) (y - cam.y);
		float rz = (float) (z - cam.z);
		int color = 0xFF000000 | g.color[i];
		int light = g.light[i];
		for (int[] face : FACES) {
			NORMAL.set(0.0F).setComponent(face[4], face[5]);
			ROTATION.transform(NORMAL);
			for (int k = 0; k < 4; k++) {
				Vector3f p = CORNERS[face[k]];
				buffer.addVertex(pose, rx + p.x, ry + p.y, rz + p.z)
						.setColor(color)
						.setUv(g.u[i] + FACE_UV[k][0] * g.du[i], g.v[i] + FACE_UV[k][1] * g.dv[i])
						.setOverlay(OVERLAY)
						.setLight(light)
						.setNormal(pose, NORMAL.x, NORMAL.y, NORMAL.z);
			}
		}
	}
}
