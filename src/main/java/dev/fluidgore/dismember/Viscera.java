package dev.fluidgore.dismember;

import dev.fluidgore.event.Events;
import dev.fluidgore.anatomy.Anatomy;
import dev.fluidgore.anatomy.Anatomy.Piece;
import dev.fluidgore.anatomy.Anatomy.Region;
import dev.fluidgore.anatomy.MeltRenderer;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.GoreSystems;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.material.Material;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.Ragdoll;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.physics.Blast;
import dev.fluidgore.trigger.EntityFilter;
import dev.fluidgore.trigger.Fate;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.animal.sheep.SheepFurModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A body's anatomy in the world, for a blast to tear apart piece by piece (see {@link Anatomy}): every bone and organ of
 * a person, a sheep, a pig or a cow, where it lies inside the body as it stands this instant, its true size, and what
 * it is called.
 *
 * <p>A blast does not hit a body evenly. Each piece takes the overpressure where it is, which falls steeply with
 * distance, so the side facing the charge takes many times what the far side does, and the body itself shields what lies
 * behind it. Whatever takes more than it can bear is torn out and thrown away from the charge at the speed the blast's
 * impulse gives a piece of its depth ({@link Blast#throwSpeed}): a rib or a hoof faster than a lung, everything within a
 * metre of a charge at tens of metres a second, spinning as it goes. A cow over a block of TNT loses its underside first,
 * legs and guts thrown down and out, the spine and ribs above coming apart last and slowest.
 *
 * <p>Every piece keeps its name, so what lands can be told for what it is (see {@link Giblets#describe}).
 */
public final class Viscera {
	/** Where the model's origin is, above the entity's feet, in blocks (see Dismemberment). */
	private static final float MODEL_Y_OFFSET = 1.501F;
	/** The fastest a piece is thrown, blocks a tick: faster ones are gone before they can be seen. */
	private static final double FASTEST = 1.6;
	/** How much of the overpressure the body lets through to the far side of itself. */
	private static final float SHIELDED = 0.4F;
	private static final RandomSource RANDOM = Fate.stream("viscera");

	/**
	 * One piece of a body's inside, where it is in the world: its joint box ({@code hx, hy, hz}, half sides in blocks, in its
	 * own frame), which is how it moves and lands, and the cubes it is drawn with (null for a piece of one cube).
	 * <ul>
	 *   <li>As a giblet ({@link Giblets#organ(ClientLevel, Organ, Vec3, LivingEntity, int, float)}): {@code shape}, mirrored
	 *   to the giblet's frame, turned by {@code pitch} and {@code yaw} as it lay (a giblet cannot roll).</li>
	 *   <li>As a loose piece of its own ({@link #part}): {@code modelShape}, mirrored the way the body part's fit runs, in the
	 *   joint box {@code box} (lo x, y, z, hi x, y, z, the body part's pixels), turned by {@code rotation}: the body's yaw,
	 *   its roll if it lies on its side, and the body part's own turn, through the same flip the renderer applies.</li>
	 * </ul>
	 *
	 * @param part the limb or head it is in, or null for the trunk
	 * @param side "left" or "right" for one of a pair (a lung, a limb's bones), empty for an organ there is one of
	 */
	public record Organ(Anatomy.Kind kind, String name, String side, Vec3 at, float hx, float hy, float hz, @Nullable BodyPart part,
			Anatomy.@Nullable Shape shape, float pitch, float yaw, Anatomy.@Nullable Shape modelShape, float[] box, Quaternionf rotation, float scale) {
		/** Its smallest depth, in blocks (metres): how much of it the blast has to move. */
		public float thickness() {
			return 2.0F * Math.min(hx, Math.min(hy, hz));
		}
	}

	private Viscera() {
	}

	/**
	 * The anatomy of {@code entity} as it stands now, or an empty list if it has none drawn (anything that is not a person
	 * or one of the beasts, or a model this cannot pose). Parts already severed ({@code missing}) are left out.
	 */
	public static List<Organ> of(LivingEntity entity, int missing) {
		List<Organ> organs = new ArrayList<>();
		ModelParts.Posed posed = ModelParts.pose(entity);
		if (posed == null) {
			return organs;
		}

		Anatomy.Beast beast = posed.model() instanceof QuadrupedModel<?> && !(posed.model() instanceof SheepFurModel) ? Anatomy.Beast.of(entity.getType()) : null;
		if (beast == null && !(posed.model() instanceof HumanoidModel<?>)) {
			return organs;
		}

		boolean b = beast != null;
		ModelPart root = posed.model().root();
		ModelPart[] parts = ModelParts.index(posed.model());
		Lie lie = lie(entity, posed);
		region(organs, entity, posed, lie, root, ModelParts.torso(posed.model()), b ? Region.BEAST_TRUNK : Region.TORSO, false, null, beast);
		BodyPart[] limbs = {BodyPart.HEAD, BodyPart.RIGHT_ARM, BodyPart.LEFT_ARM, BodyPart.RIGHT_LEG, BodyPart.LEFT_LEG};
		Region[] regions = b ? new Region[] {Region.BEAST_HEAD, Region.FORELEG, Region.FORELEG, Region.HINDLEG, Region.HINDLEG}
				: new Region[] {Region.HEAD, Region.ARM, Region.ARM, Region.LEG, Region.LEG};
		for (int i = 0; i < limbs.length; i++) {
			if ((missing & limbs[i].bit()) == 0) {
				region(organs, entity, posed, lie, root, parts[limbs[i].ordinal()], regions[i], limbs[i] == BodyPart.LEFT_ARM || limbs[i] == BodyPart.LEFT_LEG,
						limbs[i], beast);
			}
		}

		return organs;
	}

	/**
	 * Where and how a body lies, as the renderer draws it: where its feet are, the way it faces (degrees, as {@code bodyRot})
	 * and how far it has rolled onto its side (radians). A dead body this game keeps is where its corpse lies, as
	 * {@code Dismemberment} severs from it; a living one stands where it is.
	 */
	private record Lie(Vec3 origin, float yaw, float roll) {
	}

	private static Lie lie(LivingEntity entity, ModelParts.Posed posed) {
		Corpse corpse = GoreSystems.suffering().corpse(entity.getId());
		if (corpse != null) {
			Ragdoll body = corpse.body;
			return new Lie(new Vec3(body.x(1.0F), body.y(1.0F), body.z(1.0F)), body.yaw(1.0F), body.roll(1.0F));
		}

		return new Lie(entity.position(), posed.state().bodyRot, 0.0F);
	}

	private static void region(List<Organ> organs, LivingEntity entity, ModelParts.Posed posed, Lie lie, ModelPart root, @Nullable ModelPart part,
			Region region, boolean left, @Nullable BodyPart bodyPart, Anatomy.@Nullable Beast beast) {
		if (part == null) {
			return;
		}

		List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part).fluidgore$getCubes();
		if (cubes.isEmpty()) {
			return;
		}

		ModelPart.Cube cube = MeltRenderer.core(cubes);
		boolean turned = region == Region.BEAST_TRUNK && MeltRenderer.turned(part);
		float[] fit = Anatomy.fit(region, left, turned, new float[] {cube.minX, cube.minY, cube.minZ}, new float[] {cube.maxX, cube.maxY, cube.maxZ});
		// One organ is one object, however many cubes draw it.
		Piece[] table = beast != null ? Anatomy.pieces(region, left, beast, turned, Anatomy.Detail.MIXED, Anatomy.male(entity.getUUID()),
				entity.isBaby()) : Anatomy.pieces(region, left);
		Piece[] pieces = Anatomy.objects(table);
		Anatomy.@Nullable Shape[] shapes = Anatomy.shapes(table);
		float scale = posed.state().scale * ModelParts.chainScale(root, part);
		float yaw = (float) Math.toRadians(180.0F - lie.yaw());
		Quaternionf facing = new Quaternionf().rotationY(yaw);
		// A giblet's own frame is the part's with x and y the other way, as the world's are to the model's (a half turn
		// about z), so it keeps the part's turn about x the other way round; a fit that runs an axis backwards mirrors it.
		float pitch = -part.xRot;
		int mirror = (fit[0] < 0.0F ? 0 : 1) | (fit[1] < 0.0F ? 0 : 2) | (fit[2] < 0.0F ? 4 : 0);
		int fitMirror = (fit[0] < 0.0F ? 1 : 0) | (fit[1] < 0.0F ? 2 : 0) | (fit[2] < 0.0F ? 4 : 0);
		// A loose piece is drawn as the body is (see GoreRenderer): its turn, then the model's flip (a half turn about z),
		// then its own cubes. So its turn is the body's yaw and roll, and the part's whole turn in model space seen
		// through that flip.
		Quaternionf flip = new Quaternionf().rotationZ(Mth.PI);
		Quaternionf rotation = new Quaternionf(facing).rotateZ(lie.roll()).mul(flip).mul(ModelParts.chainRotation(root, part))
				.mul(new Quaternionf(flip).conjugate());
		for (int k = 0; k < pieces.length; k++) {
			Piece piece = pieces[k];
			float[] lo = new float[3];
			float[] hi = new float[3];
			for (int i = 0; i < 3; i++) {
				float a = piece.min(i) * fit[i] + fit[3 + i];
				float c = piece.max(i) * fit[i] + fit[3 + i];
				lo[i] = Math.min(a, c);
				hi[i] = Math.max(a, c);
			}

			Vector3f local = new Vector3f((lo[0] + hi[0]) / 32.0F, (lo[1] + hi[1]) / 32.0F, (lo[2] + hi[2]) / 32.0F);
			Vector3f model = ModelParts.toModelSpace(root, part, local);
			if (model == null) {
				continue;
			}

			// Model space to the world as Dismemberment severs a part: tipped about the feet by the roll and lifted onto the
			// floor, then turned to face the body's way.
			Vector3f offset = new Vector3f(-model.x, MODEL_Y_OFFSET - model.y, model.z).mul(posed.state().scale);
			if (lie.roll() != 0.0F) {
				offset.rotateZ(lie.roll());
				offset.y += ModelParts.sideLift(entity.getId(), entity.getBbWidth(), posed.state().scale) * Math.abs(Mth.sin(lie.roll()));
			}

			facing.transform(offset);
			Vec3 at = lie.origin().add(offset.x, offset.y, offset.z);
			// A limb's bones are its side's; in the trunk and the head only one of a pair has a side ("the right lung").
			String side = bodyPart != null && bodyPart != BodyPart.HEAD ? left ? "left" : "right" : Anatomy.paired(table, piece.name()) ? piece.side() : "";
			organs.add(new Organ(piece.kind(), piece.name(), side, at, (hi[0] - lo[0]) / 32.0F * scale, (hi[1] - lo[1]) / 32.0F * scale,
					(hi[2] - lo[2]) / 32.0F * scale, bodyPart, shapes[k] != null ? shapes[k].mirrored(mirror) : null, pitch, yaw,
					shapes[k] != null ? shapes[k].mirrored(fitMirror) : null, new float[] {lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]}, rotation, scale));
		}
	}

	/**
	 * Tears out of {@code entity} every piece of its anatomy that the blast of {@code tntKg} at {@code from} hits with at
	 * least {@code kPa} where it lies (all of them, with {@code kPa} 0), skipping those in severed parts, and throws each
	 * away from the charge. {@code seen} is how much of the body the blast could see (cover cuts it, never to nothing).
	 * Returns how many were thrown.
	 */
	public static int blow(ClientLevel level, BloodWorld blood, Giblets giblets, LivingEntity entity, int missing, Vec3 from, double tntKg, float seen, float kPa,
			float sear) {
		List<Organ> organs = of(entity, missing);
		if (organs.isEmpty()) {
			return 0;
		}

		Vec3 centre = entity.getBoundingBox().getCenter();
		Vec3 through = centre.subtract(from);
		through = through.lengthSqr() > 1.0E-6 ? through.normalize() : new Vec3(0.0, 1.0, 0.0);
		double half = Math.max(0.2, entity.getBbWidth() * 0.5);
		// Its weight, for what its lungs can bear under water (see Underwater).
		double kg = dev.fluidgore.physics.BodyMass.kilograms(entity);
		int rgb = EntityFilter.bloodColor(entity);
		int thrown = 0;
		for (Organ organ : organs) {
			Vec3 out = organ.at().subtract(from);
			double metres = out.length();
			// The body shields what is behind it: the far side takes a fraction of what the near side does.
			float behind = (float) Mth.clamp(organ.at().subtract(centre).dot(through) / half, -1.0, 1.0);
			float shield = Mth.lerp((behind + 1.0F) * 0.5F, 1.0F, SHIELDED);
			float own = Blast.covered(Events.overpressure(level, organ.at(), metres, tntKg, 1.0E7, kg), seen) * shield;
			if (own < kPa) {
				continue;
			}

			Vec3 dir = metres > 1.0E-3 ? out.scale(1.0 / metres) : new Vec3(RANDOM.nextGaussian(), 1.0, RANDOM.nextGaussian()).normalize();
			double speed = Math.min(FASTEST, Events.throwSpeed(level, organ.at(), metres, tntKg, organ.thickness()) * shield * (0.8 + 0.4 * RANDOM.nextDouble()) / 20.0);
			// A little scatter: the wave is turbulent and the body breaks up unevenly.
			Vec3 vel = dir.add(RANDOM.nextGaussian() * 0.12, RANDOM.nextGaussian() * 0.08, RANDOM.nextGaussian() * 0.12).normalize().scale(speed);
			// How hard the blast's cloud takes it: a charge's fireball sears it, a vessel's gas chills it; pressure alone does
			// neither. One draw of the dice either way, so the rest comes out the same whatever the source.
			float roll = RANDOM.nextFloat();
			String what = String.format(java.util.Locale.ROOT, "%s%s thrown at %.1f m/s by %.0f kPa", organ.side().isEmpty() ? "" : organ.side() + " ", organ.name(),
					speed * 20.0, own);
			if (whole(organ)) {
				// A soft organ of many cubes comes out a loose piece of its own: it can be picked up, thrown and cut, and the
				// other players see it as this player does (see part).
				GorePart made = part(level, organ, vel, entity, rgb, sear);
				Events.record(Events.Channel.PRESSURE, -1, Events.Effect.IMPULSE, Events.piece(made.key), what);
				GorePartManager.cloud(made);
			} else {
				int made = giblets.organ(level, organ, vel, entity, rgb, sear);
				if (made < 0) {
					break;
				}

				Events.record(Events.Channel.PRESSURE, -1, Events.Effect.IMPULSE, Events.piece(giblets.key(made)), what);
				giblets.cloud(made, roll);
			}

			thrown++;
			if (organ.kind() == Anatomy.Kind.RUMEN) {
				// The rumen bursts: its cud, green and stinking, sprays out with it.
				BloodEmitter.cut(blood, level, organ.at(), dir, Tissue.CUD.rgb, 0.08F, 10);
			} else if (organ.kind() == Anatomy.Kind.LUNG || organ.kind() == Anatomy.Kind.HEART || organ.kind() == Anatomy.Kind.LIVER) {
				// The blood-filled organs go in a spray of their own blood.
				BloodEmitter.cut(blood, level, organ.at(), dir, rgb, 0.03F, 5);
			}
		}

		return thrown;
	}

	/**
	 * For demos and tests: takes the organs of {@code entity} called one of {@code names} out of it whole, each from where
	 * it lies (as it lies: a body on its side gives up its organs on their side), thrown at {@code vel} (blocks a tick).
	 * Returns the loose pieces made (see {@link #whole}); any of them too small or too hard to be one goes among the
	 * giblets as a blast's would. Their spin comes from the shared dice, as a blast's does. Nothing else of the body is
	 * touched.
	 */
	public static List<GorePart> lift(ClientLevel level, Giblets giblets, LivingEntity entity, java.util.Set<String> names, Vec3 vel) {
		List<GorePart> made = new ArrayList<>();
		int rgb = EntityFilter.bloodColor(entity);
		for (Organ organ : of(entity, 0)) {
			if (names.contains(organ.name())) {
				if (whole(organ)) {
					made.add(part(level, organ, vel, entity, rgb, 0.0F));
				} else if (giblets.organ(level, organ, vel, entity, rgb, 0.0F) < 0) {
					break;
				}
			}
		}

		return made;
	}

	/**
	 * True if {@code organ} comes out of the body a loose piece of its own rather than a giblet: a soft organ (not bone,
	 * cartilage or horn) of several cubes (a liver, a lung, the heart), no more than one evidence record holds. Those can be
	 * carried, thrown and cut like a limb, and go through the same sync. Bones and single blocks stay giblets; so does an
	 * organ of more cubes than {@link OrganModel#MOST_CUBES} (a ruminant's rumen), drawn by its shape all the same.
	 */
	public static boolean whole(Organ organ) {
		Material m = Material.of(organ.kind());
		return organ.modelShape() != null && organ.modelShape().cubes() <= OrganModel.MOST_CUBES && !m.bone() && m != Material.KERATIN;
	}

	/**
	 * {@code organ} of {@code entity} out of it as a loose piece, thrown at {@code vel}: its own model of the cubes it is
	 * made of ({@link OrganModel#build}), drawn with anatomy.png, where and as it lay in the body. It keeps its name, side
	 * and owner, what the body had been through ({@code sear}: the body's char, as for a giblet), and enough blood for a
	 * few landings and a little dripping. Added to the dismemberment's pieces, named by the shared dice like any piece
	 * (so a cut on it reaches the others: see {@link dev.fluidgore.net.GoreSync}).
	 */
	public static GorePart part(ClientLevel level, Organ organ, Vec3 vel, LivingEntity entity, int rgb, float sear) {
		float[] box = organ.box();
		ModelPart model = OrganModel.build(organ.modelShape(), box);
		float[] drawn = OrganModel.bounds(OrganModel.blocks(model, organ.kind()));
		Vector3f localCenter = new Vector3f((drawn[0] + drawn[3]) / 32.0F, (drawn[1] + drawn[4]) / 32.0F, (drawn[2] + drawn[5]) / 32.0F);
		Vector3f half = new Vector3f(drawn[3] - drawn[0], drawn[4] - drawn[1], drawn[5] - drawn[2]).mul(organ.scale() / 32.0F);
		// A bounded/coarser organ may not reach every side of the in-body box. Fit collision to the geometry actually
		// detached, shifting its pivot in world space so that none of its cubes move when that pivot changes.
		Vector3f shift = new Vector3f(localCenter).sub((box[0] + box[3]) / 32.0F, (box[1] + box[4]) / 32.0F, (box[2] + box[5]) / 32.0F)
				.mul(-organ.scale(), -organ.scale(), organ.scale());
		organ.rotation().transform(shift);
		Vec3 at = organ.at().add(shift.x, shift.y, shift.z);
		GoreConfig config = GoreConfig.get();
		int lifetime = Math.max(400, Math.round(config.partLifetimeSeconds * 20 * config.goreLevel.duration));
		GorePart part = new GorePart(model, MeltRenderer.TEXTURE, localCenter, half, organ.scale(), rgb, lifetime);
		part.organ = organ.name();
		part.side = organ.side();
		part.tissue = organ.kind();
		part.owner = entity.getType();
		part.label = GorePart.organLabel(part.owner, part.organ, part.side, 0);
		// Blood for a few landings, and a little to drip: finite, so no throw or cut makes more of it.
		part.condition.fluid = ORGAN_FLUID;
		part.bleedLeft = ORGAN_BLEED * config.goreLevel.puddles;
		part.condition.inherit(GoreSystems.dismemberment().bodyCondition(entity, part.material()));
		if (sear > 0.05F) {
			part.condition.sear(sear);
		}

		part.event = Events.current();
		part.eventSource = Events.source();
		part.x = part.prevX = at.x;
		part.y = part.prevY = at.y;
		part.z = part.prevZ = at.z;
		part.rotation.set(organ.rotation());
		part.prevRotation.set(organ.rotation());
		part.vx = vel.x;
		part.vy = vel.y;
		part.vz = vel.z;
		// It tumbles as fast as it flies, the smallest fastest.
		float largest = Math.max(half.x, Math.max(half.y, half.z));
		part.spinAxis.set(RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F).normalize();
		if (!part.spinAxis.isFinite()) {
			part.spinAxis.set(1.0F, 0.0F, 0.0F);
		}

		part.spinSpeed = Mth.clamp((float) vel.length() / Math.max(0.05F, largest) * 0.12F, 0.0F, 0.8F);
		part.light = net.minecraft.util.LightCoordsUtil.getLightCoords(level, net.minecraft.core.BlockPos.containing(organ.at()));
		GoreSystems.dismemberment().parts().add(part);
		return part;
	}

	/** Loose blood a whole organ holds, in landing splats, and blood it drips. */
	private static final float ORGAN_FLUID = 0.3F;
	private static final float ORGAN_BLEED = 0.04F;

	/**
	 * The overpressure each limb (and the head) of {@code entity} takes from a blast, by its pieces' worst: what decides
	 * which come off. Indexed by {@link BodyPart} ordinal; 0 for one with no anatomy here.
	 */
	public static float[] limbs(LivingEntity entity, int missing, Vec3 from, double tntKg, float seen) {
		float[] worst = new float[BodyPart.values().length];
		double kg = dev.fluidgore.physics.BodyMass.kilograms(entity);
		for (Organ organ : of(entity, missing)) {
			if (organ.part() != null) {
				float own = Blast.covered(Events.overpressure(entity.level(), organ.at(), organ.at().distanceTo(from), tntKg, 1.0E7, kg), seen);
				worst[organ.part().ordinal()] = Math.max(worst[organ.part().ordinal()], own);
			}
		}

		return worst;
	}
}
