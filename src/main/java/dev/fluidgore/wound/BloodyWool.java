package dev.fluidgore.wound;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodCell;
import dev.fluidgore.blood.BloodFluidSim;
import dev.fluidgore.blood.BloodSupply;
import dev.fluidgore.blood.SurfaceProbe;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.render.GoreRenderer;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.Suffering;
import dev.fluidgore.trigger.EntityFilter;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Blood on an animal's coat, region by region (head, each leg, the body): a sheep's fleece, a bird's feathers and the
 * hide of every other animal that bleeds. Each region holds three things, drawn by {@link CoatRenderer}:
 * <ul>
 * <li>a <b>soak</b>, 0..1: blood that soaked in all round it, from its own wounds and from lying in blood;</li>
 * <li>a <b>spatter</b> on each face, 0..1: blood flung onto it by a neighbour's blow or a blast, on the side that faced it;</li>
 * <li>a <b>wade</b>, in model pixels: how high blood has climbed from the bottom of the region, from standing, walking
 *     or lying in a pool.</li>
 * </ul>
 * What a coat is decides how it takes blood ({@link Kind}). A fleece drinks it: it takes far more, the pool under it
 * shrinks as it does, and blood climbs the wool above where it touched. Feathers take less. Hide only holds a film
 * where blood touched, and it rinses off in water. All of them wash out in rain and water, and a fleece's whole colour
 * also reddens as it fills.
 * <p>
 * Everything here follows from hits, pools and the weather, never from dice.
 */
public final class BloodyWool {
	/** Tint multiplied into the wool layer, as an rgb int; null for clean wool. */
	public static final RenderStateDataKey<Integer> KEY = RenderStateDataKey.create(() -> "fluidgore:bloody_wool");
	/** A bird's plumage: how much of it is plucked and how wet it is, for its shape; null for no bird or a dry, full coat. */
	public static final RenderStateDataKey<Plumage> PLUMAGE = RenderStateDataKey.create(() -> "fluidgore:plumage");
	/** The blood on a body's coat, for drawing; null for a clean coat. */
	public static final RenderStateDataKey<Drawn> COAT = RenderStateDataKey.create(() -> "fluidgore:blood_coat");
	/** Regions: the {@link BodyPart}s by ordinal, then the body ({@link WoundSet#TORSO}). */
	public static final int REGIONS = WoundSet.TORSO + 1;
	/** Animals this close to a blow get spattered, more the closer they stand. */
	private static final double SPATTER_RANGE = 3.0;
	/** Matted, drying blood in white wool. */
	private static final int SOAKED = 0x8E2424;
	/** How far the fleece's whole colour goes towards {@link #SOAKED} when completely soaked. */
	private static final float MAX_STAIN = 0.85F;
	/** Soak per tick for a dying animal lying in its own blood, on the body and head. */
	private static final float LYING = 0.004F;
	/** How often (ticks) each animal is checked against the pools; each on its own tick. */
	private static final int POOL_EVERY = 4;
	/** Only animals this close to the camera are checked against pools. */
	private static final double POOL_RANGE_SQ = 64.0 * 64.0;
	/** Wetting per check (every {@link #WET_EVERY} ticks) of a bird in rain or water, and drying per check out of it. */
	private static final float WETTING = 0.1F;
	private static final float DRYING = 0.004F;
	private static final int WET_EVERY = 4;
	/** Wet feathers go this much darker. */
	private static final float WET_DARK = 0.3F;
	private static final double WET_RANGE_SQ = 64.0 * 64.0;
	/** Model pixels of a grown animal: 1/16 of a block. Pool depth in millimetres per pixel. */
	private static final float MM_PER_PIXEL = 62.5F;
	/** Ticks a stain takes to dry dark. */
	private static final float DRIES = 6000.0F;
	/** How often (ticks) bodies lying against each other smear blood onto each other, and how much of a bloody coat goes. */
	private static final int CONTACT_EVERY = 10;
	private static final float CONTACT = 0.05F;
	/** Bodies this far apart (blocks) or nearer are touching. */
	private static final double TOUCH = 0.1;

