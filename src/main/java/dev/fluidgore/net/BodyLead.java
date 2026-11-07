package dev.fluidgore.net;

import dev.fluidgore.FluidGore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The server tells one player's game that it leads the fall of a body the server keeps ({@code lead}), or no longer
 * does. The leading game's ragdoll falls, tumbles and rolls with its own weight and momentum, and it reports where the
 * body got to ({@link BodyFall}); the server moves its body there, and every other game's ragdoll follows it as before.
 */
public record BodyLead(int entityId, boolean lead) implements CustomPacketPayload {
	public static final Type<BodyLead> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "body_lead"));
	public static final StreamCodec<ByteBuf, BodyLead> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, BodyLead::entityId,
			ByteBufCodecs.BOOL, BodyLead::lead,
			BodyLead::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
