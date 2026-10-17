package dev.fluidgore.dismember;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.net.GoreAction;
import dev.fluidgore.net.GoreSync;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.CorpseStrike;
import dev.fluidgore.suffer.Ragdoll;
import dev.fluidgore.trigger.Fate;
import dev.fluidgore.wound.BloodyWool;
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
 * Every pluck and cut keeps the body on the ground longer, so it is not gone before the work is done. With Fluid Gore on
 * the server too, every player sees the same work (see {@link GoreSync}); the pieces are named alike on every screen
 * ({@link GorePart#key}) so cuts on them can be shown as well.
 */
public final class Butchery {
	/** Plucks that leave a bird completely bare. */
	private static final int PLUCKS_TO_BARE = 12;
	/** Smallest side, in model pixels, that can still be cut in two. */
	private static final float MIN_CUT = 1.2F;
	/** Pieces at least this thick, in model pixels, still have bone in their cut faces. */
	private static final float BONE_IN = 5.0F;
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
		GoreSync.act(GoreAction.of(GoreAction.PLUCK, corpse.entity.getId(), 0L, player.getEyePosition(), player.getViewVector(1.0F), 0, 0.0F));
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
		// The feathers come away in a tuft and drift down; a bare bird only gives up the odd stray one. Those from round a
		// wound, or off a bird soaked in its own blood, come away wet with it.
		int n = bare ? 1 : 5 + RANDOM.nextInt(5);
		float soaked = GoreSystems.dismemberment().wool().soak(id);
		Vec3 wound = GoreSystems.dismemberment().wounds().deepest(id, corpse.position(), corpse.yaw());
		float bloody = Math.max(soaked, wound != null && wound.distanceToSqr(at) < 0.35 * 0.35 ? 0.8F : 0.0F);
		Vec3 away = new Vec3(-look.x, 0.4, -look.z).normalize();
		if (GoreConfig.get().feathers) {
			GoreSystems.dismemberment().feathers().tuft(entity, at, away, n, 0.06, bloody, dev.fluidgore.trigger.EntityFilter.bloodColor(entity));
		} else {
			ItemParticleOption feather = new ItemParticleOption(ParticleTypes.ITEM, Items.FEATHER);
			for (int i = 0; i < n; i++) {
				level.addParticle(feather, at.x, at.y, at.z, (RANDOM.nextDouble() - 0.5) * 0.12 - look.x * 0.05, 0.08 + RANDOM.nextDouble() * 0.1,
						(RANDOM.nextDouble() - 0.5) * 0.12 - look.z * 0.05);
			}
		}

		level.playLocalSound(at.x, at.y, at.z, SoundEvents.WOOL_HIT, SoundSource.NEUTRAL, 0.5F, 1.3F + RANDOM.nextFloat() * 0.3F, false);
		return true;
	}

	/**
	 * A blade through the carcass where it is already cut to the bone. The backbone gives on the last of
	 * {@link #CHOPS_TO_SPLIT} such chops and the carcass splits; until then each one crunches into the bone.
	 */
	public void chopThrough(BloodWorld blood, ClientLevel level, Corpse corpse, ModelPart torso, Vec3 look, boolean axe) {
		int id = corpse.entity.getId();
		int n = chops.get(id) + 1;
		chops.put(id, n);
		corpse.keepFor(KEEP_TICKS);
		// An axe goes through the backbone in one; a knife has to work at it.
		if (n >= (axe ? 1 : CHOPS_TO_SPLIT)) {
			split(blood, level, corpse, torso, look);
		} else {
			Vec3 at = corpse.position();
			level.playLocalSound(at.x, at.y, at.z, SoundEvents.BONE_BLOCK_HIT, SoundSource.NEUTRAL, 0.7F, 0.8F + RANDOM.nextFloat() * 0.2F, false);
			squelch(level, at, 0.6F);
		}
	}

	/** The wet, sucking sound of a blade going into and out of a carcass. */
	private static void squelch(ClientLevel level, Vec3 at, float volume) {
		if (!GoreConfig.get().goreSounds) {
			return;
		}

		level.playLocalSound(at.x, at.y, at.z, SoundEvents.HONEY_BLOCK_BREAK, SoundSource.NEUTRAL, volume, 0.6F + RANDOM.nextFloat() * 0.2F, false);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.SLIME_BLOCK_BREAK, SoundSource.NEUTRAL, volume * 0.8F, 0.5F + RANDOM.nextFloat() * 0.2F, false);
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

		// No wounds yet, but the pieces it is cut into must know what meat they are (pink for a chicken).
		List<ModelPart.Cube> trunk = ((ModelPartAccessor) (Object) carcass.model).fluidgore$getCubes();
		if (carcass.wounds == null && !trunk.isEmpty()) {
			carcass.wounds = Wounds.cutFaces(carcass.model, trunk.getFirst(), Flesh.of(entity), carcass.bloodRgb, 0, 1.0F);
		}

		dismemberment.parts().add(carcass);
		if (!cut(blood, level, carcass, look)) {
			carcass.vy += 0.15;
		}

		// Opened up: the guts slide out of the belly in a gush of blood that pools where it lay, with a wet, heavy
		// slop. The fleece or feathers come away in bloody clumps.
		Vec3 centre = new Vec3(body.x(1.0F), body.y(1.0F) + entity.getBbHeight() * 0.3, body.z(1.0F));
		if (GoreConfig.get().goreLevel.enabled() && Flesh.of(entity).bleeds()) {
			int rgb = dev.fluidgore.trigger.EntityFilter.bloodColor(entity);
			float size = entity.getBbWidth() * entity.getBbHeight();
			dismemberment.giblets().spill(level, blood, entity, centre.subtract(look.scale(0.5)), 0.6F);
			BloodEmitter.burst(blood, level, centre, rgb, 0.12F * size, Math.round(24 * size) + 8);
			BloodEmitter.drip(blood, new Vec3(centre.x, body.y(1.0F) + 0.1, centre.z), rgb, 0.4F * size);
			squelch(level, centre, 1.0F);
			BloodEmitter.burstSound(level, centre, 0.6F);
			if (BloodyWool.woolly(entity) || Feathers.feathered(entity)) {
				dismemberment.feathers().tuft(entity, centre, new Vec3(0.0, 1.0, 0.0), 10, 0.1,
						Math.max(0.6F, dismemberment.wool().soak(entity.getId())), rgb);
			}
		}

		corpse.keepFor(KEEP_TICKS);
	}

	/**
	 * A blade on a loose piece. A piece made of several blocks of model (a horse's head with its neck, muzzle and mane, a
	 * chicken's head with its beak) is hacked apart into those blocks first; a single block is cut in two across its
	 * longest side, raw meat on the new faces (and on any it was already cut on), until the pieces are too small to
	 * cut. Returns false if the piece cannot be cut (bone, or too small).
	 */
	public boolean cut(BloodWorld blood, ClientLevel level, GorePart part, Vec3 look) {
		if (part.bone() || part.shattered) {
			return false;
		}

		List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part.model).fluidgore$getCubes();
		if (cubes.size() != 1 || !((ModelPartAccessor) (Object) part.model).fluidgore$getChildren().isEmpty()) {
			return hackApart(blood, level, part, look);
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
		Quaternionf rotation = new Quaternionf(part.rotation);
		for (int side = 0; side < 2; side++) {
			float[] lo = min.clone();
			float[] hi = max.clone();
			if (side == 0) {
				hi[axis] = at;
			} else {
				lo[axis] = at;
			}

			// The new face, where the knife went through, is raw; so is every face the piece was already cut on.
			int raw = part.rawFaces | 1 << axis * 2 + (side == 0 ? 1 : 0);
			piece(part, cube, lo, hi, raw, rotation, look, 1.0F);
		}

		part.shattered = true;
		juice(blood, level, part, 6);
		return true;
	}

	/**
	 * Hacks a piece of several blocks apart: each block of it (children placed where they hang, their own tilt left
	 * out) becomes a loose piece of its own where it was. Returns false if there was nothing to take apart.
	 */
	private boolean hackApart(BloodWorld blood, ClientLevel level, GorePart part, Vec3 look) {
		List<float[]> blocks = new ArrayList<>();
		List<ModelPart.Cube> sources = new ArrayList<>();
		collect(part.model, 0.0F, 0.0F, 0.0F, blocks, sources);
		if (blocks.size() < 2) {
			return false;
		}

		Quaternionf rotation = new Quaternionf(part.rotation);
		for (int i = 0; i < blocks.size(); i++) {
			float[] b = blocks.get(i);
			// Where each block was joined to the rest is not known, so it comes apart with its skin on; the knife opens it.
			piece(part, sources.get(i), new float[]{b[0], b[1], b[2]}, new float[]{b[3], b[4], b[5]}, 0, rotation, look, 0.6F);
		}

		part.shattered = true;
		juice(blood, level, part, 10);
		level.playLocalSound(part.x, part.y, part.z, SoundEvents.BONE_BLOCK_BREAK, SoundSource.NEUTRAL, 0.7F, 0.9F + RANDOM.nextFloat() * 0.2F, false);
		return true;
	}

	/** Every cube under {@code model} as {minX, minY, minZ, maxX, maxY, maxZ} in the piece's own pixels, with its source cube. */
	private static void collect(ModelPart model, float ox, float oy, float oz, List<float[]> blocks, List<ModelPart.Cube> sources) {
		for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) model).fluidgore$getCubes()) {
			blocks.add(new float[]{cube.minX + ox, cube.minY + oy, cube.minZ + oz, cube.maxX + ox, cube.maxY + oy, cube.maxZ + oz});
			sources.add(cube);
		}

		for (ModelPart child : ((ModelPartAccessor) (Object) model).fluidgore$getChildren().values()) {
			collect(child, ox + child.x, oy + child.y, oz + child.z, blocks, sources);
		}
	}

	/**
	 * A new loose piece cut from {@code part}: the box {@code lo}..{@code hi} (the piece's own pixels) skinned like
	 * {@code skin}, raw on the faces in {@code raw}, placed where that box was on the old piece and falling away from its
	 * middle. {@code fling} scales how hard it moves apart. Added to the dismemberment's parts.
	 */
	private void piece(GorePart part, ModelPart.Cube skin, float[] lo, float[] hi, int raw, Quaternionf rotation, Vec3 look, float fling) {
		float[] texture = textureOf(skin);
		ModelPart.Cube block = new ModelPart.Cube(Math.round(texture[0]), Math.round(texture[1]), lo[0], lo[1], lo[2], hi[0] - lo[0], hi[1] - lo[1],
				hi[2] - lo[2], 0.0F, 0.0F, 0.0F, false, texture[2], texture[3], EnumSet.allOf(Direction.class));
		ModelPart model = new ModelPart(new ArrayList<>(List.of(block)), new HashMap<>());
		Vector3f centre = new Vector3f((lo[0] + hi[0]) / 32.0F, (lo[1] + hi[1]) / 32.0F, (lo[2] + hi[2]) / 32.0F);
		Vector3f extents = new Vector3f((hi[0] - lo[0]) / 32.0F, (hi[1] - lo[1]) / 32.0F, (hi[2] - lo[2]) / 32.0F).mul(part.scale);
		GorePart piece = new GorePart(model, part.texture, centre, extents, part.scale, part.bloodRgb, Math.max(part.lifetime - part.age, 400));
		Flesh flesh = part.wounds != null ? part.wounds.flesh() : Flesh.MEAT;
		piece.tint = part.tint;
		piece.rot = part.rot;
		piece.arid = part.arid;
		piece.light = part.light;
		piece.rawFaces = raw;
		// Diced meat is meat through and through; only a big piece still has bone in its cut faces. Kept even with no
		// raw face yet, so the piece remembers what it is made of.
		float smallest = Math.min(hi[0] - lo[0], Math.min(hi[1] - lo[1], hi[2] - lo[2]));
		piece.wounds = Wounds.cutFaces(model, block, flesh, part.bloodRgb, raw, smallest >= BONE_IN ? 1.0F : 0.7F);
		// Where the piece's centre is in the world: the old pivot, moved by the shift from the old centre to the new,
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

		// Diced on the spot, as on a block: the pieces fall apart and stay about where they were cut.
		piece.vx = part.vx + (apart.x * 0.04 + look.x * 0.02) * fling;
		piece.vy = Math.max(part.vy, 0.0) + (0.05 + RANDOM.nextDouble() * 0.04) * fling;
		piece.vz = part.vz + (apart.z * 0.04 + look.z * 0.02) * fling;
		piece.spinAxis.set(RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F).normalize();
		if (!piece.spinAxis.isFinite()) {
			piece.spinAxis.set(1.0F, 0.0F, 0.0F);
		}

		piece.spinSpeed = (0.08F + RANDOM.nextFloat() * 0.12F) * fling;
		piece.bleedLeft = 0.04F * GoreConfig.get().goreLevel.puddles;
		GoreSystems.dismemberment().parts().add(piece);
	}

	/** The wet of a cut: blood, scraps of raw meat flicked off the knife, and the squelch of it. */
	private void juice(BloodWorld blood, ClientLevel level, GorePart part, int scraps) {
		Vec3 where = new Vec3(part.x, part.y, part.z);
		BloodEmitter.burst(blood, level, where, part.bloodRgb, 0.03F, 6);
		Flesh flesh = part.wounds != null ? part.wounds.flesh() : Flesh.MEAT;
		ItemParticleOption meat = new ItemParticleOption(ParticleTypes.ITEM, switch (flesh) {
			case POULTRY -> Items.CHICKEN;
			case PORK -> Items.PORKCHOP;
			case ENDER -> Items.ENDER_PEARL;
			case ROTTEN -> Items.ROTTEN_FLESH;
			default -> Items.BEEF;
		});
		for (int i = 0; i < scraps; i++) {
			level.addParticle(meat, where.x, where.y, where.z, (RANDOM.nextDouble() - 0.5) * 0.15, 0.05 + RANDOM.nextDouble() * 0.12,
					(RANDOM.nextDouble() - 0.5) * 0.15);
		}

		level.playLocalSound(where.x, where.y, where.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.6F, 1.2F, false);
		level.playLocalSound(where.x, where.y, where.z, SoundEvents.SLIME_SQUISH_SMALL, SoundSource.NEUTRAL, 0.8F, 0.7F + RANDOM.nextFloat() * 0.3F, false);
		level.playLocalSound(where.x, where.y, where.z, SoundEvents.HONEY_BLOCK_BREAK, SoundSource.NEUTRAL, 0.5F, 1.1F + RANDOM.nextFloat() * 0.3F, false);
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

	/** Handfuls plucked and chops through the backbone so far, for evidence (see {@code net/Evidence}). */
	public int plucks(int entityId) {
		return plucks.get(entityId);
	}

	public int chops(int entityId) {
		return chops.get(entityId);
	}

	/** Restores what was plucked and chopped on a body this player was not there for. */
	public void restore(int entityId, int plucked, int chopped) {
		if (plucked > 0) {
			plucks.put(entityId, plucked);
		}

		if (chopped > 0) {
			chops.put(entityId, chopped);
		}
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