	/**
	 * How a coat takes blood.
	 *
	 * @param most      the most a region can soak
	 * @param spatter   spatter alone (a neighbour's blow, a blast) stains a region no further than this
	 * @param climb     model pixels a tick blood climbs above where it touched, while the coat stands in it
	 * @param drinks    pool units a tick the coat takes out of a pool it stands in, while it is not full
	 * @param water     soak washed out per tick in water
	 * @param rain      soak washed out per tick in rain
	 * @param lyingSoak how far lying in a pool soaks every region (a thrashing body smears itself all over)
	 */
	public enum Kind {
		FLEECE(1.0F, 0.6F, 0.02F, 0.0015F, 0.0015F, 0.0008F, 1.0F),
		FEATHERS(1.0F, 0.5F, 0.01F, 0.0006F, 0.003F, 0.0015F, 0.8F),
		HIDE(0.8F, 0.45F, 0.0F, 0.0001F, 0.02F, 0.003F, 0.55F);

		final float most;
		final float spatter;
		final float climb;
		final float drinks;
		final float water;
		final float rain;
		final float lyingSoak;

		Kind(float most, float spatter, float climb, float drinks, float water, float rain, float lyingSoak) {
			this.most = most;
			this.spatter = spatter;
			this.climb = climb;
			this.drinks = drinks;
			this.water = water;
			this.rain = rain;
			this.lyingSoak = lyingSoak;
		}
	}

	/** How plucked (0..1) and how wet (0..1) a bird's coat is. */
	public record Plumage(float plucked, float wet) {
	}

	/**
	 * A coat as drawn this frame: per region its soak (0..1), its spatter on each face ({@link #FACES} a region, in
	 * {@link CoatRenderer}'s face order) and its wade (model pixels), the blood's colour as it shows now (dried darker
	 * with age), and a seed so each body's stains sit differently.
	 */
	public record Drawn(float[] soak, float[] splash, float[] wade, int rgb, int seed) {
	}

	/**
	 * The blood a piece took with it when it was cut off a body: its region's soak, spatter ({@link #FACES}) and wade, the
	 * blood's colour and when it last came on, and the body's seed. It does not change after: it dries, it is not added to.
	 */
	public record Piece(int region, float soak, float[] splash, float wade, int rgb, long wetAt, int seed) {
		/** The colour as it shows now, dried darker with age. */
		public int shown() {
			return GoreRenderer.shade(rgb, Math.min(1.0F, Math.max(0.0F, Wounds.now() - wetAt) / DRIES));
		}
	}

	/**
	 * Faces of a region: -x, +x, -y, +y, -z, +z in the model's own space (+y is down, -z is forward, as a model is
	 * built). Spatter lands on the faces turned towards where it came from.
	 */
	public static final int FACES = 6;

	/** One body's coat. */
	static final class Coat {
		final float[] soak = new float[REGIONS];
		final float[] splash = new float[REGIONS * FACES];
		final float[] wade = new float[REGIONS];
		int rgb = -1;
		/** Game time blood last came onto it. */
		long wetAt;

		boolean clean() {
			for (int r = 0; r < REGIONS; r++) {
				if (soak[r] > 0.0F || wade[r] > 0.0F) {
					return false;
				}
			}

			for (float s : splash) {
				if (s > 0.0F) {
					return false;
				}
			}

			return true;
		}

		/** One region as a whole, 0..1: its soak, and its spatter spread over all its faces. */
		float region(int r) {
			float faces = 0.0F;
			for (int f = 0; f < FACES; f++) {
				faces += splash[r * FACES + f];
			}

			return Math.min(1.0F, soak[r] + faces / FACES);
		}

		/** The coat as a whole, 0..1: the body counts for half, the rest share the other half. */
		float overall() {
			float rest = 0.0F;
			for (int r = 0; r < WoundSet.TORSO; r++) {
				rest += Math.min(1.0F, region(r) + wade[r] / 12.0F);
			}

			return Math.min(1.0F, 0.5F * region(WoundSet.TORSO) + 0.5F * rest / WoundSet.TORSO);
		}
	}

