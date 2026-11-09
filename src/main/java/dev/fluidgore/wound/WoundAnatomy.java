package dev.fluidgore.wound;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fluidgore.GoreSystems;
import dev.fluidgore.anatomy.Anatomy;
import dev.fluidgore.anatomy.Anatomy.Kind;
import dev.fluidgore.anatomy.Anatomy.Piece;
import dev.fluidgore.anatomy.Anatomy.Region;
import dev.fluidgore.anatomy.MeltRenderer;
import dev.fluidgore.anatomy.Probe;
import dev.fluidgore.blood.BloodEmitter;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.Tissue;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.ragdoll.Bends;
import dev.fluidgore.trigger.EntityFilter;
import dev.fluidgore.trigger.Fate;
import java.util.List;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.animal.sheep.SheepFurModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A wound as a window into the body under it. Where a body carries an anatomy table (a sheep, its trunk, head and legs;
 * the other beasts as far as their tables go), what shows in a wound is not a stock picture of meat and bone but what
 * the table has there, cell by cell, at the depth the wound has reached: the fat under the skin where there is fat, the
 * named muscle, a rib over the chest, the rumen's wall behind the flank's thin layers, the loin eye and the kidney's suet
 * over the loin. The deeper the wound, the deeper the floor it shows (see {@link Probe}).
 *
 * <p>What a blow reaches answers, in sight and sound only: a cut rumen leaks its cud, a pierced lung froths the blood,
 * a cut great vessel spurts. Which organ each wound has reached is kept with it ({@link WoundSet#reached}), so a body
 * that died of it dies as it would (see {@code Suffering}), and a player who comes along later finds the same wounds.
 */
public final class WoundAnatomy {
	/** The severity at which a wound's floor shows the body under the skin: the skin has split. */
	static final float OPENS = 0.2F;
	/** The deepest a wound goes, in a cube's pixels, and never past a third of the cube's thickness. */
	private static final float DEEPEST = 3.0F;
	/** The cells the floor is drawn in: half pixels, the grain the tables are cut at. */
	private static final float CELL = 0.5F;
	/** The anatomy atlas: eight 16-texel tiles a row, 128 texels square. */
	private static final float TILE = 1.0F / 8.0F;
	private static final float TEXEL = TILE / 16.0F;
	/** The floor darkens with depth: the bottom of a deep wound is in shadow. */
	private static final int DEEP_SHADE = 0xA6A6A6;
	/** How far the fresh blood in a wound tints what shows in it. */
	private static final float WET = 0.18F;
	private static final RandomSource RANDOM = Fate.stream("wound_anatomy");

	/**
	 * A wound's place in the body's anatomy.
	 *
	 * @param table         the table under this cube
	 * @param fit           how the table's canonical pixels map onto the cube (see {@link Anatomy#fit})
	 * @param canonicalSign which way is out, along the wound's axis, in canonical pixels
	 * @param deepest       the deepest this wound goes, in the cube's pixels
	 */
	record Context(Piece[] table, float[] fit, int canonicalSign, float deepest) {
	}

	private WoundAnatomy() {
	}

	/** Gives {@code set} the anatomy of {@code entity}, drawn with {@code model}: which animal it is, its sex and its age. */
	public static void bind(WoundSet set, LivingEntity entity, Model<?> model) {
		set.beast = model instanceof QuadrupedModel<?> && !(model instanceof SheepFurModel) ? Anatomy.Beast.of(entity.getType()) : null;
		set.male = Anatomy.male(entity.getUUID());
		set.young = entity.isBaby();
	}

	/** Where wound {@code i} of {@code set} lies in the body's anatomy, or null where there is no table under it. */
	static @Nullable Context of(WoundSet set, int i) {
		Anatomy.Beast beast = set.beast;
		if (beast == null) {
			return null;
		}

		ModelPart part = set.part[i];
		List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part).fluidgore$getCubes();
		if (cubes.isEmpty() || MeltRenderer.core(cubes) != set.cube[i]) {
			return null;
		}

		BodyPart bodyPart = set.bodyPart[i];
		Region region = bodyPart == null ? Region.BEAST_TRUNK : switch (bodyPart) {
			case HEAD -> Region.BEAST_HEAD;
			case RIGHT_ARM, LEFT_ARM -> Region.FORELEG;
			case RIGHT_LEG, LEFT_LEG -> Region.HINDLEG;
			default -> null;
		};
		if (region == null) {
			return null;
		}

		boolean left = bodyPart == BodyPart.LEFT_ARM || bodyPart == BodyPart.LEFT_LEG;
		boolean turned = region == Region.BEAST_TRUNK && MeltRenderer.turned(part);
		ModelPart.Cube cube = set.cube[i];
		float[] fit = Anatomy.fit(region, left, turned, new float[] {cube.minX, cube.minY, cube.minZ}, new float[] {cube.maxX, cube.maxY, cube.maxZ});
		Piece[] table = Anatomy.pieces(region, left, beast, turned, Anatomy.Detail.MIXED, set.male, set.young);
		int axis = set.axis[i];
		float thickness = axis == 0 ? cube.maxX - cube.minX : axis == 1 ? cube.maxY - cube.minY : cube.maxZ - cube.minZ;
		return new Context(table, fit, Probe.canonicalSign(fit, axis, set.sign[i]), Math.min(DEEPEST, thickness / 3.0F));
	}

	/** How deep wound {@code i} has gone, in the cube's pixels: from the split skin down to {@link Context#deepest}. */
	static float depthPixels(WoundSet set, int i, Context ctx) {
		float open = Mth.clamp((set.severity[i] - OPENS) / (1.0F - OPENS), 0.0F, 1.0F);
		return Math.max(0.05F, open * ctx.deepest());
	}

	/** {@link #depthPixels} in the table's canonical pixels along the wound's axis. */
	static float depth(WoundSet set, int i, Context ctx) {
		return depthPixels(set, i, ctx) / Math.abs(ctx.fit()[set.axis[i]]);
	}

	/** The surface point of wound {@code i} (its centre on the cube's face), in canonical pixels. */
	private static float[] surface(WoundSet set, int i, Context ctx) {
		ModelPart.Cube cube = set.cube[i];
		int a = set.axis[i];
		int b = (a + 1) % 3;
		int c = (a + 2) % 3;
		float[] p = new float[3];
		p[a] = set.sign[i] > 0 ? max(cube, a) : min(cube, a);
		p[b] = set.u[i];
		p[c] = set.v[i];
		return Probe.canonical(ctx.fit(), p[0], p[1], p[2]);
	}

	/** Whether the table has anything at the bottom of wound {@code i}: if not, the stock layers are drawn instead. */
	static boolean shows(WoundSet set, int i, Context ctx) {
		return Probe.under(ctx.table(), surface(set, i, ctx), set.axis[i], ctx.canonicalSign(), depth(set, i, ctx)) != null;
	}

	/**
	 * The organ of note that wound {@code i} reaches at its depth now (the deepest one on the way in), or null for a
	 * wound still in the body wall.
	 */
	static @Nullable Kind reach(WoundSet set, int i) {
		Context ctx = of(set, i);
		if (ctx == null || set.severity[i] <= OPENS) {
			return null;
		}

		Kind reached = null;
		for (Probe.Layer layer : Probe.layers(ctx.table(), surface(set, i, ctx), set.axis[i], ctx.canonicalSign(), depth(set, i, ctx))) {
			if (ofNote(layer.kind())) {
				reached = layer.kind();
			}
		}

		return reached;
	}

	/** An organ a blow into it answers for: not the body wall, bone, fat or a gland. */
	private static boolean ofNote(Kind kind) {
		return switch (kind) {
			case LUNG, HEART, AORTA, VEIN, TRACHEA, RUMEN, RETICULUM, STOMACH, INTESTINE, COLON, BLADDER, LIVER, GALLBLADDER, KIDNEY, SPLEEN, BRAIN -> true;
			default -> false;
		};
	}

	/**
	 * A blow has just reached {@code organ} through wound {@code i} of {@code victim} for the first time: what the organ
	 * lets out. A cut rumen or reticulum spills cud; a cut gut, gut; a pierced lung froths the blood pale with air; a
	 * cut great vessel or heart spurts; a pierced bladder wets the wound.
	 */
	static void answer(LivingEntity victim, WoundSet set, int i, Vec3 at, Vec3 normal, Kind organ) {
		if (!(victim.level() instanceof ClientLevel level)) {
			return;
		}

		int rgb = EntityFilter.bloodType(victim).rgb;
		switch (organ) {
			case RUMEN, RETICULUM, STOMACH -> {
				GoreSystems.dismemberment().giblets().leak(level, victim, at, normal, Tissue.CUD, 3 + RANDOM.nextInt(4));
				level.playLocalSound(at.x, at.y, at.z, SoundEvents.SLIME_BLOCK_BREAK, SoundSource.NEUTRAL, 0.5F, 0.6F + RANDOM.nextFloat() * 0.2F, false);
			}
			case INTESTINE, COLON -> {
				GoreSystems.dismemberment().giblets().leak(level, victim, at, normal, Tissue.GUT, 1 + RANDOM.nextInt(2));
				level.playLocalSound(at.x, at.y, at.z, SoundEvents.SLIME_BLOCK_BREAK, SoundSource.NEUTRAL, 0.35F, 0.7F + RANDOM.nextFloat() * 0.2F, false);
			}
			case LUNG -> {
				// Air in the blood: a pale pink froth at the wound.
				BloodEmitter.burst(GoreSystems.blood(), level, at, lerp(rgb, 0xFFD0D0, 0.45F), 0.03F, 10);
				level.playLocalSound(at.x, at.y, at.z, SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.NEUTRAL, 0.6F, 0.8F + RANDOM.nextFloat() * 0.2F, false);
			}
			case HEART, AORTA, VEIN -> {
				int index = i;
				GoreSystems.blood().spurts().start(() -> woundAt(victim, set, index), normal, rgb, 60 + RANDOM.nextInt(40), 1.1F);
			}
			case BLADDER -> BloodEmitter.burst(GoreSystems.blood(), level, at, 0xE8D27A, 0.02F, 6);
			default -> {
			}
		}
	}

	/** Where wound {@code i} of {@code set} is in the world now, from the spot it was struck at and how the body has turned since. */
	private static Vec3 woundAt(LivingEntity entity, WoundSet set, int i) {
		if (Float.isNaN(set.ox[i])) {
			return entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
		}

		return entity.position().add(new Vec3(set.ox[i], set.oy[i], set.oz[i]).yRot((set.yaw[i] - entity.yBodyRot) * Mth.DEG_TO_RAD));
	}

	/**
	 * Draws the floor of wound {@code i}: the tissue the table has under each half-pixel cell of its footprint, at the
	 * wound's depth, {@code lift} pixels proud of the face. {@code r} is the footprint's radius; a cut is a thin stroke
	 * along its angle, a round wound a disc.
	 */
	static void floor(WoundSet set, int i, Context ctx, float r, float lift, PoseStack.Pose pose, VertexConsumer anatomy, int light, int overlay) {
		ModelPart.Cube cube = set.cube[i];
		int a = set.axis[i];
		int b = (a + 1) % 3;
		int c = (a + 2) % 3;
		float face = set.sign[i] > 0 ? max(cube, a) : min(cube, a);
		float depth = depth(set, i, ctx);
		float deep = depthPixels(set, i, ctx) / Math.max(ctx.deepest(), 1.0E-3F);
		boolean cut = set.morph[i] == WoundSet.SLASH || set.morph[i] == WoundSet.INCISED || set.morph[i] == WoundSet.STICK;
		float angle = cut ? (set.variant[i] & 3) * (Mth.PI / 4.0F) : 0.0F;
		float cos = Mth.cos(angle);
		float sin = Mth.sin(angle);
		float halfLong = r;
		float halfWide = cut ? Math.max(0.5F, r * 0.3F) : r;
		float u = set.u[i];
		float v = set.v[i];
		float b0 = Math.max(min(cube, b), floorTo(u - r));
		float b1 = Math.min(max(cube, b), ceilTo(u + r));
		float c0 = Math.max(min(cube, c), floorTo(v - r));
		float c1 = Math.min(max(cube, c), ceilTo(v + r));
		int shade = lerp(0xFFFFFF, DEEP_SHADE, deep);
		int wet = lerp(0xFFFFFF, set.blood, WET * (1.0F - 0.5F * deep));
		int color = 0xFF000000 | multiply(shade, wet);
		float[] p = new float[3];
		for (float pb = b0; pb < b1 - 1.0E-4F; pb += CELL) {
			for (float pc = c0; pc < c1 - 1.0E-4F; pc += CELL) {
				float db = pb + CELL * 0.5F - u;
				float dc = pc + CELL * 0.5F - v;
				float along = db * cos + dc * sin;
				float across = -db * sin + dc * cos;
				boolean in = cut ? Math.abs(along) <= halfLong && Math.abs(across) <= halfWide : db * db + dc * dc <= r * r;
				if (!in) {
					continue;
				}

				p[a] = face;
				p[b] = pb + CELL * 0.5F;
				p[c] = pc + CELL * 0.5F;
				float[] at = Probe.canonical(ctx.fit(), p[0], p[1], p[2]);
				Piece piece = null;
				// Nothing that deep under this cell (the edge of the body, a cavity): the nearest tissue above it shows.
				for (float d = depth; piece == null && d >= 0.02F; d *= 0.5F) {
					piece = Probe.under(ctx.table(), at, a, ctx.canonicalSign(), d);
				}

				if (piece == null) {
					continue;
				}

				int tile = piece.kind().tile;
				float u0 = (tile % 8) * TILE + Mth.positiveModulo(pb, 16.0F) * TEXEL;
				float v0 = (tile / 8) * TILE + Mth.positiveModulo(pc, 16.0F) * TEXEL;
				float cb0 = Math.max(min(cube, b), pb);
				float cb1 = Math.min(max(cube, b), pb + CELL);
				float cc0 = Math.max(min(cube, c), pc);
				float cc1 = Math.min(max(cube, c), pc + CELL);
				if (cb0 >= cb1 || cc0 >= cc1) {
					continue;
				}

				Bends.quad(anatomy, pose, set.part[i], a, b, c, face + set.sign[i] * lift, set.sign[i], cb0, cb1, cc0, cc1, u0, u0 + CELL * TEXEL, v0,
						v0 + CELL * TEXEL, color, light, overlay);
			}
		}
	}

	private static float floorTo(float x) {
		return (float) Math.floor(x / CELL) * CELL;
	}

	private static float ceilTo(float x) {
		return (float) Math.ceil(x / CELL) * CELL;
	}

	private static float min(ModelPart.Cube cube, int axis) {
		return axis == 0 ? cube.minX : axis == 1 ? cube.minY : cube.minZ;
	}

	private static float max(ModelPart.Cube cube, int axis) {
		return axis == 0 ? cube.maxX : axis == 1 ? cube.maxY : cube.maxZ;
	}

	private static int lerp(int from, int to, float t) {
		int r = Math.round(Mth.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF));
		int g = Math.round(Mth.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF));
		int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
		return r << 16 | g << 8 | b;
	}

	private static int multiply(int x, int y) {
		int r = (x >> 16 & 0xFF) * (y >> 16 & 0xFF) / 255;
		int g = (x >> 8 & 0xFF) * (y >> 8 & 0xFF) / 255;
		int b = (x & 0xFF) * (y & 0xFF) / 255;
		return r << 16 | g << 8 | b;
	}
}
