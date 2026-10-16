package dev.fluidgore.net;

import dev.fluidgore.FluidGore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * How long ago a kept body died, sent by the server to a player who starts seeing it, so a body that died out of
 * sight rots as far as it really has (see {@code rot/Decay}).
 *
 * @param entityId the body
 * @param ticks    ticks since it died, by the game clock or the day clock (sleeping, {@code /time add}), whichever is more
 */
public record BodyAge(int entityId, long ticks) implements CustomPacketPayload {
	public static final Type<BodyAge> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "body_age"));
	public static final StreamCodec<ByteBuf, BodyAge> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, BodyAge::entityId,
			ByteBufCodecs.VAR_LONG, BodyAge::ticks,
			BodyAge::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
