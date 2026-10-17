package dev.fluidgore.net;

import dev.fluidgore.FluidGore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Where a loose piece is and how it moves, from the player whose game is moving it (they punched, kicked, carried,
 * threw or cut it), passed on by the server to the players nearby so their pieces follow (see {@link PieceSync}).
 *
 * @param key   the piece, see {@code GorePart#key}
 * @param flags {@link #HELD}, {@link #RESTING}, {@link #SHATTERED}
 */
public record PieceState(long key, float x, float y, float z, float vx, float vy, float vz, float qx, float qy, float qz, float qw, float ax, float ay,
		float az, float spin, byte flags) implements CustomPacketPayload {
	/** Being carried: it hangs where the carrier holds it, no falling in between. */
	public static final byte HELD = 1;
	/** Come to rest, lying exactly like this. */
	public static final byte RESTING = 2;
	/** A bone that broke apart. */
	public static final byte SHATTERED = 4;

	public static final Type<PieceState> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "piece"));
	public static final StreamCodec<ByteBuf, PieceState> CODEC = StreamCodec.of((buf, s) -> {
		buf.writeLong(s.key);
		for (float f : new float[]{s.x, s.y, s.z, s.vx, s.vy, s.vz, s.qx, s.qy, s.qz, s.qw, s.ax, s.ay, s.az, s.spin}) {
			buf.writeFloat(f);
		}

		buf.writeByte(s.flags);
	}, buf -> new PieceState(buf.readLong(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
			buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
			buf.readByte()));

	public Vec3 at() {
		return new Vec3(x, y, z);
	}

	/** False for a state no game of ours writes: not a number, out of the world, or moving impossibly fast. */
	public boolean finite() {
		if (!GoreAction.inWorld(x) || !GoreAction.inWorld(y) || !GoreAction.inWorld(z)) {
			return false;
		}

		for (float f : new float[]{vx, vy, vz, spin}) {
			if (!(Math.abs(f) <= 100.0F)) {
				return false;
			}
		}

		for (float f : new float[]{qx, qy, qz, qw, ax, ay, az}) {
			if (!(Math.abs(f) <= 2.0F)) {
				return false;
			}
		}

		return true;
	}

	public boolean has(byte flag) {
		return (flags & flag) != 0;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
