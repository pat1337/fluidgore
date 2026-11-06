package dev.fluidgore.rot;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.blood.BloodTextures;
import dev.fluidgore.blood.BloodWorld;
import dev.fluidgore.blood.Decal;
import dev.fluidgore.blood.SurfaceProbe;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.config.GoreLevel;
import dev.fluidgore.dismember.GorePart;
import dev.fluidgore.net.BodyRot;
import dev.fluidgore.suffer.Corpse;
import dev.fluidgore.trigger.EntityFilter;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Bodies rot (Extreme gore level, {@code bodyDecay}). A body that stays (a kept body, see {@code server/KeptBodies}, or
 * a severed piece) goes through the stages in {@link Rot} at the pace of a real carcass, one Minecraft day for a day.
 *
 * <p>The pace is counted the way forensic science counts it, in degree-days: a day at 20 degrees is one decay-day,
 * a day at 10 is half of one, and below freezing nothing happens. A biome's temperature is taken as degrees Celsius by
 * {@code 25 * t - 5} (plains 15, jungle 19, desert 45, snowy plains -5). In water a body rots half as fast (Casper's
 * law). A bigger body takes longer: the days are divided by the square root of its volume against a pig's, so with
 * the stages in {@link Rot}, in the plains a chicken is bones in about ten days, a pig in four weeks, a cow in five and
 * a horse in eight. In dry heat (no rain and over 20 degrees: deserts, badlands, savanna, the Nether) it mummifies.
 *
 * <p>The decay-days are counted up as time goes ({@link RotClock}), never worked out again from the body's age: each
 * stretch of time adds days at the heat and wet of where the body lay through it, so a carcass carried from the
 * plains into the snow stops where it got to and does not go back to fresh. Time is the game clock or the day clock,
 * whichever has gone further, so it keeps rotting while players sleep or skip time. Each body is measured where it
 * lies every five seconds and before a skip is counted, and the time since is counted at that.
 *
 * <p>Time this client did not see is an approximation. A body the server keeps comes with the server's own count
 * ({@link BodyRot}, see {@code server/KeptBodies.rotted}), which is right for where the body really lay. With no word
 * from the server (it lacks the mod, or the word has not come yet) a body that comes into view dead is taken to have
 * lain where it lies now since its death clock started, which for a body that vanilla removes is a second at most.
 * A body seen again after Bodies Rot was switched off and on counts the gap at where it lies then. Everything a body
 * looks like follows from its count and its UUID, so every player with the mod sees the same rot; only the flies and
 * drips are this screen's own. Rot Speed is each player's own: it speeds what they watch, and a body they come to is
 * the server's real-time count times it.
 */
