package dev.fluidgore.dismember;

import dev.fluidgore.anatomy.Anatomy;
import dev.fluidgore.anatomy.MeltRenderer;
import dev.fluidgore.blood.BloodTextures;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.BrokenBlocks;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.event.Events;
import dev.fluidgore.material.Condition;
import dev.fluidgore.material.Exposure;
import dev.fluidgore.material.HeatEffects;
import dev.fluidgore.material.Material;
import dev.fluidgore.physics.Buoyancy;
import dev.fluidgore.material.Thermal;
import dev.fluidgore.physics.Blast;
import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.trigger.Fate;
import dev.fluidgore.wound.Flesh;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Small pieces of a body: the confetti a blast makes of an animal, and the bits a pick or an axe knocks out of a
 * wound. Each piece is a tiny box of one tissue (muscle, fat, bone, hide, gut, cud, organ) a few model pixels across,
 * thrown out, tumbling, bouncing by how firm it is, and leaving a smear where wet ones land. Hide pieces wear a few
 * texels of the animal's own skin. Flat arrays with a fixed capacity; resting pieces stop simulating.
 */
public final class Giblets {
	public static final int CAPACITY = 4000;
	private static final double GRAVITY = 0.06;
	/** Vanilla's hold of water on a mob, per tick. */
	private static final double WATER_DRAG = 0.8;
	private static final double DRAG = 0.985;
	/** Ticks between reaction steps of a piece that is hot or in heat, and of one that is cold. */
	private static final int REACT_HOT = 2;
	private static final int REACT_COLD = 10;
	/** Ticks a flame put to a piece (a flint and steel) goes on burning on it: enough to dry and catch a thin scrap. */
	private static final int KINDLE = 160;
	private static final int GREASE = 0x7A5A1E;
	private static final BlockParticleOption CHAR = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COAL_BLOCK.defaultBlockState());
	private static final RandomSource RANDOM = Fate.stream("giblets");
	/** Smoke, flames and hisses: this screen's own (they do not change what anything is). */
	private static final RandomSource FX = RandomSource.create();

	public final double[] x = new double[CAPACITY];
	public final double[] y = new double[CAPACITY];
	public final double[] z = new double[CAPACITY];
	public final double[] px = new double[CAPACITY];
	public final double[] py = new double[CAPACITY];
	public final double[] pz = new double[CAPACITY];
	private final double[] vx = new double[CAPACITY];
	private final double[] vy = new double[CAPACITY];
	private final double[] vz = new double[CAPACITY];
	/** Half sizes, in blocks. */
	public final float[] hx = new float[CAPACITY];
	public final float[] hy = new float[CAPACITY];
	public final float[] hz = new float[CAPACITY];
	/** Tumble: angles (radians) now and last tick, and spin per tick. */
	public final float[] rx = new float[CAPACITY];
	public final float[] ry = new float[CAPACITY];
	public final float[] prx = new float[CAPACITY];
	public final float[] pry = new float[CAPACITY];
	private final float[] sx = new float[CAPACITY];
	private final float[] sy = new float[CAPACITY];
	public final int[] color = new int[CAPACITY];
	public final int[] light = new int[CAPACITY];
	/** Texture window (u, v, width, height); texture null = the shared flesh texture. */
	public final float[] u = new float[CAPACITY];
	public final float[] v = new float[CAPACITY];
	public final float[] du = new float[CAPACITY];
	public final float[] dv = new float[CAPACITY];
	public final @Nullable Identifier[] texture = new Identifier[CAPACITY];
	/**
	 * The cubes a torn-out organ is drawn with (see {@link Anatomy.Shape}), shared by every piece of that organ; null for
	 * a plain box. It moves, lands and burns as its box all the same.
	 */
	public final Anatomy.@Nullable Shape[] shape = new Anatomy.Shape[CAPACITY];
	/**
	 * What each piece is, for whoever finds it (see {@link #describe}): the anatomical name of a real organ or bone
	 * ({@code Anatomy.Piece#name}), or null for a scrap of plain tissue; its side; and the kind of body it came out of.
	 */
	private final @Nullable String[] organ = new String[CAPACITY];
	private final String[] side = new String[CAPACITY];
	private final @Nullable EntityType<?>[] owner = new EntityType<?>[CAPACITY];
	private final Tissue[] tissue = new Tissue[CAPACITY];
	/** What each piece is made of and what has happened to it (see {@link Thermal}); its colour before heat touched it. */
	private final Material[] material = new Material[CAPACITY];
	private final Condition[] condition = new Condition[CAPACITY];
	private final int[] base = new int[CAPACITY];
	/** Ticks a flame a player holds to each piece still burns on it (see {@link Handling#FLAME}). */
	private final int[] kindle = new int[CAPACITY];
	/**
	 * The event that made each piece and its source (see {@link Events}; 0 for none known), and the piece's own name in
	 * the trace: what its later collisions and changes are put down to.
	 */
	private final long[] event = new long[CAPACITY];
	private final Events.Source[] source = new Events.Source[CAPACITY];
	private final long[] key = new long[CAPACITY];
	/**
	 * Pieces made so far by each event from each body: numbers their keys, so that one body making more pieces (a chip off
	 * a wound elsewhere) does not renumber another's.
	 */
	private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap serials = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
	/** The body the pieces now being made come from (its UUID bits), 0 if none. */
	private long keyBody;
	private final int[] blood = new int[CAPACITY];
	private final boolean[] resting = new boolean[CAPACITY];
	/** Ticks a wet piece stays stuck to the wall it slapped into before it peels off and drops; 0 if not stuck. */
	private final int[] stuck = new int[CAPACITY];
	public final int[] age = new int[CAPACITY];
	public final int[] life = new int[CAPACITY];
	private int count;
	/** Landing sounds left this tick: a rain of meat is a patter, not a wall of noise. */
	private int sounds;
	/** Hisses, sizzles and crackles left this tick. */
	private int hisses;
	private int ticks;
	/** What the body a piece comes out of has been through (see {@code Dismemberment#bodyCondition}). */
	private BiFunction<LivingEntity, Material, Condition> bodies = (entity, m) -> new Condition();

	{
		for (int i = 0; i < CAPACITY; i++) {
			condition[i] = new Condition();
		}
	}

	public int count() {
		return count;
	}

	/** Where pieces learn what their body had been through before they came out of it. */
	public void bodies(BiFunction<LivingEntity, Material, Condition> bodies) {
		this.bodies = bodies;
	}

	/** What piece {@code i} is made of. */
	/** Where scrap {@code i} is now. */
	public Vec3 position(int i) {
		return new Vec3(x[i], y[i], z[i]);
	}

	public Material material(int i) {
		return material[i];
	}

	/** What has happened to piece {@code i}: live, so a test or a demo may set it too. */
	public Condition condition(int i) {
		return condition[i];
	}

	/** The event that made piece {@code i} (0 for none known). */
	public long event(int i) {
		return event[i];
	}

	/** Piece {@code i}'s name in the event trace. */
	public long key(int i) {
		return key[i];
	}

	/** A body's UUID folded to one number, for naming its pieces. */
	private static long bodyBits(LivingEntity body) {
		return body.getUUID().getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ body.getUUID().getLeastSignificantBits();
	}

	/** Records that piece {@code i} came out of {@code body} already burnt (the body's own fire, an earlier one). */
	private void traceInherited(int i, LivingEntity body) {
		if (condition[i].charred > 0.0F) {
			Events.record(Events.id(Events.Source.ENVIRONMENT, body.position(), 0.0), Events.Source.ENVIRONMENT, Events.Channel.THERMAL, -1, Events.Effect.MATERIAL,
					Events.piece(key[i]), String.format(java.util.Locale.ROOT, "burns of %s, charred %.2f", Events.body(body), condition[i].charred));
		}
	}

	/** True if piece {@code i} lies still. */
	public boolean resting(int i) {
		return resting[i];
	}

	/** Puts piece {@code i} at {@code at} and lets it fall from there at {@code vel}. */
	public void place(int i, Vec3 at, Vec3 vel) {
		x[i] = px[i] = at.x;
		y[i] = py[i] = at.y;
		z[i] = pz[i] = at.z;
		vx[i] = vel.x;
		vy[i] = vel.y;
		vz[i] = vel.z;
		resting[i] = false;
		stuck[i] = 0;
	}

	/** How much of its full size piece {@code i} still has; pieces shrink away at the end of their life. */
	public float scale(int i) {
		int left = life[i] - age[i];
		return left >= 40 ? 1.0F : Math.max(0.0F, left / 40.0F);
	}

	/**
	 * A body torn apart by a blast at {@code from} (or from inside, if null): pieces of every tissue in their real
	 * proportions, thrown away from the blast. Returns the number of pieces made.
	 */
	public int burst(ClientLevel level, BloodWorld world, LivingEntity entity, @Nullable Vec3 from) {
		return pieces(level, world, entity, from, 1.0F, false, false);
	}

	/**
	 * The flesh of a body whose bones and organs were thrown as themselves (see {@code Viscera}): scraps of muscle, fat
	 * and hide, and a cud-chewer's cud, {@code share} of a whole body's worth.
	 */
	public int burstFlesh(ClientLevel level, BloodWorld world, LivingEntity entity, @Nullable Vec3 from, float share) {
		return pieces(level, world, entity, from, share, false, true);
	}

	/**
	 * A body ripped open by a blast that did not shred it: the gut, the organs, the rumen's half-digested grass and
	 * some muscle and fat spill out of the belly and are thrown off. {@code force} 0..1 is how hard the blast hit.
	 */
	public int spill(ClientLevel level, BloodWorld world, LivingEntity entity, Vec3 from, float force) {
		return pieces(level, world, entity, from, 0.2F + 0.25F * force, true, false);
	}

	private int pieces(ClientLevel level, BloodWorld world, LivingEntity entity, @Nullable Vec3 from, float share, boolean innards, boolean fleshOnly) {
		GoreConfig config = GoreConfig.get();
		GoreLevel preset = config.goreLevel;
		Flesh flesh = Flesh.of(entity);
		int rgb = dev.fluidgore.trigger.EntityFilter.bloodType(entity).rgb;
		float width = entity.getBbWidth();
		float height = entity.getBbHeight();
		// A cow makes about 160 pieces at Medium; smaller bodies fewer.
		float body = width * width * height / (0.9F * 0.9F * 1.4F);
		int n = Math.round(Mth.clamp(160.0F * body, 20.0F, 500.0F) * preset.particles * share);
		n = Math.min(n, config.maxGiblets);
		makeRoom(n, config.maxGiblets);

		List<float[]> skin = skinWindows(entity);
		Identifier skinTexture = skin.isEmpty() ? null : skinTexture(entity);
		Vec3 centre = entity.position().add(0.0, height * 0.5, 0.0);
		Vec3 origin = from != null ? from : centre.subtract(0.0, height * 0.3, 0.0);
		int lightAt = LightCoordsUtil.getLightCoords(level, entity.blockPosition());
		Condition fleshWas = bodies.apply(entity, Material.MUSCLE);
		Condition boneWas = bodies.apply(entity, Material.BONE);
		keyBody = bodyBits(entity);
		int made = 0;
		for (int k = 0; k < n && count < config.maxGiblets; k++) {
			Tissue t = switch (flesh) {
				case BONE -> Tissue.BONE;
				case DRIED -> RANDOM.nextFloat() < 0.3F ? Tissue.BONE : RANDOM.nextFloat() < 0.4F ? Tissue.HIDE : Tissue.MUSCLE;
				case MEAT, POULTRY, PORK, BEEF, MUTTON, ENDER, ROTTEN -> fleshOnly ? Tissue.pickFlesh(RANDOM.nextFloat())
						: innards ? Tissue.pickInnards(RANDOM.nextFloat()) : Tissue.pick(RANDOM.nextFloat());
			};
			if (t == Tissue.HIDE && skinTexture == null) {
				t = Tissue.MUSCLE;
			}

			if (t == Tissue.CUD && !Tissue.chewsCud(entity.getType())) {
				// Only a cud-chewer's belly is full of fermenting grass; anything else spills gut.
				t = Tissue.GUT;
			}

			// Innards come out of the belly; a shredded body comes from all of it.
			double spread = innards ? 0.35 : 1.0;
			double ox = (RANDOM.nextDouble() - 0.5) * width * spread;
			double oy = innards ? height * (0.3 + RANDOM.nextDouble() * 0.25) : RANDOM.nextDouble() * height;
			double oz = (RANDOM.nextDouble() - 0.5) * width * spread;
			Vec3 at = entity.position().add(ox, oy, oz);
			Vec3 out = at.subtract(origin);
			double dist = out.length();
			out = dist < 1.0E-3 ? new Vec3(RANDOM.nextGaussian(), 1.0, RANDOM.nextGaussian()).normalize() : out.scale(1.0 / dist);
			// Lighter, smaller pieces fly further; the nearer the blast, the harder.
			double speed = (0.35 + RANDOM.nextDouble() * 0.55) * (t == Tissue.BONE ? 0.8 : 1.0) * Mth.clamp(2.5 / (dist + 1.0), 0.6, 1.6)
					* (innards ? 0.5 : 1.0);
			Vec3 vel = out.scale(speed).add(RANDOM.nextGaussian() * 0.12, 0.15 + RANDOM.nextDouble() * 0.25, RANDOM.nextGaussian() * 0.12);
			int i = spawn(at, vel, t, flesh, rgb, lightAt, Math.round((config.partLifetimeSeconds * 10 + RANDOM.nextInt(200)) * preset.duration));
			owner[i] = entity.getType();
			// The side that took the fireball is seared black.
			// How hard the blast's cloud (a fireball, or cold gas) takes this piece: one draw of the dice either way, so what else
			// the blast does comes out the same whatever its source.
			float roll = from == null ? 0.0F : RANDOM.nextFloat();
			if (t == Tissue.HIDE) {
				float[] w = skin.get(RANDOM.nextInt(skin.size()));
				texture[i] = skinTexture;
				u[i] = w[0];
				v[i] = w[1];
				du[i] = w[2];
				dv[i] = w[3];
				base[i] = 0xFFFFFF;
			}

			condition[i].inherit(t == Tissue.BONE ? boneWas : fleshWas);
			traceInherited(i, entity);
			if (from != null) {
				cloud(i, roll);
			}

			recolor(i);
			made++;
		}

		keyBody = 0L;
		return made;
	}

	/**
	 * What a wound lets out of the organ behind it (see {@code WoundAnatomy}): {@code n} bits of {@code tissue} (cud from
	 * a cut rumen, gut from a cut bowel) that come out of the wound at {@code at} with a slow push along {@code out}, the
	 * wound's normal, and fall. Returns how many were made.
	 */
	public int leak(ClientLevel level, LivingEntity entity, Vec3 at, Vec3 out, Tissue tissue, int n) {
		GoreConfig config = GoreConfig.get();
		if (!config.goreLevel.enabled() || n <= 0) {
			return 0;
		}

		makeRoom(n, config.maxGiblets);
		Flesh flesh = Flesh.of(entity);
		int rgb = dev.fluidgore.trigger.EntityFilter.bloodType(entity).rgb;
		int lightAt = LightCoordsUtil.getLightCoords(level, entity.blockPosition());
		Condition fleshWas = bodies.apply(entity, Material.MUSCLE);
		keyBody = bodyBits(entity);
		Vec3 push = out.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 1.0, 0.0) : out.normalize();
		int made = 0;
		for (int k = 0; k < n && count < config.maxGiblets; k++) {
			Vec3 vel = push.scale(0.08 + RANDOM.nextDouble() * 0.08).add(RANDOM.nextGaussian() * 0.03, 0.04 + RANDOM.nextDouble() * 0.05,
					RANDOM.nextGaussian() * 0.03);
			int i = spawn(at, vel, tissue, flesh, rgb, lightAt, Math.round((config.partLifetimeSeconds * 10 + RANDOM.nextInt(200)) * config.goreLevel.duration));
			owner[i] = entity.getType();
			condition[i].inherit(fleshWas);
			traceInherited(i, entity);
			recolor(i);
			made++;
		}

		keyBody = 0L;
		return made;
	}

	/**
	 * Bits knocked out of a wound by a pick or an axe: skin and fat first, muscle as it goes deeper, bone once it is
	 * down to the bone. {@code normal} points out of the wound.
	 */
	public void chips(ClientLevel level, LivingEntity entity, Vec3 at, Vec3 normal, int n, float depth) {
		GoreConfig config = GoreConfig.get();
		Flesh flesh = Flesh.of(entity);
		int rgb = dev.fluidgore.trigger.EntityFilter.bloodType(entity).rgb;
		makeRoom(n, config.maxGiblets);
		int lightAt = LightCoordsUtil.getLightCoords(level, BlockPos.containing(at));
		Condition fleshWas = bodies.apply(entity, Material.MUSCLE);
		Condition boneWas = bodies.apply(entity, Material.BONE);
		keyBody = bodyBits(entity);
		for (int k = 0; k < n && count < config.maxGiblets; k++) {
			Tissue t;
			if (flesh == Flesh.BONE) {
				t = Tissue.BONE;
			} else {
				float roll = RANDOM.nextFloat();
				t = depth >= 0.75F && roll < 0.4F ? Tissue.BONE : depth >= 0.4F && roll < 0.8F ? Tissue.MUSCLE : Tissue.FAT;
			}

			Vec3 vel = normal.scale(0.15 + RANDOM.nextDouble() * 0.2).add(RANDOM.nextGaussian() * 0.07, 0.12 + RANDOM.nextDouble() * 0.1,
					RANDOM.nextGaussian() * 0.07);
			int i = spawn(at, vel, t, flesh, rgb, lightAt, Math.round((config.partLifetimeSeconds * 10 + RANDOM.nextInt(100)) * config.goreLevel.duration));
			owner[i] = entity.getType();
			condition[i].inherit(t == Tissue.BONE ? boneWas : fleshWas);
			traceInherited(i, entity);
			recolor(i);
		}

		keyBody = 0L;
	}

	/**
	 * A real piece of a body's anatomy torn out of it (see {@code Viscera}): a block of its true size, drawn with its own
	 * tile of anatomy.png (a lung looks like a lung, a rib like a rib), thrown at {@code vel} and tumbling as fast as it
	 * flies. It keeps its name, side and owner, so it can be told for what it is where it lands, and what {@code body}
	 * had been through (burnt, dried, alight). Returns its index, or -1 if there is no room.
	 */
	public int organ(ClientLevel level, Vec3 at, Vec3 vel, float halfX, float halfY, float halfZ, Anatomy.Kind kind, String name, String side,
			LivingEntity body, int bloodRgb, float sear) {
		keyBody = bodyBits(body);
		int i = organ(level, at, vel, halfX, halfY, halfZ, kind, name, side, body.getType(), bloodRgb, sear);
		keyBody = 0L;
		if (i >= 0) {
			condition[i].inherit(bodies.apply(body, material[i]));
			traceInherited(i, body);
			recolor(i);
		}

		return i;
	}

	/**
	 * A torn-out organ as {@link Viscera} finds it in {@code body}: drawn with the cubes it is made of (a liver keeps its
	 * lobes and gallbladder, a lung its lobes) and turned as it lay in the body, but one piece that flies, tumbles, lands
	 * and burns as its joint box. Returns its index, or -1 if there is no room.
	 */
	public int organ(ClientLevel level, Viscera.Organ o, Vec3 vel, LivingEntity body, int bloodRgb, float sear) {
		int i = organ(level, o.at(), vel, o.hx(), o.hy(), o.hz(), o.kind(), o.name(), o.side(), body, bloodRgb, sear);
		if (i >= 0) {
			// After the dice are drawn, so a shape changes nothing else that is thrown.
			shape[i] = o.shape();
			rx[i] = prx[i] = o.pitch();
			ry[i] = pry[i] = o.yaw();
		}

		return i;
	}

	/** {@link #organ(ClientLevel, Vec3, Vec3, float, float, float, Anatomy.Kind, String, String, LivingEntity, int, float)} of a body not at hand. */
	public int organ(ClientLevel level, Vec3 at, Vec3 vel, float halfX, float halfY, float halfZ, Anatomy.Kind kind, String name, String side,
			EntityType<?> owner, int bloodRgb, float sear) {
		GoreConfig config = GoreConfig.get();
		makeRoom(1, config.maxGiblets);
		if (count >= Math.min(config.maxGiblets, CAPACITY)) {
			return -1;
		}

		Tissue t = tissueOf(kind);
		int lightAt = LightCoordsUtil.getLightCoords(level, BlockPos.containing(at));
		// Organs and bones lie about for as long as severed parts: they are what is left to find.
		int i = spawn(at, vel, t, Flesh.MEAT, bloodRgb, lightAt, Math.round(config.partLifetimeSeconds * 20 * config.goreLevel.duration));
		hx[i] = Math.max(halfX, 1.0F / 128.0F);
		hy[i] = Math.max(halfY, 1.0F / 128.0F);
		hz[i] = Math.max(halfZ, 1.0F / 128.0F);
		float largest = Math.max(halfX, Math.max(halfY, halfZ));
		// It tumbles as fast as it flies, the smallest fastest.
		float speed = (float) vel.length();
		float spin = Mth.clamp(speed / Math.max(0.05F, largest) * 0.12F, 0.1F, 1.6F);
		sx[i] = (RANDOM.nextFloat() - 0.5F) * 2.0F * spin;
		sy[i] = (RANDOM.nextFloat() - 0.5F) * 2.0F * spin;
		texture[i] = MeltRenderer.TEXTURE;
		// The tile, one texel to a model pixel as on the body: the biggest side's worth of it.
		float texels = Mth.clamp(Math.round(largest * 32.0F), 2, 16);
		u[i] = (kind.tile % 8 * 16) / 128.0F;
		v[i] = (kind.tile / 8 * 16) / 128.0F;
		du[i] = texels / 128.0F;
		dv[i] = texels / 128.0F;
		material[i] = Material.of(kind);
		base[i] = 0xFFFFFF;
		if (sear > 0.05F) {
			condition[i].sear(sear);
		}

		recolor(i);
		organ[i] = name;
		this.side[i] = side;
		this.owner[i] = owner;
		return i;
	}

	/**
	 * How many pieces of {@code owner}'s anatomy called {@code name} are lying about (any name, with null), and the index
	 * of the first of them in {@code first[0]} (-1 if none).
	 */
	public int organs(@Nullable EntityType<?> owner, @Nullable String name, int[] first) {
		int n = 0;
		first[0] = -1;
		for (int i = 0; i < count; i++) {
			if (organ[i] != null && (owner == null || this.owner[i] == owner) && (name == null || name.equals(organ[i]))) {
				if (n++ == 0) {
					first[0] = i;
				}
			}
		}

		return n;
	}

	/** What a piece of anatomy leaves where it lands and how it sounds: bone and horn clatter, organs slap down wet. */
	private static Tissue tissueOf(Anatomy.Kind kind) {
		Material m = Material.of(kind);
		return m == Material.BONE || m == Material.KERATIN ? Tissue.BONE : m == Material.GUT ? Tissue.GUT : m == Material.FAT ? Tissue.FAT
				: m == Material.MUSCLE ? Tissue.MUSCLE : Tissue.ORGAN;
	}

	/**
	 * The piece the ray from {@code eye} along {@code look} (a unit vector) hits first within {@code reach} blocks, or -1:
	 * each piece taken as a box round its centre as big as its longest side.
	 */
	public int aimed(Vec3 eye, Vec3 look, double reach) {
		int best = -1;
		double nearest = reach;
		for (int i = 0; i < count; i++) {
			double r = Math.max(hx[i], Math.max(hy[i], hz[i])) + 0.03;
			double t = new net.minecraft.world.phys.AABB(x[i] - r, y[i] - r, z[i] - r, x[i] + r, y[i] + r, z[i] + r).clip(eye, eye.add(look.scale(reach)))
					.map(hit -> hit.distanceTo(eye)).orElse(Double.MAX_VALUE);
			if (t < nearest) {
				nearest = t;
				best = i;
			}
		}

		return best;
	}

	/**
	 * What piece {@code i} is, as someone looking at it would say: whose it is, what it is (its organ or bone, or the
	 * tissue of a plain scrap), which side, and the state it is in (burning, charred, seared, cooked).
	 */
	public Component describe(int i) {
		EntityType<?> whose = owner[i];
		String id = organ[i] != null ? organ[i] : switch (tissue[i]) {
			case MUSCLE -> "muscle";
			case FAT -> "fat";
			case BONE -> "bone_fragment";
			case HIDE -> "hide";
			case GUT -> "gut";
			case CUD -> "cud";
			case ORGAN -> "organ_tissue";
			case BLOOD -> "clot";
		};
		Component what = Component.translatableWithFallback("fluidgore.organ." + id, id.replace('_', ' '));
		Component sided = side[i] == null || side[i].isEmpty() ? what
				: Component.translatableWithFallback("fluidgore.organ.side." + side[i], side[i] + " %s", what);
		Component named = whose == null ? sided : Component.translatable("fluidgore.organ.of", whose.getDescription(), sided);
		return state(named, material[i], condition[i]);
	}

	/** {@code named} with the state heat and water have left it in, as someone finding it would say. */
	public static Component state(Component named, Material material, Condition c) {
		if (c.burning) {
			return Component.translatable("fluidgore.organ.burning", named);
		}

		if (material.bone() && c.charred >= dev.fluidgore.wound.BurntBone.CALCINED) {
			return Component.translatable("fluidgore.organ.calcined", named);
		}

		Component said = c.charred > 0.6F ? Component.translatable("fluidgore.organ.charred", named)
				: c.charred > 0.25F ? Component.translatable("fluidgore.organ.seared", named)
				: c.cooked > 0.5F && !material.bone() ? Component.translatable("fluidgore.organ.cooked", named) : named;
		return c.hot() ? Component.translatable("fluidgore.organ.hot", said) : said;
	}

	private int spawn(Vec3 at, Vec3 vel, Tissue t, Flesh flesh, int rgb, int lightAt, int lifetime) {
		int i = count++;
		x[i] = px[i] = at.x;
		y[i] = py[i] = at.y;
		z[i] = pz[i] = at.z;
		vx[i] = vel.x;
		vy[i] = vel.y;
		vz[i] = vel.z;
		float size = (t.minPx + RANDOM.nextInt(t.maxPx - t.minPx + 1)) / 32.0F;
		hx[i] = size;
		hy[i] = t == Tissue.HIDE ? 1.0F / 64.0F : size * (0.7F + RANDOM.nextFloat() * 0.6F);
		// Bone and gut come in long bits.
		hz[i] = t == Tissue.BONE || t == Tissue.GUT ? size * (1.5F + RANDOM.nextFloat()) : size * (0.7F + RANDOM.nextFloat() * 0.6F);
		rx[i] = prx[i] = RANDOM.nextFloat() * Mth.TWO_PI;
		ry[i] = pry[i] = RANDOM.nextFloat() * Mth.TWO_PI;
		sx[i] = (RANDOM.nextFloat() - 0.5F) * 0.8F;
		sy[i] = (RANDOM.nextFloat() - 0.5F) * 0.8F;
		tissue[i] = t;
		material[i] = t.material;
		// Blood for a few landings: a raw piece slapped down again and again leaves less each time, then nothing.
		condition[i].reset(t.wet * 3.0F);
		kindle[i] = 0;
		blood[i] = t == Tissue.CUD ? Tissue.CUD.rgb : rgb;
		base[i] = color[i] = tint(t, flesh);
		light[i] = lightAt;
		texture[i] = null;
		shape[i] = null;
		organ[i] = null;
		side[i] = "";
		owner[i] = null;
		event[i] = Events.current();
		source[i] = Events.source();
		// Numbered within the event and body that made it, so the same event set up again names its pieces the same.
		long stream = (event[i] == 0L ? 0x5DEECE66DL : event[i]) * 31L + keyBody;
		if (serials.size() > 4096) {
			serials.clear();
		}

		key[i] = it.unimi.dsi.fastutil.HashCommon.mix(stream + serials.addTo(stream, 1) + 1);
		// A random 4x4-texel window of the 16x16 flesh texture, so no two pieces look the same.
		u[i] = RANDOM.nextInt(4) * 0.25F;
		v[i] = RANDOM.nextInt(4) * 0.25F;
		du[i] = 0.25F;
		dv[i] = 0.25F;
		resting[i] = false;
		stuck[i] = 0;
		age[i] = 0;
		life[i] = Math.max(100, lifetime);
		return i;
	}

	/** Chars pieces {@code from} up to the newest by {@code amount} (0..1): the scraps of a body that burnt. */
	public void searFrom(int from, float amount) {
		for (int i = Math.max(0, from); i < count; i++) {
			condition[i].sear(amount);
			Events.record(Events.id(Events.Source.ENVIRONMENT, new Vec3(x[i], y[i], z[i]), 0.0), Events.Source.ENVIRONMENT, Events.Channel.THERMAL, -1,
					Events.Effect.MATERIAL, Events.piece(key[i]), String.format(java.util.Locale.ROOT, "from a burnt body, sear %.2f", amount));
			recolor(i);
		}
	}

	/**
	 * The acting event's cloud on piece {@code i} (see {@link Events#cloud}), {@code roll} 0..1 from the blast's dice. A
	 * charge's fireball sears the piece and leaves it hot, smoking where it lands (fat scraps catch); a gas vessel's
	 * expanding gas chills it, frosting it white for a while. Nothing outside the cloud is touched.
	 */
	public void cloud(int i, float roll) {
		float depth = Events.cloud(new Vec3(x[i], y[i], z[i]));
		if (depth <= 0.0F) {
			return;
		}

		Condition c = condition[i];
		if (Events.thermal()) {
			float sear = depth * (0.45F + 0.55F * roll);
			c.sear(sear);
			c.temperature = Math.max(c.temperature, Condition.AMBIENT + 600.0F * depth);
			Events.record(Events.Channel.THERMAL, -1, Events.Effect.MATERIAL, Events.piece(key[i]),
					String.format(java.util.Locale.ROOT, "fireball, %.0f%% in, sear %.2f, %.0f C", depth * 100.0F, sear, c.temperature));
		} else {
			c.temperature = Math.min(c.temperature, Condition.AMBIENT - 25.0F * depth);
			Events.record(Events.Channel.EXPANSION, -1, Events.Effect.MATERIAL, Events.piece(key[i]),
					String.format(java.util.Locale.ROOT, "chilled by the expanding gas, %.0f%% in, %.0f C", depth * 100.0F, c.temperature));
		}

		recolor(i);
	}

	/**
	 * A blast of {@code tntKg} at {@code centre} ({@code psi}: the vessel's pressure, which caps it close in) reaching the
	 * pieces already lying about: each is thrown by the push of the wave on it as a blown-out organ is, and its cloud sears
	 * or chills those in it. A piece in the worst of it is pulped to a smear. Effects go to the event now acting.
	 */
	public void blast(ClientLevel level, BloodWorld world, Vec3 centre, double tntKg, double psi) {
		double reach = Blast.reach(tntKg, psi, 30.0F);
		for (int i = 0; i < count; i++) {
			if (age[i] >= life[i]) {
				continue;
			}

			Vec3 at = new Vec3(x[i], y[i], z[i]);
			Vec3 out = at.subtract(centre);
			double metres = out.length();
			if (metres > reach) {
				continue;
			}

			float kPa = Events.overpressure(level, at, Math.max(0.3, metres), tntKg, psi, Double.NaN);
			if (kPa >= Blast.SHRED_KPA * 3.0F && material[i] != Material.BONE) {
				// Right at the charge: nothing left of it but a smear.
				age[i] = life[i];
				float wet = Thermal.release(condition[i], condition[i].fluid);
				world.landOnFloor(level, x[i], y[i] - hy[i], z[i], Math.max(0.004F, wet), blood[i], world.decalLifetime());
				Events.record(Events.Channel.PRESSURE, -1, Events.Effect.WOUND, Events.piece(key[i]), String.format(java.util.Locale.ROOT, "pulped at %.0f kPa", kPa));
				continue;
			}

			Vec3 dir = metres > 1.0E-3 ? out.scale(1.0 / metres) : new Vec3(0.0, 1.0, 0.0);
			double thick = 2.0 * Math.min(hx[i], Math.min(hy[i], hz[i]));
			double speed = Math.min(1.6, Events.throwSpeed(level, at, metres, tntKg, thick) / 20.0);
			if (speed > 0.02) {
				resting[i] = false;
				stuck[i] = 0;
				vx[i] += dir.x * speed;
				vy[i] += Math.max(0.0, dir.y) * speed + speed * 0.4;
				vz[i] += dir.z * speed;
				sx[i] += (dice(i, 1) - 0.5F) * (float) speed * 2.0F;
				sy[i] += (dice(i, 2) - 0.5F) * (float) speed * 2.0F;
				Events.record(Events.Channel.PRESSURE, -1, Events.Effect.IMPULSE, Events.piece(key[i]),
						String.format(java.util.Locale.ROOT, "thrown at %.1f m/s by %.0f kPa", speed * 20.0, kPa));
			} else if (Events.stirred(level, at, tntKg)) {
				// Under water the wave hardly moves it; the bubble's flow will, so it must not lie asleep.
				resting[i] = false;
				stuck[i] = 0;
			}

			cloud(i, dice(i, 3));
		}
	}

	/**
	 * Dice {@code k} for piece {@code i} under the event now acting, 0..1: from what the piece is called and what hit it,
	 * never this screen's own stream, so the same blast on the same pieces comes out the same (and is traced the same).
	 */
	private float dice(int i, int k) {
		long h = it.unimi.dsi.fastutil.HashCommon.mix(key[i] * 0x9E3779B97F4A7C15L ^ Events.current() + k);
		return (h >>> 40) / (float) (1L << 24);
	}

	/** The continuous valve jet moves small tissue scraps too, using the same occlusion as shaped organs. */
	public void jet(ClientLevel level, Vec3 origin, Vec3 direction, double psi, double area) {
		double reach = dev.fluidgore.physics.GasJet.reach(level, origin, direction, psi, area);
		for (int i = 0; i < count; i++) {
			if (age[i] >= life[i]) continue;
			Vec3 at = new Vec3(x[i], y[i], z[i]);
			if (at.distanceToSqr(origin) > (reach + 0.5) * (reach + 0.5)) continue;
			double shove = dev.fluidgore.physics.GasJet.push(new net.minecraft.world.phys.AABB(at, at).inflate(0.03), origin, direction, psi, area, reach);
			if (shove < 0.0002) continue;
			resting[i] = false; stuck[i] = 0;
			vx[i] = Math.clamp(vx[i] + direction.x * shove, -1.6, 1.6);
			vy[i] = Math.clamp(vy[i] + direction.y * shove + shove * 0.08, -1.6, 1.6);
			vz[i] = Math.clamp(vz[i] + direction.z * shove, -1.6, 1.6);
		}
	}

	/** The index of the piece called {@code key} (see {@link #key}), or -1. */
	public int byKey(long key) {
		for (int i = 0; i < count; i++) {
			if (this.key[i] == key && age[i] < life[i]) {
				return i;
			}
		}

		return -1;
	}

	/** What a player can do to a piece: punch it, cut it with a blade, put a flame to it, stamp on it. */
	public enum Handling {
		PUNCH, CUT, FLAME, STAMP
	}

	/**
	 * A player does {@code what} to piece {@code i}, from {@code eye} looking along {@code look}. A punch knocks it away,
	 * squeezing out some of the blood it still has; a blade cuts it in two (each half keeps what happened to it and half
	 * its blood), or minces a scrap too small to halve; a flame held to it heats it as a fire would, for a few seconds; a
	 * stamp squashes a raw piece flat into a smear, crushes a charred one to ash and kicks a bone away. Returns true if
	 * anything happened.
	 */
	public boolean handle(ClientLevel level, BloodWorld world, int i, Handling what, Vec3 eye, Vec3 look) {
		if (i < 0 || i >= count || age[i] >= life[i]) {
			return false;
		}

		Condition c = condition[i];
		String target = Events.piece(key[i]);
		long event = Events.id(Events.Source.PLAYER, eye, key[i]);
		switch (what) {
			case PUNCH -> {
				resting[i] = false;
				stuck[i] = 0;
				vx[i] += look.x * 0.45 + (FX.nextDouble() - 0.5) * 0.08;
				vy[i] += 0.2 + Math.max(0.0, look.y) * 0.25;
				vz[i] += look.z * 0.45 + (FX.nextDouble() - 0.5) * 0.08;
				sx[i] += (FX.nextFloat() - 0.5F) * 1.2F;
				sy[i] += (FX.nextFloat() - 0.5F) * 1.2F;
				float squeezed = Thermal.release(c, tissue[i].wet * 0.5F);
				if (squeezed > 0.0F) {
					world.landOnFloor(level, x[i], y[i] - hy[i], z[i], squeezed * GoreConfig.get().goreLevel.puddles, blood[i], world.decalLifetime());
				}

				SoundEvent sound = material[i].bone() ? SoundEvents.BONE_BLOCK_HIT : c.charred > 0.4F ? SoundEvents.TUFF_HIT : SoundEvents.SLIME_SQUISH_SMALL;
				level.playLocalSound(x[i], y[i], z[i], sound, SoundSource.PLAYERS, 0.8F, 0.9F + FX.nextFloat() * 0.3F, false);
				if (Thermal.crumbles(material[i], c, 1.0)) {
					crumble(level, i, 0.5);
				}

				Events.record(event, Events.Source.PLAYER, Events.Channel.HANDLING, -1, Events.Effect.IMPULSE, target,
						String.format(java.util.Locale.ROOT, "punched, blood squeezed out %.4f", squeezed));
			}
			case CUT -> {
				float longest = Math.max(hx[i], Math.max(hy[i], hz[i]));
				if (material[i].bone() && c.charred < dev.fluidgore.wound.BurntBone.CALCINED) {
					// A knife does not go through bone; it scrapes.
					level.playLocalSound(x[i], y[i], z[i], SoundEvents.BONE_BLOCK_HIT, SoundSource.PLAYERS, 0.7F, 1.4F, false);
					return true;
				}

				float bled = Thermal.release(c, c.fluid * 0.5F);
				if (bled > 0.0F) {
					world.landOnFloor(level, x[i], y[i] - hy[i], z[i], bled * GoreConfig.get().goreLevel.puddles, blood[i], world.decalLifetime());
				}

				if (longest < 1.0F / 32.0F || count >= Math.min(GoreConfig.get().maxGiblets, CAPACITY)) {
					// Too small to halve: minced into the ground.
					age[i] = life[i];
					world.smear(level, x[i], y[i] - hy[i], z[i], new Vec3(look.x, 0.0, look.z).normalize(), longest * 3.0F + 0.08F, blood[i]);
					Events.record(event, Events.Source.PLAYER, Events.Channel.HANDLING, -1, Events.Effect.WOUND, target, "minced");
				} else {
					int j = split(i);
					Events.record(event, Events.Source.PLAYER, Events.Channel.HANDLING, -1, Events.Effect.WOUND, target,
							String.format(java.util.Locale.ROOT, "cut in two; the other half is %s, blood out %.4f", Events.piece(key[j]), bled));
				}

				level.playLocalSound(x[i], y[i], z[i], c.charred > 0.4F ? SoundEvents.TUFF_HIT : SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 0.8F,
						1.1F + FX.nextFloat() * 0.3F, false);
			}
			case FLAME -> {
				kindle[i] = KINDLE;
				level.playLocalSound(x[i], y[i], z[i], SoundEvents.FLINTANDSTEEL_USE, SoundSource.PLAYERS, 1.0F, 0.9F + FX.nextFloat() * 0.2F, false);
				Events.record(Events.id(Events.Source.ENVIRONMENT, new Vec3(x[i], y[i], z[i]), Exposure.FIRE), Events.Source.ENVIRONMENT, Events.Channel.THERMAL, -1,
						Events.Effect.MATERIAL, target, "a flame held to it by a player");
			}
			case STAMP -> {
				if (material[i].bone() && c.charred < dev.fluidgore.wound.BurntBone.CALCINED) {
					resting[i] = false;
					vx[i] += look.x * 0.2;
					vy[i] += 0.15;
					vz[i] += look.z * 0.2;
					level.playLocalSound(x[i], y[i], z[i], SoundEvents.BONE_BLOCK_HIT, SoundSource.PLAYERS, 0.8F, 1.0F, false);
					Events.record(event, Events.Source.PLAYER, Events.Channel.HANDLING, -1, Events.Effect.IMPULSE, target, "kicked away underfoot");
					return true;
				}

				if (c.charred >= material[i].brittle()) {
					crumble(level, i, 1.0);
					age[i] = life[i];
					Events.record(event, Events.Source.PLAYER, Events.Channel.HANDLING, -1, Events.Effect.MATERIAL, target, "crushed to ash underfoot");
					return true;
				}

				age[i] = life[i];
				float wet = Thermal.release(c, c.fluid);
				Vec3 way = new Vec3(look.x, 0.0, look.z).lengthSqr() > 1.0E-6 ? new Vec3(look.x, 0.0, look.z).normalize() : new Vec3(1.0, 0.0, 0.0);
				world.smear(level, x[i], y[i] - hy[i], z[i], way, Math.max(hx[i], hz[i]) * 3.0F + 0.12F, c.cooked > 0.5F ? 0x5A3A28 : blood[i]);
				if (wet > 0.0F) {
					world.landOnFloor(level, x[i], y[i] - hy[i], z[i], wet * GoreConfig.get().goreLevel.puddles, blood[i], world.decalLifetime());
				}

				level.playLocalSound(x[i], y[i], z[i], SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.0F, 0.6F, false);
				Events.record(event, Events.Source.PLAYER, Events.Channel.HANDLING, -1, Events.Effect.WOUND, target,
						String.format(java.util.Locale.ROOT, "stamped flat, blood %.4f", wet));
			}
		}

		return true;
	}

	/**
	 * Halves piece {@code i} across its longest side: the new half (returned) is a copy, keeping everything that happened
	 * to it, and the two share what loose blood it had. Both fall a little apart.
	 */
	private int split(int i) {
		int j = count++;
		move(i, j);
		key[j] = it.unimi.dsi.fastutil.HashCommon.mix(key[i] + 1L);
		// Cut through, an organ is two plain halves of its tissue: its cubes no longer fit either.
		shape[i] = shape[j] = null;
		condition[i].fluid *= 0.5F;
		condition[j].fluid = condition[i].fluid;
		float[] h = {hx[i], hy[i], hz[i]};
		int axis = h[0] >= h[1] && h[0] >= h[2] ? 0 : h[1] >= h[2] ? 1 : 2;
		double shift = h[axis] * 0.5;
		switch (axis) {
			case 0 -> {
				hx[i] *= 0.5F;
				hx[j] *= 0.5F;
				x[i] -= shift;
				x[j] += shift;
			}
			case 1 -> {
				hy[i] *= 0.5F;
				hy[j] *= 0.5F;
				y[j] += shift;
			}
			default -> {
				hz[i] *= 0.5F;
				hz[j] *= 0.5F;
				z[i] -= shift;
				z[j] += shift;
			}
		}

		resting[i] = resting[j] = false;
		vy[i] += 0.08;
		vy[j] += 0.1;
		vx[j] += (FX.nextDouble() - 0.5) * 0.1;
		vz[j] += (FX.nextDouble() - 0.5) * 0.1;
		return j;
	}

	/** Piece {@code i}'s colour: its own, as heat has left it (see {@link Condition#tint}). */
	private void recolor(int i) {
		int heat = condition[i].tint(material[i]);
		int rgb = base[i];
		int r = (rgb >> 16 & 0xFF) * (heat >> 16 & 0xFF) / 255;
		int g = (rgb >> 8 & 0xFF) * (heat >> 8 & 0xFF) / 255;
		int b = (rgb & 0xFF) * (heat & 0xFF) / 255;
		color[i] = r << 16 | g << 8 | b;
	}

	private static int tint(Tissue t, Flesh flesh) {
		int base = t == Tissue.ORGAN && RANDOM.nextBoolean() ? 0xD48A8E : t.rgb;
		if (flesh == Flesh.DRIED && t == Tissue.MUSCLE) {
			base = 0x6B5A3C;
		} else if (flesh == Flesh.POULTRY && (t == Tissue.MUSCLE || t == Tissue.FAT)) {
			// Raw chicken: pink meat, yellow fat.
			base = t == Tissue.MUSCLE ? 0xF4A2AC : 0xF4E2A8;
		} else if ((flesh == Flesh.PORK || flesh == Flesh.BEEF || flesh == Flesh.MUTTON || flesh == Flesh.ENDER) && (t == Tissue.MUSCLE || t == Tissue.FAT)) {
			base = flesh.tint(t == Tissue.MUSCLE ? Flesh.Tissue.MUSCLE : Flesh.Tissue.FAT, 0);
		} else if (flesh == Flesh.ROTTEN) {
			// Rotting: grey-green muscle, waxy grey fat, black-brown guts and organs, stained bone.
			base = switch (t) {
				case MUSCLE -> 0x6B5E3A;
				case FAT -> 0xB5B08C;
				case BONE -> 0xBFAE7A;
				case GUT -> 0x5E4A30;
				case ORGAN -> 0x3A2818;
				case BLOOD -> 0x3A2414;
				default -> base;
			};
		}

		// Every piece a little different.
		float f = 0.85F + RANDOM.nextFloat() * 0.25F;
		int r = Math.min(255, Math.round((base >> 16 & 0xFF) * f));
		int g = Math.min(255, Math.round((base >> 8 & 0xFF) * f));
		int b = Math.min(255, Math.round((base & 0xFF) * f));
		return r << 16 | g << 8 | b;
	}

	public void tick(ClientLevel level, BloodWorld world) {
		sounds = 3;
		hisses = 2;
		ticks++;
		GoreConfig config = GoreConfig.get();
		boolean reacting = config.pieceReactions;
		float pace = config.cremationSpeedPercent / 100.0F;
		int kept = 0;
		for (int i = 0; i < count; i++) {
			age[i]++;
			if (age[i] >= life[i]) {
				continue;
			}

			px[i] = x[i];
			py[i] = y[i];
			pz[i] = z[i];
			prx[i] = rx[i];
			pry[i] = ry[i];
			if (stuck[i] > 0 && --stuck[i] == 0) {
				// Peels off the wall and drops.
				resting[i] = false;
				vx[i] = vy[i] = vz[i] = 0.0;
			}

			// A resting piece whose floor or wall was broken away falls: at once if it was a block nearby this tick, and
			// in any case on its own check every second.
			// Or water came over a scrap that floats (fat): it comes up.
			if (resting[i] && (i + ticks) % 20 == 0 && material[i].density() < 1.0F
					&& Buoyancy.submerged(level, x[i], y[i] - bottom(i), y[i] + bottom(i), z[i]) > 0.0F) {
				resting[i] = false;
			}

			if (resting[i] && ((i + ticks) % 20 == 0 || BrokenBlocks.near(x[i], y[i], z[i])) && !supported(level, i)) {
				resting[i] = false;
				stuck[i] = 0;
				vx[i] = vy[i] = vz[i] = 0.0;
			}

			if (!resting[i]) {
				step(level, world, i);
			}

			// Heat and water go on working on a piece lying still: hot or near heat often, cold ones now and then.
			if (reacting && age[i] < life[i]) {
				Condition c = condition[i];
				int every = c.burning || kindle[i] > 0 || c.temperature > 35.0F || c.temperature < 10.0F ? REACT_HOT : REACT_COLD;
				if ((i + ticks) % every == 0) {
					react(level, world, i, every, pace);
				}
			}

			if (kept != i) {
				move(i, kept);
			}

			kept++;
		}

		for (int i = kept; i < count; i++) {
			texture[i] = null;
			shape[i] = null;
		}

		count = kept;
	}

	/**
	 * {@code ticks} of what lies round piece {@code i} working on it (see {@link Thermal}), and what that shows: steam
	 * off a wet piece in the heat, smoke as it chars, a flame while it burns (embers glow in the dark), grease running
	 * off hot fat, a hiss and a puff of steam when water puts it out.
	 */
	private void react(ClientLevel level, BloodWorld world, int i, int ticks, float pace) {
		Condition c = condition[i];
		Exposure around = Exposure.at(level, x[i], y[i], z[i], hy[i]);
		if (kindle[i] > 0) {
			// A flame held to it: a fire's heat at its surface, until the flame is spent (water still wins).
			kindle[i] = Math.max(0, kindle[i] - ticks);
			if (!around.submerged()) {
				around = new Exposure(Math.max(around.heat(), Exposure.FIRE), true, false, around.rained());
			}
		}

		if (!Thermal.active(c, around)) {
			return;
		}

		float charredBefore = c.charred;
		float tint = c.cooked + c.charred;
		// Frost shows only while it is cold: it is drawn again each step until it has warmed.
		boolean frosty = c.temperature < 5.0F;
		boolean lit = c.burning;
		float thickness = Math.min(hx[i], Math.min(hy[i], hz[i])) * 32.0F;
		int events = Thermal.step(material[i], c, around, thickness, ticks, pace);
		Events.heat(Events.piece(key[i]), new Vec3(x[i], y[i], z[i]), around, events, charredBefore, c);
		if (Math.abs(c.cooked + c.charred - tint) > 0.004F || c.temperature < 5.0F || frosty) {
			recolor(i);
		}

		if (c.burning != lit || c.burning || c.temperature > 500.0F) {
			// Glowing embers light themselves; once out and cooled, the world's light again.
			light[i] = c.burning || c.temperature > 500.0F ? LightCoordsUtil.FULL_BRIGHT : LightCoordsUtil.getLightCoords(level, BlockPos.containing(x[i], y[i] + 0.1, z[i]));
		}

		hisses = HeatEffects.show(level, events, material[i], x[i], y[i], z[i], y[i] + hy[i], Math.max(hx[i], hz[i]) * 2.0F, hisses);
		if ((events & Thermal.DRIPPING) != 0 && resting[i] && stuck[i] == 0 && FX.nextInt(12) == 0) {
			// Rendered fat runs out from under it: a greasy stain spreading where it lies.
			world.landOnFloor(level, x[i], y[i] - hy[i], z[i], 0.003F * GoreConfig.get().goreLevel.puddles, GREASE, world.decalLifetime());
		}
	}

	/**
	 * Piece {@code i} landed hard enough to break: charred through, it crumbles to char and ash. What is left is
	 * smaller; a piece with little left goes altogether.
	 */
	private void crumble(ClientLevel level, int i, double impact) {
		Condition c = condition[i];
		float size = Math.max(hx[i], Math.max(hy[i], hz[i]));
		int n = Math.round(4 + size * 120.0F);
		for (int k = 0; k < n; k++) {
			level.addParticle(k % 3 == 0 ? ParticleTypes.ASH : CHAR, x[i], y[i], z[i], FX.nextGaussian() * 0.06, 0.04 + FX.nextDouble() * 0.08,
					FX.nextGaussian() * 0.06);
		}

		if (sounds > 0) {
			sounds--;
			level.playLocalSound(x[i], y[i], z[i], material[i].bone() ? SoundEvents.BONE_BLOCK_BREAK : SoundEvents.BASALT_BREAK, SoundSource.NEUTRAL,
					(float) Mth.clamp(impact * 2.0, 0.2, 0.7), 1.2F + FX.nextFloat() * 0.4F, false);
		}

		Events.record(event[i], source[i], Events.Channel.COLLISION, -1, Events.Effect.MATERIAL, Events.piece(key[i]),
				String.format(java.util.Locale.ROOT, "crumbled landing at %.2f, integrity %.2f", impact, c.integrity * 0.6F));
		c.integrity *= 0.6F;
		hx[i] *= 0.7F;
		hy[i] *= 0.7F;
		hz[i] *= 0.7F;
		if (c.integrity < 0.15F || size < 1.0F / 48.0F) {
			age[i] = life[i];
		}
	}

	private void step(ClientLevel level, BloodWorld world, int i) {
		float under = Buoyancy.submerged(level, x[i], y[i] - bottom(i), y[i] + bottom(i), z[i]);
		if (under > 0.0F) {
			// In water a scrap sinks or floats by what it is made of (bone sinks, fat floats), held back by the water and
			// carried by its current.
			Vec3 current = Buoyancy.current(level, x[i], y[i], z[i]);
			vy[i] = (vy[i] - GRAVITY * (1.0F - under / material[i].density())) * WATER_DRAG;
			vx[i] = current.x + (vx[i] - current.x) * WATER_DRAG;
			vz[i] = current.z + (vz[i] - current.z) * WATER_DRAG;
			sx[i] *= 0.85F;
			sy[i] *= 0.85F;
		} else {
			vy[i] = (vy[i] - GRAVITY) * DRAG;
			vx[i] *= DRAG;
			vz[i] *= DRAG;
		}

		Tissue t = tissue[i];
		// A dried, cooked piece is firmer and bounces more than a raw one.
		float restitution = Thermal.restitution(material[i], condition[i]);
		double bottom = bottom(i);
		boolean landed = false;
		// Speed it came down at, for the sound of it landing.
		double fall = 0.0;
		// Axis by axis, so a piece slides along a wall or the floor instead of stopping dead.
		if (solid(level, x[i] + vx[i], y[i] - bottom, z[i])) {
			if (hitWall(level, world, i, Direction.Axis.X)) {
				return;
			}

			vx[i] = -vx[i] * restitution;
		} else {
			x[i] += vx[i];
		}

		if (solid(level, x[i], y[i] - bottom, z[i] + vz[i])) {
			if (hitWall(level, world, i, Direction.Axis.Z)) {
				return;
			}

			vz[i] = -vz[i] * restitution;
		} else {
			z[i] += vz[i];
		}

		if (solid(level, x[i], y[i] + vy[i] - bottom, z[i])) {
			if (vy[i] < 0.0) {
				landed = true;
				fall = -vy[i];
				y[i] = surface(level, x[i], y[i] + vy[i] - bottom, z[i]) + bottom;
				vy[i] = -vy[i] * restitution;
				vx[i] *= 0.55;
				vz[i] *= 0.55;
				sx[i] *= 0.5F;
				sy[i] *= 0.5F;
			} else {
				vy[i] = 0.0;
			}
		} else {
			y[i] += vy[i];
		}

		rx[i] += sx[i];
		ry[i] += sy[i];
		if (landed) {
			if (Thermal.crumbles(material[i], condition[i], fall)) {
				crumble(level, i, fall);
				if (age[i] >= life[i]) {
					return;
				}
			} else if (fall > 0.15) {
				thud(level, i, t, fall);
			}

			// What loose blood it still has, it leaves where it lands; a piece cooked dry leaves none.
			float wet = Thermal.release(condition[i], t.wet);
			if (wet > 0.0F && under == 0.0F) {
				if (event[i] != 0L) {
					Events.record(event[i], source[i], Events.Channel.COLLISION, -1, Events.Effect.WOUND, Events.piece(key[i]),
							String.format(java.util.Locale.ROOT, "landed at %.2f, left blood %.4f", fall, wet));
				}

				world.landOnFloor(level, x[i], y[i] - bottom, z[i], wet * GoreConfig.get().goreLevel.puddles, blood[i], world.decalLifetime());
			}

			if (vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i] < 0.0025) {
				resting[i] = true;
				// Settle flat: lying on its broad side, the thinner of the two sides it turns over between down.
				double floor = y[i] - bottom;
				float quarter = hz[i] < hy[i] ? Mth.HALF_PI : 0.0F;
				rx[i] = Math.round((rx[i] - quarter) / Mth.PI) * Mth.PI + quarter;
				prx[i] = rx[i];
				y[i] = floor + bottom(i);
				light[i] = LightCoordsUtil.getLightCoords(level, BlockPos.containing(x[i], y[i] + 0.1, z[i]));
			}
		}

		if (y[i] < level.getMinY() - 16) {
			age[i] = life[i];
		}
	}

	/**
	 * Piece {@code i} slapped into a wall moving along {@code axis}. A wet piece leaves a smear on the wall, and one
	 * that hit hard enough sticks there for a while before it peels off. Returns true if it stuck.
	 */
	private boolean hitWall(ClientLevel level, BloodWorld world, int i, Direction.Axis axis) {
		Tissue t = tissue[i];
		double along = axis == Direction.Axis.X ? vx[i] : vz[i];
		double speed = Math.abs(along);
		if (Thermal.crumbles(material[i], condition[i], speed)) {
			crumble(level, i, speed);
			return age[i] >= life[i];
		}

		// Only a piece still wet with blood smears a wall or sticks to it.
		if (condition[i].fluid <= 0.0F || speed < 0.12) {
			return false;
		}

		BlockPos block = axis == Direction.Axis.X ? BlockPos.containing(x[i] + vx[i], y[i], z[i]) : BlockPos.containing(x[i], y[i], z[i] + vz[i]);
		// The face that was hit looks back at the piece.
		Direction face = axis == Direction.Axis.X ? (along > 0.0 ? Direction.WEST : Direction.EAST) : (along > 0.0 ? Direction.NORTH : Direction.SOUTH);
		if (GoreConfig.get().wallSplatter) {
			double a = axis == Direction.Axis.X ? z[i] : x[i];
			float half = Math.min(0.4F, 0.06F + hx[i] * 3.0F + (float) speed * 0.2F);
			world.decals().addWall(level, block, face, a, y[i], half, BloodTextures.splat(RANDOM), RANDOM.nextInt(8),
					world.vary(blood[i], 0.15F), world.decalLifetime());
		}

		if (speed < 0.3 || RANDOM.nextFloat() > 0.5F) {
			return false;
		}

		// Flattened against the wall, right on its surface.
		double plane = axis == Direction.Axis.X ? (along > 0.0 ? block.getX() : block.getX() + 1.0) : (along > 0.0 ? block.getZ() : block.getZ() + 1.0);
		double offset = (along > 0.0 ? -1.0 : 1.0) * hy[i];
		if (axis == Direction.Axis.X) {
			x[i] = plane + offset;
			ry[i] = Mth.HALF_PI;
		} else {
			z[i] = plane + offset;
			ry[i] = 0.0F;
		}

		rx[i] = Mth.HALF_PI;
		prx[i] = rx[i];
		pry[i] = ry[i];
		vx[i] = vy[i] = vz[i] = 0.0;
		resting[i] = true;
		stuck[i] = 30 + RANDOM.nextInt(400);
		thud(level, i, t, speed);
		return true;
	}

	/** A piece of meat or bone slapping down, while this tick's budget of landing sounds lasts. */
	private void thud(ClientLevel level, int i, Tissue t, double impact) {
		if (sounds <= 0 || !GoreConfig.get().goreSounds || RANDOM.nextInt(4) != 0) {
			return;
		}

		sounds--;
		// Raw meat slaps; cooked dry it lands dull and firm; charred, it clinks like a cinder.
		Condition c = condition[i];
		SoundEvent sound = t == Tissue.BONE ? SoundEvents.BONE_BLOCK_FALL : c.charred > 0.4F ? SoundEvents.TUFF_HIT
				: c.moisture < 0.4F && c.cooked > 0.5F ? SoundEvents.MUD_HIT : RANDOM.nextBoolean() ? SoundEvents.SLIME_SQUISH_SMALL : SoundEvents.HONEY_BLOCK_FALL;
		float volume = (float) Mth.clamp(impact * 1.5, 0.15, 0.7);
		level.playLocalSound(x[i], y[i], z[i], sound, SoundSource.NEUTRAL, volume, 0.8F + RANDOM.nextFloat() * 0.5F, false);
	}

	/**
	 * How far piece {@code i} reaches below its centre as it is turned now: its box tipped about x (the turn about the
	 * upright does not change it). The bounds of a shaped organ's cubes, since its box holds them all.
	 */
	public double bottom(int i) {
		return Math.abs(Mth.cos(rx[i])) * hy[i] + Math.abs(Mth.sin(rx[i])) * hz[i];
	}

	/** A resting piece still lies on a floor, or a stuck one still has its wall. */
	private boolean supported(ClientLevel level, int i) {
		if (stuck[i] == 0) {
			return solid(level, x[i], y[i] - bottom(i) - 0.02, z[i]);
		}

		double reach = hy[i] + 0.02;
		return solid(level, x[i] + reach, y[i], z[i]) || solid(level, x[i] - reach, y[i], z[i]) || solid(level, x[i], y[i], z[i] + reach)
				|| solid(level, x[i], y[i], z[i] - reach);
	}

	/** True if the point is inside a block as it is drawn (shape bounds: exact for full blocks and slabs). */
	private static boolean solid(ClientLevel level, double wx, double wy, double wz) {
		BlockPos pos = BlockPos.containing(wx, wy, wz);
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return false;
		}

		VoxelShape shape = dev.fluidgore.blood.SurfaceProbe.shape(level, pos, state);
		if (shape.isEmpty()) {
			return false;
		}

		double lx = wx - pos.getX();
		double ly = wy - pos.getY();
		double lz = wz - pos.getZ();
		return lx >= shape.min(Direction.Axis.X) && lx <= shape.max(Direction.Axis.X)
				&& ly >= shape.min(Direction.Axis.Y) && ly <= shape.max(Direction.Axis.Y)
				&& lz >= shape.min(Direction.Axis.Z) && lz <= shape.max(Direction.Axis.Z);
	}

	/** Top of the drawn block at a point inside it. */
	private static double surface(ClientLevel level, double wx, double wy, double wz) {
		BlockPos pos = BlockPos.containing(wx, wy, wz);
		VoxelShape shape = dev.fluidgore.blood.SurfaceProbe.shape(level, pos, level.getBlockState(pos));
		return shape.isEmpty() ? wy : pos.getY() + shape.max(Direction.Axis.Y);
	}

	/** Drops the oldest pieces (lowest indices: pieces are kept in spawn order) so {@code n} more fit under the cap. */
	private void makeRoom(int n, int cap) {
		int drop = count + n - Math.min(cap, CAPACITY);
		if (drop <= 0) {
			return;
		}

		drop = Math.min(drop, count);
		for (int i = drop; i < count; i++) {
			move(i, i - drop);
		}

		count -= drop;
	}

	private void move(int from, int to) {
		x[to] = x[from];
		y[to] = y[from];
		z[to] = z[from];
		px[to] = px[from];
		py[to] = py[from];
		pz[to] = pz[from];
		vx[to] = vx[from];
		vy[to] = vy[from];
		vz[to] = vz[from];
		hx[to] = hx[from];
		hy[to] = hy[from];
		hz[to] = hz[from];
		rx[to] = rx[from];
		ry[to] = ry[from];
		prx[to] = prx[from];
		pry[to] = pry[from];
		sx[to] = sx[from];
		sy[to] = sy[from];
		color[to] = color[from];
		light[to] = light[from];
		u[to] = u[from];
		v[to] = v[from];
		du[to] = du[from];
		dv[to] = dv[from];
		texture[to] = texture[from];
		shape[to] = shape[from];
		organ[to] = organ[from];
		side[to] = side[from];
		owner[to] = owner[from];
		event[to] = event[from];
		source[to] = source[from];
		key[to] = key[from];
		tissue[to] = tissue[from];
		material[to] = material[from];
		condition[to].set(condition[from]);
		base[to] = base[from];
		kindle[to] = kindle[from];
		blood[to] = blood[from];
		resting[to] = resting[from];
		stuck[to] = stuck[from];
		age[to] = age[from];
		life[to] = life[from];
	}

	/** Texel windows on the animal's own skin: a small piece of a random face of each of its model's cubes. */
	private static List<float[]> skinWindows(LivingEntity entity) {
		List<float[]> windows = new ArrayList<>();
		ModelParts.Posed posed = ModelParts.pose(entity);
		if (posed == null) {
			return windows;
		}

		for (ModelPart part : posed.model().root().getAllParts()) {
			for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).fluidgore$getCubes()) {
				for (ModelPart.Polygon polygon : cube.polygons) {
					float u0 = Float.MAX_VALUE;
					float v0 = Float.MAX_VALUE;
					float u1 = -Float.MAX_VALUE;
					float v1 = -Float.MAX_VALUE;
					for (ModelPart.Vertex vertex : polygon.vertices()) {
						u0 = Math.min(u0, vertex.u());
						v0 = Math.min(v0, vertex.v());
						u1 = Math.max(u1, vertex.u());
						v1 = Math.max(v1, vertex.v());
					}

					float w = (u1 - u0) * 0.2F;
					float h = (v1 - v0) * 0.2F;
					if (w > 0.0F && h > 0.0F) {
						float su = u0 + RANDOM.nextFloat() * (u1 - u0 - w);
						float sv = v0 + RANDOM.nextFloat() * (v1 - v0 - h);
						windows.add(new float[]{su, sv, w, h});
					}
				}
			}
		}

		return windows;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static @Nullable Identifier skinTexture(LivingEntity entity) {
		ModelParts.Posed posed = ModelParts.pose(entity);
		return posed == null ? null : ((net.minecraft.client.renderer.entity.LivingEntityRenderer) posed.renderer()).getTextureLocation(posed.state());
	}

	/**
	 * A cleaning tool dragged over the floor at {@code at} (3.1): every piece lying within {@code radius} is squashed and
	 * smeared along {@code stroke} (or round where it lay, for a dab), a dark streak of gut and blood. Returns how many.
	 */
	public int squash(ClientLevel level, BloodWorld world, Vec3 at, float radius, @Nullable Vec3 stroke) {
		int squashed = 0;
		for (int i = 0; i < count; i++) {
			double dx = x[i] - at.x;
			double dz = z[i] - at.z;
			if (!resting[i] || stuck[i] > 0 || age[i] >= life[i] || Math.abs(y[i] - at.y) > 0.5 || dx * dx + dz * dz > radius * radius) {
				continue;
			}

			age[i] = life[i];
			squashed++;
			Vec3 way = stroke != null ? stroke : new Vec3(RANDOM.nextDouble() - 0.5, 0.0, RANDOM.nextDouble() - 0.5).normalize();
			// Gut is darker than the blood it lay in: crushed meat and what was in it.
			int rgb = blood[i];
			int dark = (Math.round(((rgb >> 16) & 0xFF) * 0.55F) << 16) | (Math.round(((rgb >> 8) & 0xFF) * 0.5F) << 8) | Math.round((rgb & 0xFF) * 0.5F);
			world.smear(level, x[i] + way.x * 0.2, y[i], z[i] + way.z * 0.2, way, Math.max(hx[i], hz[i]) * 2.5F + 0.12F, dark);
		}

		if (squashed > 0) {
			level.playLocalSound(at.x, at.y, at.z, SoundEvents.SLIME_SQUISH_SMALL, SoundSource.PLAYERS, Math.min(1.0F, 0.3F + squashed * 0.1F),
					0.7F + RANDOM.nextFloat() * 0.2F, false);
		}

		return squashed;
	}

	/** Takes away every piece inside {@code box}, keeping the rest in the order they came. */
	public void removeIn(net.minecraft.world.phys.AABB box) {
		int kept = 0;
		for (int i = 0; i < count; i++) {
			if (box.contains(x[i], y[i], z[i])) {
				continue;
			}

			if (kept != i) {
				move(i, kept);
			}

			kept++;
		}

		for (int i = kept; i < count; i++) {
			texture[i] = null;
			shape[i] = null;
		}

		count = kept;
	}

	public void clear() {
		for (int i = 0; i < count; i++) {
			texture[i] = null;
		}

		count = 0;
		serials.clear();
		keyBody = 0L;
	}
}
