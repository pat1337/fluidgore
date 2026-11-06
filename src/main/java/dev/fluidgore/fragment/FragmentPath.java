package dev.fluidgore.fragment;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** One server-traced game fragment. Collision uses entity boxes, not client render models or internal organs. */
public record FragmentPath(int index, Vec3 end, byte material, @Nullable Contact contact) {
	public static final int MAX_PATHS = 256;
	public static final double MAX_RANGE = 96.0;
	public static final byte CARBON = 0, METAL = 1, STONE = 2;

	/** Position and direction in the victim's yaw frame, relative to its width and height at contact. */
	public record Contact(int entityId, UUID uuid, Vec3 local, Vec3 direction, float damage) {
		public boolean finite() {
			return entityId >= 0 && finiteVector(local, 2.01) && local.y >= -0.01 && local.y <= 1.01
					&& finiteVector(direction, 1.01) && Math.abs(direction.lengthSqr() - 1.0) < 0.02
					&& Float.isFinite(damage) && damage > 0 && damage <= 50;
		}
	}

	public boolean finite(Vec3 origin) {
		return index >= 0 && index < MAX_PATHS && material >= CARBON && material <= STONE && finiteVector(end, 3.1E7)
				&& end.distanceToSqr(origin) <= (MAX_RANGE + 0.1) * (MAX_RANGE + 0.1) && (contact == null || contact.finite());
	}

	private static boolean finiteVector(Vec3 v, double bound) {
		return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z)
				&& Math.abs(v.x) <= bound && Math.abs(v.y) <= bound && Math.abs(v.z) <= bound;
	}

	public void write(ByteBuf buf, Vec3 origin) {
		buf.writeShort(index); buf.writeByte(material);
		vector(buf, end.subtract(origin));
		buf.writeBoolean(contact != null);
		if (contact != null) {
			buf.writeInt(contact.entityId); buf.writeLong(contact.uuid.getMostSignificantBits()); buf.writeLong(contact.uuid.getLeastSignificantBits());
			vector(buf, contact.local); vector(buf, contact.direction); buf.writeFloat(contact.damage);
		}
	}

	public static FragmentPath read(ByteBuf buf, Vec3 origin) {
		int index = buf.readUnsignedShort(); byte material = buf.readByte();
		Vec3 end = origin.add(vector(buf));
		Contact contact = buf.readBoolean() ? new Contact(buf.readInt(), new UUID(buf.readLong(), buf.readLong()), vector(buf), vector(buf), buf.readFloat()) : null;
		return new FragmentPath(index, end, material, contact);
	}

	private static void vector(ByteBuf buf, Vec3 v) { buf.writeFloat((float) v.x); buf.writeFloat((float) v.y); buf.writeFloat((float) v.z); }
	private static Vec3 vector(ByteBuf buf) { return new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat()); }
}
