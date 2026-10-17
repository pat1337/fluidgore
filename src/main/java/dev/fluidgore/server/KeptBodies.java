package dev.fluidgore.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.fluidgore.FluidGore;
import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.net.BodyAge;
import dev.fluidgore.net.GoreAction;
import dev.fluidgore.net.PieceState;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

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
	/**
	 * When a kept body died: the game time and the overworld's day clock then, saved with it, so it goes on rotting
	 * while nobody is near and while players sleep (see {@code rot/Decay}).
	 */
	public static final AttachmentType<Died> DIED = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "died"),
			builder -> builder.persistent(Died.CODEC));

	public record Died(long game, long clock) {
		static final Codec<Died> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.LONG.fieldOf("game").forGetter(Died::game),
				Codec.LONG.fieldOf("clock").forGetter(Died::clock)).apply(i, Died::new));

		/** Ticks since, by whichever clock has gone further. */
		long age(ServerLevel level) {
			return Math.max(0L, Math.max(level.getGameTime() - game, level.getOverworldClockTime() - clock));
		}
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
		PayloadTypeRegistry.clientboundPlay().register(BodyAge.TYPE, BodyAge.CODEC);
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			Died died = entity.getAttached(DIED);
			if (died != null && entity.level() instanceof ServerLevel level && ServerPlayNetworking.canSend(player, BodyAge.TYPE)) {
				ServerPlayNetworking.send(player, new BodyAge(entity.getId(), died.age(level)));
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

			// Whoever rode it falls off, as they would if it were gone.
			body.ejectPassengers();
			body.stopRiding();
			bodies.removeIf(Entity::isRemoved);
			while (bodies.size() > config.maxKeptBodies) {
				LivingEntity oldest = bodies.removeFirst();
				vanish(oldest);
			}
		}

		return true;
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

			if (action.kind() == GoreAction.CLEAN && !isKept(entity)) {
				// Dying the vanilla way (or the dragon, the wither, another mod's boss, in a death that does something at
				// its end): it goes when its own death says, never because a client asked.
				return;
			}

			body = entity;
		}

		// A body the server no longer has (a mob that vanished the vanilla way) may still lie on the clients' screens.
		Vec3 where = body != null ? body.position() : action.at();
		// A body blown apart is reported by every client that saw it go, from wherever they stood.
		if (!(player.distanceToSqr(where) <= (action.kind() == GoreAction.CLEAN ? GONE_RANGE_SQ : REACH_SQ)) || !ACTIONS.take(player, 1)) {
			return;
		}

		for (ServerPlayer other : PlayerLookup.around(level, where, SHOW_RANGE)) {
			if (other != player && ServerPlayNetworking.canSend(other, GoreAction.TYPE)) {
				ServerPlayNetworking.send(other, action);
			}
		}

		if (action.kind() == GoreAction.CLEAN && body != null) {
			clean(body);
		}

		if (action.kind() == GoreAction.CUT) {
			EvidenceStore.cut(player, action.partKey());
		}
	}
}
