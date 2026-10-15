package dev.fluidgore.server;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.net.GoreAction;
import dev.fluidgore.net.PieceState;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
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

	/** Per dimension, oldest first. Entries whose chunk unloaded are dropped when the next body is added. */
	private static final Map<ServerLevel, LinkedHashSet<LivingEntity>> BODIES = new WeakHashMap<>();
	private static final Set<Identifier> TYPES = new HashSet<>();
	private static List<String> cachedSource;

	private KeptBodies() {
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(GoreAction.TYPE, GoreAction.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GoreAction.TYPE, GoreAction.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PieceState.TYPE, PieceState.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PieceState.TYPE, PieceState.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(GoreAction.TYPE, (action, context) -> receive(context.player(), action));
		ServerPlayNetworking.registerGlobalReceiver(PieceState.TYPE, (state, context) -> relay(context.player(), state));
		// A dead body is meat, not an animal: no milking, shearing, feeding, leashing or riding it.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				entity instanceof LivingEntity living && !(entity instanceof Player) && living.isDeadOrDying() ? InteractionResult.FAIL : InteractionResult.PASS);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> BODIES.clear());
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
		if (player.distanceToSqr(state.at()) > GONE_RANGE_SQ) {
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
		LivingEntity body = null;
		if (action.entityId() >= 0 && level.getEntity(action.entityId()) instanceof LivingEntity entity) {
			if (entity.isAlive()) {
				// Only the dead are butchered; a living animal's gore comes from real hits.
				return;
			}

			body = entity;
		}

		// A body the server no longer has (a mob that vanished the vanilla way) may still lie on the clients' screens.
		Vec3 where = body != null ? body.position() : action.at();
		// A body blown apart is reported by every client that saw it go, from wherever they stood.
		if (player.distanceToSqr(where) > (action.kind() == GoreAction.CLEAN ? GONE_RANGE_SQ : REACH_SQ)) {
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
			EvidenceStore.cut(level, action.partKey());
		}
	}
}
