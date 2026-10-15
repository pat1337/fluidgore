package dev.fluidgore.wound;

import dev.fluidgore.dismember.BodyPart;
import dev.fluidgore.mixin.ModelPartAccessor;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import org.jspecify.annotations.Nullable;

/**
 * A {@link WoundSet} as bytes, to be kept by the server and shown to players who were not there (see
 * {@code net/Evidence}). A wound sits on a part and a cube of the model; those are written as their place in the part
 * tree (parts in depth-first order, children by name; cubes in their order), so the same model on another screen
 * finds the same part.
 */
public final class WoundCodec {
	private WoundCodec() {
	}

	public static void write(WoundSet set, ByteBuf buf) {
		List<ModelPart> parts = parts(set.root);
		buf.writeByte(set.flesh.ordinal());
		buf.writeInt(set.blood);
		buf.writeFloat(set.trauma);
		buf.writeInt(set.broken);
		buf.writeInt(set.smashed);
		buf.writeInt(set.quiet);
		for (float b : set.bone) {
			buf.writeFloat(b);
		}

		int count = 0;
		for (int i = 0; i < set.count; i++) {
			if (parts.indexOf(set.part[i]) >= 0) {
				count++;
			}
		}

		buf.writeByte(count);
		for (int i = 0; i < set.count; i++) {
			int part = parts.indexOf(set.part[i]);
			if (part < 0) {
				continue;
			}

			buf.writeShort(part);
			buf.writeByte(cubes(set.part[i]).indexOf(set.cube[i]));
			buf.writeByte(set.bodyPart[i] == null ? -1 : set.bodyPart[i].ordinal());
			buf.writeByte(set.axis[i]);
			buf.writeByte(set.sign[i]);
			buf.writeByte(set.variant[i]);
			buf.writeByte(set.morph[i]);
			for (float f : new float[]{set.u[i], set.v[i], set.radius[i], set.severity[i], set.ox[i], set.oy[i], set.oz[i], set.yaw[i]}) {
				buf.writeFloat(f);
			}
		}
	}

	/** Wounds on {@code model} (an entity's) or on {@code root} (a loose piece, with {@code model} null). */
	public static WoundSet read(ByteBuf buf, @Nullable Model<?> model, ModelPart root) {
		Flesh[] fleshes = Flesh.values();
		Flesh flesh = fleshes[Math.floorMod(buf.readByte(), fleshes.length)];
		int blood = buf.readInt();
		WoundSet set = model != null ? new WoundSet(model, flesh, blood) : new WoundSet(root, flesh, blood);
		set.trauma = buf.readFloat();
		set.broken = buf.readInt();
		set.smashed = buf.readInt();
		set.quiet = buf.readInt();
		for (int i = 0; i < set.bone.length; i++) {
			set.bone[i] = buf.readFloat();
		}

		List<ModelPart> parts = parts(set.root);
		BodyPart[] bodyParts = BodyPart.values();
		int count = buf.readUnsignedByte();
		for (int n = 0; n < count; n++) {
			int part = buf.readShort();
			int cube = buf.readByte();
			int bodyPart = buf.readByte();
			byte axis = buf.readByte();
			byte sign = buf.readByte();
			byte variant = buf.readByte();
			byte morph = buf.readByte();
			float[] f = new float[8];
			for (int k = 0; k < f.length; k++) {
				f[k] = buf.readFloat();
			}

			if (part < 0 || part >= parts.size() || set.count >= WoundSet.MAX) {
				continue;
			}

			List<ModelPart.Cube> partCubes = cubes(parts.get(part));
			if (cube < 0 || cube >= partCubes.size()) {
				// Another model than the one it was cut into (a mob that changed its look): this wound has no place.
				continue;
			}

			int i = set.count++;
			set.part[i] = parts.get(part);
			set.cube[i] = partCubes.get(cube);
			set.bodyPart[i] = bodyPart >= 0 && bodyPart < bodyParts.length ? bodyParts[bodyPart] : null;
			set.axis[i] = axis;
			set.sign[i] = sign;
			set.variant[i] = variant;
			set.morph[i] = morph;
			set.u[i] = f[0];
			set.v[i] = f[1];
			set.radius[i] = f[2];
			set.severity[i] = f[3];
			set.ox[i] = f[4];
			set.oy[i] = f[5];
			set.oz[i] = f[6];
			set.yaw[i] = f[7];
		}

		return set;
	}

	/** Every part under {@code root}, root first, depth first, children in name order. */
	public static List<ModelPart> parts(ModelPart root) {
		List<ModelPart> out = new ArrayList<>();
		collect(root, out);
		return out;
	}

	private static void collect(ModelPart part, List<ModelPart> out) {
		out.add(part);
		Map<String, ModelPart> sorted = new TreeMap<>(((ModelPartAccessor) (Object) part).fluidgore$getChildren());
		for (ModelPart child : sorted.values()) {
			collect(child, out);
		}
	}

	private static List<ModelPart.Cube> cubes(ModelPart part) {
		return ((ModelPartAccessor) (Object) part).fluidgore$getCubes();
	}
}