	private final Int2ObjectOpenHashMap<Coat> coats = new Int2ObjectOpenHashMap<>();
	private final Int2FloatOpenHashMap wet = new Int2FloatOpenHashMap();
	private int tick;

	public static boolean woolly(Entity entity) {
		return entity instanceof Sheep sheep && !sheep.isSheared();
	}

	/** A coat that drinks blood, and the setting for it is on: an unsheared sheep's fleece, or a bird's feathers. */
	public static boolean soaks(Entity entity) {
		Kind kind = kind(entity);
		return kind == Kind.FLEECE || kind == Kind.FEATHERS;
	}

	/** How this entity's coat takes blood, or null if it takes none (settings off, a player, something that does not bleed). */
	public static @Nullable Kind kind(Entity entity) {
		GoreConfig config = GoreConfig.get();
		if (!(entity instanceof LivingEntity living) || entity instanceof Player || !config.goreLevel.enabled()) {
			return null;
		}

		if (woolly(entity)) {
			return config.bloodyWool ? Kind.FLEECE : null;
		}

		if (entity instanceof Chicken) {
			return config.bloodyFeathers ? Kind.FEATHERS : null;
		}

		if (!config.bloodyCoats || !EntityFilter.affects(living) || !Flesh.of(living).bleeds() || EntityFilter.isBone(living)
				|| entity instanceof Enemy && !config.woundHostiles) {
			return null;
		}

		return Kind.HIDE;
	}

	/** How blood-soaked this animal's coat is as a whole, 0..1. */
	public float soak(int entityId) {
		Coat coat = coats.get(entityId);
		return coat == null ? 0.0F : coat.overall();
	}

	/** How blood-soaked one region of this animal's coat is, 0..1 ({@link WoundSet#TORSO} for the body). */
	public float soak(int entityId, int region) {
		Coat coat = coats.get(entityId);
		return coat == null ? 0.0F : coat.region(region);
	}

	/** The spatter on one face ({@code 0..}{@link #FACES}) of one region of this animal's coat, 0..1. */
	public float splash(int entityId, int region, int face) {
		Coat coat = coats.get(entityId);
		return coat == null ? 0.0F : coat.splash[region * FACES + face];
	}

	/** How high blood has climbed one region of this animal's coat, in model pixels. */
	public float wade(int entityId, int region) {
		Coat coat = coats.get(entityId);
		return coat == null ? 0.0F : coat.wade[region];
	}

	/** How wet this bird's feathers are, 0..1. */
	public float wet(int entityId) {
		return wet.get(entityId);
	}

	/** Tint for the wool layer of this sheep, or null if its wool is clean. */
	public @Nullable Integer tint(int entityId) {
		// The body of the fleece: blood on the legs stays on the legs (drawn region by region), it does not pink the lot.
		Coat coat = coats.get(entityId);
		return coat == null ? null : stain(0.8F * coat.region(WoundSet.TORSO) + 0.2F * coat.region(BodyPart.HEAD.ordinal()));
	}

	/** The coat of this body for drawing, or null if it is clean. */
	public @Nullable Drawn drawn(LivingEntity entity) {
		Coat coat = coats.get(entity.getId());
		if (coat == null || kind(entity) == null) {
			return null;
		}

		float dryness = Math.min(1.0F, Math.max(0.0F, Wounds.now() - coat.wetAt) / DRIES);
		int rgb = coat.rgb == -1 ? EntityFilter.bloodColor(entity) : coat.rgb;
		return new Drawn(coat.soak.clone(), coat.splash.clone(), coat.wade.clone(),GoreRenderer.shade(rgb, dryness), entity.getUUID().hashCode());
	}

