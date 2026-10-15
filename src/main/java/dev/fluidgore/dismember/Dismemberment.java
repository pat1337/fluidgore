package dev.fluidgore.dismember;

import java.util.HashMap;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.BoneEmitter;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.suffer.Voice;
import dev.fluidgore.trigger.EntityFilter;
import dev.fluidgore.trigger.Fate;
import dev.fluidgore.wound.BloodyWool;
import dev.fluidgore.wound.Burns;
import dev.fluidgore.wound.WoundSet;
import dev.fluidgore.wound.Wounds;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
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

	private static final RandomSource RANDOM = Fate.stream("dismember");
	/** Vanilla living-entity renderers shift the model down by this amount after flipping it. */
	private static final float MODEL_Y_OFFSET = 1.501F;

	private final Int2IntOpenHashMap missing = new Int2IntOpenHashMap();
	private final GorePartManager parts = new GorePartManager();
	private final PartInteraction interaction = new PartInteraction(parts);
	private final Wounds wounds = new Wounds();
	private final Giblets giblets = new Giblets();
	private final Burns burns = new Burns();
	private final BloodyWool wool = new BloodyWool();
	private final Butchery butchery = new Butchery();
	/** Bodies torn to pieces by a blast: not drawn at all any more. */
	private final IntOpenHashSet vaporized = new IntOpenHashSet();
	private int tick;

	public PartInteraction interaction() {
		return interaction;
	}

	public Wounds wounds() {
		return wounds;
	}

	public Butchery butchery() {
		return butchery;
	}

	public Burns burns() {
		return burns;
	}

	public BloodyWool wool() {
		return wool;
	}

	public Giblets giblets() {
		return giblets;
	}

	/** True if this entity was torn apart by a blast; its renderer is skipped. */
	public boolean vaporized(int entityId) {
		return vaporized.contains(entityId);
	}

	/** Bodies with nothing left to draw: torn or butchered apart, or cleaned away. */
	public IntOpenHashSet vaporizedIds() {
		return vaporized;
	}

	/** A body cleaned away: no longer drawn, though the entity is there until the server takes it. */
	public void hide(int entityId) {
		vaporized.add(entityId);
	}

	/**
	 * A blast at {@code from} tore {@code entity} apart: it is no longer drawn, its body becomes a spray of pieces of
	 * every tissue it is made of, and blood goes everywhere.
	 */
	public void vaporize(BloodWorld blood, ClientLevel level, LivingEntity entity, Vec3 from) {
		if (!vaporized.add(entity.getId())) {
			return;
		}

		giblets.burst(level, blood, entity, from);
		int rgb = EntityFilter.bloodColor(entity);
		Vec3 centre = entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
		BloodEmitter.burst(blood, level, centre, rgb, 0.6F * entity.getBbWidth() * entity.getBbHeight(), Math.round(40 * entity.getBbWidth()));
		Vec3 out = centre.subtract(from);
		// A red mist: fine blood driven out hard in every direction, painting the walls and floor around.
		BloodEmitter.blast(blood, level, centre, out.lengthSqr() > 1.0E-6 ? out.normalize() : null, rgb,
				0.4F * entity.getBbWidth() * entity.getBbHeight(), Math.round(60 * entity.getBbWidth()));

		if (GoreConfig.get().goreSounds) {
			BloodEmitter.burstSound(level, centre, 1.0F);
		}
	}

	/**
	 * A blast close by, but not close enough to shred the body: it tears two to four limbs (or the head) off and
	 * throws them, rips the belly open so the gut and organs spill out, and sprays blood over everything around.
	 * {@code force} is how close it was to shredding the body, 0..1.
	 */
	public void tear(BloodWorld blood, ClientLevel level, LivingEntity entity, Vec3 from, float force) {
		Vec3 centre = entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
		Vec3 out = centre.subtract(from);
		Vec3 flat = new Vec3(out.x, 0.0, out.z);
		flat = flat.lengthSqr() > 1.0E-6 ? flat.normalize() : null;
		List<BodyPart> candidates = remaining(entity);
		int count = 2 + RANDOM.nextInt(2) + (force > 0.5F ? 1 : 0);
		for (int i = 0; i < count && !candidates.isEmpty(); i++) {
			BodyPart chosen = candidates.remove(RANDOM.nextInt(candidates.size()));
			// Each piece goes its own way, roughly away from the blast.
			Vec3 away = flat == null ? null : flat.yRot((float) (RANDOM.nextGaussian() * 0.6));
			sever(blood, level, entity, chosen, away, entity::position, Float.NaN, 0.0F, 2.5 + 3.0 * force);
		}

		giblets.spill(level, blood, entity, from, force);
		int rgb = EntityFilter.bloodColor(entity);
		BloodEmitter.blast(blood, level, centre, out.lengthSqr() > 1.0E-6 ? out.normalize() : null, rgb,
				0.25F * entity.getBbWidth() * entity.getBbHeight(), Math.round(30 * entity.getBbWidth()));
		if (GoreConfig.get().goreSounds) {
			BloodEmitter.burstSound(level, centre, 0.8F);
		}
	}

	/**
	 * The end of a death, Terraria style: what is left of a body lying at {@code at} bursts apart. Its head and limbs
	 * are thrown up and out with a few scraps of flesh in a spray of blood, and can be kicked about until they fade,
	 * sooner than ordinarily severed parts.
	 */
	public void pop(BloodWorld blood, ClientLevel level, LivingEntity entity, Vec3 at, float yaw, float roll) {
		GoreConfig config = GoreConfig.get();
		int lifetime = Math.max(100, Math.round(config.partLifetimeSeconds * 10 * config.goreLevel.duration));
		float burnt = burns.charOf(entity.getId());
		for (BodyPart bodyPart : remaining(entity)) {
			Vec3 away = new Vec3(RANDOM.nextGaussian(), 0.0, RANDOM.nextGaussian());
			GorePart part = sever(blood, level, entity, bodyPart, away.lengthSqr() > 1.0E-6 ? away.normalize() : null, () -> at, yaw, roll, 1.4);
			if (part != null) {
				part.lifetime = lifetime;
				if (burnt > 0.0F) {
					part.tint = Burns.tint(burnt);
				}
				part.voice = null;
				part.twitchTicks = 0;
			}
		}

		// The oldest pieces may have made room for these, so the new ones are the last {@code made}.
		int made = giblets.spill(level, blood, entity, at, 0.0F);
		if (burnt > 0.0F) {
			giblets.searFrom(giblets.count() - made, burnt);
		}

		Vec3 centre = at.add(0.0, entity.getBbWidth() * 0.4, 0.0);
		// Burnt flesh is cooked through and hardly bleeds.
		BloodEmitter.burst(blood, level, centre, EntityFilter.bloodColor(entity), 0.12F * entity.getBbWidth() * entity.getBbHeight() * (1.0F - 0.8F * burnt),
				Math.round(24 * entity.getBbWidth()));
		if (config.goreSounds) {
			BloodEmitter.burstSound(level, centre, 0.6F);
		}
	}

	/**
	 * Lifts the carcass of {@code entity} off as a loose piece: the trunk ({@code torso}, its body part) with whatever
	 * limbs and head were still on it coming off first, a spill of innards, and the body itself gone. Returns the piece
	 * (not yet added), or null if the model cannot be posed.
	 */
	public @Nullable GorePart carcass(BloodWorld blood, ClientLevel level, LivingEntity entity, ModelPart torso, Supplier<Vec3> origin, float yaw,
			float roll) {
		ModelParts.Posed posed = ModelParts.pose(entity);
		if (posed == null) {
			return null;
		}

		for (BodyPart bodyPart : remaining(entity)) {
			Vec3 away = new Vec3(RANDOM.nextGaussian(), 0.0, RANDOM.nextGaussian());
			sever(blood, level, entity, bodyPart, away.lengthSqr() > 1.0E-6 ? away.normalize() : null, origin, yaw, roll, 0.5);
		}

		// The trunk alone, so it can be cut; whatever hangs off it (a horse's tail) drops off on its own.
		ModelPart trunk = new ModelPart(new ArrayList<>(((ModelPartAccessor) (Object) torso).fluidgore$getCubes()), new HashMap<>());
		GorePart part = loose(level, entity, posed, torso, trunk, origin, yaw, roll);
		if (part == null) {
			return null;
		}

		for (ModelPart child : ((ModelPartAccessor) (Object) torso).fluidgore$getChildren().values()) {
			GorePart hanging = loose(level, entity, posed, child, ModelParts.copy(child), origin, yaw, roll);
			if (hanging != null) {
				hanging.vy = 0.1;
				hanging.tint = butchery.tint(entity.getId()) != null ? butchery.tint(entity.getId()) : hanging.tint;
				parts.add(hanging);
			}
		}

		part.bleedLeft = 0.1F * GoreConfig.get().goreLevel.puddles;
		vaporized.add(entity.getId());
		giblets.spill(level, blood, entity, new Vec3(part.x, part.y, part.z), 0.0F);
		BloodEmitter.burst(blood, level, new Vec3(part.x, part.y, part.z), EntityFilter.bloodColor(entity), 0.06F, 12);
		return part;
	}

	/**
	 * A loose piece drawn with {@code model}, placed exactly where {@code source} (a part of the posed model) is on the
	 * body: the same mapping from model space to the world that {@link #sever} uses. Not yet added.
	 */
	private @Nullable GorePart loose(ClientLevel level, LivingEntity entity, ModelParts.Posed posed, ModelPart source, ModelPart model,
			Supplier<Vec3> origin, float yaw, float roll) {
		EntityModel root = posed.model();
		LivingEntityRenderState state = posed.state();
		float[] b = ModelParts.bounds(model);
		Vector3f localCenter = new Vector3f((b[0] + b[3]) * 0.5F, (b[1] + b[4]) * 0.5F, (b[2] + b[5]) * 0.5F);
		Vector3f modelCenter = ModelParts.toModelSpace(root.root(), source, localCenter);
		if (modelCenter == null) {
			return null;
		}

		Quaternionf bodyRotation = new Quaternionf().rotationY((float) Math.toRadians(180.0F - yaw));
		Vector3f offset = new Vector3f(-modelCenter.x, MODEL_Y_OFFSET - modelCenter.y, modelCenter.z).mul(state.scale);
		float scale = state.scale * ModelParts.chainScale(root.root(), source);
		if (roll != 0.0F) {
			offset.rotateZ(roll);
			offset.y += ModelParts.sideLift(entity.getId(), entity.getBbWidth(), state.scale) * Math.abs((float) Math.sin(roll));
		}

		bodyRotation.transform(offset);
		Vector3f half = new Vector3f((b[3] - b[0]) * 0.5F, (b[4] - b[1]) * 0.5F, (b[5] - b[2]) * 0.5F).mul(scale);
		Identifier texture = ((LivingEntityRenderer) posed.renderer()).getTextureLocation(state);
		int lifetime = Math.max(400, Math.round(GoreConfig.get().partLifetimeSeconds * 20 * GoreConfig.get().goreLevel.duration));
		GorePart part = new GorePart(model, texture, localCenter, half, scale, EntityFilter.bloodColor(entity), lifetime);
		Vec3 base = origin.get();
		part.x = part.prevX = base.x + offset.x;
		part.y = part.prevY = base.y + offset.y;
		part.z = part.prevZ = base.z + offset.z;
		Quaternionf partRotation = new Quaternionf(bodyRotation).rotateZ(roll);
		part.rotation.set(partRotation);
		part.prevRotation.set(partRotation);
		part.spinAxis.set(1.0F, 0.0F, 0.0F);
		part.light = net.minecraft.util.LightCoordsUtil.getLightCoords(level, entity.blockPosition());
		return part;
	}

	/**
	 * A bone smashed beyond saving: a limb is torn off where it broke; the head is caved in and bursts, bone and
	 * brains, with nothing left to fly.
	 */
	public void smash(BloodWorld blood, ClientLevel level, LivingEntity entity, BodyPart part, Supplier<Vec3> origin, float yaw, float roll,
			@Nullable Vec3 away) {
		if ((missingMask(entity.getId()) & part.bit()) != 0) {
			return;
		}

		GorePart piece = sever(blood, level, entity, part, away, origin, yaw, roll, part == BodyPart.HEAD ? 0.3 : 1.0);
		if (piece == null || part != BodyPart.HEAD) {
			return;
		}

		Vec3 at = new Vec3(piece.x, piece.y, piece.z);
		piece.shattered = true;
		BoneEmitter.shatter(level, at, 1.0F);
		giblets.chips(level, entity, at, new Vec3(0.0, 1.0, 0.0), 10, 1.0F);
		BloodEmitter.burst(blood, level, at, EntityFilter.bloodColor(entity), 0.08F, 16);
	}

	public GorePartManager parts() {
		return parts;
	}

	public int missingMask(int entityId) {
		return missing.get(entityId);
	}

	/** Restores the parts a body lost before this player was there (see {@code net/Evidence}). */
	public void restoreMissing(int entityId, int mask) {
		if (mask != 0) {
			missing.put(entityId, missing.get(entityId) | mask);
		}
	}

	/**
	 * Called for a hit with a bladed weapon whose spot on the body is not known. Lethal hits can take several parts;
	 * heavy non-lethal hits take one.
	 */
	public void onBladedHit(BloodWorld blood, ClientLevel level, LivingEntity entity, boolean lethal, @Nullable Vec3 away) {
		GoreLevel preset = GoreConfig.get().goreLevel;
		if (!preset.enabled() || RANDOM.nextFloat() >= preset.partChance) {
			return;
		}

		int maxParts = lethal ? preset.maxPartsPerHit : 1;
		int count = 1 + (maxParts > 1 ? RANDOM.nextInt(maxParts) : 0);
		List<BodyPart> candidates = remaining(entity);
		for (int i = 0; i < count && !candidates.isEmpty(); i++) {
			// The head is the most dramatic cut, so it is favoured on kills.
			BodyPart chosen = lethal && candidates.contains(BodyPart.HEAD) && RANDOM.nextFloat() < 0.45F
					? BodyPart.HEAD
					: candidates.get(RANDOM.nextInt(candidates.size()));
			candidates.remove(chosen);
			sever(blood, level, entity, chosen, away, entity::position, Float.NaN, 0.0F, 1.0);
		}
	}

	/**
	 * Cuts a random remaining part off a body the physics has moved: it may have walked, been thrown and tipped over
	 * since the server last placed the entity, so the caller says where the body is now.
	 */
	public void severOnBody(BloodWorld blood, ClientLevel level, LivingEntity entity, Supplier<Vec3> origin, float yaw, float roll, @Nullable Vec3 away) {
		List<BodyPart> candidates = remaining(entity);
		if (!candidates.isEmpty()) {
			sever(blood, level, entity, candidates.get(RANDOM.nextInt(candidates.size())), away, origin, yaw, roll, 1.0);
		}
	}

	/**
	 * Cuts off exactly the part that was struck. {@code origin}, {@code yaw} and {@code roll} place the body as for
	 * {@link #severOnBody}; a living entity passes its own position and NaN yaw.
	 */
	public void severPart(BloodWorld blood, ClientLevel level, LivingEntity entity, BodyPart part, Supplier<Vec3> origin, float yaw,
			float roll, @Nullable Vec3 away) {
		if ((missing.get(entity.getId()) & part.bit()) == 0) {
			sever(blood, level, entity, part, away, origin, yaw, roll, 1.0);
		}
	}

	private List<BodyPart> remaining(LivingEntity entity) {
		List<BodyPart> candidates = new ArrayList<>(List.of(BodyPart.values()));
		int already = missing.get(entity.getId());
		candidates.removeIf(p -> (already & p.bit()) != 0);
		return candidates;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private @Nullable GorePart sever(BloodWorld blood, ClientLevel level, LivingEntity entity, BodyPart bodyPart, @Nullable Vec3 away,
			Supplier<Vec3> origin, float yawOverride, float roll, double fling) {
		ModelParts.Posed posed = ModelParts.pose(entity);
		if (posed == null) {
			return null;
		}

		EntityModel model = posed.model();
		LivingEntityRenderState state = posed.state();
		ModelPart source = ModelParts.index(model)[bodyPart.ordinal()];
		if (source == null) {
			return null;
		}

		Identifier texture = ((LivingEntityRenderer) posed.renderer()).getTextureLocation(state);
		ModelPart piece = ModelParts.copy(source);
		// Before the part is marked missing, so the stump is found on the parts around it.
		WoundSet pieceWounds = wounds.onSevered(entity, posed, source, piece, bodyPart);

		float[] b = ModelParts.bounds(source);
		Vector3f localCenter = new Vector3f((b[0] + b[3]) * 0.5F, (b[1] + b[4]) * 0.5F, (b[2] + b[5]) * 0.5F);
		Vector3f modelCenter = ModelParts.toModelSpace(model.root(), source, localCenter);
		if (modelCenter == null) {
			return null;
		}

		// Model space -> world, mirroring LivingEntityRenderer: rotate by body yaw, scale, flip X/Y, shift down.
		// The model-space centre already includes any scaling inside the model (baby models shrink their root);
		// the loose copy has none of its parents, so it carries that scale itself.
		float yaw = Float.isNaN(yawOverride) ? state.bodyRot : yawOverride;
		Quaternionf bodyRotation = new Quaternionf().rotationY((float) Math.toRadians(180.0F - yaw));
		Vector3f offset = new Vector3f(-modelCenter.x, MODEL_Y_OFFSET - modelCenter.y, modelCenter.z).mul(state.scale);
		float scale = state.scale * ModelParts.chainScale(model.root(), source);
		if (roll != 0.0F) {
			// A tipped-over body: lean about its feet like the renderer does, then lift it so it rests on the floor.
			offset.rotateZ(roll);
			offset.y += ModelParts.sideLift(entity.getId(), entity.getBbWidth(), state.scale) * Math.abs((float) Math.sin(roll));
		}

		bodyRotation.transform(offset);

		Vector3f half = new Vector3f((b[3] - b[0]) * 0.5F, (b[4] - b[1]) * 0.5F, (b[5] - b[2]) * 0.5F).mul(scale);
		int rgb = EntityFilter.bloodColor(entity);
		GoreLevel preset = GoreConfig.get().goreLevel;
		int lifetime = Math.max(100, Math.round(GoreConfig.get().partLifetimeSeconds * 20 * preset.duration));

		GorePart part = new GorePart(piece, texture, localCenter, half, scale, rgb, lifetime);
		part.wounds = pieceWounds;
		part.armor = ArmorPieces.of(entity, posed, bodyPart);
		Integer plucked = butchery.tint(entity.getId());
		if (plucked != null) {
			part.tint = plucked;
		}
		Vec3 base = origin.get();
		part.x = part.prevX = base.x + offset.x;
		part.y = part.prevY = base.y + offset.y;
		part.z = part.prevZ = base.z + offset.z;
		Quaternionf partRotation = new Quaternionf(bodyRotation).rotateZ(roll);
		part.rotation.set(partRotation);
		part.prevRotation.set(partRotation);

		double ax = away != null ? away.x : RANDOM.nextDouble() - 0.5;
		double az = away != null ? away.z : RANDOM.nextDouble() - 0.5;
		part.vx = ax * 0.18 * fling + (RANDOM.nextDouble() - 0.5) * 0.12;
		part.vz = az * 0.18 * fling + (RANDOM.nextDouble() - 0.5) * 0.12;
		part.vy = 0.18 + RANDOM.nextDouble() * 0.15 + (bodyPart == BodyPart.HEAD ? 0.08 : 0.0) + 0.1 * (fling - 1.0);
		part.spinAxis.set(RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F).normalize();
		if (!part.spinAxis.isFinite()) {
			part.spinAxis.set(1.0F, 0.0F, 0.0F);
		}

		part.spinSpeed = (0.2F + RANDOM.nextFloat() * 0.35F) * (float) Math.sqrt(fling);
		part.bleedLeft = part.bone() ? 0.0F : 0.12F * preset.puddles;
		part.light = net.minecraft.util.LightCoordsUtil.getLightCoords(level, entity.blockPosition());
		if (bodyPart == BodyPart.HEAD && GoreConfig.get().sufferingLevel.enabled()) {
			// The head keeps twitching and moaning for a few seconds (see Suffering#tickSeveredHeads).
			part.voice = Voice.of(entity);
			part.twitchTicks = Math.round((80 + RANDOM.nextInt(80)) * GoreConfig.get().sufferingLevel.duration);
		}
		parts.add(part);

		missing.put(entity.getId(), missing.get(entity.getId()) | bodyPart.bit());
		// The stump stays attached to the body (which may keep moving); the part flies off on its own.
		Vec3 stumpOffset = new Vec3(offset.x, offset.y, offset.z);
		BloodEmitter.sever(blood, level, entity, () -> origin.get().add(stumpOffset), () -> new Vec3(part.x, part.y, part.z), rgb);
		return part;
	}

	/** Physics for severed parts and bleeding stumps on entities that are still walking around. */
	public void tick(ClientLevel level, BloodWorld blood) {
		butchery.tick();
		tick++;
		interaction.tick(level, blood);
		parts.tick(level, blood);
		wounds.tick(level, blood);
		giblets.tick(level, blood);
		burns.tick(level, blood);
		wool.tick(level);
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
		vaporized.remove(entityId);
		wounds.forget(entityId);
		burns.forget(entityId);
		wool.forget(entityId);
		butchery.forget(entityId);
	}

	public void onEntityUnload(int entityId) {
		missing.remove(entityId);
		vaporized.remove(entityId);
		wounds.forget(entityId);
		burns.forget(entityId);
		wool.forget(entityId);
		butchery.forget(entityId);
	}

	public void clear() {
		interaction.clear();
		missing.clear();
		wounds.clear();
		giblets.clear();
		vaporized.clear();
		burns.clear();
		wool.clear();
		butchery.clear();
		parts.clear();
	}
}
