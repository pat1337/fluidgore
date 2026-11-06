package dev.fluidgore.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.fluidgore.fragment.FragmentEffects;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Small vanilla-textured chips following the server paths. No luminous trails, particles or registered entities. */
final class FragmentRenderer {
	private static final Identifier[] TEXTURES = {
			Identifier.withDefaultNamespace("textures/block/black_wool.png"), Identifier.withDefaultNamespace("textures/block/iron_block.png"),
			Identifier.withDefaultNamespace("textures/block/stone.png")};
	private static final int[][] FACES = {{0,1,3,2,0,-1},{4,6,7,5,0,1},{0,4,5,1,1,-1},{2,3,7,6,1,1},{0,2,6,4,2,-1},{1,5,7,3,2,1}};
	private FragmentRenderer() {}
	static void submit(SubmitNodeCollector collector, PoseStack poses, Vec3 camera, Frustum frustum, float partial) {
		var flights = FragmentEffects.flights();
		if (flights.isEmpty()) return;
		for (int material = 0; material < TEXTURES.length; material++) {
			int chosen = material;
			collector.submitCustomGeometry(poses, RenderTypes.entityCutout(TEXTURES[material]), (pose, buffer) -> {
				Vector3f point = new Vector3f(), normal = new Vector3f();
				for (var f : flights) {
					if (f.path.material() != chosen) continue;
					Vec3 at = f.position(partial); float scale = f.size(partial);
					if (scale <= 0 || camera.distanceToSqr(at) > 64 * 64 || !frustum.isVisible(new AABB(at, at).inflate(0.15))) continue;
					Quaternionf rotation = new Quaternionf().rotationXYZ(f.path.index() + (f.age + partial) * 0.7F, f.path.index() * 0.3F, (f.age + partial) * 0.4F);
					var level = net.minecraft.client.Minecraft.getInstance().level;
					int light = level == null ? 0 : LightCoordsUtil.getLightCoords(level, net.minecraft.core.BlockPos.containing(at));
					for (int[] face : FACES) {
						rotation.transform(normal.set(0,0,0).setComponent(face[4], face[5]));
						for (int corner = 0; corner < 4; corner++) {
							int c = face[corner];
							rotation.transform(point.set((c & 4) != 0 ? 0.05F : -0.05F, (c & 2) != 0 ? 0.012F : -0.012F, (c & 1) != 0 ? 0.025F : -0.025F).mul(scale));
							buffer.addVertex(pose, (float)(at.x-camera.x)+point.x, (float)(at.y-camera.y)+point.y, (float)(at.z-camera.z)+point.z)
									.setColor(0xFFFFFFFF).setUv(corner == 1 || corner == 2 ? 0.5F : 0, corner >= 2 ? 0.5F : 0)
									.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, normal.x, normal.y, normal.z);
						}
					}
				}
			});
		}
	}
}
