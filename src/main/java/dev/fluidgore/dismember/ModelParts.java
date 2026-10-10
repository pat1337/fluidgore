package dev.fluidgore.dismember;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.suffer.BodyPose;
import dev.fluidgore.suffer.Ragdoll;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Model-part lookup, cloning and measuring helpers for dismemberment. */
public final class ModelParts {
	/** Per-model cache of severable parts, indexed by {@link BodyPart#ordinal()}. Weak so models can unload. */
	private static final Map<Model<?>, ModelPart[]> INDEX = new WeakHashMap<>();
	private static final Map<ModelPart, ModelPart[]> CHAINS = new WeakHashMap<>();
	/** The entity's id, attached to its render state so the model it is drawn with can be recorded. */
	public static final RenderStateDataKey<Integer> ENTITY_ID = RenderStateDataKey.create(() -> "fluidgore:entity_id");
	private static final Int2ObjectOpenHashMap<EntityModel<?>> DRAWN = new Int2ObjectOpenHashMap<>();
	/** Arm (or front leg) and leg slots in the order ragdoll joints use them. */
	public static final BodyPart[] LIMBS = {BodyPart.RIGHT_ARM, BodyPart.LEFT_ARM, BodyPart.RIGHT_LEG, BodyPart.LEFT_LEG};

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



	/** An entity's shared model, posed for that entity right now, with the render state used to pose it. */
	public record Posed(EntityModel<?> model, LivingEntityRenderState state, LivingEntityRenderer<?, ?, ?> renderer) {
	}

	/**
	 * Poses the shared model like this entity this frame, including a corpse's ragdoll joints, so measurements match
	 * what the player sees. Every render calls {@code setupAnim} again before drawing, so this has no visible side
	 * effect. Null for entities without a living-entity renderer.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static @Nullable Posed pose(LivingEntity entity) {
		Minecraft mc = Minecraft.getInstance();
		EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
		if (!(renderer instanceof LivingEntityRenderer livingRenderer)) {
			return null;
		}

		if (!(renderer.createRenderState(entity, partialTick()) instanceof LivingEntityRenderState state)) {
			return null;
		}

		// Many renderers swap models per entity while drawing (baby or adult, cow and pig variants), so the
		// renderer's current model is whatever was drawn last. Use the one this entity was really drawn with.
		EntityModel model = DRAWN.get(entity.getId());
		if (model == null) {
			model = livingRenderer.getModel();
		}

		model.setupAnim(state);
		BodyPose body = state.getData(BodyPose.KEY);
		if (body != null) {
			applyJoints(index(model), body.joints(), null);
		}

		return new Posed(model, state, livingRenderer);
	}

	/** The partial tick of the frame being drawn, which places and poses entities between ticks. */
	public static float partialTick() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	/** Called as each living entity is drawn, with the model its renderer picked for it. */
	public static void recordDrawn(int entityId, EntityModel<?> model) {
		DRAWN.put(entityId, model);
	}

	/** The model this entity was last drawn with, or null if it has not been drawn yet. */
	public static @Nullable EntityModel<?> drawn(int entityId) {
		return DRAWN.get(entityId);
	}

	public static void forgetDrawn(int entityId) {
		DRAWN.remove(entityId);
	}

	public static void clearDrawn() {
		DRAWN.clear();
	}

	/** Combined scale of {@code target} and every part above it (baby models shrink their root this way). */
	public static float chainScale(ModelPart root, ModelPart target) {
		float scale = 1.0F;
		for (ModelPart part : chain(root, target)) {
			scale *= part.xScale;
		}

		return scale;
	}

	/** Adds a ragdoll's joint offsets to the head and limbs; {@code before} sees each part before it is changed. */
	public static void applyJoints(ModelPart[] parts, float[] j, @Nullable Consumer<ModelPart> before) {
		ModelPart head = parts[BodyPart.HEAD.ordinal()];
		if (head != null) {
			if (before != null) {
				before.accept(head);
			}

			head.xRot += j[Ragdoll.HEAD_X];
			head.yRot += j[Ragdoll.HEAD_Y];
			head.zRot += j[Ragdoll.HEAD_Z];
		}

		for (int i = 0; i < LIMBS.length; i++) {
			ModelPart limb = parts[LIMBS[i].ordinal()];
			if (limb != null) {
				if (before != null) {
					before.accept(limb);
				}

				limb.xRot += j[Ragdoll.LIMB_X + i];
				limb.zRot += j[Ragdoll.LIMB_Z + i];
			}
		}
	}

	/** The parts from the root down to {@code target}, inclusive; empty if it is not in the model. Cached. */
	public static ModelPart[] chain(ModelPart root, ModelPart target) {
		return CHAINS.computeIfAbsent(target, t -> {
			List<ModelPart> chain = new ArrayList<>();
			return findChain(root, t, chain) ? chain.toArray(ModelPart[]::new) : new ModelPart[0];
		});
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
