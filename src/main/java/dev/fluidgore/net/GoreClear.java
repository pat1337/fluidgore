package dev.fluidgore.net;

import dev.fluidgore.FluidGore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

/**
 * An operator (or a command block) cleared an area with {@code /fluidgore clear} (see {@code server/ClearCommand}): the
 * server has taken away the bodies it kept there, and every player's game takes away the blood, pieces and bodies it
 * shows there. Only the box is sent; each game clears what it has.
 */
public record GoreClear(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) implements CustomPacketPayload {
	public static final Type<GoreClear> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "clear"));
	public static final StreamCodec<ByteBuf, GoreClear> CODEC = StreamCodec.composite(
			ByteBufCodecs.DOUBLE, GoreClear::minX,
			ByteBufCodecs.DOUBLE, GoreClear::minY,
			ByteBufCodecs.DOUBLE, GoreClear::minZ,
			ByteBufCodecs.DOUBLE, GoreClear::maxX,
			ByteBufCodecs.DOUBLE, GoreClear::maxY,
			ByteBufCodecs.DOUBLE, GoreClear::maxZ,
			GoreClear::new);

	public GoreClear(AABB box) {
		this(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
	}

	public AABB box() {
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/** A box can be as big as the world, but never not a number (it would match nothing, or everything). */
	public boolean finite() {
		return Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ) && Double.isFinite(maxX) && Double.isFinite(maxY)
				&& Double.isFinite(maxZ);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
