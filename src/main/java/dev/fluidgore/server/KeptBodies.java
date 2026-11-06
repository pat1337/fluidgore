package dev.fluidgore.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.fluidgore.FluidGore;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.net.BodyRot;
import dev.fluidgore.net.GoreAction;
import dev.fluidgore.net.PieceState;
import dev.fluidgore.physics.BodyFootprint;
import dev.fluidgore.physics.BodySlide;
import dev.fluidgore.physics.Buoyancy;
import dev.fluidgore.rot.RotClock;
import dev.fluidgore.trigger.EntityFilter;
import dev.fluidgore.wound.Weapon;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The server half of Fluid Gore, and the only part of it that is not cosmetic. It does two things:
 * <ol>
 * <li><b>Keeps bodies.</b> Vanilla removes a dead mob one second after it dies, in a puff of smoke. A slaughtered
 *     animal (one of {@code sufferingAnimals}) is kept instead: still a dead entity, lying where it fell, saved with
 *     the world, until a player cleans it away or butchers it apart. Its drops and experience came out when it died,
 *     as in vanilla; nothing can be done with it but gore (interactions with a dead body are refused).</li>
 * <li><b>Passes on what players do to bodies</b> ({@link GoreAction}) to the other players nearby, so they all see
 *     the same butchery. Likewise where loose pieces are moved to ({@link PieceState}). The server only checks the player is close enough; the gore is worked out on each client.</li>
 * </ol>
 * Players without the mod see a kept body lying on its side, tinted red as vanilla tints the dying.
 */
