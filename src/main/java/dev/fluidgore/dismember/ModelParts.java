package dev.fluidgore.dismember;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.fluidgore.mixin.ModelPartAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Model-part lookup, cloning and measuring helpers for dismemberment. */
public final class ModelParts {
	/** Per-model cache of severable parts, indexed by {@link BodyPart#ordinal()}. Weak so models can unload. */
	private static final Map<Model<?>, ModelPart[]> INDEX = new WeakHashMap<>();

	private ModelParts() {
	}

	public static ModelPart[] index(Model<?> model) {
		return INDEX.computeIfAbsent(model, m -> {
			Function<String, @Nullable ModelPart> lookup = m.root().createPartLookup();
			ModelPart[] parts = new ModelPart[BodyPart.values().length];
			for (BodyPart part : BodyPart.values()) {
				for (String name : part.modelNames) {
					ModelPart found = lookup.apply(name);
					if (found != null && found != m.root()) {
						parts[part.ordinal()] = found;
						break;
					}
				}
			}

			return parts;
		});
	}

	/**
	 * Deep-copies a part so it can be posed independently of the shared entity model. Cube geometry is immutable
	 * and shared; only the tree and poses are new. The copy's own pose is zero, children keep their current pose.
	 */
	public static ModelPart copy(ModelPart source) {
		ModelPart copy = copyTree(source);
		copy.loadPose(PartPose.ZERO);
		copy.visible = true;
		return copy;
	}

	private static ModelPart copyTree(ModelPart source) {
		ModelPartAccessor accessor = (ModelPartAccessor) (Object) source;
		Map<String, ModelPart> children = new LinkedHashMap<>();
		accessor.fluidgore$getChildren().forEach((name, child) -> children.put(name, copyTree(child)));
		ModelPart copy = new ModelPart(new ArrayList<>(accessor.fluidgore$getCubes()), children);
		PartPose pose = source.storePose();
		copy.setInitialPose(pose);
		copy.loadPose(pose);
		copy.visible = source.visible;
		copy.skipDraw = source.skipDraw;
		return copy;
	}

	/** Axis-aligned bounds (in blocks, in the part's own space) of a part's cubes and its direct children's cubes. */
	public static float[] bounds(ModelPart part) {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		include(b, part, 0.0F, 0.0F, 0.0F);
		((ModelPartAccessor) (Object) part).fluidgore$getChildren().values()
				.forEach(child -> include(b, child, child.x, child.y, child.z));
		if (b[0] > b[3]) {
			return new float[]{-0.125F, -0.125F, -0.125F, 0.125F, 0.125F, 0.125F};
		}

		for (int i = 0; i < 6; i++) {
			b[i] /= 16.0F;
		}

		return b;
	}

	private static void include(float[] b, ModelPart part, float ox, float oy, float oz) {
		for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).fluidgore$getCubes()) {
			b[0] = Math.min(b[0], cube.minX + ox);
			b[1] = Math.min(b[1], cube.minY + oy);
			b[2] = Math.min(b[2], cube.minZ + oz);
			b[3] = Math.max(b[3], cube.maxX + ox);
			b[4] = Math.max(b[4], cube.maxY + oy);
			b[5] = Math.max(b[5], cube.maxZ + oz);
		}
	}

	/**
	 * Transforms a point given in {@code target}'s local space (blocks) into model space by walking the part
	 * hierarchy from the root, applying each part's current pose. Returns null if the part is not in the model.
	 */
	public static @Nullable Vector3f toModelSpace(ModelPart root, ModelPart target, Vector3f local) {
		List<ModelPart> chain = new ArrayList<>();
		if (!findChain(root, target, chain)) {
			return null;
		}

		PoseStack stack = new PoseStack();
		for (ModelPart part : chain) {
			part.translateAndRotate(stack);
		}

		return stack.last().pose().transformPosition(new Vector3f(local));
	}

	private static boolean findChain(ModelPart current, ModelPart target, List<ModelPart> chain) {
		chain.add(current);
		if (current == target) {
			return true;
		}

		for (ModelPart child : ((ModelPartAccessor) (Object) current).fluidgore$getChildren().values()) {
			if (findChain(child, target, chain)) {
				return true;
			}
		}

		chain.removeLast();
		return false;
	}
}
