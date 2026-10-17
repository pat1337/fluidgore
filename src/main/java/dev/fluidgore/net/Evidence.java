package dev.fluidgore.net;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.dismember.Dismemberment;
import dev.fluidgore.dismember.GorePart;
import dev.fluidgore.dismember.ModelParts;
import dev.fluidgore.dismember.PieceCodec;
import dev.fluidgore.wound.WoundCodec;
import dev.fluidgore.wound.WoundSet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Iterator;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The client half of evidence: what this player does stays for the players who come along later (see
 * {@code server/EvidenceStore}). After this player hits or butchers a creature, this game sends the server a picture of
 * its gore (wounds, missing limbs, broken bones, char, plucks and chops); after it cuts off or moves a piece, what the
 * piece is (its position goes with {@link PieceSync}). When this player starts seeing a creature with a picture, or
 * comes near a piece, the server sends it and this game puts it back: the zombie is missing the arm, the arm lies where
 * it fell.
 */
public final class Evidence {
	private static final int VERSION = 1;
	/** Ticks between two pictures of the same creature while it is being worked on. */
	private static final int EVERY = 10;
	/** Ticks a picture waits for its creature to be drawn here (its model is needed) before it is dropped. */
	private static final int WAIT = 400;

	private static final IntOpenHashSet DIRTY = new IntOpenHashSet();
	private static final Int2ObjectOpenHashMap<byte[]> PENDING = new Int2ObjectOpenHashMap<>();
	private static final Int2IntOpenHashMap WAITED = new Int2IntOpenHashMap();
	/** Pieces sent by the server, waiting for where they lie. */
	private static final Long2ObjectOpenHashMap<GorePart> UNPLACED = new Long2ObjectOpenHashMap<>();
	/** When each unplaced piece came; one whose pose never follows is dropped after {@link #WAIT}. */
	private static final Long2IntOpenHashMap UNPLACED_AT = new Long2IntOpenHashMap();
	/** Poses of unplaced pieces whose ground is not loaded yet: they are put down once it is. */
	private static final Long2ObjectOpenHashMap<PieceState> GROUND = new Long2ObjectOpenHashMap<>();
	private static int tick;