public final class KeptBodies {
	/** How far from a body a player can be and still act on it (reach plus lag). */
	private static final double REACH_SQ = 10.0 * 10.0;
	/** How far from a body a player can be and still report it gone (torn apart by a blast they watched). */
	private static final double GONE_RANGE_SQ = 64.0 * 64.0;
	/** Players this close to a body are shown what is done to it. */
	private static final double SHOW_RANGE = 96.0;
	/** Vanilla's tip-over takes 20 ticks; the death clock of a kept body stops a little after. */
	private static final int DEATH_CLOCK_MAX = 40;
	/** Death ticks before a body lies on its side (vanilla's tip-over), and may slide down a drop. */
	private static final int TIPPED = 20;
	/** Vanilla's gravity and friction (ground: a plain block's 0.6 times 0.91), for a body vanilla does not move. */
	private static final double GRAVITY = 0.08;
	private static final double GROUND_FRICTION = 0.546;
	private static final double AIR_FRICTION = 0.91;
	/** Vanilla's hold of water on a mob, per tick. */
	private static final double WATER_DRAG = 0.8;
	/** Bounding-box volume of an adult cow, the reference body for {@link #heft}. */
	private static final float COW_VOLUME = 0.9F * 0.9F * 1.4F;
	/** Bodies overlapping are eased apart by this share of the overlap a tick, at most {@link #SPREAD_MAX} blocks. */
	private static final double SPREAD_RATE = 0.25;
	private static final double SPREAD_MAX = 0.06;
	/** Overlap, in blocks, bodies may lie into each other by: pressed together, not through each other. */
	private static final double SPREAD_SLACK = 0.05;
	/**
	 * When a kept body died: the game time and the overworld's day clock then, saved with it. Its rot ({@link #ROTTED})
	 * is counted from here.
	 */
	public static final AttachmentType<Died> DIED = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "died"),
			builder -> builder.persistent(Died.CODEC));
	/**
	 * How far a kept body has rotted, saved with it and counted on by {@link #rotted}, so it goes on rotting while
	 * nobody is near and while players sleep, at the pace of wherever it lay (see {@code rot/RotClock}).
	 */
	public static final AttachmentType<RotClock> ROTTED = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "rotted"),
			builder -> builder.persistent(RotClock.CODEC));
	/** How often a kept body's rot is counted on while its chunk is loaded: five seconds is a 0.2% slice of a day. */
	private static final int ROT_COUNT_TICKS = 100;

	public record Died(long game, long clock) {
		static final Codec<Died> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.LONG.fieldOf("game").forGetter(Died::game),
				Codec.LONG.fieldOf("clock").forGetter(Died::clock)).apply(i, Died::new));
	}

	/** Per dimension, oldest first. Entries whose chunk unloaded are dropped when the next body is added. */
	private static final Map<ServerLevel, LinkedHashSet<LivingEntity>> BODIES = new WeakHashMap<>();
	private static final Set<Identifier> TYPES = new HashSet<>();
	private static List<String> cachedSource;
	/** A blow, pluck or cut per click at most, and a burst of clean-ups when a blast takes many bodies at once. */
	private static final Throttle ACTIONS = new Throttle(64, 20);
	/** Every moving piece streams ten states a second; twenty pieces of a diced carcass at once is plenty. */
	private static final Throttle POSES = new Throttle(400, 200);

	private KeptBodies() {
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(GoreAction.TYPE, GoreAction.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GoreAction.TYPE, GoreAction.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PieceState.TYPE, PieceState.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PieceState.TYPE, PieceState.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BodyRot.TYPE, BodyRot.CODEC);
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			if (entity instanceof LivingEntity body && entity.level() instanceof ServerLevel level && ServerPlayNetworking.canSend(player, BodyRot.TYPE)) {
				RotClock rot = rotted(level, body);
				if (rot != null) {
					ServerPlayNetworking.send(player, new BodyRot(entity.getId(), rot.days(), rot.arid()));
				}
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(GoreAction.TYPE, (action, context) -> receive(context.player(), action));
		ServerPlayNetworking.registerGlobalReceiver(PieceState.TYPE, (state, context) -> relay(context.player(), state));
		// A dead body is meat, not an animal: no milking, shearing, feeding, leashing or riding it.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				entity instanceof LivingEntity living && !(entity instanceof Player) && living.isDeadOrDying() ? InteractionResult.FAIL : InteractionResult.PASS);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			BODIES.clear();
			ACTIONS.clear();
			POSES.clear();
		});
	}

	/**
	 * True if {@code entity} is a body the server is keeping right now. Only these can be cleaned away by a player, and
	 * they do not count against the mob cap (see {@code NaturalSpawnerMixin}): a lying carcass is not an animal.
	 */
	public static boolean isKept(Entity entity) {
		if (!(entity instanceof LivingEntity body) || body instanceof Player || !body.isDeadOrDying() || !(body.level() instanceof ServerLevel level)) {
			return false;
		}

		LinkedHashSet<LivingEntity> bodies = BODIES.get(level);
		return bodies != null && bodies.contains(body);
	}

	/**
	 * Called every tick of a dead entity's death on the server, instead of vanilla's. Returns true if the body is kept
	 * (vanilla's removal is then skipped), false to let it vanish as usual.
	 */
	public static boolean keep(LivingEntity body) {
		GoreConfig config = GoreConfig.get();
		if (!config.keepBodies || body instanceof Player || !(body.level() instanceof ServerLevel level) || !kept(body.getType())) {
			return false;
		}

		body.deathTime = Math.min(body.deathTime + 1, DEATH_CLOCK_MAX);
		LinkedHashSet<LivingEntity> bodies = BODIES.computeIfAbsent(level, l -> new LinkedHashSet<>());
		if (bodies.add(body)) {
			if (!body.hasAttached(DIED)) {
				body.setAttached(DIED, new Died(level.getGameTime() - body.deathTime, level.getOverworldClockTime() - body.deathTime));
			}

			BurningBodies.keptBurning(body);
			// Whoever rode it falls off, as they would if it were gone.
			body.ejectPassengers();
			body.stopRiding();
			bodies.removeIf(Entity::isRemoved);
			while (bodies.size() > config.maxKeptBodies) {
				LivingEntity oldest = bodies.removeFirst();
				vanish(oldest);
			}
		}

		if ((level.getGameTime() + body.getId()) % ROT_COUNT_TICKS == 0) {
			rotted(level, body);
		}

		// A carcass is no wall: blocks can be placed where it lies (it is pushed up out of them, below).
		body.blocksBuilding = false;
		unstick(level, body);
		// Lying over a drop it slumps down it, and players walking into it shove it along. Vanilla's own movement
		// (gravity, friction) carries it from there, and every client is sent where it goes.
		Vec3 push = shoved(level, body);
		if (body.deathTime >= TIPPED && body.onGround()) {
			push = push.add(spread(level, body, bodies));
		}

		if (config.bodySlide && body.deathTime >= TIPPED && body.onGround()) {
			push = push.add(BodySlide.pull(level, body.getBoundingBox()));
		}

		// In water it sinks while fresh and comes up as it bloats, as the clients' ragdolls do (Buoyancy). Lying down, it
		// is as tall as it is wide.
		AABB box = body.getBoundingBox();
		double tall = body.deathTime >= TIPPED ? Math.min(box.getYsize(), box.getXsize()) : box.getYsize();
		float wet = Buoyancy.submerged(level, body.getX(), box.minY, box.minY + tall, body.getZ());
		float lift = 0.0F;
		if (wet > 0.0F) {
			RotClock rot = body.getAttached(ROTTED);
			lift = Buoyancy.lift(rot == null ? 0.0F : RotClock.swell(rot.days(), rot.arid()));
		}

		if (body instanceof Mob mob && mob.isNoAi()) {
			// Vanilla never moves a mob without AI, not even to fall: a dead weight falls all the same.
			Vec3 v = body.getDeltaMovement().add(push).add(0.0, -GRAVITY * (1.0F - lift * wet), 0.0);
			body.move(MoverType.SELF, v);
			if (wet > 0.0F) {
				body.setDeltaMovement(v.scale(WATER_DRAG));
			} else {
				double friction = body.onGround() ? GROUND_FRICTION : AIR_FRICTION;
				body.setDeltaMovement(v.x * friction, body.onGround() ? 0.0 : v.y * 0.98, v.z * friction);
			}
		} else if (wet > 0.0F) {
			// Vanilla moves it through the water, sinking a sixteenth of its weight: this is the rest, less the lift.
			body.setDeltaMovement(body.getDeltaMovement().add(push).add(0.0, GRAVITY / 16.0 - GRAVITY * (1.0F - lift * wet), 0.0));
		} else if (push != Vec3.ZERO) {
			body.setDeltaMovement(body.getDeltaMovement().add(push));
		}

		return true;
	}

	/**
	 * Counts a kept body's rot on to now, where it lies, and saves it: every {@link #ROT_COUNT_TICKS} while its chunk
	 * is loaded, and for each player who starts seeing it. The time it lay unloaded (nobody near, or the server off) is
	 * counted all at once when it is next counted, at the heat and wet of where it lies then. That is where it lay all
	 * along, as a body does not move while it is unloaded; only a biome changed under it in the meantime is missed. A
	 * body kept by an older Fluid Gore, with no count yet, has its whole age counted the same way. Null for anything
	 * that is not a kept body.
	 */
	static @Nullable RotClock rotted(ServerLevel level, LivingEntity body) {
		RotClock rot = body.getAttached(ROTTED);
		if (rot == null) {
			Died died = body.getAttached(DIED);
			if (died == null) {
				return null;
			}

			rot = RotClock.died(died.game(), died.clock());
		}

		// As the clients measure it (rot/Decay): a little above its feet, in water or not.
		BlockPos pos = BlockPos.containing(body.getX(), body.getY() + Math.max(0.15, body.getBbWidth() * 0.3), body.getZ());
		boolean wet = body.isInWater();
		float width = body.getBbWidth();
		rot = rot.to(level.getGameTime(), level.getOverworldClockTime(), RotClock.rate(level, pos, wet),
				RotClock.size(width * width * body.getBbHeight()), RotClock.arid(level, pos, wet));
		body.setAttached(ROTTED, rot);
		return rot;
	}

	/** How readily a body moves, from its size: 1 for a cow, more for a chicken, less for a horse (as the clients' ragdoll). */
	private static double heft(LivingEntity body) {
		float width = body.getBbWidth();
		float volume = width * width * body.getBbHeight();
		return Mth.clamp(1.0F / Mth.sqrt(Math.max(0.01F, volume) / COW_VOLUME), 0.55F, 1.8F);
	}

	/**
	 * Bodies lying together rest side by side instead of through each other (vanilla never pushes the dead): each is
	 * eased out of the kept bodies it overlaps, a little each tick. The push this tick.
	 */
	private static Vec3 spread(ServerLevel level, LivingEntity body, LinkedHashSet<LivingEntity> bodies) {
		BodyFootprint mine = lying(body);
		Vec3 push = Vec3.ZERO;
		for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, body.getBoundingBox().inflate(1.5),
				e -> e != body && e.isDeadOrDying() && bodies.contains(e) && e.deathTime >= TIPPED)) {
			if (Math.abs(body.getY() - other.getY()) > 0.6) {
				continue;
			}

			double[] out = mine.into(lying(other));
			double depth = out[2] - SPREAD_SLACK;
			if (depth <= 0.0) {
				continue;
			}

			double ox = out[0];
			double oz = out[1];
			if (Math.abs(body.getX() - other.getX()) + Math.abs(body.getZ() - other.getZ()) < 1.0E-6) {
				// Lying exactly together: the lower id goes one way, the other the other.
				double way = body.getId() < other.getId() ? 1.0 : -1.0;
				ox *= way;
				oz *= way;
			}

			double k = Math.min(SPREAD_MAX, depth * SPREAD_RATE) * heft(body);
			push = push.add(ox * k, 0.0, oz * k);
		}

		return push;
	}

	/** A kept body lying on its side, its middle where the server has it (as the clients draw it). */
	private static BodyFootprint lying(LivingEntity body) {
		return BodyFootprint.of(body.getX(), body.getZ(), body.yBodyRot, body.getBbWidth(), body.getBbHeight(), Mth.HALF_PI,
				BodyFootprint.fourLegged(body));
	}

	/** Players walking into a body push it along, as they would a box: the push this tick. */
	private static Vec3 shoved(ServerLevel level, LivingEntity body) {
		AABB box = body.getBoundingBox();
		Vec3 push = Vec3.ZERO;
		for (Player player : level.getEntitiesOfClass(Player.class, box.inflate(0.05), p -> !p.isSpectator())) {
			// Standing on top of it is not walking into it.
			if (player.getY() > box.maxY - 0.2) {
				continue;
			}

			Vec3 stride = player.getKnownMovement();
			if (stride.horizontalDistanceSqr() > 0.0025) {
				push = push.add(stride.x * 0.4 * heft(body), 0.0, stride.z * 0.4 * heft(body));
			}
		}

		return push;
	}

	/**
	 * A block was placed where the body lies: the body is shoved out of its way, to the nearest place it fits, sideways
	 * along the ground if it can be, up onto the block if not. Build straight through a pile of carcasses and they are
	 * pushed aside.
	 */
	private static void unstick(ServerLevel level, LivingEntity body) {
		AABB box = body.getBoundingBox().deflate(0.01);
		if (level.noBlockCollision(body, box)) {
			return;
		}

		for (double step = 0.1; step <= 1.6; step += 0.1) {
			// Sideways first, in eight directions; then up.
			for (int k = 0; k < 9; k++) {
				double dx = k < 8 ? Math.round(Math.cos(k * Math.PI / 4.0) * 100.0) / 100.0 * step : 0.0;
				double dz = k < 8 ? Math.round(Math.sin(k * Math.PI / 4.0) * 100.0) / 100.0 * step : 0.0;
				double dy = k < 8 ? 0.0 : step;
				AABB moved = box.move(dx, dy, dz);
				// Sideways only onto something it can lie on, not out over a drop into a wall's far side.
				if (level.noBlockCollision(body, moved) && (k == 8 || !level.noBlockCollision(body, moved.move(0.0, -0.2, 0.0)))) {
					body.setPos(body.getX() + dx, body.getY() + dy, body.getZ() + dz);
					return;
				}
			}
		}
	}

	/**
	 * A blow on a kept body moves it on the server, so it is where every player sees it and does not lie, unseen, where
	 * it was: knocked along the swing with a hop, as the clients' ragdoll is (see {@code CorpseStrike}). A blade cuts
	 * rather than bats, so a carcass being butchered stays where it lies.
	 */
	private static void knock(LivingEntity body, GoreAction action) {
		Weapon weapon = action.weapon() >= 0 && action.weapon() < Weapon.values().length ? Weapon.values()[action.weapon()] : Weapon.BLUNT;
		double knock = weapon.cuts() ? 0.05 : 1.0;
		double heft = heft(body);
		double push = (weapon.cuts() ? 0.25 : 0.32) * (0.8 + Math.min(action.damage(), 10.0F) * 0.05) * heft * knock;
		Vec3 look = new Vec3(action.dx(), 0.0, action.dz());
		if (look.lengthSqr() < 1.0E-6) {
			return;
		}

		look = look.normalize();
		Vec3 v = body.getDeltaMovement();
		body.setDeltaMovement(Mth.clamp(v.x + look.x * push, -0.8, 0.8), Math.min(0.5, v.y + 0.18 * heft * knock), Mth.clamp(v.z + look.z * push, -0.8, 0.8));
	}

	/** True if a body is kept for now: one being kept, or one that will be when it next ticks. */
	public static boolean kept(EntityType<?> type) {
		List<String> source = GoreConfig.get().sufferingAnimals;
		if (source != cachedSource) {
			cachedSource = source;
			TYPES.clear();
			for (String entry : source) {
				Identifier id = Identifier.tryParse(entry.trim());
				if (id != null) {
					TYPES.add(id);
				}
			}
		}

		return TYPES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type));
	}

	/** A kept body is cleaned away (or gone, butchered apart): it goes up in smoke. */
	public static void clean(LivingEntity body) {
		if (body.level() instanceof ServerLevel level) {
			LinkedHashSet<LivingEntity> bodies = BODIES.get(level);
			if (bodies != null) {
				bodies.remove(body);
			}
		}

		vanish(body);
	}

	/** The body goes the vanilla way: a puff of smoke, and it is gone. */
	private static void vanish(LivingEntity body) {
		if (!body.isRemoved()) {
			body.level().broadcastEntityEvent(body, (byte) 60);
			body.remove(Entity.RemovalReason.KILLED);
		}
	}

	/** A loose piece moved on one player's screen: shown to the others near it. The server keeps nothing of it. */
	private static void relay(ServerPlayer player, PieceState state) {
		// Not a number would slip past every distance check below (NaN compares false).
		if (!state.finite() || !(player.distanceToSqr(state.at()) <= GONE_RANGE_SQ) || !POSES.take(player, 1)) {
			return;
		}

		EvidenceStore.moved(player, state);

		for (ServerPlayer other : PlayerLookup.around(player.level(), state.at(), SHOW_RANGE)) {
			if (other != player && ServerPlayNetworking.canSend(other, PieceState.TYPE)) {
				ServerPlayNetworking.send(other, state);
			}
		}
	}

	private static void receive(ServerPlayer player, GoreAction action) {
		ServerLevel level = player.level();
		if (!action.finite()) {
			return;
		}

		LivingEntity body = null;
		if (action.entityId() >= 0 && level.getEntity(action.entityId()) instanceof LivingEntity entity) {
			if (entity.isAlive() || entity instanceof Player) {
				// Only the dead are butchered; a living animal's gore comes from real hits. A dead player is respawning.
				return;
			}

			if ((action.kind() == GoreAction.CLEAN || action.kind() == GoreAction.VAPORIZED) && !isKept(entity)) {
				// Dying the vanilla way (or the dragon, the wither, another mod's boss, in a death that does something at
				// its end): it goes when its own death says, never because a client asked.
				return;
			}

			body = entity;
		}

		// A body the server no longer has (a mob that vanished the vanilla way) may still lie on the clients' screens.
		Vec3 where = body != null ? body.position() : action.at();
		// A body blown apart is reported by every client that saw it go, from wherever they stood. Manual cleaning is an
		// ordinary close-range interaction with a real cleaning tool, never that long-range automatic report.
		double range = action.kind() == GoreAction.VAPORIZED ? GONE_RANGE_SQ : REACH_SQ;
		if (!(player.distanceToSqr(where) <= range) || !ACTIONS.take(player, 1)) {
			return;
		}

		if (action.kind() == GoreAction.CLEAN
				&& (body == null || !(action.damage() > 0.0F) || !BloodyTools.isTool(player.getMainHandItem()))) {
			return;
		}

		if (action.kind() == GoreAction.VAPORIZED && action.damage() != 0.0F) {
			return;
		}

		if (action.kind() == GoreAction.WASH && !BloodyTools.wash(player)) {
			// No bloody tool, or no water to wash it in.
			return;
		}

		for (ServerPlayer other : PlayerLookup.around(level, where, SHOW_RANGE)) {
			if (other != player && ServerPlayNetworking.canSend(other, GoreAction.TYPE)) {
				ServerPlayNetworking.send(other, action);
			}
		}

		if ((action.kind() == GoreAction.CLEAN || action.kind() == GoreAction.VAPORIZED) && body != null) {
			if (action.kind() == GoreAction.CLEAN) {
				// Only a body wiped by hand goes onto the tool; automatic removal does not stain whatever is held.
				BloodyTools.wipedBody(player, EntityFilter.bloodType(body).rgb);
			}

			clean(body);
		}

		if (action.kind() == GoreAction.SCRUB) {
			BloodyTools.absorb(player, action.damage(), (int) (action.partKey() & 0xFFFFFF));
		}

		if (action.kind() == GoreAction.STRIKE && body != null && isKept(body)) {
			knock(body, action);
		}

		if (action.kind() == GoreAction.CUT) {
			EvidenceStore.cut(player, action.partKey());
		}
	}
}
