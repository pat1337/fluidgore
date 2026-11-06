package dev.fluidgore.wound;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodTextures;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.BoneEmitter;
import dev.fluidgore.blood.SurfaceProbe;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.dismember.Giblets;
import dev.fluidgore.net.Evidence;
import dev.fluidgore.net.GoreAction;
import dev.fluidgore.net.GoreSync;
import dev.fluidgore.physics.BodyMass;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.suffer.CorpseStrike;
import dev.fluidgore.trigger.EntityFilter;
import dev.fluidgore.trigger.Fate;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Burning a body where it lies. Put fire to a dead animal (a flint and steel or a fire charge on it, or lay it in fire,
 * on a lit campfire or in lava) and it burns the way a carcass really does on an open fire:
 * <ol>
 * <li>The hair, wool or feathers go up first in a flash of flame and white, stinking smoke, and the skin sears and
 *     blackens.</li>
 * <li>Once the fat under the skin melts it feeds the fire itself and the body burns on its own, even with the fire
 *     under it gone: thick, black, greasy smoke, fat dripping and spitting, the skin splitting over glowing cracks. The
 *     heat shortens the muscles and draws the limbs in (the pugilistic, boxer's pose of every burnt body).</li>
 * <li>The flesh burns away and the bones come through, scorched brown and charred black, and the body shrinks to a
 *     charred skeleton: ribs, skull and long bones. The fire burns down to embers and ash. Only a fire that goes on
 *     under the bare bones (a campfire, a pyre, lava) burns them on to grey and at last calcined white
 *     ({@link BurntBone}).</li>
 * </ol>
 * How long it takes and how big the fire and its smoke are goes with the body's weight ({@link #kilograms}): a
 * chicken flares and is bones in a quarter of a minute under a wisp of smoke, a sheep burns for most of a minute, a
 * cow for nearly two under a tall black column. (A real pyre takes hours; this is the same fire, sped up.)
 *
 * <p>Water or rain puts it out, and so does taking it away from the fire before the fat has caught. Whatever was left
 * stays as it was: charred, cracked, half-burnt meat on blackened bone. Blows knock the char off it in black crumbs
 * and flakes, down to the bone ({@link #strike}). Once only bone is left, each blow breaks a bone (the legs first,
 * then the skull, last the ribs and spine) until the skeleton falls in on itself and is gone, leaving ash: a burnt
 * body can be cleared away like that, and bones and char do not drag at the legs as a carcass does.
 *
 * <p>Lighting a body goes through {@link GoreSync} ({@link GoreAction#IGNITE}), so with the server half everyone sees
 * the same fire; the burning itself follows from the fire it lies in and the time, the same on every screen.
 */
public final class Cremation {
	public static final RenderStateDataKey<Charred> KEY = RenderStateDataKey.create(() -> "fluidgore:cremation");

	/**
	 * What a burnt body looks like, for the renderer.
	 *
	 * @param bare how much of the flesh is gone, burnt or knocked off, 0..1
	 * @param burn how long it burnt, 0..1: how roasted its meat is
	 * @param bone how burnt its bones are, 0..1 (see {@link BurntBone})
	 * @param glow the embers in its cracks, 0..1
	 * @param seed the body's own dice for where the char splits, from its UUID
	 */
	public record Charred(float bare, float burn, float bone, float glow, long seed) {
		/**
		 * How far the heat has drawn the limbs in, 0..1. It is the muscle shortening as it cooks, so it comes once the fire
		 * is through the hide, not while only the hair singes (see {@code Melt.flex}).
		 */
		public float flex() {
			return ramp(bare, 0.15F, 0.5F);
		}

		/** How far the body has shrunk to its bones, 0..1. */
		public float wasted() {
			return ramp(bare, 0.25F, 1.0F);
		}

		/**
		 * The wool has burnt off. Wool hardly burns: it chars black and smoulders where it lies on the hide (the burn
		 * tint darkens it) and is gone only once the skin under it is burning.
		 */
		public boolean shorn() {
			return bare > 0.2F;
		}
	}

	/** Burnt this far, the body's own fat keeps the fire going without anything under it. */
	private static final float CATCHES = 0.06F;
	/** Ticks a flame put to it, or a fire it lay in, keeps it alight until it catches: vanilla's eight seconds and two. */
	private static final int KINDLE = 200;
	/** Seconds to burn to the bone per cube root of the body's kilograms, at a speed of 100%. */
	private static final float SECONDS = BodyMass.SECONDS;
	/** Ticks a body that went out smokes on. */
	private static final int SMOULDER = 900;
	/** Ticks the embers in a body take to go cold after the flames are gone. */
	private static final int COOLING = 1200;
	/** Flesh this far gone counts as burnt: blows knock the char off rather than open wounds. */
	private static final float CHARRED = 0.25F;
	/** Flesh this far gone leaves only bone: blows break it. */
	private static final float BONE = 0.97F;
	/** Flesh this far gone has laid the bones bare to a fire under them. */
	private static final float BONE_SHOWS = 0.5F;
	/** A fire under bare bone takes this many times as long as the flesh took to burn to calcine them (lava a third of it). */
	private static final float CALCINING = 3.0F;
	private static final double RANGE_SQ = 64.0 * 64.0;
	private static final double SOUND_SQ = 24.0 * 24.0;
	/** The bones break in this order; the trunk (ribs and spine) is last, and then the skeleton is gone. */
	private static final BodyPart[] BONES = {BodyPart.RIGHT_LEG, BodyPart.LEFT_LEG, BodyPart.RIGHT_ARM, BodyPart.LEFT_ARM, BodyPart.HEAD};
	private static final BlockParticleOption CHAR = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COAL_BLOCK.defaultBlockState());
	private static final BlockParticleOption CINDER = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.BLACKSTONE.defaultBlockState());
	private static final DustParticleOptions SOOT = new DustParticleOptions(0x1A1410, 0.5F);
	/** The grey ash a pyre leaves on the ground. */
	private static final int ASH = 0x5E5A55;
	/** Where the char splits and how the bits fly: the same on every screen (see {@link Fate}). */
	private static final RandomSource RANDOM = Fate.stream("cremation");
	/** Smoke and flames: this screen's own. */
	private static final RandomSource FX = RandomSource.create();
	private static final class Fire {
		final float kg;
		/** Cube root of the kilograms: how big the fire and its smoke are, and how long it takes. */
		final float size;
		final long seed;
		final boolean wool;
		float burn;
		/** Char knocked off by blows, 0..1 like {@link #burn}. */
		float crumble;
		float glow;
		int kindle;
		boolean lit;
		/** Burnt to the bone: nothing left to feed the flames, they die. */
		boolean spent;
		/** How burnt the bones are, 0..1 (see {@link BurntBone}): not how much flesh is gone. */
		float bone;
		boolean flared;
		/** Put out with water or rain: wet through, it does not catch again on its own, only from a fire or a flame put to it. */
		boolean doused;
		/** It was burning before it died (it came here from Melting): its burns have the redness of a living body's. */
		boolean alive;
		/** Ticks since it went out. */
		int out = SMOULDER;
		/** Blows on the bare bones. */
		int blows;
		float flames;
		float smoke;
		int crackle;

		Fire(LivingEntity entity) {
			this.kg = kilograms(entity);
			this.size = (float) Math.cbrt(kg);
			this.seed = entity.getUUID().getMostSignificantBits() ^ entity.getUUID().getLeastSignificantBits() ^ 0x5DEECE66DL;
			this.wool = entity instanceof Sheep sheep && !sheep.isSheared();
		}

		float bare() {
			return Math.max(burn, crumble);
		}
	}

	private final Int2ObjectOpenHashMap<Fire> fires = new Int2ObjectOpenHashMap<>();
	private int tick;
	private int lastUse = -100;

	/** What a body weighs, in kilograms (see {@link BodyMass}). */
	public static float kilograms(LivingEntity entity) {
		return BodyMass.kilograms(entity);
	}

	/** Seconds {@code entity} takes to burn to the bone in a fire, at the speed set. */
	public static float seconds(LivingEntity entity) {
		return BodyMass.seconds(entity);
	}

	/** How much of a body's flesh is gone, burnt or knocked off: 0 for one that never burnt. */
	public float bare(int entityId) {
		Fire fire = fires.get(entityId);
		return fire == null ? 0.0F : fire.bare();
	}

	/** True if the body was burning while it was still alive (see {@code Melt.vital}). */
	public boolean burntAlive(int entityId) {
		Fire fire = fires.get(entityId);
		return fire != null && fire.alive;
	}

	/** How long it has burnt, 0..1 (1: to the bone). */
	public float burn(int entityId) {
		Fire fire = fires.get(entityId);
		return fire == null ? 0.0F : fire.burn;
	}

	/** How burnt its bones are, 0..1 (see {@link BurntBone}). */
	public float bone(int entityId) {
		Fire fire = fires.get(entityId);
		return fire == null ? 0.0F : fire.bone;
	}

	/** True if this body has a fire here (alight, out or burnt down): then {@link #flames} alone says how it burns. */
	public boolean tracks(int entityId) {
		return fires.containsKey(entityId);
	}

	/** True while the body is burning: its own flames, smoke and sounds are drawn here, not {@link Burns}'. */
	public boolean burning(int entityId) {
		Fire fire = fires.get(entityId);
		return fire != null && fire.lit;
	}

	/**
	 * How big the flames on a burning body are, 0 (out) to 1: small as it catches, fiercest while its fat burns, dying
	 * down on the bare bones.
	 */
	public float flames(int entityId) {
		Fire fire = fires.get(entityId);
		if (fire == null || !fire.lit) {
			return 0.0F;
		}

		return Mth.clamp(fire.glow * (0.45F + 0.75F * Mth.sin(Mth.PI * fire.burn)), 0.0F, 1.0F);
	}

	/** Bone broken off a burnt skeleton so far. */
	public int blows(int entityId) {
		Fire fire = fires.get(entityId);
		return fire == null ? 0 : fire.blows;
	}

	/** Render state for the body, or null if it never burnt. */
	public @Nullable Charred look(int entityId) {
		Fire fire = fires.get(entityId);
		return fire == null || fire.bare() <= 0.01F ? null : new Charred(fire.bare(), fire.burn, fire.bone, fire.glow, fire.seed);
	}

	/** Use key with a flint and steel or a fire charge on a body: sets it alight. Returns true if it did. */
	public boolean onUse(Minecraft mc) {
		LocalPlayer player = mc.player;
		GoreConfig config = GoreConfig.get();
		if (!config.cremation || !config.goreLevel.enabled() || player == null || !(mc.level instanceof ClientLevel)) {
			return false;
		}

		ItemStack held = player.getMainHandItem();
		boolean charge = held.is(Items.FIRE_CHARGE);
		if (!charge && !held.is(Items.FLINT_AND_STEEL)) {
			return false;
		}

		Corpse corpse = CorpseStrike.aimed(mc, player);
		if (corpse == null || EntityFilter.isBone(corpse.entity)) {
			return false;
		}

		Fire fire = fires.get(corpse.entity.getId());
		if (fire != null && fire.spent) {
			// Nothing left to burn: the flint lights the ground as it would.
			return false;
		}

		if (tick - lastUse >= 10) {
			lastUse = tick;
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			GoreSync.act(GoreAction.of(GoreAction.IGNITE, corpse.entity.getId(), 0L, player.getEyePosition(), player.getViewVector(1.0F), charge ? 1 : 0,
					0.0F));
		}

		return true;
	}

	/** A flame put to a body (by this player or another): it is alight, and catches if it burns long enough. */
	public boolean ignite(ClientLevel level, Corpse corpse, boolean charge) {
		LivingEntity entity = corpse.entity;
		if (!GoreConfig.get().cremation || EntityFilter.isBone(entity)) {
			return false;
		}

		Fire fire = fires.computeIfAbsent(entity.getId(), id -> new Fire(entity));
		if (fire.spent) {
			return false;
		}

		fire.kindle = Math.max(fire.kindle, charge ? KINDLE * 2 : KINDLE);
		fire.doused = false;
		corpse.keepFor(fire.kindle + 40);
		Vec3 at = CorpseStrike.centre(corpse);
		level.playLocalSound(at.x, at.y, at.z, charge ? SoundEvents.FIRECHARGE_USE : SoundEvents.FLINTANDSTEEL_USE, SoundSource.PLAYERS, 1.0F,
				0.9F + RANDOM.nextFloat() * 0.2F, false);
		for (int i = 0; i < (charge ? 12 : 5); i++) {
			level.addParticle(ParticleTypes.FLAME, at.x + RANDOM.nextGaussian() * 0.15, at.y, at.z + RANDOM.nextGaussian() * 0.15,
					RANDOM.nextGaussian() * 0.02, 0.03, RANDOM.nextGaussian() * 0.02);
		}

		return true;
	}

	public void tick(ClientLevel level, BloodWorld blood) {
		GoreConfig config = GoreConfig.get();
		if (!config.cremation) {
			clear();
			return;
		}

		tick++;
		Entity camera = Minecraft.getInstance().getCameraEntity();
		for (Corpse corpse : GoreSystems.suffering().corpses()) {
			LivingEntity entity = corpse.entity;
			int id = entity.getId();
			if (EntityFilter.isBone(entity) || GoreSystems.dismemberment().vaporized(id)) {
				continue;
			}

			AABB box = CorpseStrike.box(corpse);
			int heat = heat(level, corpse, box);
			Fire fire = fires.get(id);
			if (fire == null) {
				// A person who burnt alive (see Melting) burns on from how far it got; on their own fat if they died on fire.
				float melted = GoreSystems.dismemberment().melting().handOver(id);
				if (heat == 0 && melted <= 0.0F) {
					continue;
				}

				fire = new Fire(entity);
				fire.burn = melted;
				fire.bone = BurntBone.ownFire(melted);
				fire.alive = melted > 0.0F;
				fire.doused = melted > 0.0F && heat == 0;
				fires.put(id, fire);
			}

			burn(level, blood, corpse, fire, box, heat, config, camera);
		}

		fires.int2ObjectEntrySet().removeIf(e -> GoreSystems.suffering().corpse(e.getIntKey()) == null && level.getEntity(e.getIntKey()) == null);
	}

	/**
	 * What the body lies in: 2 for lava, 1 for fire (a fire, a lit campfire, or the entity itself on fire on the
	 * server), 0 for nothing.
	 */
	private static int heat(ClientLevel level, Corpse corpse, AABB box) {
		return Math.max(around(level, box), !corpse.unloaded && corpse.entity.isOnFire() ? 1 : 0);
	}

	/** The fire under and around the body, not its own: 2 for lava, 1 for a fire or a lit campfire, 0 for none. */
	private static int around(ClientLevel level, AABB box) {
		int heat = 0;
		BlockPos from = BlockPos.containing(box.minX, box.minY - 0.2, box.minZ);
		BlockPos to = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			BlockState state = level.getBlockState(pos);
			if (state.getFluidState().is(FluidTags.LAVA)) {
				return 2;
			}

			if (state.is(BlockTags.FIRE) || CampfireBlock.isLitCampfire(state)) {
				heat = 1;
			}
		}

		return heat;
	}

	private void burn(ClientLevel level, BloodWorld blood, Corpse corpse, Fire fire, AABB box, int heat, GoreConfig config, @Nullable Entity camera) {
		int id = corpse.entity.getId();
		Vec3 centre = box.getCenter();
		BlockPos at = BlockPos.containing(centre);
		boolean drenched = level.getFluidState(at).is(FluidTags.WATER) || level.getFluidState(BlockPos.containing(centre.x, box.minY + 0.1, centre.z)).is(FluidTags.WATER);
		boolean rain = level.isRainingAt(at) || level.isRainingAt(at.above());
		if (heat > 0) {
			fire.kindle = KINDLE;
			fire.doused = false;
		} else if (fire.kindle > 0) {
			fire.kindle--;
		}

		boolean was = fire.lit;
		// Lava burns through anything; the rain puts out a body burning on its own, and water puts out everything.
		fire.lit = !fire.spent && !drenched && (heat == 2 || !rain && (heat > 0 || fire.kindle > 0 || fire.burn >= CATCHES && !fire.doused));
		if (fire.lit) {
			float ticks = 20.0F * SECONDS * fire.size * 100.0F / config.cremationSpeedPercent;
			float pace = heat == 2 ? 3.0F : heat == 1 ? 1.3F : 1.0F;
			fire.burn = Math.min(1.0F, fire.burn + pace / ticks);
			fire.out = 0;
			float fat = Mth.sin(Mth.PI * fire.burn);
			fire.glow += (0.55F + 0.45F * fat - fire.glow) * 0.05F;
			GoreSystems.dismemberment().burns().restore(id, Math.min(1.0F, 0.05F + fire.burn * 5.0F));
			if (fire.burn >= 1.0F) {
				fire.spent = true;
				fire.lit = false;
				burntOut(level, blood, corpse, fire, box);
			}
		} else {
			fire.out++;
			fire.glow = Math.max(0.0F, fire.glow - (drenched || rain ? 0.02F : 1.0F / COOLING));
		}

		// The flesh burning off the bones chars them, and here no further: a game's simplification of a body's own fire
		// rarely being hot and long enough on its own to calcine them (see BurntBone).
		fire.bone = Math.max(fire.bone, BurntBone.ownFire(fire.burn));
		int under = heat > 0 ? around(level, box) : 0;
		if (under > 0 && !drenched && fire.burn > BONE_SHOWS) {
			// A fire that goes on under the bare bones burns the carbon out of them, slowly: grey, then chalk white.
			boolean white = fire.bone >= BurntBone.CALCINED;
			float ticks = 20.0F * SECONDS * fire.size * CALCINING * 100.0F / config.cremationSpeedPercent;
			fire.bone = Math.min(1.0F, fire.bone + (under == 2 ? 3.0F : 1.0F) * ramp(fire.burn, BONE_SHOWS, 1.0F) / ticks);
			if (!white && fire.bone >= BurntBone.CALCINED) {
				Evidence.touched(id);
			}
		}

		if (fire.lit && !was) {
			caught(level, fire, box);
		} else if (was && !fire.lit && !fire.spent) {
			fire.doused = drenched || rain;
			fire.kindle = 0;
			wentOut(level, fire, box, drenched || rain);
			Evidence.touched(id);
		}

		if (fire.bare() > 0.01F) {
			// The remains stay until they are broken up (or for as long as loose pieces, without the server half).
			corpse.keepFor(fire.lit ? 400 : config.partLifetimeSeconds * 20);
		}

		if (camera != null && camera.distanceToSqr(centre) < RANGE_SQ) {
			effects(level, fire, box, config, camera.distanceToSqr(centre) < SOUND_SQ);
		}
	}

	/**
	 * Flames take hold. A fleece does not flash up: wool is slow to light and puts itself out, so it scorches and
	 * smoulders, a thick cloud of white, stinking smoke with a hiss, before the fire gets through it to the skin.
	 */
	private static void caught(ClientLevel level, Fire fire, AABB box) {
		Vec3 c = box.getCenter();
		level.playLocalSound(c.x, c.y, c.z, SoundEvents.FIRE_AMBIENT, SoundSource.NEUTRAL, Math.min(1.5F, 0.6F + 0.1F * fire.size), 0.9F, false);
		if (fire.wool && !fire.flared && fire.burn < 0.05F) {
			fire.flared = true;
			level.playLocalSound(c.x, c.y, c.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.5F, 0.6F, false);
			for (int i = 0; i < 30; i++) {
				Vec3 p = inside(box, 0.8F);
				level.addParticle(i % 3 == 0 ? ParticleTypes.SMOKE : ParticleTypes.WHITE_SMOKE, p.x, box.maxY - 0.1, p.z, FX.nextGaussian() * 0.01,
						0.03 + FX.nextDouble() * 0.03, FX.nextGaussian() * 0.01);
				if (i % 6 == 0) {
					level.addParticle(ParticleTypes.SMALL_FLAME, p.x, p.y, p.z, 0.0, 0.01, 0.0);
				}
			}
		}
	}

	/** Put out: a hiss and a cloud of steam if it was water, the flames dying if not. */
	private static void wentOut(ClientLevel level, Fire fire, AABB box, boolean doused) {
		Vec3 c = box.getCenter();
		if (doused) {
			level.playLocalSound(c.x, c.y, c.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.7F, 0.8F + FX.nextFloat() * 0.3F, false);
			int n = Math.round(6 + 4 * fire.size);
			for (int i = 0; i < n; i++) {
				Vec3 p = inside(box, 0.9F);
				level.addParticle(FX.nextBoolean() ? ParticleTypes.CLOUD : ParticleTypes.WHITE_SMOKE, p.x, box.maxY, p.z, FX.nextGaussian() * 0.02, 0.06,
						FX.nextGaussian() * 0.02);
			}
		} else {
			level.playLocalSound(c.x, c.y, c.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.2F, 1.4F, false);
		}
	}

	/** Burnt to the bone: the flames die down on a charred skeleton, and grey ash lies under it. */
	private static void burntOut(ClientLevel level, BloodWorld blood, Corpse corpse, Fire fire, AABB box) {
		Vec3 c = box.getCenter();
		level.playLocalSound(c.x, c.y, c.z, SoundEvents.BONE_BLOCK_HIT, SoundSource.NEUTRAL, 0.5F, 0.7F, false);
		ash(level, blood, box, 0.75F);
		Evidence.touched(corpse.entity.getId());
	}

	/** Grey ash on the ground under {@code box}, over the black scorch {@link Burns} leaves. */
	private static void ash(ClientLevel level, BloodWorld blood, AABB box, float spread) {
		Vec3 c = box.getCenter();
		double surface = SurfaceProbe.surfaceBelow(level, c.x, box.minY + 0.5, box.minY - 1.5, c.z);
		if (Double.isNaN(surface)) {
			return;
		}

		float half = (float) Math.max(0.3, Math.max(box.getXsize(), box.getZsize()) * 0.5 * spread);
		blood.decals().addFloor(level, c.x, surface, c.z, half, BloodTextures.splat(FX), FX.nextInt(8), ASH, false, blood.decalLifetime());
	}

	/**
	 * Flames, smoke, fat and sounds, all as big as the body: a burning chicken is a small fire under a wisp of smoke,
	 * a burning cow a roaring one under a tall black column.
	 */
	private static void effects(ClientLevel level, Fire fire, AABB box, GoreConfig config, boolean near) {
		float particles = config.goreLevel.particles;
		float s = fire.size;
		if (fire.lit) {
			// Fiercest while the fat burns, in the middle of it; small at the start and on the bare bones at the end.
			float fat = Mth.sin(Mth.PI * fire.burn);
			float fierce = fire.glow * (0.45F + 0.75F * fat);
			fire.flames += 0.35F * s * fierce * particles;
			while (fire.flames >= 1.0F) {
				fire.flames -= 1.0F;
				Vec3 p = inside(box, 0.85F);
				level.addParticle(FX.nextFloat() < 0.8F ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME, p.x, p.y, p.z, FX.nextGaussian() * 0.008,
						0.015 + FX.nextDouble() * 0.03 * Mth.sqrt(s), FX.nextGaussian() * 0.008);
			}

			// Steam and singed hair first, white; then the fat, thick and black; thin and pale once only bone is left.
			// The heat carries it straight up in a plume off the top of the body, so the body itself stays in sight.
			fire.smoke += 0.045F * s * Mth.sqrt(s) * (0.4F + fierce) * particles;
			while (fire.smoke >= 1.0F) {
				fire.smoke -= 1.0F;
				ParticleOptions smoke = fire.burn < 0.1F ? ParticleTypes.WHITE_SMOKE : ParticleTypes.SMOKE;
				Vec3 p = inside(box, 0.45F);
				level.addParticle(smoke, p.x, box.maxY + 0.2, p.z, FX.nextGaussian() * 0.005, 0.1 + FX.nextDouble() * 0.06, FX.nextGaussian() * 0.005);
			}

			// A big body sends up a column of smoke that can be seen from far off.
			if (FX.nextFloat() < s * 0.02F * fierce * particles) {
				Vec3 p = inside(box, 0.5F);
				level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, p.x, box.maxY, p.z, 0.0, 0.07, 0.0);
			}

			if (fire.burn > 0.12F && fire.burn < 0.75F && FX.nextFloat() < 0.04F * s * fat) {
				// Fat running out of it, dripping into the fire.
				Vec3 p = inside(box, 0.8F);
				level.addParticle(ParticleTypes.FALLING_LAVA, p.x, box.minY + 0.15, p.z, 0.0, 0.0, 0.0);
			}

			if (FX.nextFloat() < 0.015F * s * fierce) {
				Vec3 p = inside(box, 0.6F);
				level.addParticle(ParticleTypes.LAVA, p.x, p.y, p.z, 0.0, 0.0, 0.0);
			}

			if (FX.nextFloat() < 0.15F * fire.burn * Mth.sqrt(s) * particles) {
				Vec3 p = inside(box, 0.9F);
				level.addParticle(fire.burn > 0.7F ? ParticleTypes.WHITE_ASH : ParticleTypes.ASH, p.x, box.maxY, p.z, 0.0, 0.0, 0.0);
			}

			if (near && config.goreSounds) {
				Vec3 c = box.getCenter();
				if (--fire.crackle <= 0) {
					// A bigger fire roars lower and louder.
					fire.crackle = 20 + FX.nextInt(30) - Math.min(15, Math.round(s * 1.5F));
					level.playLocalSound(c.x, c.y, c.z, FX.nextBoolean() ? SoundEvents.FIRE_AMBIENT : SoundEvents.CAMPFIRE_CRACKLE, SoundSource.NEUTRAL,
							Math.min(1.4F, 0.3F + 0.12F * s) * (0.4F + fierce), 1.15F - Math.min(0.4F, 0.03F * s) + FX.nextFloat() * 0.15F, false);
				}

				if (FX.nextFloat() < 0.006F * s * fat) {
					level.playLocalSound(c.x, c.y, c.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.08F + 0.02F * s, 1.2F + FX.nextFloat() * 0.3F, false);
				}

				if (FX.nextFloat() < 0.01F * s * fat) {
					level.playLocalSound(c.x, c.y, c.z, SoundEvents.LAVA_POP, SoundSource.NEUTRAL, 0.3F, 0.9F + FX.nextFloat() * 0.3F, false);
				}

				if (fire.burn > 0.6F && FX.nextFloat() < 0.004F * s) {
					// Bones splitting in the heat.
					level.playLocalSound(c.x, c.y, c.z, SoundEvents.BONE_BLOCK_HIT, SoundSource.NEUTRAL, 0.25F, 1.3F + FX.nextFloat() * 0.3F, false);
				}
			}
		} else if (fire.out < SMOULDER && fire.burn > 0.0F) {
			// Put out or burnt down: it smokes on, less and less.
			float left = 1.0F - fire.out / (float) SMOULDER;
			if (FX.nextFloat() < 0.25F * Mth.sqrt(s) * left * particles) {
				Vec3 p = inside(box, 0.8F);
				level.addParticle(ParticleTypes.SMOKE, p.x, box.maxY - 0.1, p.z, 0.0, 0.03, 0.0);
			}

			if (fire.glow > 0.2F && FX.nextFloat() < 0.03F * fire.glow * Mth.sqrt(s)) {
				Vec3 p = inside(box, 0.8F);
				level.addParticle(ParticleTypes.WHITE_ASH, p.x, box.maxY, p.z, 0.0, 0.0, 0.0);
			}
		}
	}

	/**
	 * A blow on a burnt body. Returns false if it is not burnt enough for that (then it is an ordinary blow). Charred
	 * flesh comes off the bone in black crumbs, flakes and soot; poking one still alight throws sparks and makes it flare.
	 * Bare bone breaks: the limbs, then the skull, then the ribs and spine, and the skeleton is gone.
	 */
	public boolean strike(ClientLevel level, BloodWorld blood, Corpse corpse, Vec3 eye, Vec3 look, Weapon weapon, float damage) {
		LivingEntity entity = corpse.entity;
		Fire fire = fires.get(entity.getId());
		if (fire == null || fire.bare() < CHARRED) {
			return false;
		}

		AABB box = CorpseStrike.box(corpse);
		Vec3 at = box.clip(eye, eye.add(look.scale(8.0))).orElse(box.getCenter());
		boolean blunt = !weapon.cuts();
		if (fire.bare() < BONE) {
			// A big body takes more blows to clear than a small one.
			float step = (0.25F + 0.04F * Math.min(damage, 10.0F)) * (blunt ? 1.3F : 0.9F) / Mth.sqrt(fire.size);
			fire.crumble = Math.min(1.0F, Math.max(fire.crumble, fire.burn) + step);
			crumble(level, entity, fire, at, look);
		} else {
			fire.blows++;
			int per = Math.max(1, Math.round(Mth.sqrt(fire.size) / 1.5F));
			if (fire.blows % per == 0) {
				breakBone(level, blood, corpse, fire, box, at, look);
			} else {
				BoneEmitter.burnt(level, at, look, 3, 4, 0.12, fire.bone);
				level.playLocalSound(at.x, at.y, at.z, SoundEvents.BONE_BLOCK_HIT, SoundSource.PLAYERS, 0.7F, 0.9F + RANDOM.nextFloat() * 0.2F, false);
			}
		}

		corpse.keepFor(60);
		Evidence.touched(entity.getId());
		return true;
	}

	/** Charred flesh knocked off the bone. */
	private static void crumble(ClientLevel level, LivingEntity entity, Fire fire, Vec3 at, Vec3 look) {
		float particles = GoreConfig.get().goreLevel.particles;
		Vec3 away = new Vec3(-look.x, 0.3, -look.z).normalize();
		int n = Math.round((10 + 3 * fire.size) * particles);
		for (int i = 0; i < n; i++) {
			level.addParticle(RANDOM.nextFloat() < 0.6F ? CHAR : CINDER, at.x, at.y, at.z, away.x * 0.1 + RANDOM.nextGaussian() * 0.08,
					0.05 + RANDOM.nextDouble() * 0.15, away.z * 0.1 + RANDOM.nextGaussian() * 0.08);
			if (i % 3 == 0) {
				level.addParticle(SOOT, at.x + RANDOM.nextGaussian() * 0.2, at.y + RANDOM.nextDouble() * 0.3, at.z + RANDOM.nextGaussian() * 0.2, 0.0, 0.0, 0.0);
			}
		}

		for (int i = 0; i < 4; i++) {
			level.addParticle(ParticleTypes.ASH, at.x, at.y, at.z, 0.0, 0.0, 0.0);
		}

		// Lumps of it: burnt meat, black right through.
		Giblets giblets = GoreSystems.dismemberment().giblets();
		int before = giblets.count();
		giblets.chips(level, entity, at, away, Math.round(2 + fire.size * 0.5F), 0.6F);
		giblets.searFrom(before, 1.0F);
		if (fire.lit) {
			// Raking a fire: sparks fly and it flares up.
			for (int i = 0; i < 8; i++) {
				level.addParticle(ParticleTypes.LAVA, at.x, at.y, at.z, 0.0, 0.0, 0.0);
				level.addParticle(ParticleTypes.FLAME, at.x, at.y, at.z, RANDOM.nextGaussian() * 0.04, 0.05 + RANDOM.nextDouble() * 0.05, RANDOM.nextGaussian() * 0.04);
			}

			level.addParticle(ParticleTypes.SMOKE, at.x, at.y + 0.2, at.z, 0.0, 0.08, 0.0);
		}

		if (GoreConfig.get().goreSounds) {
			SoundEvent crunch = RANDOM.nextBoolean() ? SoundEvents.BASALT_BREAK : SoundEvents.GRAVEL_HIT;
			level.playLocalSound(at.x, at.y, at.z, crunch, SoundSource.PLAYERS, 0.8F, 0.7F + RANDOM.nextFloat() * 0.3F, false);
		}
	}

	/** A bone breaks off a burnt skeleton; the last blow brings what is left down in a heap of bone and ash. */
	private static void breakBone(ClientLevel level, BloodWorld blood, Corpse corpse, Fire fire, AABB box, Vec3 at, Vec3 look) {
		int id = corpse.entity.getId();
		int missing = GoreSystems.dismemberment().missingMask(id);
		for (BodyPart part : BONES) {
			if ((missing & part.bit()) == 0) {
				GoreSystems.dismemberment().restoreMissing(id, part.bit());
				BoneEmitter.burnt(level, at, look, Math.round(10 + fire.size * 2), Math.round(6 + fire.size * 2), 0.18, fire.bone);

				level.playLocalSound(at.x, at.y, at.z, SoundEvents.BONE_BLOCK_BREAK, SoundSource.PLAYERS, 1.0F, 0.8F + RANDOM.nextFloat() * 0.3F, false);
				return;
			}
		}

		// The ribs and spine: the skeleton falls in on itself, and it is gone.
		Vec3 c = box.getCenter();
		float size = Math.min(3.0F, 0.6F + fire.size * 0.3F);
		BoneEmitter.burnt(level, c, null, Math.round(14 * size), Math.round(24 * size), 0.3, fire.bone);
		level.playLocalSound(c.x, c.y, c.z, SoundEvents.BONE_BLOCK_BREAK, SoundSource.PLAYERS, 1.0F, 0.55F, false);
		// Ash the colour of the bones it came from: black and grey off a charred skeleton, pale only off a calcined one.
		DustParticleOptions boneAsh = new DustParticleOptions(BurntBone.color(fire.bone), 0.5F);
		ParticleOptions flakes = fire.bone >= BurntBone.GREY ? ParticleTypes.WHITE_ASH : ParticleTypes.ASH;
		int n = Math.round(20 + fire.size * 6);
		for (int i = 0; i < n; i++) {
			Vec3 p = inside(box, 0.9F);
			level.addParticle(i % 2 == 0 ? flakes : boneAsh, p.x, p.y, p.z, RANDOM.nextGaussian() * 0.03, 0.02 + RANDOM.nextDouble() * 0.04,
					RANDOM.nextGaussian() * 0.03);
		}

		level.addParticle(ParticleTypes.POOF, c.x, c.y, c.z, 0.0, 0.02, 0.0);
		level.playLocalSound(c.x, c.y, c.z, SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 1.0F, 0.7F, false);
		if (!fire.spent) {
			ash(level, blood, box, 0.6F);
		}

		// Nothing left: not drawn any more, and the server is asked to take the body (see GoreSync.tick).
		GoreSystems.dismemberment().hide(id);
	}

	/** A random point in the lower part of {@code box}, {@code spread} of the way out from the middle. */
	private static Vec3 inside(AABB box, float spread) {
		Vec3 c = box.getCenter();
		return new Vec3(c.x + (FX.nextDouble() - 0.5) * box.getXsize() * spread, box.minY + FX.nextDouble() * box.getYsize() * 0.7,
				c.z + (FX.nextDouble() - 0.5) * box.getZsize() * spread);
	}

	/** Writes how burnt a body is, for evidence (see {@code net/Evidence}). */
	public void write(int entityId, ByteBuf buf) {
		Fire fire = fires.get(entityId);
		buf.writeFloat(fire == null ? 0.0F : fire.burn);
		buf.writeFloat(fire == null ? 0.0F : fire.crumble);
		buf.writeShort(fire == null ? 0 : Math.min(fire.blows, Short.MAX_VALUE));
		buf.writeFloat(fire == null ? 0.0F : fire.bone);
	}

	/** Reads what {@link #write} wrote and puts it on a body this client did not see burn. */
	public void read(LivingEntity entity, ByteBuf buf) {
		float burn = buf.readFloat();
		float crumble = buf.readFloat();
		int blows = buf.readShort();
		// A game from before bone heat sent none: the flesh burning off charred them, no more.
		float bone = buf.readableBytes() >= 4 ? buf.readFloat() : BurntBone.ownFire(burn);
		if (!(bone >= 0.0F && bone <= 1.0F)) {
			bone = BurntBone.ownFire(burn);
		}

		if (!(burn >= 0.0F && burn <= 1.0F) || !(crumble >= 0.0F && crumble <= 1.0F) || blows < 0 || burn + crumble <= 0.0F
				|| fires.containsKey(entity.getId())) {
			return;
		}

		Fire fire = new Fire(entity);
		fire.burn = burn;
		fire.crumble = crumble;
		fire.blows = blows;
		fire.bone = Math.max(bone, BurntBone.ownFire(burn));
		fire.spent = burn >= 1.0F;
		// Out when it was pictured: it stays out until fire is put to it again.
		fire.doused = true;
		fires.put(entity.getId(), fire);
		GoreSystems.dismemberment().burns().restore(entity.getId(), Math.min(1.0F, 0.05F + burn * 5.0F));
	}

	/** The body has a new id (it lies on after its player respawned under the old one). */
	public void rekey(int from, int to) {
		Fire fire = fires.remove(from);
		if (fire != null) {
			fires.put(to, fire);
		}
	}

	public void forget(int entityId) {
		fires.remove(entityId);
	}

	public void clear() {
		fires.clear();
	}

	static float ramp(float x, float from, float to) {
		float t = Mth.clamp((x - from) / (to - from), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}
}