	/** The blood on {@code part} of this body, for the piece being cut off it; null if that part of the coat is clean. */
	public @Nullable Piece piece(LivingEntity entity, BodyPart part) {
		Coat coat = coats.get(entity.getId());
		if (coat == null || kind(entity) == null) {
			return null;
		}

		int r = part.ordinal();
		float[] splash = new float[FACES];
		System.arraycopy(coat.splash, r * FACES, splash, 0, FACES);
		boolean any = coat.soak[r] > 0.0F || coat.wade[r] > 0.0F;
		for (float s : splash) {
			any |= s > 0.0F;
		}

		return any ? new Piece(r, coat.soak[r], splash, coat.wade[r], coat.rgb == -1 ? EntityFilter.bloodColor(entity) : coat.rgb, coat.wetAt,
				entity.getUUID().hashCode()) : null;
	}

	/**
	 * Tint for a bird whose feathers cover {@code 1 - plucked} of it: blood reddens them, water darkens them; the bare
	 * skin shows neither. Null if the coat is clean and dry.
	 */
	public @Nullable Integer featherTint(int entityId, float plucked) {
		float covered = 1.0F - plucked;
		// A bird bleeds into the feathers round the wound (drawn as stains there) before it reddens all over: the whole
		// coat takes on only half as much as a fleece, and only once it is properly soaked.
		float s = soak(entityId);
		Integer blood = stain(s * s * covered * 0.6F);
		float dark = WET_DARK * wet.get(entityId) * covered;
		if (dark <= 0.01F) {
			return blood;
		}

		int rgb = blood == null ? 0xFFFFFF : blood;
		float k = 1.0F - dark;
		return Math.round((rgb >> 16 & 0xFF) * k) << 16 | Math.round((rgb >> 8 & 0xFF) * k) << 8 | Math.round((rgb & 0xFF) * k);
	}

	private static @Nullable Integer stain(float s) {
		if (s <= 0.01F) {
			return null;
		}

		float t = Math.min(1.0F, s) * MAX_STAIN;
		int r = Math.round(Mth.lerp(t, 255, SOAKED >> 16 & 0xFF));
		int g = Math.round(Mth.lerp(t, 255, SOAKED >> 8 & 0xFF));
		int b = Math.round(Mth.lerp(t, 255, SOAKED & 0xFF));
		return r << 16 | g << 8 | b;
	}

	/**
	 * A blow that opened a wound {@code depth} deep (0..1) on {@code victim} at {@code at}, on {@code part} (null for the
	 * body): blood into the coat round it, and spatter over the coats of every animal close by, on the part of them at
	 * the height it flew.
	 */
	public void hit(ClientLevel level, LivingEntity victim, Vec3 at, float depth, @Nullable BodyPart part) {
		GoreConfig config = GoreConfig.get();
		if (!config.goreLevel.enabled() || !Flesh.of(victim).bleeds()) {
			return;
		}

		float bleed = config.goreLevel.puddles;
		int rgb = EntityFilter.bloodColor(victim);
		Kind own = kind(victim);
		if (own != null) {
			int region = part == null ? WoundSet.TORSO : part.ordinal();
			// Hide shows it round the wound and in the runs down from it (see Runs); a fleece or feathers drink it in.
			float take = own == Kind.HIDE ? 0.35F : 1.0F;
			add(victim.getId(), own, region, (0.08F + 0.3F * depth) * bleed * take, own.most, rgb);
			if (region == BodyPart.HEAD.ordinal()) {
				// A head or throat wound runs down the neck onto the chest.
				add(victim.getId(), own, WoundSet.TORSO, (0.03F + 0.12F * depth) * bleed * take, own.most, rgb);
			}
		}

		spatter(level, victim, at, SPATTER_RANGE, (0.03F + 0.12F * depth) * bleed, rgb);
	}

	/**
	 * A blast at {@code at} burst a body that bled {@code rgb}: blood and flesh over every coat within {@code range},
	 * the nearer the more, on every part that faced it.
	 */
	public void blast(ClientLevel level, @Nullable LivingEntity source, Vec3 at, double range, int rgb) {
		GoreConfig config = GoreConfig.get();
		if (!config.goreLevel.enabled()) {
			return;
		}

		spatter(level, source, at, range, 0.5F * config.goreLevel.puddles, rgb);
	}

