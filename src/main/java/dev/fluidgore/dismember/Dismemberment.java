package dev.fluidgore.dismember;

import dev.fluidgore.blood.BloodSupply;
import java.util.HashMap;
import dev.fluidgore.GoreSystems;
import dev.fluidgore.anatomy.Melting;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.rot.Rot;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.BoneEmitter;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.event.Events;
import dev.fluidgore.material.Condition;
import dev.fluidgore.material.Material;
import dev.fluidgore.wound.BurntBone;
import dev.fluidgore.suffer.Voice;
import dev.fluidgore.trigger.EntityFilter;
import dev.fluidgore.trigger.Fate;
import dev.fluidgore.wound.BloodyWool;
import dev.fluidgore.wound.Burns;
import dev.fluidgore.wound.Cremation;
import dev.fluidgore.wound.Flesh;
import dev.fluidgore.wound.WoundSet;
import dev.fluidgore.wound.Wounds;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import dev.fluidgore.physics.Blast;
import net.minecraft.util.Mth;
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
import net.minecraft.world.entity.EntityType;
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
	private final Feathers feathers = new Feathers();
	private final Burns burns = new Burns();
	private final Cremation cremation = new Cremation();
	private final Melting melting = new Melting();
	private final BloodyWool wool = new BloodyWool();
	private final Butchery butchery = new Butchery();
	{
		giblets.bodies(this::bodyCondition);
	}

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

	public Cremation cremation() {
		return cremation;
	}

	public Melting melting() {
		return melting;
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

	/**
	 * How many pieces of {@code owner}'s anatomy called {@code name} are lying about (any owner, any name, with null):
	 * organs and bones among the giblets, and whole organs and their portions among the loose pieces (see {@link Viscera#whole}).
	 */
	public int organs(@Nullable EntityType<?> owner, @Nullable String name) {
		int n = giblets.organs(owner, name, new int[1]);
		for (GorePart part : parts.parts()) {
			if (part.organ != null && !part.shattered && (owner == null || part.owner == owner) && (name == null || name.equals(part.organ))) {
				n++;
			}
		}

		return n;
	}

	public Feathers feathers() {
		return feathers;
	}

	/**
	 * What {@code entity}'s {@code material} has been through, as a piece of it would carry it off: the char of its
	 * burns ({@link Burns}), the flesh its own fire has burnt away ({@link Cremation}: cooked through, dried, the fat
	 * gone; bone as hot as the skeleton got), how deep a living body had burnt ({@link Melting}), and whether it is
	 * alight now. A body nothing has touched gives a fresh condition.
	 */
	public Condition bodyCondition(LivingEntity entity, Material material) {
		int id = entity.getId();
		Condition c = new Condition();
		float charred = burns.charOf(id);
		float burnt = cremation.burn(id);
		float bare = melting.bare(id);
		if (material.bone()) {
			c.charred = Math.max(cremation.bone(id), BurntBone.ownFire(Math.max(burnt, bare)));
			c.moisture = 1.0F - 0.9F * Mth.clamp(burnt * 1.5F, 0.0F, 1.0F);
		} else {
			c.sear(charred);
			c.cooked = Math.max(c.cooked, Math.max(Mth.clamp(burnt * 2.0F, 0.0F, 1.0F), bare));
			c.moisture = Math.min(c.moisture, Math.min(1.0F - 0.9F * Mth.clamp(burnt * 1.5F, 0.0F, 1.0F), 1.0F - 0.7F * bare));
			c.fuel = Math.min(c.fuel, 1.0F - burnt);
			c.integrity = Math.min(c.integrity, 1.0F - 0.5F * burnt);
		}

		c.burning = entity.isOnFire() || cremation.burning(id);
		if (c.burning) {
			c.temperature = 300.0F;
		}

		return c;
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
		vaporize(blood, level, entity, from, Blast.TNT_BLOCK_KG, 1.0F);
	}

	/**
	 * A blast of {@code tntKg} at {@code from} tore {@code entity} apart ({@code seen}: how much of it the blast could
	 * see). A body with an anatomy (a person, a sheep, a pig, a cow) comes apart into its own bones and organs, each
	 * thrown by the blast where it lay (see {@link Viscera}), with scraps of muscle, fat and hide; anything else into
	 * pieces of every tissue in their real proportions.
	 */
	public void vaporize(BloodWorld blood, ClientLevel level, LivingEntity entity, Vec3 from, double tntKg, float seen) {
		if (vaporized.contains(entity.getId())) {
			return;
		}

		// The body bursts over everything standing round it.
		if (Flesh.of(entity).bleeds()) {
			wool.blast(level, entity, entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0), 4.0, EntityFilter.bloodColor(entity));
		}

		float burnt = burns.charOf(entity.getId());
		int organs = Viscera.blow(level, blood, giblets, entity, missingMask(entity.getId()), from, tntKg, seen, 0.0F, burnt);
		vaporized.add(entity.getId());
		if (organs > 0) {
			giblets.burstFlesh(level, blood, entity, from, 0.6F);
		} else {
			giblets.burst(level, blood, entity, from);
		}

		int rgb = EntityFilter.bloodColor(entity);
		Vec3 centre = entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
		featherCloud(entity, centre, from, 1.0F, rgb);
		BloodEmitter.burst(blood, level, centre, rgb, 0.6F * entity.getBbWidth() * entity.getBbHeight(), Math.round(40 * entity.getBbWidth()));
		Vec3 out = centre.subtract(from);
		// A red mist: fine blood driven out hard in every direction, painting the walls and floor around.
		BloodEmitter.blast(blood, level, centre, out.lengthSqr() > 1.0E-6 ? out.normalize() : null, rgb,
				0.4F * entity.getBbWidth() * entity.getBbHeight(), Math.round(60 * entity.getBbWidth()));

		if (GoreConfig.get().goreSounds) {
			BloodEmitter.burstSound(level, centre, 1.0F);
		}
	}

	/** A bird caught in a blast: a cloud of feathers blown out, half of them bloody, that snows down for seconds after. */
	private void featherCloud(LivingEntity entity, Vec3 centre, Vec3 from, float share, int rgb) {
		if (!Feathers.feathered(entity)) {
			return;
		}

		float left = 1.0F - butchery.plucked(entity.getId());
		Vec3 out = centre.subtract(from);
		feathers.tuft(entity, centre, out.lengthSqr() > 1.0E-6 ? out.normalize() : new Vec3(0.0, 1.0, 0.0), Math.round(90 * share * left), 0.3, 0.6F, rgb);
	}

	/**
	 * A blast close by, but not close enough to shred the body: it tears two to four limbs (or the head) off and
	 * throws them, rips the belly open so the gut and organs spill out, and sprays blood over everything around.
	 * {@code force} is how close it was to shredding the body, 0..1.
	 */
	public void tear(BloodWorld blood, ClientLevel level, LivingEntity entity, Vec3 from, float force) {
		tear(blood, level, entity, from, force, Blast.TNT_BLOCK_KG, 1.0F);
	}

	/**
	 * A blast of {@code tntKg} at {@code from} close by, but not close enough to shred the body. With an anatomy, the
	 * limbs that took the blast hardest (by the overpressure on their own bones, see {@link Viscera#limbs}) come off and
	 * fly, as many as took enough to tear them; the belly wall facing the charge bursts and what took more than it could
	 * bear inside (gut, liver, a lung, ribs on the near side) is blown out of it. Without one, two to four limbs come
	 * off at random and the gut spills. {@code force} is how close it was to shredding the body, 0..1.
	 */
	public void tear(BloodWorld blood, ClientLevel level, LivingEntity entity, Vec3 from, float force, double tntKg, float seen) {
		Vec3 centre = entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
		if (Flesh.of(entity).bleeds()) {
			wool.blast(level, entity, centre, 3.0, EntityFilter.bloodColor(entity));
		}

		Vec3 out = centre.subtract(from);
		Vec3 flat = new Vec3(out.x, 0.0, out.z);
		flat = flat.lengthSqr() > 1.0E-6 ? flat.normalize() : null;
		int missingBefore = missingMask(entity.getId());
		float[] limbs = Viscera.limbs(entity, missingBefore, from, tntKg, seen);
		boolean anatomy = false;
		for (float kPa : limbs) {
			anatomy |= kPa > 0.0F;
		}

		if (anatomy) {
			// The limbs that took enough to tear them off, worst first; at least the worst, since the body is torn.
			List<BodyPart> torn = new ArrayList<>();
			for (BodyPart part : remaining(entity)) {
				if (limbs[part.ordinal()] >= Blast.TEAR_KPA) {
					torn.add(part);
				}
			}

			torn.sort((a, b) -> Float.compare(limbs[b.ordinal()], limbs[a.ordinal()]));
			if (torn.isEmpty()) {
				BodyPart worst = null;
				for (BodyPart part : remaining(entity)) {
					if (worst == null || limbs[part.ordinal()] > limbs[worst.ordinal()]) {
						worst = part;
					}
				}

				if (worst != null) {
					torn.add(worst);
				}
			}

			for (BodyPart part : torn) {
				Vec3 away = flat == null ? null : flat.yRot((float) (RANDOM.nextGaussian() * 0.35));
				// Thrown by the push of the blast on it: the harder it was hit, the further.
				float hit = (float) Math.log(Math.max(1.0F, limbs[part.ordinal()]) / Blast.TEAR_KPA);
				GorePart limb = sever(blood, level, entity, part, away, entity::position, Float.NaN, 0.0F, 2.5 + 2.5 * Mth.clamp(hit, 0.0F, 1.5F));
				Events.record(Events.Channel.PRESSURE, -1, Events.Effect.IMPULSE, limb != null ? Events.piece(limb.key) : Events.body(entity),
						String.format(java.util.Locale.ROOT, "%s torn off by %.0f kPa", part, limbs[part.ordinal()]));
			}

			// The belly wall facing the charge bursts: what took more than it could bear is blown out of it.
			Viscera.blow(level, blood, giblets, entity, missingMask(entity.getId()), from, tntKg, seen, Blast.TEAR_KPA * 1.6F, burns.charOf(entity.getId()));
			giblets.spill(level, blood, entity, from, force * 0.5F);
		} else {
			List<BodyPart> candidates = remaining(entity);
			int count = 2 + RANDOM.nextInt(2) + (force > 0.5F ? 1 : 0);
			for (int i = 0; i < count && !candidates.isEmpty(); i++) {
				BodyPart chosen = candidates.remove(RANDOM.nextInt(candidates.size()));
				// Each piece goes its own way, roughly away from the blast.
				Vec3 away = flat == null ? null : flat.yRot((float) (RANDOM.nextGaussian() * 0.6));
				sever(blood, level, entity, chosen, away, entity::position, Float.NaN, 0.0F, 2.5 + 3.0 * force);
			}

			giblets.spill(level, blood, entity, from, force);
		}
		int rgb = EntityFilter.bloodColor(entity);
		featherCloud(entity, centre, from, 0.4F + 0.4F * force, rgb);
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
				// Its burns came with it (see bodyCondition).
				part.lifetime = lifetime;
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

	/** What a severed part of {@code model} is called: a person's arm, a beast's foreleg, a bird's wing. */
	/**
	 * What is inside the severed head {@code piece} of a beast (see {@link HeadSection}), so a blade can split it down the
	 * middle; null for a person, a beast with no anatomy drawn, or a head of more than one block (a pig's snout, a cow's
	 * horns), which a blade hacks apart instead.
	 */
	private static @Nullable HeadSection section(LivingEntity entity, EntityModel<?> model, ModelPart piece) {
		if (!(model instanceof net.minecraft.client.model.QuadrupedModel<?>) || model instanceof net.minecraft.client.model.animal.sheep.SheepFurModel) {
			return null;
		}

		dev.fluidgore.anatomy.Anatomy.Beast beast = dev.fluidgore.anatomy.Anatomy.Beast.of(entity.getType());
		java.util.List<ModelPart.Cube> cubes = ((dev.fluidgore.mixin.ModelPartAccessor) (Object) piece).fluidgore$getCubes();
		if (beast == null || cubes.size() != 1 || !((dev.fluidgore.mixin.ModelPartAccessor) (Object) piece).fluidgore$getChildren().isEmpty()) {
			return null;
		}

		ModelPart.Cube c = cubes.getFirst();
		return new HeadSection(beast, dev.fluidgore.anatomy.Anatomy.male(entity.getUUID()), entity.isBaby(), new float[] {c.minX, c.minY, c.minZ, c.maxX, c.maxY, c.maxZ}, 0);
	}

	private static String limbName(EntityModel<?> model, BodyPart part) {
		boolean person = model instanceof net.minecraft.client.model.HumanoidModel<?>;
		boolean bird = model instanceof net.minecraft.client.model.animal.chicken.ChickenModel;
		return switch (part) {
			case HEAD -> "head";
			case RIGHT_ARM -> person ? "right_arm" : bird ? "right_wing" : "right_foreleg";
			case LEFT_ARM -> person ? "left_arm" : bird ? "left_wing" : "left_foreleg";
			case RIGHT_LEG -> person || bird ? "right_leg" : "right_hind_leg";
			case LEFT_LEG -> person || bird ? "left_leg" : "left_hind_leg";
		};
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
		inheritRot(part, entity);
		part.condition.inherit(bodyCondition(entity, part.material()));
		part.event = Events.current();
		part.eventSource = Events.source();
		part.owner = entity.getType();
		part.label = GorePart.named(part.owner, source == ModelParts.torso(root) ? "carcass" : "remains");
		Vec3 base = origin.get();
		part.x = part.prevX = base.x + offset.x;
		part.y = part.prevY = base.y + offset.y;
		part.z = part.prevZ = base.z + offset.z;
		GorePartManager.cloud(part);
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
		if (!lethal) {
			// A body that lives through the blow keeps its head: only a limb can go.
			candidates.remove(BodyPart.HEAD);
		}

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
		inheritRot(part, entity);
		part.condition.inherit(bodyCondition(entity, part.material()));
		part.event = Events.current();
		part.eventSource = Events.source();
		part.owner = entity.getType();
		part.label = GorePart.named(part.owner, limbName(model, bodyPart));
		part.wounds = pieceWounds;
		part.coat = wool.piece(entity, bodyPart);
		part.armor = ArmorPieces.of(entity, posed, bodyPart);
		if (bodyPart == BodyPart.HEAD) {
			part.section = section(entity, model, piece);
		}

		Integer plucked = butchery.tint(entity.getId());
		if (plucked != null) {
			part.tint = plucked;
		}
		Vec3 base = origin.get();
		part.x = part.prevX = base.x + offset.x;
		part.y = part.prevY = base.y + offset.y;
		part.z = part.prevZ = base.z + offset.z;
		GorePartManager.cloud(part);
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
		feathers.tick(level, blood);
		burns.tick(level, blood);
		melting.tick(level, blood);
		cremation.tick(level, blood);
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
					BloodSupply.begin(living);
					BloodEmitter.drip(blood, stump, EntityFilter.bloodColor(living), 0.01F);
					BloodSupply.end();
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
		cremation.forget(entityId);
		melting.forget(entityId);
		wool.forget(entityId);
		butchery.forget(entityId);
	}

	/**
	 * A body lies on under a new id: its player respawned and took the old one back (see {@code Suffering.release}).
	 * What is missing from it, its wounds, its burns and its fire go with it.
	 */
	public void rekey(int from, int to) {
		if (missing.containsKey(from)) {
			missing.put(to, missing.remove(from));
		}

		if (vaporized.remove(from)) {
			vaporized.add(to);
		}

		WoundSet set = wounds.get(from);
		wounds.forget(from);
		if (set != null) {
			wounds.restore(to, set);
		}

		burns.rekey(from, to);
		cremation.rekey(from, to);
		melting.rekey(from, to);
	}

	public void onEntityUnload(int entityId) {
		missing.remove(entityId);
		vaporized.remove(entityId);
		wounds.forget(entityId);
		burns.forget(entityId);
		cremation.forget(entityId);
		melting.forget(entityId);
		wool.forget(entityId);
		butchery.forget(entityId);
	}

	public void clear() {
		interaction.clear();
		missing.clear();
		wounds.clear();
		giblets.clear();
		feathers.clear();
		vaporized.clear();
		burns.clear();
		cremation.clear();
		melting.clear();
		wool.clear();
		butchery.clear();
		parts.clear();
	}

	/** A piece cut off a rotting body is as rotten as the body, and rots on from there by itself. */
	private static void inheritRot(GorePart part, LivingEntity entity) {
		Rot rot = GoreSystems.decay().of(entity.getId());
		if (rot != null) {
			part.rot = rot.days;
			part.arid = rot.arid;
		}
	}
}
