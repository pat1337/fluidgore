package dev.fluidgore.net;

import dev.fluidgore.FluidGore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A tank block is bursting (sent by the server just before its explosion packet): where, how big and at what pressure,
 * and how many of its fragments hit each body, so the gore on every screen matches the damage the server did.
 *
 * @param ids  bodies hit by fragments
 * @param hits fragments in each, same order as {@code ids}
 */
public record TankBurstPayload(double x, double y, double z, float litres, float psi, float radius, int[] ids, int[] hits)
		implements CustomPacketPayload {
	public static final Type<TankBurstPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "tank_burst"));
	/** At most this many bodies listed (the rest were too far for any fragment to count). */
	public static final int MAX_BODIES = 512;
	public static final StreamCodec<ByteBuf, TankBurstPayload> CODEC = StreamCodec.of((buf, p) -> {
		buf.writeDouble(p.x);
		buf.writeDouble(p.y);
		buf.writeDouble(p.z);
		buf.writeFloat(p.litres);
		buf.writeFloat(p.psi);
		buf.writeFloat(p.radius);
		int n = Math.min(p.ids.length, MAX_BODIES);
		buf.writeShort(n);
		for (int i = 0; i < n; i++) {
			buf.writeInt(p.ids[i]);
			buf.writeByte(Math.min(p.hits[i], 127));
		}
	}, buf -> {
		double x = buf.readDouble();
		double y = buf.readDouble();
		double z = buf.readDouble();
		float litres = buf.readFloat();
		float psi = buf.readFloat();
		float radius = buf.readFloat();
		int n = Math.min(buf.readUnsignedShort(), MAX_BODIES);
		int[] ids = new int[n];
		int[] hits = new int[n];
		for (int i = 0; i < n; i++) {
			ids[i] = buf.readInt();
			hits[i] = buf.readByte();
		}

		return new TankBurstPayload(x, y, z, litres, psi, radius, ids, hits);
	});

	/** No NaN or absurd sizes (a hostile or broken server must not crash the client's sums). */
	public boolean finite() {
		return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Math.abs(x) < 3.0E7 && Math.abs(z) < 3.0E7
				&& Float.isFinite(litres) && litres > 0.0F && litres <= 5000.0F && Float.isFinite(psi) && psi >= 0.0F && psi <= 50_000.0F
				&& Float.isFinite(radius) && radius >= 0.0F && radius <= 200.0F;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