public final class Decay {
	/** More time than this between two ticks is a skip, counted only after measuring where the body lies. */
	private static final long SKIP_TICKS = 20;
	/** Flies, drips and sounds only near the camera. */
	private static final double EFFECTS_SQ = 32.0 * 32.0;
	private static final double SOUND_SQ = 14.0 * 14.0;
	private static final DustParticleOptions FLY = new DustParticleOptions(0x0E0E0C, 0.45F);
	private static final DustParticleOptions BLUEBOTTLE = new DustParticleOptions(0x1E3448, 0.4F);
	private static final BlockParticleOption MAGGOT = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.BONE_BLOCK.defaultBlockState());
	private static final BlockParticleOption OOZE = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.MUD.defaultBlockState());
	private static final DustParticleOptions SPRAY = new DustParticleOptions(0x3A2A14, 1.2F);
	private static final ColorParticleOption STENCH = ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0x60707A3A);
	/** The pool of decomposition fluid: black-brown, and it does not dry like blood. */
	private static final int STAIN = 0x22180E;
	private static final RandomSource RANDOM = RandomSource.create();

	private static final class Body {
		final Rot rot;
		/** Its rot counted so far, at this player's Rot Speed; {@link #rot} shows it. */
		RotClock count;
		/** Decay-days per real day here (heat, water), dry heat here, and the body's size factor, measured now and then. */
		float rate = -1.0F;
		boolean aridHere;
		float size = 1.0F;
		/** Days last tick, to catch the moment it bursts; -1 before the first. */
		float last = -1.0F;
		int seen;
		@Nullable Decal stain;
		float stainHalf;
		int nextBuzz;

		Body(long seed, RotClock count) {
			this.rot = new Rot(seed);
			this.count = count;
		}

		void show() {
			rot.days = count.days();
			rot.arid = count.arid();
		}
	}

	private final Int2ObjectOpenHashMap<Body> bodies = new Int2ObjectOpenHashMap<>();
	/**
	 * Counts for bodies not being rotted here yet, at this player's Rot Speed: the server's word, or what a body had when
	 * Bodies Rot was switched off.
	 */
	private final Int2ObjectOpenHashMap<RotClock> told = new Int2ObjectOpenHashMap<>();
	private int tick;

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(BodyRot.TYPE, (rot, context) -> GoreSystems.decay().told(rot.entityId(), rot.days(), rot.arid()));
	}

	/** On: Extreme gore and Bodies Rot. */
	public static boolean active() {
		GoreConfig config = GoreConfig.get();
		return config.bodyDecay && config.goreLevel == GoreLevel.EXTREME;
	}

	/** The server says this body has rotted {@code days} decay-days (at 1x), and whether it is drying into a mummy. */
	void told(int entityId, float days, boolean arid) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || !Float.isFinite(days)) {
			return;
		}

		RotClock count = new RotClock(Math.max(0.0F, days) * GoreConfig.get().decaySpeed, arid, level.getGameTime(), level.getOverworldClockTime());
		Body body = bodies.get(entityId);
		if (body == null) {
			told.put(entityId, count);
			return;
		}

		// Already rotting here from the entity's death clock: the server knows better, but rot never goes back.
		if (count.days() > body.count.days()) {
			body.count = count;
			body.show();
		}

		// Seen at this age for the first time: no burst to play.
		body.last = -1.0F;
	}

	/** How far {@code entityId} has rotted, or null if it is not rotting (alive, or decay is off). */
	public @Nullable Rot of(int entityId) {
		Body body = bodies.get(entityId);
		return body == null || body.rate < 0.0F ? null : body.rot;
	}

	/** Decay-days of a body, for the pieces cut off it: 0 if it is not rotting. */
	public float days(int entityId) {
		Rot rot = of(entityId);
		return rot == null ? 0.0F : rot.days;
	}

	public void tick(ClientLevel level, BloodWorld blood) {
		if (!active()) {
			// What the server told stays, for when it is switched back on.
			stop();
			return;
		}

		tick++;
		GoreConfig config = GoreConfig.get();
		Entity camera = Minecraft.getInstance().getCameraEntity();
		long game = level.getGameTime();
		long clock = level.getOverworldClockTime();
		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof LivingEntity entity) || entity.isAlive() || entity instanceof Player || entity.isRemoved()
					|| EntityFilter.isBone(entity) || !EntityFilter.affects(entity)
					// Burnt to char and bone: nothing left to rot.
					|| GoreSystems.dismemberment().cremation().bare(entity.getId()) > 0.3F) {
				continue;
			}

			Body body = bodies.get(entity.getId());
			if (body == null) {
				// Died in front of this player, or came into view dead: the server's count if it has given it, else the
				// entity's own death clock (ticks since this client saw it die or saw it at all), counted below at
				// where it lies now.
				RotClock count = told.remove(entity.getId());
				body = new Body(entity.getUUID().getLeastSignificantBits() ^ entity.getUUID().getMostSignificantBits(),
						count != null ? count : RotClock.died(game - entity.deathTime, clock - entity.deathTime));
				bodies.put(entity.getId(), body);
			}

			body.seen = tick;
			// Measured where it lies every five seconds, and again before a skip (sleep, /time add) is counted, so the
			// skip goes at where it lies through it, as the server counts it.
			long since = Math.max(game - body.count.game(), clock - body.count.clock());
			if (body.rate < 0.0F || since > SKIP_TICKS || (tick + entity.getId()) % 100 == 0) {
				BlockPos at = BlockPos.containing(where(entity));
				body.rate = RotClock.rate(level, at, entity.isInWater());
				body.aridHere = RotClock.arid(level, at, entity.isInWater());
				body.size = RotClock.size(entity.getBbWidth() * entity.getBbWidth() * entity.getBbHeight());
			}

			body.count = body.count.to(game, clock, body.rate * config.decaySpeed, body.size, body.aridHere);
			body.show();
			if (camera != null && camera.distanceToSqr(entity) < EFFECTS_SQ) {
				effects(level, blood, entity, body, camera);
			}

			body.last = body.rot.days;
		}

		bodies.int2ObjectEntrySet().removeIf(entry -> {
			if (entry.getValue().seen == tick) {
				return false;
			}

			removeStain(blood, entry.getValue());
			return true;
		});

		if (tick % 20 == 0) {
			ageParts(level, config);
		}
	}

	/**
	 * Severed pieces rot by their own size, where they lie, from the rot of the body they came off: counted up a second
	 * at a time like a body ({@link RotClock}), and mummifying or not settled the same way.
	 */
	private void ageParts(ClientLevel level, GoreConfig config) {
		for (GorePart part : GoreSystems.dismemberment().parts().parts()) {
			if (part.bone() || part.held) {
				continue;
			}

			BlockPos pos = BlockPos.containing(part.x, part.y, part.z);
			boolean wet = !level.getFluidState(pos).isEmpty();
			float volume = 8.0F * part.halfExtents.x * part.halfExtents.y * part.halfExtents.z;
			part.arid = RotClock.arid(part.arid, part.rot, RotClock.arid(level, pos, wet));
			part.rot += 20.0F / RotClock.DAY * RotClock.rate(level, pos, wet) * config.decaySpeed / RotClock.size(volume);
		}
	}

	private void effects(ClientLevel level, BloodWorld blood, LivingEntity entity, Body body, Entity camera) {
		Rot rot = body.rot;
		Vec3 centre = where(entity);
		float width = Math.max(0.3F, entity.getBbWidth());
		boolean sounds = GoreConfig.get().goreSounds;
		boolean near = camera.distanceToSqr(entity) < SOUND_SQ;

		// Flies sit still in the dark.
		float flies = level.isDarkOutside() ? rot.flies() * 0.15F : rot.flies();
		if (flies > 0.0F && RANDOM.nextFloat() < flies * Math.min(2.0F, body.size) * 0.8F) {
			// Blowflies: black and metallic blue specks darting about over it.
			level.addParticle(RANDOM.nextFloat() < 0.3F ? BLUEBOTTLE : FLY, centre.x + (RANDOM.nextDouble() - 0.5) * width * 1.4,
					centre.y + RANDOM.nextDouble() * entity.getBbHeight() * 0.6, centre.z + (RANDOM.nextDouble() - 0.5) * width * 1.4,
					(RANDOM.nextDouble() - 0.5) * 2.0, RANDOM.nextDouble(), (RANDOM.nextDouble() - 0.5) * 2.0);
		}

		if (sounds && near && flies > 0.15F && tick >= body.nextBuzz) {
			body.nextBuzz = tick + 50 + RANDOM.nextInt(60);
			level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.BEE_LOOP, SoundSource.NEUTRAL, 0.04F + 0.14F * flies,
					1.45F + RANDOM.nextFloat() * 0.3F, false);
		}

		float maggots = rot.maggots();
		if (maggots > 0.0F && RANDOM.nextFloat() < maggots * 0.25F * body.size) {
			// Maggots spilling off it.
			level.addParticle(MAGGOT, centre.x + (RANDOM.nextDouble() - 0.5) * width, centre.y + RANDOM.nextDouble() * 0.3,
					centre.z + (RANDOM.nextDouble() - 0.5) * width, 0.0, 0.0, 0.0);
		}

		float ooze = rot.ooze();
		if (ooze > 0.0F && RANDOM.nextFloat() < ooze * 0.08F) {
			level.addParticle(OOZE, centre.x + (RANDOM.nextDouble() - 0.5) * width, centre.y, centre.z + (RANDOM.nextDouble() - 0.5) * width,
					0.0, 0.0, 0.0);
		}

		float stench = rot.stench();
		if (stench > 0.0F && RANDOM.nextFloat() < stench * 0.04F) {
			level.addParticle(STENCH, centre.x + (RANDOM.nextDouble() - 0.5) * width, centre.y + entity.getBbHeight() * 0.3,
					centre.z + (RANDOM.nextDouble() - 0.5) * width, 0.0, 0.0, 0.0);
		}

		float swell = rot.swell();
		if (sounds && near && swell > 0.3F && !rot.burst() && RANDOM.nextFloat() < 0.003F) {
			// Gas moving in the gut.
			level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.SLIME_SQUISH_SMALL, SoundSource.NEUTRAL, 0.25F, 0.45F + RANDOM.nextFloat() * 0.2F, false);
		}

		if (body.last >= 0.0F && body.last < Rot.ACTIVE && rot.days >= Rot.ACTIVE && !rot.arid) {
			burst(level, entity, centre, width, sounds);
		}

		stain(level, blood, entity, body, centre);
	}

	/** The bloated body splits: a wet rip, the gas out, the inside sprayed about. */
	private static void burst(ClientLevel level, LivingEntity entity, Vec3 centre, float width, boolean sounds) {
		if (sounds) {
			level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.HONEY_BLOCK_BREAK, SoundSource.NEUTRAL, 0.9F, 0.5F, false);
			level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.SLIME_SQUISH, SoundSource.NEUTRAL, 0.8F, 0.45F, false);
			level.playLocalSound(centre.x, centre.y, centre.z, SoundEvents.MUD_BREAK, SoundSource.NEUTRAL, 0.7F, 0.6F, false);
		}

		int count = Math.round(20 + 30 * width);
		for (int i = 0; i < count; i++) {
			level.addParticle(SPRAY, centre.x, centre.y + entity.getBbHeight() * 0.25, centre.z, (RANDOM.nextDouble() - 0.5) * 4.0,
					RANDOM.nextDouble() * 2.0, (RANDOM.nextDouble() - 0.5) * 4.0);
			if (i % 3 == 0) {
				level.addParticle(MAGGOT, centre.x + (RANDOM.nextDouble() - 0.5) * width, centre.y + 0.3, centre.z + (RANDOM.nextDouble() - 0.5) * width,
						0.0, 0.0, 0.0);
			}
		}
	}

	/** The pool of fluid it lies in, kept the size it has reached (and put back if the blood cap took it). */
	private void stain(ClientLevel level, BloodWorld blood, LivingEntity entity, Body body, Vec3 centre) {
		float stain = body.rot.stain();
		if (stain <= 0.0F || entity.isInWater() || (tick + entity.getId()) % 40 != 0) {
			return;
		}

		float half = entity.getBbWidth() * (0.5F + 0.8F * stain);
		boolean gone = body.stain == null || !blood.decals().decals().contains(body.stain);
		if (!gone && half < body.stainHalf + 0.1F) {
			return;
		}

		double surface = SurfaceProbe.surfaceBelow(level, centre.x, entity.getY() + 0.5, entity.getY() - 1.5, centre.z);
		if (Double.isNaN(surface)) {
			return;
		}

		removeStain(blood, body);
		body.stain = blood.decals().addFloor(level, centre.x, surface, centre.z, half, BloodTextures.POOL, (int) (body.rot.seed & 7), STAIN, false,
				Integer.MAX_VALUE / 2);
		body.stainHalf = half;
	}

	private static void removeStain(BloodWorld blood, Body body) {
		if (body.stain != null) {
			blood.decals().decals().remove(body.stain);
			body.stain = null;
		}
	}

	/** The middle of the body, where the ragdoll has it if it is a corpse. */
	private static Vec3 where(LivingEntity entity) {
		Corpse corpse = GoreSystems.suffering().corpse(entity.getId());
		Vec3 feet = corpse != null ? corpse.position() : entity.position();
		return feet.add(0.0, Math.max(0.15, entity.getBbWidth() * 0.3), 0.0);
	}

	public void forget(int entityId) {
		told.remove(entityId);
	}

	private void stop() {
		if (bodies.isEmpty()) {
			return;
		}

		BloodWorld blood = GoreSystems.blood();
		for (Int2ObjectMap.Entry<Body> entry : bodies.int2ObjectEntrySet()) {
			// It rots on from the count it has when switched back on.
			told.put(entry.getIntKey(), entry.getValue().count);
			removeStain(blood, entry.getValue());
		}

		bodies.clear();
	}

	public void clear() {
		stop();
		told.clear();
	}
}
