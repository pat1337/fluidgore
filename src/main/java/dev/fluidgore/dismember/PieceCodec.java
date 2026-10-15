package dev.fluidgore.dismember;

import dev.fluidgore.mixin.ModelPartAccessor;
import dev.fluidgore.wound.WoundCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A loose piece as bytes: what it is (its own model, every cube and face exactly as cut, its texture, size, tint, raw
 * faces and wounds), not where it is; that comes in a {@code PieceState}. Kept by the server so that a player who comes
 * along later finds the same pieces lying there (see {@code net/Evidence}).
 */
public final class PieceCodec {
	private static final int VERSION = 1;

	private PieceCodec() {
	}

	public static byte[] write(GorePart p) {
		ByteBuf buf = Unpooled.buffer();
		buf.writeByte(VERSION);
		string(buf, p.texture.toString());
		vector(buf, p.localCenter);
		vector(buf, p.halfExtents);
		buf.writeFloat(p.scale);
		buf.writeInt(p.bloodRgb);
		buf.writeInt(p.tint);
		buf.writeInt(p.rawFaces);
		model(buf, p.model);
		buf.writeBoolean(p.wounds != null);
		if (p.wounds != null) {
			WoundCodec.write(p.wounds, buf);
		}

		return ByteBufUtil.getBytes(buf);
	}

	/** The piece {@code data} describes, named {@code key}, with {@code lifetime} ticks to live; null if it is unreadable. */
	public static @Nullable GorePart read(byte[] data, long key, int lifetime) {
		ByteBuf buf = Unpooled.wrappedBuffer(data);
		try {
			if (buf.readByte() != VERSION) {
				return null;
			}

			Identifier texture = Identifier.tryParse(string(buf));
			Vector3f centre = vector(buf);
			Vector3f half = vector(buf);
			float scale = buf.readFloat();
			int blood = buf.readInt();
			int tint = buf.readInt();
			int rawFaces = buf.readInt();
			ModelPart model = model(buf);
			if (texture == null) {
				return null;
			}

			GorePart part = new GorePart(model, texture, centre, half, scale, blood, lifetime);
			part.key = key;
			part.tint = tint;
			part.rawFaces = rawFaces;
			if (buf.readBoolean()) {
				part.wounds = WoundCodec.read(buf, null, model);
			}

			return part;
		} catch (RuntimeException e) {
			// Written by another version, or cut short: the piece is simply not shown.
			return null;
		}
	}

	private static void model(ByteBuf buf, ModelPart part) {
		for (float f : new float[]{part.x, part.y, part.z, part.xRot, part.yRot, part.zRot, part.xScale, part.yScale, part.zScale}) {
			buf.writeFloat(f);
		}

		buf.writeBoolean(part.visible);
		List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part).fluidgore$getCubes();
		buf.writeShort(cubes.size());
		for (ModelPart.Cube cube : cubes) {
			for (float f : new float[]{cube.minX, cube.minY, cube.minZ, cube.maxX, cube.maxY, cube.maxZ}) {
				buf.writeFloat(f);
			}

			buf.writeByte(cube.polygons.length);
			for (ModelPart.Polygon polygon : cube.polygons) {
				buf.writeByte(polygon.vertices().length);
				for (ModelPart.Vertex v : polygon.vertices()) {
					buf.writeFloat(v.x());
					buf.writeFloat(v.y());
					buf.writeFloat(v.z());
					buf.writeFloat(v.u());
					buf.writeFloat(v.v());
				}

				buf.writeFloat(polygon.normal().x());
				buf.writeFloat(polygon.normal().y());
				buf.writeFloat(polygon.normal().z());
			}
		}

		Map<String, ModelPart> children = new TreeMap<>(((ModelPartAccessor) (Object) part).fluidgore$getChildren());
		buf.writeShort(children.size());
		for (Map.Entry<String, ModelPart> child : children.entrySet()) {
			string(buf, child.getKey());
			model(buf, child.getValue());
		}
	}

	private static ModelPart model(ByteBuf buf) {
		float[] pose = new float[9];
		for (int i = 0; i < pose.length; i++) {
			pose[i] = buf.readFloat();
		}

		boolean visible = buf.readBoolean();
		int cubeCount = buf.readShort();
		List<ModelPart.Cube> cubes = new ArrayList<>(cubeCount);
		Direction[] directions = Direction.values();
		for (int c = 0; c < cubeCount; c++) {
			float minX = buf.readFloat();
			float minY = buf.readFloat();
			float minZ = buf.readFloat();
			float maxX = buf.readFloat();
			float maxY = buf.readFloat();
			float maxZ = buf.readFloat();
			int polygons = Math.min(buf.readUnsignedByte(), directions.length);
			// A cube with as many faces as the original; its faces are then replaced by the original ones, exactly.
			EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);
			for (int i = 0; i < polygons; i++) {
				faces.add(directions[i]);
			}

			ModelPart.Cube cube = new ModelPart.Cube(0, 0, minX, minY, minZ, maxX - minX, maxY - minY, maxZ - minZ, 0.0F, 0.0F, 0.0F, false, 64.0F,
					64.0F, faces);
			for (int i = 0; i < polygons; i++) {
				int count = buf.readUnsignedByte();
				ModelPart.Vertex[] vertices = new ModelPart.Vertex[count];
				for (int k = 0; k < count; k++) {
					vertices[k] = new ModelPart.Vertex(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
				}

				cube.polygons[i] = new ModelPart.Polygon(vertices, new Vector3f(buf.readFloat(), buf.readFloat(), buf.readFloat()));
			}

			cubes.add(cube);
		}

		int childCount = buf.readShort();
		Map<String, ModelPart> children = new HashMap<>();
		for (int c = 0; c < childCount; c++) {
			String name = string(buf);
			children.put(name, model(buf));
		}

		ModelPart part = new ModelPart(cubes, children);
		part.x = pose[0];
		part.y = pose[1];
		part.z = pose[2];
		part.xRot = pose[3];
		part.yRot = pose[4];
		part.zRot = pose[5];
		part.xScale = pose[6];
		part.yScale = pose[7];
		part.zScale = pose[8];
		part.visible = visible;
		return part;
	}

	private static void vector(ByteBuf buf, Vector3f v) {
		buf.writeFloat(v.x);
		buf.writeFloat(v.y);
		buf.writeFloat(v.z);
	}

	private static Vector3f vector(ByteBuf buf) {
		return new Vector3f(buf.readFloat(), buf.readFloat(), buf.readFloat());
	}

	private static void string(ByteBuf buf, String s) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		buf.writeShort(bytes.length);
		buf.writeBytes(bytes);
	}

	private static String string(ByteBuf buf) {
		byte[] bytes = new byte[buf.readShort()];
		buf.readBytes(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
