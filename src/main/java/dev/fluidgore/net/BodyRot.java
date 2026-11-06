package dev.fluidgore.net;

import dev.fluidgore.FluidGore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * How far a kept body has rotted, sent by the server to a player who starts seeing it, so a body that rotted out of
 * sight is as far gone as it really is, by where it really lay (see {@code rot/Decay} and {@code rot/RotClock}).
 *
 * @param entityId the body
 * @param days     decay-days so far, at Rot Speed 1x
 * @param arid     drying into a mummy
 */
public record BodyRot(int entityId, float days, boolean arid) implements CustomPacketPayload {
	public static final Type<BodyRot> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "body_rot"));
	public static final StreamCodec<ByteBuf, BodyRot> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, BodyRot::entityId,
			ByteBufCodecs.FLOAT, BodyRot::days,
			ByteBufCodecs.BOOL, BodyRot::arid,
			BodyRot::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
