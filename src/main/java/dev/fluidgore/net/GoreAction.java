package dev.fluidgore.net;

import dev.fluidgore.FluidGore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * One thing a player did to a dead body, sent to the server and passed on to every other player nearby so their
 * screens do it too. It carries what the player did (where they stood, where they looked, what they held), never the
 * result: each client works the result out itself with the same dice ({@link #seed}), as it does for live hits.
 *
 * @param kind     what was done, see the constants
 * @param entityId the body (STRIKE, PLUCK, CLEAN), or -1
 * @param partKey  the loose piece (CUT), see {@code GorePart#key}, or 0
 * @param x        the eye (STRIKE, PLUCK, CUT) or the scrubbed point (SCRUB)
 * @param dx       the view direction, or the scrubbed face's normal (SCRUB)
 * @param weapon   ordinal of the {@code Weapon} held (STRIKE)
 * @param damage   the attack damage of the held item (STRIKE)
 */
public record GoreAction(byte kind, int entityId, long partKey, float x, float y, float z, float dx, float dy, float dz, byte weapon,
		float damage) implements CustomPacketPayload {
	/** A blow on a body that is down. */
	public static final byte STRIKE = 0;
	/** A handful of feathers out of a dead bird. */
	public static final byte PLUCK = 1;
	/** A blade through a loose piece. */
	public static final byte CUT = 2;
	/** The body is cleaned away (or gone, butchered apart): the server removes it. */
	public static final byte CLEAN = 3;
	/** Blood scrubbed off a surface. */
	public static final byte SCRUB = 4;

	public static final Type<GoreAction> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "action"));
	public static final StreamCodec<ByteBuf, GoreAction> CODEC = StreamCodec.of((buf, a) -> {
		buf.writeByte(a.kind);
		buf.writeInt(a.entityId);
		buf.writeLong(a.partKey);
		buf.writeFloat(a.x);
		buf.writeFloat(a.y);
		buf.writeFloat(a.z);
		buf.writeFloat(a.dx);
		buf.writeFloat(a.dy);
		buf.writeFloat(a.dz);
		buf.writeByte(a.weapon);
		buf.writeFloat(a.damage);
	}, buf -> new GoreAction(buf.readByte(), buf.readInt(), buf.readLong(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
			buf.readFloat(), buf.readFloat(), buf.readByte(), buf.readFloat()));

	public static GoreAction of(byte kind, int entityId, long partKey, Vec3 at, Vec3 dir, int weapon, float damage) {
		return new GoreAction(kind, entityId, partKey, (float) at.x, (float) at.y, (float) at.z, (float) dir.x, (float) dir.y, (float) dir.z,
				(byte) weapon, damage);
	}

	public Vec3 at() {
		return new Vec3(x, y, z);
	}

	public Vec3 dir() {
		return new Vec3(dx, dy, dz);
	}

	/**
	 * The dice for this action, from its own fields (and the body's UUID, mixed in by {@link GoreSync}): the player who
	 * did it and everyone who is shown it roll the same.
	 * The fields are floats on both sides (the sender rounds before it plays the action itself), so the bits agree.
	 */
	public long seed() {
		long s = kind;
		s = s * 31 + partKey;
		for (float f : new float[]{x, y, z, dx, dy, dz, damage}) {
			s = s * 31 + Float.floatToIntBits(f);
		}

		return s * 31 + weapon;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
