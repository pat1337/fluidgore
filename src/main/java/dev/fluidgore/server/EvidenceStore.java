package dev.fluidgore.server;

import com.mojang.serialization.Codec;
import dev.fluidgore.FluidGore;
import dev.fluidgore.net.EvidenceRecord;
import dev.fluidgore.net.PieceState;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Evidence: what was done stays for whoever comes along later. The server keeps, without reading it, the gore of every
 * body and creature a player has hit or butchered (the latest picture the player’s game sent of it: wounds, missing
 * limbs, broken bones, char, plucks), saved with the entity in the world, and the loose pieces players cut off or
 * moved (what each piece is and where it lies), for as long as the piece lives. A player who starts seeing a creature
 * is sent its picture; one who comes near a piece is sent the piece.
 */
public final class EvidenceStore {
	/** An entity's gore, as the clients wrote it. Saved with the entity. */
	public static final AttachmentType<byte[]> GORE = AttachmentRegistry.create(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "gore"),
			builder -> builder.persistent(Codec.BYTE_BUFFER.xmap(EvidenceStore::bytes, ByteBuffer::wrap)));
	/** Pieces this close to a player are sent to them. */
	private static final double SHOW_SQ = 64.0 * 64.0;
	/** A player this far from a piece has unloaded it, and is sent it again when they come back. */
	private static final double FORGET_SQ = 160.0 * 160.0;
	/** How far from what it describes a player may be to send a record of it. */
	private static final double REPORT_SQ = 64.0 * 64.0;
	private static final int MAX_PIECES = 512;
	private static final int MAX_LIFETIME = 72000;
	/** Ticks a piece may wait for where it lies (its first pose comes within a few ticks) before it is forgotten. */
	private static final int UNPLACED_TICKS = 600;
	/** Bytes a player may upload: a whole carcass diced at once, then a steady trickle. */
	private static final Throttle BYTES = new Throttle(1 << 20, 256 << 10);

	private static final class Piece {
		byte[] data;
		@Nullable PieceState pose;
		long expires;
		long created;
		final Set<UUID> shown = new HashSet<>();
	}

	private static final Map<ServerLevel, Map<Long, Piece>> PIECES = new WeakHashMap<>();

	private EvidenceStore() {
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(EvidenceRecord.TYPE, EvidenceRecord.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EvidenceRecord.TYPE, EvidenceRecord.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(EvidenceRecord.TYPE, (record, context) -> receive(context.player(), record));
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			byte[] gore = entity.getAttached(GORE);
			if (gore != null && ServerPlayNetworking.canSend(player, EvidenceRecord.TYPE)) {
				ServerPlayNetworking.send(player, new EvidenceRecord(false, entity.getId(), 0, gore));
			}
		});
		ServerTickEvents.END_LEVEL_TICK.register(EvidenceStore::deliver);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			PIECES.clear();
			BYTES.clear();
		});
	}

	private static void receive(ServerPlayer player, EvidenceRecord record) {
		ServerLevel level = player.level();
		if (!BYTES.take(player, record.data().length + 16)) {
			return;
		}

		if (!record.piece()) {
			// Only what this player really did: a creature they hurt (the game remembers for five seconds; the picture
			// comes half a second after the blow), or a body being kept. Never a stranger's pet or a villager across
			// the room, which would carry whatever was written on it in the world save for good.
			if (level.getEntity((int) record.id()) instanceof LivingEntity entity && !(entity instanceof Player)
					&& player.distanceToSqr(entity) < REPORT_SQ && (entity.getLastHurtByPlayer() == player || KeptBodies.isKept(entity))) {
				entity.setAttached(GORE, record.data());
			}

			return;
		}

		Map<Long, Piece> pieces = PIECES.computeIfAbsent(level, l -> new HashMap<>());
		Piece piece = pieces.computeIfAbsent(record.id(), k -> {
			Piece made = new Piece();
			made.created = level.getGameTime();
			return made;
		});
		piece.data = record.data();
		piece.expires = level.getGameTime() + Math.max(0, Math.min(record.lifetime(), MAX_LIFETIME));
		// It is on the sender's screen already.
		piece.shown.add(player.getUUID());
		while (pieces.size() > MAX_PIECES) {
			Long soonest = null;
			long at = Long.MAX_VALUE;
			for (Map.Entry<Long, Piece> entry : pieces.entrySet()) {
				if (entry.getValue().expires < at) {
					at = entry.getValue().expires;
					soonest = entry.getKey();
				}
			}

			pieces.remove(soonest);
		}
	}

	/**
	 * Forgets every piece last seen lying inside {@code box} (see {@link ClearCommand}), so nobody arriving later is sent
	 * it. Returns how many.
	 */
	static int removeIn(ServerLevel level, net.minecraft.world.phys.AABB box) {
		Map<Long, Piece> pieces = PIECES.get(level);
		if (pieces == null) {
			return 0;
		}

		int before = pieces.size();
		pieces.values().removeIf(piece -> piece.pose != null && box.contains(piece.pose.at()));
		return before - pieces.size();
	}

	/** A piece moved on someone's screen (see {@code KeptBodies#relay}): it lies there now, or broke apart. */
	static void moved(ServerPlayer player, PieceState state) {
		Map<Long, Piece> pieces = PIECES.get(player.level());
		Piece piece = pieces == null ? null : pieces.get(state.key());
		if (piece == null) {
			return;
		}

		if (state.has(PieceState.SHATTERED)) {
			pieces.remove(state.key());
		} else if (player.distanceToSqr(state.at()) < REPORT_SQ) {
			piece.pose = state;
		}
	}

	/** A piece was cut in two: it is gone, and its halves come in their own records. */
	static void cut(ServerPlayer player, long key) {
		Map<Long, Piece> pieces = PIECES.get(player.level());
		Piece piece = pieces == null ? null : pieces.get(key);
		// Only by someone near it (where it last lay); one never put down is the cutter's own, just made.
		if (piece != null && (piece.pose == null || player.distanceToSqr(piece.pose.at()) < REPORT_SQ)) {
			pieces.remove(key);
		}
	}

	private static void deliver(ServerLevel level) {
		long now = level.getGameTime();
		Map<Long, Piece> pieces = PIECES.get(level);
		if (pieces == null || now % 20 != 0) {
			return;
		}

		pieces.values().removeIf(piece -> piece.expires <= now || piece.pose == null && now - piece.created > UNPLACED_TICKS);
		for (ServerPlayer player : level.players()) {
			if (!ServerPlayNetworking.canSend(player, EvidenceRecord.TYPE)) {
				continue;
			}

			UUID id = player.getUUID();
			for (Map.Entry<Long, Piece> entry : pieces.entrySet()) {
				Piece piece = entry.getValue();
				if (piece.pose == null) {
					// Not come to rest anywhere yet: nothing to show.
					continue;
				}

				double d = player.distanceToSqr(new Vec3(piece.pose.x(), piece.pose.y(), piece.pose.z()));
				if (d < SHOW_SQ && piece.shown.add(id)) {
					ServerPlayNetworking.send(player, new EvidenceRecord(true, entry.getKey(), (int) (piece.expires - now), piece.data));
					ServerPlayNetworking.send(player, piece.pose);
				} else if (d > FORGET_SQ) {
					piece.shown.remove(id);
				}
			}
		}
	}

	private static byte[] bytes(ByteBuffer buffer) {
		byte[] out = new byte[buffer.remaining()];
		buffer.duplicate().get(out);
		return out;
	}
}
