package dev.fluidgore.net;

import dev.fluidgore.FluidGore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Where the game leading a kept body's fall ({@link BodyLead}) has it now: the middle of the ragdoll across the ground
 * and its lowest point, and whether it has come to rest. Sent a few times a second while it moves.
 */
public record BodyFall(int entityId, double x, double y, double z, boolean resting) implements CustomPacketPayload {
	public static final Type<BodyFall> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "body_fall"));
	public static final StreamCodec<ByteBuf, BodyFall> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, BodyFall::entityId,
			ByteBufCodecs.DOUBLE, BodyFall::x,
			ByteBufCodecs.DOUBLE, BodyFall::y,
			ByteBufCodecs.DOUBLE, BodyFall::z,
			ByteBufCodecs.BOOL, BodyFall::resting,
			BodyFall::new);

	public Vec3 at() {
		return new Vec3(x, y, z);
	}

	public boolean finite() {
		return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
