package dev.fluidgore.dismember;

import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.suffer.Voice;
import dev.fluidgore.trigger.EntityFilter;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Decides when parts come off, spawns the severed {@link GorePart}s, and remembers which parts each entity has
 * lost so its own model hides them (see {@code ModelFeatureRendererMixin}).
 */
public final class Dismemberment {
	/** Bitmask of {@link BodyPart}s missing on the entity being rendered; attached to its render state. */
	public static final RenderStateDataKey<Integer> MISSING_PARTS = RenderStateDataKey.create(() -> "fluidgore:missing_parts");

	private static final RandomSource RANDOM = RandomSource.create();
	/** Vanilla living-entity renderers shift the model down by this amount after flipping it. */
	private static final float MODEL_Y_OFFSET = 1.501F;

	private final Int2IntOpenHashMap missing = new Int2IntOpenHashMap();
	private final GorePartManager parts = new GorePartManager();
	private final PartInteraction interaction = new PartInteraction(parts);

	public PartInteraction interaction() {
		return interaction;
	}
	private int tick;

	public GorePartManager parts() {
		return parts;
	}

	public int missingMask(int entityId) {
		return missing.get(entityId);
	}

	/**
	 * Called for a hit with a bladed weapon. Lethal hits can take several parts; heavy non-lethal hits take one.
	 */
	public void onBladedHit(BloodWorld blood, ClientLevel level, LivingEntity entity, boolean lethal, @Nullable Vec3 away) {
		GoreLevel preset = GoreConfig.get().goreLevel;
		if (!preset.enabled() || RANDOM.nextFloat() >= preset.partChance) {
			return;
		}

		int maxParts = lethal ? preset.maxPartsPerHit : 1;
		int count = 1 + (maxParts > 1 ? RANDOM.nextInt(maxParts) : 0);
		List<BodyPart> candidates = new ArrayList<>(List.of(BodyPart.values()));
		int already = missing.get(entity.getId());
		candidates.removeIf(p -> (already & p.bit()) != 0);
		for (int i = 0; i < count && !candidates.isEmpty(); i++) {
			// The head is the most dramatic cut, so it is favoured on kills.
			BodyPart chosen = lethal && candidates.contains(BodyPart.HEAD) && RANDOM.nextFloat() < 0.45F
					? BodyPart.HEAD
					: candidates.get(RANDOM.nextInt(candidates.size()));
			candidates.remove(chosen);
			sever(blood, level, entity, chosen, away);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void sever(BloodWorld blood, ClientLevel level, LivingEntity entity, BodyPart bodyPart, @Nullable Vec3 away) {
		Minecraft mc = Minecraft.getInstance();
		EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
		if (!(renderer instanceof LivingEntityRenderer livingRenderer)) {
			return;
		}

		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Object rawState = renderer.createRenderState(entity, partialTick);
		if (!(rawState instanceof LivingEntityRenderState state)) {
			return;
		}

		EntityModel model = livingRenderer.getModel();
		ModelPart source = ModelParts.index(model)[bodyPart.ordinal()];
		if (source == null) {
			return;
		}

		// Pose the shared model like this entity so the measured position matches what the player sees.
		// Every render calls setupAnim again before drawing, so this has no visible side effect.
		model.setupAnim(state);
		Identifier texture = livingRenderer.getTextureLocation(state);

		float[] b = ModelParts.bounds(source);
		Vector3f localCenter = new Vector3f((b[0] + b[3]) * 0.5F, (b[1] + b[4]) * 0.5F, (b[2] + b[5]) * 0.5F);
		Vector3f modelCenter = ModelParts.toModelSpace(model.root(), source, localCenter);
		if (modelCenter == null) {
			return;
		}

		// Model space -> world, mirroring LivingEntityRenderer: rotate by body yaw, scale, flip X/Y, shift down.
		float scale = state.scale * state.ageScale;
		Quaternionf bodyRotation = new Quaternionf().rotationY((float) Math.toRadians(180.0F - state.bodyRot));
		Vector3f offset = new Vector3f(-modelCenter.x, MODEL_Y_OFFSET - modelCenter.y, modelCenter.z).mul(scale);
		bodyRotation.transform(offset);

		Vector3f half = new Vector3f((b[3] - b[0]) * 0.5F, (b[4] - b[1]) * 0.5F, (b[5] - b[2]) * 0.5F).mul(scale);
		int rgb = EntityFilter.bloodColor(entity);
		GoreLevel preset = GoreConfig.get().goreLevel;
		int lifetime = Math.max(100, Math.round(GoreConfig.get().partLifetimeSeconds * 20 * preset.duration));

		GorePart part = new GorePart(ModelParts.copy(source), texture, localCenter, half, scale, rgb, lifetime);
		part.x = part.prevX = entity.getX() + offset.x;
		part.y = part.prevY = entity.getY() + offset.y;
		part.z = part.prevZ = entity.getZ() + offset.z;
		part.rotation.set(bodyRotation);
		part.prevRotation.set(bodyRotation);

		double ax = away != null ? away.x : RANDOM.nextDouble() - 0.5;
		double az = away != null ? away.z : RANDOM.nextDouble() - 0.5;
		part.vx = ax * 0.18 + (RANDOM.nextDouble() - 0.5) * 0.12;
		part.vz = az * 0.18 + (RANDOM.nextDouble() - 0.5) * 0.12;
		part.vy = 0.18 + RANDOM.nextDouble() * 0.15 + (bodyPart == BodyPart.HEAD ? 0.08 : 0.0);
		part.spinAxis.set(RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F).normalize();
		if (!part.spinAxis.isFinite()) {
			part.spinAxis.set(1.0F, 0.0F, 0.0F);
		}

		part.spinSpeed = 0.2F + RANDOM.nextFloat() * 0.35F;
		part.bleedLeft = 0.12F * preset.puddles;
		part.light = net.minecraft.util.LightCoordsUtil.getLightCoords(level, entity.blockPosition());
		if (bodyPart == BodyPart.HEAD && GoreConfig.get().sufferingLevel.enabled()) {
			// The head keeps twitching and moaning for a few seconds (see Suffering#tickSeveredHeads).
			part.voice = Voice.of(entity);
			part.twitchTicks = Math.round((80 + RANDOM.nextInt(80)) * GoreConfig.get().sufferingLevel.duration);
		}
		parts.add(part);

		missing.put(entity.getId(), missing.get(entity.getId()) | bodyPart.bit());
		Vec3 wound = new Vec3(part.x, part.y, part.z);
		BloodEmitter.burst(blood, level, wound, rgb, 0.25F, 18);
	}

	/** Physics for severed parts and bleeding stumps on entities that are still walking around. */
	public void tick(ClientLevel level, BloodWorld blood) {
		tick++;
		interaction.tick(level, blood);
		parts.tick(level, blood);
		if (tick % 8 != 0 || missing.isEmpty()) {
			return;
		}

		for (Int2IntMap.Entry entry : missing.int2IntEntrySet()) {
			Entity entity = level.getEntity(entry.getIntKey());
			if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
				continue;
			}

			for (BodyPart part : BodyPart.values()) {
				if ((entry.getIntValue() & part.bit()) != 0) {
					Vec3 stump = new Vec3(living.getX(), living.getY() + living.getBbHeight() * part.stumpHeight, living.getZ());
					BloodEmitter.drip(blood, stump, EntityFilter.bloodColor(living), 0.01F);
				}
			}
		}
	}

	/** An entity respawned or healed from death: give it its parts back. */
	public void restore(int entityId) {
		missing.remove(entityId);
	}

	public void onEntityUnload(int entityId) {
		missing.remove(entityId);
	}

	public void clear() {
		interaction.clear();
		missing.clear();
		parts.clear();
	}
}
