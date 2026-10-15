package dev.fluidgore.net;

import dev.fluidgore.FluidGore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * What has been done to a body or a loose piece, as bytes the server keeps without reading them (see {@link Evidence}).
 *
 * @param piece    false: {@code id} is an entity id and {@code data} its gore; true: {@code id} is a piece's key
 * @param lifetime for a piece, the ticks it has left to live
 */
public record EvidenceRecord(boolean piece, long id, int lifetime, byte[] data) implements CustomPacketPayload {
	/** Largest record accepted: a cow diced into its smallest pieces is far under it. */
	public static final int MAX_BYTES = 32768;

	public static final Type<EvidenceRecord> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "evidence"));
	public static final StreamCodec<ByteBuf, EvidenceRecord> CODEC = StreamCodec.of((buf, r) -> {
		buf.writeBoolean(r.piece);
		buf.writeLong(r.id);
		buf.writeInt(r.lifetime);
		buf.writeInt(r.data.length);
		buf.writeBytes(r.data);
	}, buf -> {
		boolean piece = buf.readBoolean();
		long id = buf.readLong();
		int lifetime = buf.readInt();
		int length = buf.readInt();
		if (length < 0 || length > MAX_BYTES) {
			throw new IllegalArgumentException("Fluid Gore evidence record of " + length + " bytes");
		}

		byte[] data = new byte[length];
		buf.readBytes(data);
		return new EvidenceRecord(piece, id, lifetime, data);
	});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