	private void spatter(ClientLevel level, @Nullable LivingEntity source, Vec3 at, double range, float amount, int rgb) {
		AABB around = new AABB(at, at).inflate(range);
		for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, around, e -> e != source && kind(e) != null)) {
			Kind kind = kind(other);
			AABB box = other.getBoundingBox();
			float near = 1.0F - (float) Math.sqrt(box.distanceToSqr(at)) / (float) range;
			if (near <= 0.0F || kind == null) {
				continue;
			}

			float share = amount * near;
			float[] faces = facing(other, at);
			// Which part of it the blood reached: what stands at the height it flew.
			float height = (float) Mth.clamp((at.y - box.minY) / Math.max(0.1, box.getYsize()), 0.0, 1.0);
			boolean quadruped = ModelParts.drawn(other.getId()) instanceof QuadrupedModel<?>;
			if (height < 0.5F) {
				// Low: the legs, the near ones more, but the four of them stand together.
				for (BodyPart leg : quadruped ? ModelParts.LIMBS : new BodyPart[] {BodyPart.RIGHT_LEG, BodyPart.LEFT_LEG}) {
					splash(other.getId(), kind, leg.ordinal(), faces, share * 0.7F, rgb);
				}

				splash(other.getId(), kind, WoundSet.TORSO, faces, share * 0.3F, rgb);
			} else if (height < 0.85F) {
				splash(other.getId(), kind, WoundSet.TORSO, faces, share, rgb);
			} else {
				splash(other.getId(), kind, BodyPart.HEAD.ordinal(), faces, share, rgb);
				splash(other.getId(), kind, WoundSet.TORSO, faces, share * 0.5F, rgb);
			}
		}
	}

	/**
	 * How squarely each face of {@code entity} (in its model's space, see {@link #FACES}) is turned to {@code at}, 0..1:
	 * the faces that see it get the spatter, the far side none.
	 */
	static float[] facing(LivingEntity entity, Vec3 at) {
		return facing(at.subtract(entity.getBoundingBox().getCenter()), entity.yBodyRot, 0.0F);
	}

	/**
	 * How squarely each face of a body turned to {@code yaw} (degrees) and leaning {@code roll} (radians, as a lying
	 * body does; see {@code BodyPose}) is turned along the world direction {@code toward}, 0..1.
	 */
	static float[] facing(Vec3 toward, float yaw, float roll) {
		Vector3f to = new Vector3f((float) toward.x, (float) toward.y, (float) toward.z);
		if (to.lengthSquared() < 1.0E-6F) {
			to.set(0.0F, 1.0F, 0.0F);
		}

		// The world into the model's space: undo the renderer's turn to the body's heading and its lean, then its flip
		// of x and y.
		to.normalize().rotateY(-(180.0F - yaw) * Mth.DEG_TO_RAD).rotateZ(-roll);
		to.x = -to.x;
		to.y = -to.y;
		return new float[] {Math.max(0.0F, -to.x), Math.max(0.0F, to.x), Math.max(0.0F, -to.y), Math.max(0.0F, to.y), Math.max(0.0F, -to.z),
				Math.max(0.0F, to.z)};
	}

	/** Spatters {@code amount} onto the faces of one region, each by how squarely it faces the blood, up to the coat's limit. */
	private void splash(int entityId, Kind kind, int region, float[] faces, float amount, int rgb) {
		if (amount <= 0.0F) {
			return;
		}

		Coat coat = coats.computeIfAbsent(entityId, id -> new Coat());
		boolean took = false;
		for (int f = 0; f < FACES; f++) {
			int i = region * FACES + f;
			float now = coat.splash[i];
			float add = amount * faces[f];
			if (add > 0.0F && now < kind.spatter) {
				coat.splash[i] = Math.min(kind.spatter, now + add);
				took = true;
			}
		}

		if (took) {
			mix(coat, rgb, amount);
		}
	}

	/** Soaks {@code amount} into one region, no further than {@code most}; the coat's blood mixes towards {@code rgb}. */
	private void add(int entityId, Kind kind, int region, float amount, float most, int rgb) {
		if (amount <= 0.0F) {
			return;
		}

		Coat coat = coats.computeIfAbsent(entityId, id -> new Coat());
		float now = coat.soak[region];
		if (now >= most) {
			return;
		}

		coat.soak[region] = Math.min(most, Math.min(kind.most, now + amount));
		mix(coat, rgb, amount);
	}

	private static void mix(Coat coat, int rgb, float amount) {
		float total = 0.0F;
		for (int r = 0; r < REGIONS; r++) {
			total += coat.region(r);
		}

		coat.rgb = coat.rgb == -1 ? rgb : BloodFluidSim.mixColor(coat.rgb, rgb, amount / Math.max(amount, total));
		coat.wetAt = Wounds.now();
	}

	public void tick(ClientLevel level) {
		GoreConfig config = GoreConfig.get();
		if (!config.goreLevel.enabled() || !config.bloodyWool && !config.bloodyFeathers && !config.bloodyCoats) {
			coats.clear();
			wet.clear();
			return;
		}

		tick++;
		// An animal dying on the ground lies in its own blood: its body and head take it up.
		for (Corpse corpse : GoreSystems.suffering().corpses()) {
			Kind kind = kind(corpse.entity);
			if (kind != null && !corpse.lifeless() && Flesh.of(corpse.entity).bleeds()) {
				float amount = LYING * config.goreLevel.puddles * (kind == Kind.HIDE ? 0.5F : 1.0F);
				int rgb = EntityFilter.bloodColor(corpse.entity);
				add(corpse.entity.getId(), kind, WoundSet.TORSO, amount, kind.lyingSoak, rgb);
				add(corpse.entity.getId(), kind, BodyPart.HEAD.ordinal(), amount * 0.6F, kind.lyingSoak, rgb);
			}
		}

		if (tick % CONTACT_EVERY == 0) {
			contacts();
		}

		pools(level, config);
		wash(level);
		if (config.bloodyFeathers && tick % WET_EVERY == 0) {
			wetFeathers(level);
		}
	}

	/**
	 * Bodies lying against each other smear each other: the blood on one comes off on the flank of the other where they
	 * touch, on the side that faces it.
	 */
	private void contacts() {
		java.util.List<Corpse> lying = new java.util.ArrayList<>();
		for (Corpse corpse : GoreSystems.suffering().corpses()) {
			if (corpse.groundTicks() > 10 && kind(corpse.entity) != null) {
				lying.add(corpse);
			}
		}

		for (int i = 0; i < lying.size(); i++) {
			Corpse a = lying.get(i);
			dev.fluidgore.physics.BodyFootprint fa = Suffering.footprint(a);
			for (int j = i + 1; j < lying.size(); j++) {
				Corpse b = lying.get(j);
				// The way out of b for a: b lies the other way.
				double[] out = fa.into(Suffering.footprint(b));
				if (out[2] < -TOUCH || Math.abs(a.body.y - b.body.y) > 0.6) {
					continue;
				}

				double dy = Suffering.centre(b).y - Suffering.centre(a).y;
				smear(a, new Vec3(-out[0], dy, -out[1]), b);
				smear(b, new Vec3(out[0], -dy, out[1]), a);
			}
		}
	}

	/** The blood on {@code from}'s coat comes off on {@code onto}, on the faces of its body turned {@code toward} it. */
	private void smear(Corpse onto, Vec3 toward, Corpse from) {
		Kind kind = kind(onto.entity);
		Coat source = coats.get(from.entity.getId());
		if (kind == null || source == null || source.rgb == -1) {
			return;
		}

		float bloody = Math.max(source.region(WoundSet.TORSO), source.region(BodyPart.HEAD.ordinal()));
		if (bloody < 0.1F) {
			return;
		}

		splash(onto.entity.getId(), kind, WoundSet.TORSO, facing(toward, onto.body.yaw, onto.body.roll), CONTACT * bloody, source.rgb);
	}

	/**
	 * Animals standing, walking or lying in blood take it up. Standing, their feet do (a pool is a few millimetres deep;
	 * walking splashes it up the legs). Lying, it soaks the side they lie on and, thrashing, everywhere. A coat that
	 * drinks takes the blood out of the pool, and blood climbs the wool and feathers above where it touched.
	 */
	private void pools(ClientLevel level, GoreConfig config) {
		Entity viewer = Minecraft.getInstance().getCameraEntity();
		if (viewer == null) {
			return;
		}

		BloodFluidSim fluid = GoreSystems.blood().fluid();
		java.util.List<LivingEntity> bodies = new java.util.ArrayList<>();
		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof LivingEntity living) {
				bodies.add(living);
			}
		}

		for (Corpse corpse : GoreSystems.suffering().corpses()) {
			// A body this client keeps after the server let it go is no longer in the level.
			if (corpse.unloaded) {
				bodies.add(corpse.entity);
			}
		}

		for (LivingEntity living : bodies) {
			Entity entity = living;
			if ((tick + entity.getId()) % POOL_EVERY != 0 || entity.distanceToSqr(viewer) > POOL_RANGE_SQ) {
				continue;
			}

			Kind kind = kind(living);
			if (kind == null) {
				continue;
			}

			Corpse corpse = GoreSystems.suffering().corpse(entity.getId());
			Vec3 at = corpse != null ? corpse.position() : entity.position();
			BloodCell cell = under(fluid, living, at);
			if (cell == null || cell.volume < 0.01F) {
				// Out of the pool, blood still climbs a fleece a little way as it wicks.
				climb(living, kind, false);
				continue;
			}

			float scale = Math.max(0.3F, living.getScale() * (living.isBaby() ? 0.5F : 1.0F));
			float depth = cell.volume * BloodSupply.LITRES_PER_UNIT / (MM_PER_PIXEL * scale);
			Coat coat = coats.computeIfAbsent(entity.getId(), id -> new Coat());
			boolean lying = corpse != null && corpse.age > 20;
			float before = coat.overall();
			if (lying) {
				// Lying in it: every part touches the pool on the side it lies on, and the struggle smears it about.
				float rate = (corpse.lifeless() ? 0.004F : 0.012F) * POOL_EVERY;
				for (int r = 0; r < REGIONS; r++) {
					add(entity.getId(), kind, r, rate, kind.lyingSoak, cell.rgb);
				}
			} else {
				// Standing in it: the feet, and splashed up the legs as it walks through.
				// How fast it goes, from where it was last tick (other players' and the server's mobs have no velocity here).
				double speed = Math.sqrt(Mth.square(entity.getX() - entity.xo) + Mth.square(entity.getZ() - entity.zo));
				float splash = (float) Math.min(3.5, speed * 25.0);
				float reach = 0.5F + depth + splash;
				boolean quadruped = ModelParts.drawn(entity.getId()) instanceof QuadrupedModel<?>;
				for (BodyPart leg : quadruped ? ModelParts.LIMBS : new BodyPart[] {BodyPart.RIGHT_LEG, BodyPart.LEFT_LEG}) {
					int r = leg.ordinal();
					coat.wade[r] = Math.max(coat.wade[r], reach);
				}

				mix(coat, cell.rgb, 0.05F);
			}

			climb(living, kind, true);
			// A coat that drinks takes the blood out of the pool as it fills.
			if (coat.overall() < kind.most && kind.drinks > 0.0F) {
				fluid.drain(cell, Math.min(cell.volume, kind.drinks * POOL_EVERY * (lying ? 3.0F : 1.0F) * config.goreLevel.puddles));
			}

			if (coat.overall() > before) {
				coat.wetAt = Wounds.now();
			}
		}
	}

	/** Blood climbs a fleece or feathers above where it touched: fast while it stands in it, slowly after. */
	private void climb(LivingEntity entity, Kind kind, boolean inPool) {
		Coat coat = coats.get(entity.getId());
		if (coat == null || kind.climb <= 0.0F) {
			return;
		}

		float step = kind.climb * POOL_EVERY * (inPool ? 1.0F : 0.15F);
		for (int r = 0; r < REGIONS; r++) {
			if (coat.wade[r] > 0.0F && coat.wade[r] < 16.0F) {
				// It climbs only so far above where it touched before the fibres hold no more.
				coat.wade[r] = Math.min(16.0F, coat.wade[r] + step);
			}
		}
	}

	/** The pool under a body standing or lying at {@code at}, if any: the cell its middle or one of its corners is in. */
	private static @Nullable BloodCell under(BloodFluidSim fluid, LivingEntity entity, Vec3 at) {
		int by = SurfaceProbe.airBlockY(at.y);
		double half = entity.getBbWidth() * 0.4;
		BloodCell best = null;
		double[][] spots = {{0.0, 0.0}, {half, half}, {-half, half}, {half, -half}, {-half, -half}};
		for (double[] spot : spots) {
			int bx = Mth.floor(at.x + spot[0]);
			int bz = Mth.floor(at.z + spot[1]);
			for (int dy = 0; dy >= -1; dy--) {
				BloodCell cell = fluid.cellAt(bx, by + dy, bz);
				if (cell != null && Math.abs(cell.surfaceY - at.y) < 0.35 && (best == null || cell.volume > best.volume)) {
					best = cell;
				}
			}
		}

		return best;
	}

	/** Rain and water wash coats out: hide rinses clean quickly in water, a fleece only slowly. */
	private void wash(ClientLevel level) {
		var it = coats.int2ObjectEntrySet().iterator();
		while (it.hasNext()) {
			Int2ObjectMap.Entry<Coat> entry = it.next();
			Entity entity = level.getEntity(entry.getIntKey());
			Corpse corpse = entity == null ? GoreSystems.suffering().corpse(entry.getIntKey()) : null;
			Entity body = entity != null ? entity : corpse != null ? corpse.entity : null;
			if (body == null) {
				it.remove();
				continue;
			}

			Kind kind = kind(body);
			if (kind == null) {
				it.remove();
				continue;
			}

			float washed = body.isInWater() ? kind.water : body.isInWaterOrRain() ? kind.rain : 0.0F;
			if (washed <= 0.0F) {
				continue;
			}

			Coat coat = entry.getValue();
			for (int r = 0; r < REGIONS; r++) {
				coat.soak[r] = Math.max(0.0F, coat.soak[r] - washed);
				coat.wade[r] = Math.max(0.0F, coat.wade[r] - washed * 16.0F);
			}

			for (int i = 0; i < coat.splash.length; i++) {
				coat.splash[i] = Math.max(0.0F, coat.splash[i] - washed);
			}

			if (coat.clean()) {
				it.remove();
			}
		}
	}

	/** Birds out in the rain or in water get wet through; out of it they slowly dry. */
	private void wetFeathers(ClientLevel level) {
		Entity viewer = Minecraft.getInstance().getCameraEntity();
		if (viewer == null) {
			return;
		}

		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof Chicken && entity.isInWaterOrRain() && entity.distanceToSqr(viewer) < WET_RANGE_SQ) {
				wet.put(entity.getId(), Math.min(1.0F, wet.get(entity.getId()) + WETTING));
			}
		}

		for (Int2FloatMap.Entry entry : wet.int2FloatEntrySet()) {
			Entity entity = level.getEntity(entry.getIntKey());
			if (entity == null || !entity.isInWaterOrRain()) {
				entry.setValue(Math.max(0.0F, entry.getFloatValue() - DRYING));
			}
		}

		wet.values().removeIf(v -> v <= 0.0F);
	}

	public void forget(int entityId) {
		coats.remove(entityId);
		wet.remove(entityId);
	}

	public void clear() {
		coats.clear();
		wet.clear();
	}
}
