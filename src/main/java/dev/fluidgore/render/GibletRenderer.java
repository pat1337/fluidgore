package dev.fluidgore.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fluidgore.anatomy.Anatomy;
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
	/** Within this, a torn-out organ is drawn as the cubes it is made of; beyond, as its box. */
	private static final double SHAPE_DISTANCE_SQ = 32.0 * 32.0;
	/** Most organ cubes drawn in a frame (about four sheep's worth); the rest are drawn as their boxes. */
	private static final int SHAPE_CUBES_PER_FRAME = 1500;
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
	private static final float[] HALF = new float[3];
	private static final Vector3f[] QUAD = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
	/** Organ cubes left to draw this frame. */
	private static int shapeBudget;

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

		shapeBudget = SHAPE_CUBES_PER_FRAME;
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
		double reach = Math.max(0.2, Math.max(g.hx[i], Math.max(g.hy[i], g.hz[i])) * 1.75);
		double distanceSq = cam.distanceToSqr(x, y, z);
		if (distanceSq > MAX_DISTANCE_SQ || !frustum.isVisible(new AABB(x - reach, y - reach, z - reach, x + reach, y + reach, z + reach))) {
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
		float rx = (float) (x - cam.x);
		float ry = (float) (y - cam.y);
		float rz = (float) (z - cam.z);
		int color = 0xFF000000 | g.color[i];
		int light = g.light[i];
		Anatomy.Shape shape = g.shape[i];
		if (shape != null && distanceSq < SHAPE_DISTANCE_SQ && shapeBudget >= shape.cubes()) {
			shapeBudget -= shape.cubes();
			shaped(shape, sx, sy, sz, rx, ry, rz, color, light, pose, buffer);
			return;
		}

		for (int c = 0; c < 8; c++) {
			CORNERS[c].set((c & 4) != 0 ? sx : -sx, (c & 2) != 0 ? sy : -sy, (c & 1) != 0 ? sz : -sz);
			ROTATION.transform(CORNERS[c]);
		}

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

	/**
	 * A torn-out organ as the cubes it is made of, each on its own tissue's tile, in {@link #ROTATION} about its centre
	 * ({@code rx, ry, rz} from the camera), its box's half sides {@code sx, sy, sz}. Only the faces that show.
	 */
	private static void shaped(Anatomy.Shape shape, float sx, float sy, float sz, float rx, float ry, float rz, int color, int light, PoseStack.Pose pose,
			VertexConsumer buffer) {
		float[] half = HALF;
		half[0] = sx;
		half[1] = sy;
		half[2] = sz;
		for (int cube = 0; cube < shape.cubes(); cube++) {
			for (int a = 0; a < 3; a++) {
				for (int s = 0; s < 2; s++) {
					boolean max = s == 1;
					if (!shape.shows(cube, a, max)) {
						continue;
					}

					int b = (a + 1) % 3;
					int c = (a + 2) % 3;
					float d = shape.side(cube, a, max) * half[a];
					float b0 = shape.side(cube, b, false) * half[b];
					float b1 = shape.side(cube, b, true) * half[b];
					float c0 = shape.side(cube, c, false) * half[c];
					float c1 = shape.side(cube, c, true) * half[c];
					// Counter-clockwise seen from outside: b then c on the high side, c then b on the low.
					corner(QUAD[0], a, b, c, d, b0, c0);
					corner(QUAD[1], a, b, c, d, max ? b1 : b0, max ? c0 : c1);
					corner(QUAD[2], a, b, c, d, b1, c1);
					corner(QUAD[3], a, b, c, d, max ? b0 : b1, max ? c1 : c0);
					NORMAL.set(0.0F).setComponent(a, max ? 1.0F : -1.0F);
					ROTATION.transform(NORMAL);
					float u0 = shape.uv(cube, a, max, 0);
					float v0 = shape.uv(cube, a, max, 1);
					float u1 = shape.uv(cube, a, max, 2);
					float v1 = shape.uv(cube, a, max, 3);
					boolean along = shape.vAlongB(cube, a, max);
					for (int k = 0; k < 4; k++) {
						Vector3f p = ROTATION.transform(QUAD[k]);
						// Where the corner lies along b and c picks its texel corner: u along c and v along b, or the other way.
						boolean atB1 = k == 2 || k == (max ? 1 : 3);
						boolean atC1 = k == 2 || k == (max ? 3 : 1);
						float u = along ? (atC1 ? u1 : u0) : (atB1 ? u1 : u0);
						float v = along ? (atB1 ? v1 : v0) : (atC1 ? v1 : v0);
						buffer.addVertex(pose, rx + p.x, ry + p.y, rz + p.z)
								.setColor(color)
								.setUv(u, v)
								.setOverlay(OVERLAY)
								.setLight(light)
								.setNormal(pose, NORMAL.x, NORMAL.y, NORMAL.z);
					}
				}
			}
		}
	}

	private static void corner(Vector3f out, int a, int b, int c, float d, float pb, float pc) {
		out.setComponent(a, d).setComponent(b, pb).setComponent(c, pc);
	}
}