	private Evidence() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(EvidenceRecord.TYPE, (record, context) -> receive(record));
	}

	/** This player did something to {@code entityId}: its picture is sent soon. */
	public static void touched(int entityId) {
		DIRTY.add(entityId);
	}

	/** A piece this player cut off or moved: the server keeps what it is. */
	public static void piece(GorePart part) {
		if (!GoreSync.serverKeepsBodies()) {
			return;
		}

		byte[] data = PieceCodec.write(part);
		if (data.length <= EvidenceRecord.MAX_BYTES) {
			ClientPlayNetworking.send(new EvidenceRecord(true, part.key, part.lifetime - part.age, data));
		}
	}

	public static void tick(ClientLevel level) {
		tick++;
		if (tick % EVERY == 0 && !DIRTY.isEmpty()) {
			boolean online = GoreSync.serverKeepsBodies();
			IntIterator it = DIRTY.iterator();
			while (it.hasNext()) {
				int id = it.nextInt();
				if (online && level.getEntity(id) instanceof LivingEntity entity && !(entity instanceof Player)) {
					byte[] data = picture(entity);
					if (data.length <= EvidenceRecord.MAX_BYTES) {
						ClientPlayNetworking.send(new EvidenceRecord(false, id, 0, data));
					}
				}
			}

			DIRTY.clear();
		}

		if (!GROUND.isEmpty()) {
			Iterator<PieceState> waiting = GROUND.values().iterator();
			while (waiting.hasNext()) {
				PieceState pose = waiting.next();
				if (!UNPLACED.containsKey(pose.key())) {
					waiting.remove();
				} else if (level.isLoaded(BlockPos.containing(pose.x(), pose.y(), pose.z()))) {
					waiting.remove();
					PieceSync.apply(pose);
				}
			}
		}

		if (tick % 20 == 0 && !UNPLACED_AT.isEmpty()) {
			UNPLACED_AT.long2IntEntrySet().removeIf(entry -> {
				boolean stale = tick - entry.getIntValue() > WAIT;
				if (stale) {
					UNPLACED.remove(entry.getLongKey());
					GROUND.remove(entry.getLongKey());
				}

				return stale;
			});
		}

		Iterator<Int2ObjectMap.Entry<byte[]>> pending = PENDING.int2ObjectEntrySet().iterator();
		while (pending.hasNext()) {
			Int2ObjectMap.Entry<byte[]> entry = pending.next();
			int id = entry.getIntKey();
			if (level.getEntity(id) instanceof LivingEntity entity && ModelParts.pose(entity) != null) {
				restore(entity, entry.getValue());
				pending.remove();
				WAITED.remove(id);
			} else if (WAITED.addTo(id, 1) > WAIT) {
				pending.remove();
				WAITED.remove(id);
			}
		}
	}

	private static byte[] picture(LivingEntity entity) {
		int id = entity.getId();
		Dismemberment d = GoreSystems.dismemberment();
		ByteBuf buf = Unpooled.buffer();
		buf.writeByte(VERSION);
		buf.writeInt(d.missingMask(id));
		buf.writeBoolean(d.vaporized(id));
		buf.writeFloat(d.burns().charOf(id));
		buf.writeInt(d.butchery().plucks(id));
		buf.writeInt(d.butchery().chops(id));
		WoundSet wounds = d.wounds().get(id);
		buf.writeBoolean(wounds != null);
		if (wounds != null) {
			WoundCodec.write(wounds, buf);
		}

		return ByteBufUtil.getBytes(buf);
	}

	private static void restore(LivingEntity entity, byte[] data) {
		int id = entity.getId();
		Dismemberment d = GoreSystems.dismemberment();
		if (d.wounds().get(id) != null || d.missingMask(id) != 0) {
			// This player saw it happen: what they saw stands.
			return;
		}

		ByteBuf buf = Unpooled.wrappedBuffer(data);
		try {
			if (buf.readByte() != VERSION) {
				return;
			}

			int missing = buf.readInt();
			boolean gone = buf.readBoolean();
			float charred = buf.readFloat();
			if (!(charred >= 0.0F && charred <= 1.0F)) {
				charred = 0.0F;
			}

			int plucks = buf.readInt();
			int chops = buf.readInt();
			WoundSet wounds = null;
			if (buf.readBoolean()) {
				ModelParts.Posed posed = ModelParts.pose(entity);
				if (posed != null) {
					wounds = WoundCodec.read(buf, posed.model(), posed.model().root());
				}
			}

			d.restoreMissing(id, missing);
			if (gone) {
				d.hide(id);
			}

			d.burns().restore(id, charred);
			d.butchery().restore(id, plucks, chops);
			if (wounds != null) {
				d.wounds().restore(id, wounds);
			}
		} catch (RuntimeException e) {
			// Written by another version, or cut short: the creature is shown as it is.
		}
	}

	private static void receive(EvidenceRecord record) {
		if (!record.piece()) {
			PENDING.put((int) record.id(), record.data());
			WAITED.remove((int) record.id());
			return;
		}

		if (GoreSystems.dismemberment().parts().byKey(record.id()) != null) {
			// Already here: this player saw it come off.
			return;
		}

		GorePart part = PieceCodec.read(record.data(), record.id(), Math.max(40, record.lifetime()));
		if (part != null) {
			// Lying still from the start. It is put down when its pose comes, right after (see PieceSync).
			part.resting = true;
			part.landed = true;
			UNPLACED.put(record.id(), part);
			UNPLACED_AT.put(record.id(), tick);
		}
	}

	/** A pose for a piece the server sent, arrived before the ground it lies on: it is put down when that loads. */
	static void waitForGround(PieceState pose) {
		if (UNPLACED.containsKey(pose.key())) {
			GROUND.put(pose.key(), pose);
		}
	}

	/** The piece named {@code key} the server sent, now put down among the others; null if there is none. */
	static @Nullable GorePart place(long key) {
		GorePart part = UNPLACED.remove(key);
		UNPLACED_AT.remove(key);
		if (part != null) {
			GoreSystems.dismemberment().parts().add(part);
		}

		return part;
	}

	public static void forget(int entityId) {
		DIRTY.remove(entityId);
		PENDING.remove(entityId);
		WAITED.remove(entityId);
	}

	public static void clear() {
		UNPLACED.clear();
		UNPLACED_AT.clear();
		GROUND.clear();
		DIRTY.clear();
		PENDING.clear();
		WAITED.clear();
	}
}
