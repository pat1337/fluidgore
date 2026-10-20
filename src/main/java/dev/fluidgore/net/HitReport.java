package dev.fluidgore.net;

import dev.fluidgore.FluidGore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A hit as the server dealt it (3.0), sent to every player who can see the body: the server's word replaces what a
 * client could only infer from health drops and the damage packet. Exact damage (a shield block sends none at all), the
 * real weapon, where the blow came from and which way it went (a player's true view, not the rounded one sent to
 * everyone; an arrow's flight), and the dice for it, so every screen rolls the same whatever its settings.
 *
 * @param victim      the body hit
 * @param attacker    who caused it, or -1
 * @param flags       {@link #MELEE}, {@link #EXPLOSION}, {@link #FIRE}, {@link #FALL}, {@link #LETHAL}
 * @param weapon      ordinal of the {@code Weapon} it was, or -1 to leave it to the client (blasts, no attacker)
 * @param damage      health it took off (absorption hearts and armour already counted)
 * @param healthAfter the body's health after it
 * @param seed        the hit's dice, see {@code Fate}
 * @param sourceAt    where the damage came from, if the damage source says
 * @param rayFrom     a point the blow passed through on its way in, or null if the server does not know
 * @param rayDir      the way it was going, unit length, or null
 */
public record HitReport(int victim, int attacker, byte flags, byte weapon, float damage, float healthAfter, long seed, @Nullable Vec3 sourceAt,
		@Nullable Vec3 rayFrom, @Nullable Vec3 rayDir) implements CustomPacketPayload {
	public static final Type<HitReport> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FluidGore.MOD_ID, "hit"));
	public static final byte MELEE = 1;
	public static final byte EXPLOSION = 2;
	public static final byte FIRE = 4;
	public static final byte FALL = 8;
	public static final byte LETHAL = 16;
	private static final byte HAS_SOURCE = 32;
	private static final byte HAS_RAY = 64;

	public static final StreamCodec<ByteBuf, HitReport> CODEC = StreamCodec.of((buf, r) -> {
		byte wire = (byte) (r.flags & 31 | (r.sourceAt != null ? HAS_SOURCE : 0) | (r.rayFrom != null && r.rayDir != null ? HAS_RAY : 0));
		buf.writeInt(r.victim);
		buf.writeInt(r.attacker);
		buf.writeByte(wire);
		buf.writeByte(r.weapon);
		buf.writeFloat(r.damage);
		buf.writeFloat(r.healthAfter);
		buf.writeLong(r.seed);
		if ((wire & HAS_SOURCE) != 0) {
			writeVec(buf, r.sourceAt);
		}

		if ((wire & HAS_RAY) != 0) {
			writeVec(buf, r.rayFrom);
			buf.writeFloat((float) r.rayDir.x);
			buf.writeFloat((float) r.rayDir.y);
			buf.writeFloat((float) r.rayDir.z);
		}
	}, buf -> {
		int victim = buf.readInt();
		int attacker = buf.readInt();
		byte wire = buf.readByte();
		byte weapon = buf.readByte();
		float damage = buf.readFloat();
		float healthAfter = buf.readFloat();
		long seed = buf.readLong();
		Vec3 sourceAt = (wire & HAS_SOURCE) != 0 ? readVec(buf) : null;
		Vec3 rayFrom = null;
		Vec3 rayDir = null;
		if ((wire & HAS_RAY) != 0) {
			rayFrom = readVec(buf);
			rayDir = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
		}

		return new HitReport(victim, attacker, (byte) (wire & 31), weapon, damage, healthAfter, seed, sourceAt, rayFrom, rayDir);
	});

	private static void writeVec(ByteBuf buf, Vec3 v) {
		buf.writeDouble(v.x);
		buf.writeDouble(v.y);
		buf.writeDouble(v.z);
	}

	private static Vec3 readVec(ByteBuf buf) {
		return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
	}

	public boolean is(byte flag) {
		return (flags & flag) != 0;
	}

	/** No NaN, no absurd numbers: a broken or hostile server must not reach the gore with them. */
	public boolean finite() {
		return Float.isFinite(damage) && damage >= 0.0F && damage < 1.0E6F && Float.isFinite(healthAfter)
				&& (sourceAt == null || finite(sourceAt))
				&& (rayFrom == null || finite(rayFrom)) && (rayDir == null || finite(rayDir) && Math.abs(rayDir.lengthSqr() - 1.0) < 0.01);
	}

	private static boolean finite(Vec3 v) {
		return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z) && Math.abs(v.x) < 3.0E7 && Math.abs(v.y) < 1.0E5 && Math.abs(v.z) < 3.0E7;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
