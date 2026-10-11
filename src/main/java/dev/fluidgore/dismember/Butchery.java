package dev.fluidgore.dismember;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.CorpseStrike;
import dev.fluidgore.suffer.Ragdoll;
import dev.fluidgore.trigger.Fate;
import dev.fluidgore.wound.Flesh;
import dev.fluidgore.wound.Wounds;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Processing a carcass, as it is done on a farm or in a poultry lab: plucking a bird, jointing it (the legs, wings and
 * head come off with a blade, see {@link Dismemberment#severPart}), splitting the carcass, and cutting the pieces down
 * until they are diced.
 * <ul>
 * <li><b>Plucking</b>: right-click a dead chicken with an empty hand. A handful of feathers comes out where you aim and
 *     flies off; the skin there is bare, pale and goose-pimpled with the pits the feathers came out of. The more is
 *     plucked, the more the whole bird turns to bare skin.</li>
 * <li><b>Splitting</b>: a few chops through the carcass where it is already cut to the bone split it: whatever limbs
 *     are left come off, and the body falls apart into two raw-faced pieces.</li>
 * <li><b>Dicing</b>: a blade on any loose piece cuts it in two across its longest side, raw meat on the new faces,
 *     until the pieces are too small to cut.</li>
 * </ul>
 * Every pluck and cut keeps the body on the ground longer, so it is not gone before the work is done.
 */
public final class Butchery {
	/** Plucks that leave a bird completely bare. */
	private static final int PLUCKS_TO_BARE = 12;
	/** Smallest side, in model pixels, that can still be cut in two. */
	private static final float MIN_CUT = 2.0F;
	/** How long a body worked on stays, at least, after each pluck or cut. */
	private static final int KEEP_TICKS = 600;
	private static final int PLUCK_COOLDOWN = 3;
	/** Chops through a carcass already cut to the bone before it splits: the backbone takes a few. */
	public static final int CHOPS_TO_SPLIT = 3;
	private static final int BARE_SKIN = 0xF4CBB4;
	private static final RandomSource RANDOM = Fate.stream("butchery");

	private final Int2IntOpenHashMap plucks = new Int2IntOpenHashMap();
	private final Int2IntOpenHashMap chops = new Int2IntOpenHashMap();
	private int tick;
	private int lastPluck = -100;

	public static boolean pluckable(LivingEntity entity) {
		return entity.getType() == EntityTypes.CHICKEN;
	}

	/** How much of this bird is plucked, 0..1. */
	public float plucked(int entityId) {
		return Math.min(1.0F, plucks.get(entityId) / (float) PLUCKS_TO_BARE);
	}

	/** Multiplier tint for a plucked bird: white (feathered) through to bare skin. Null if untouched. */
	public @Nullable Integer tint(int entityId) {
		float p = plucked(entityId);
		if (p <= 0.0F) {
			return null;
		}

		int r = Math.round(Mth.lerp(p, 255, BARE_SKIN >> 16 & 0xFF));
		int g = Math.round(Mth.lerp(p, 255, BARE_SKIN >> 8 & 0xFF));
		int b = Math.round(Mth.lerp(p, 255, BARE_SKIN & 0xFF));
		return r << 16 | g << 8 | b;
	}

	public void tick() {
		tick++;
	}

	/** Use key pressed: plucks the dead bird under the crosshair if the hand is empty. Returns true if it did. */
	public boolean onUse(Minecraft mc) {
		LocalPlayer player = mc.player;
		GoreConfig config = GoreConfig.get();
		if (!config.butchery || !config.goreLevel.enabled() || player == null || !(mc.level instanceof ClientLevel level)
				|| !player.getMainHandItem().isEmpty()) {
			return false;
		}

		Corpse corpse = CorpseStrike.aimed(mc, player);
		if (corpse == null || !pluckable(corpse.entity)) {
			return false;
		}

		if (tick - lastPluck < PLUCK_COOLDOWN) {
			// Holding the use key keeps plucking, a handful every few ticks.
			return true;
		}

		lastPluck = tick;
		player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		pluck(level, corpse, player.getEyePosition(), player.getViewVector(1.0F));
		return true;
	}

	/** One handful of feathers out of {@code corpse} where the ray from {@code eye} along {@code look} lands. */
	public boolean pluck(ClientLevel level, Corpse corpse, Vec3 eye, Vec3 look) {
		LivingEntity entity = corpse.entity;
		Vec3 at = GoreSystems.dismemberment().wounds().pluck(entity, eye, look, 6.0);
		if (at == null) {
			return false;
		}

		int id = entity.getId();
		boolean bare = plucks.get(id) >= PLUCKS_TO_BARE;
		plucks.put(id, Math.min(PLUCKS_TO_BARE * 3, plucks.get(id) + 1));
		corpse.keepFor(KEEP_TICKS);
		// The feathers come away in a tuft and drift down; a bare bird only gives up the odd stray one.
		ItemParticleOption feather = new ItemParticleOption(ParticleTypes.ITEM, Items.FEATHER);
		int n = bare ? 1 : 5 + RANDOM.nextInt(5);
		for (int i = 0; i < n; i++) {
			level.addParticle(feather, at.x, at.y, at.z, (RANDOM.nextDouble() - 0.5) * 0.12 - look.x * 0.05, 0.08 + RANDOM.nextDouble() * 0.1,
					(RANDOM.nextDouble() - 0.5) * 0.12 - look.z * 0.05);
		}

		level.playLocalSound(at.x, at.y, at.z, SoundEvents.WOOL_HIT, SoundSource.NEUTRAL, 0.5F, 1.3F + RANDOM.nextFloat() * 0.3F, false);
		return true;
	}

	/**
	 * A blade through the carcass where it is already cut to the bone. The backbone gives on the last of
	 * {@link #CHOPS_TO_SPLIT} such chops and the carcass splits; until then each one crunches into the bone.
	 */
	public void chopThrough(BloodWorld blood, ClientLevel level, Corpse corpse, ModelPart torso, Vec3 look) {
		int id = corpse.entity.getId();
		int n = chops.get(id) + 1;
		chops.put(id, n);
		corpse.keepFor(KEEP_TICKS);
		if (n >= CHOPS_TO_SPLIT) {
			split(blood, level, corpse, torso, look);
		} else {
			Vec3 at = corpse.position();
			level.playLocalSound(at.x, at.y, at.z, SoundEvents.BONE_BLOCK_HIT, SoundSource.NEUTRAL, 0.7F, 0.8F + RANDOM.nextFloat() * 0.2F, false);
		}
	}

	/**
	 * Splits the carcass of {@code corpse} where {@code torso} (its body part, cube {@code cube}) was cut through:
	 * whatever limbs and head are left come off, the body is gone, and two raw-faced halves fall where it lay.
	 */
	public void split(BloodWorld blood, ClientLevel level, Corpse corpse, ModelPart torso, Vec3 look) {
		LivingEntity entity = corpse.entity;
		Dismemberment dismemberment = GoreSystems.dismemberment();
		Ragdoll body = corpse.body;
		GorePart carcass = dismemberment.carcass(blood, level, entity, torso, () -> new Vec3(body.x(1.0F), body.y(1.0F), body.z(1.0F)), body.yaw(1.0F),
				body.roll(1.0F));
		if (carcass == null) {
			return;
		}

		Integer tint = tint(entity.getId());
		if (tint != null) {
			carcass.tint = tint;
		}

		dismemberment.parts().add(carcass);
		if (!cut(blood, level, carcass, look)) {
			carcass.vy += 0.15;
		}

		corpse.keepFor(KEEP_TICKS);
	}

	/**
	 * Cuts a loose piece in two across its longest side. Each half gets raw meat on its cut face, they fall apart, and
	 * the old piece is gone. Returns false if the piece cannot be cut (bone, more than one block of model, or too small).
	 */
	public boolean cut(BloodWorld blood, ClientLevel level, GorePart part, Vec3 look) {
		List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part.model).fluidgore$getCubes();
		if (part.bone() || part.shattered || cubes.size() != 1 || !((ModelPartAccessor) (Object) part.model).fluidgore$getChildren().isEmpty()) {
			return false;
		}

		ModelPart.Cube cube = cubes.getFirst();
		float[] min = {cube.minX, cube.minY, cube.minZ};
		float[] max = {cube.maxX, cube.maxY, cube.maxZ};
		int axis = 0;
		for (int i = 1; i < 3; i++) {
			if (max[i] - min[i] > max[axis] - min[axis]) {
				axis = i;
			}
		}

		float length = max[axis] - min[axis];
		if (length < MIN_CUT) {
			return false;
		}

		float at = min[axis] + length * (0.4F + 0.2F * RANDOM.nextFloat());
		float[] texture = textureOf(cube);
		Flesh flesh = part.wounds != null ? part.wounds.flesh() : Flesh.MEAT;
		Quaternionf rotation = new Quaternionf(part.rotation);
		for (int side = 0; side < 2; side++) {
			float[] lo = min.clone();
			float[] hi = max.clone();
			if (side == 0) {
				hi[axis] = at;
			} else {
				lo[axis] = at;
			}

			ModelPart.Cube half = new ModelPart.Cube(Math.round(texture[0]), Math.round(texture[1]), lo[0], lo[1], lo[2], hi[0] - lo[0], hi[1] - lo[1],
					hi[2] - lo[2], 0.0F, 0.0F, 0.0F, false, texture[2], texture[3], EnumSet.allOf(Direction.class));
			ModelPart model = new ModelPart(new ArrayList<>(List.of(half)), new HashMap<>());
			Vector3f centre = new Vector3f((lo[0] + hi[0]) / 32.0F, (lo[1] + hi[1]) / 32.0F, (lo[2] + hi[2]) / 32.0F);
			Vector3f extents = new Vector3f((hi[0] - lo[0]) / 32.0F, (hi[1] - lo[1]) / 32.0F, (hi[2] - lo[2]) / 32.0F).mul(part.scale);
			GorePart piece = new GorePart(model, part.texture, centre, extents, part.scale, part.bloodRgb, Math.max(part.lifetime - part.age, 400));
			piece.tint = part.tint;
			piece.light = part.light;
			piece.wounds = Wounds.cutFaces(model, half, flesh, part.bloodRgb, new int[][]{{axis, side == 0 ? 1 : -1}});
			// Where the half's centre is in the world: the old pivot, moved by the shift from the old centre to the new,
			// through the same flip, scale and rotation the renderer applies.
			Vector3f shift = new Vector3f(centre).sub(part.localCenter).mul(-part.scale, -part.scale, part.scale);
			rotation.transform(shift);
			piece.x = piece.prevX = part.x + shift.x;
			piece.y = piece.prevY = part.y + shift.y;
			piece.z = piece.prevZ = part.z + shift.z;
			piece.rotation.set(rotation);
			piece.prevRotation.set(rotation);
			Vector3f apart = new Vector3f(shift).normalize();
			if (!apart.isFinite()) {
				apart.set(RANDOM.nextFloat() - 0.5F, 0.0F, RANDOM.nextFloat() - 0.5F);
			}

			piece.vx = part.vx + apart.x * 0.08 + look.x * 0.05;
			piece.vy = Math.max(part.vy, 0.0) + 0.12 + RANDOM.nextDouble() * 0.06;
			piece.vz = part.vz + apart.z * 0.08 + look.z * 0.05;
			piece.spinAxis.set(RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F).normalize();
			if (!piece.spinAxis.isFinite()) {
				piece.spinAxis.set(1.0F, 0.0F, 0.0F);
			}

			piece.spinSpeed = 0.15F + RANDOM.nextFloat() * 0.2F;
			piece.bleedLeft = 0.04F * GoreConfig.get().goreLevel.puddles;
			GoreSystems.dismemberment().parts().add(piece);
		}

		part.shattered = true;
		Vec3 where = new Vec3(part.x, part.y, part.z);
		BloodEmitter.burst(blood, level, where, part.bloodRgb, 0.03F, 6);
		level.playLocalSound(where.x, where.y, where.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.7F, 1.1F, false);
		level.playLocalSound(where.x, where.y, where.z, SoundEvents.SLIME_SQUISH_SMALL, SoundSource.NEUTRAL, 0.6F, 0.8F + RANDOM.nextFloat() * 0.2F, false);
		return true;
	}

	/**
	 * Where a cube's skin sits on its texture and how big the texture is: {u, v, width, height} in texture pixels, read
	 * back from the cube's own corners (a box unwraps to 2 * (depth + width) by depth + height).
	 */
	private static float[] textureOf(ModelPart.Cube cube) {
		float minU = Float.MAX_VALUE;
		float minV = Float.MAX_VALUE;
		float maxU = -Float.MAX_VALUE;
		float maxV = -Float.MAX_VALUE;
		for (ModelPart.Polygon polygon : cube.polygons) {
			for (ModelPart.Vertex vertex : polygon.vertices()) {
				minU = Math.min(minU, vertex.u());
				maxU = Math.max(maxU, vertex.u());
				minV = Math.min(minV, vertex.v());
				maxV = Math.max(maxV, vertex.v());
			}
		}

		float w = cube.maxX - cube.minX;
		float h = cube.maxY - cube.minY;
		float d = cube.maxZ - cube.minZ;
		float texW = 2.0F * (d + w) / (maxU - minU);
		float texH = (d + h) / (maxV - minV);
		if (!Float.isFinite(texW) || !Float.isFinite(texH) || texW <= 0.0F || texH <= 0.0F) {
			return new float[]{0.0F, 0.0F, 64.0F, 32.0F};
		}

		return new float[]{minU * texW, minV * texH, texW, texH};
	}

	public void forget(int entityId) {
		plucks.remove(entityId);
		chops.remove(entityId);
	}

	public void clear() {
		plucks.clear();
		chops.clear();
	}
}
