package dev.fluidgore.dismember;

import dev.fluidgore.anatomy.Anatomy;
import dev.fluidgore.event.Events;
import dev.fluidgore.material.Condition;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A loose piece as bytes: what it is (its own model, every cube and face exactly as cut, its texture, size, tint, raw
 * faces and wounds, its condition, and for an organ or a portion of one its name, side, owner, tissue, cuts and the
 * event that made it), not where it is; that comes in a {@code PieceState}. Kept by the server so that a player who
 * comes along later finds the same pieces lying there (see {@code net/Evidence}).
 */
public final class PieceCodec {
	/** 3: wounds keep the organ they reached; 2: wounds carry their ages and the runs of blood on the skin (both are still read, and 1). */
	private static final int VERSION = 3;
	/** A horse is four parts deep and a few dozen big; diced pieces are smaller. */
	private static final int MAX_DEPTH = 16;
	private static final int MAX_PARTS = 256;
	private static final int MAX_CUBES = 64;
	private static final float MAX_NUMBER = 4096.0F;
	/** Marks the piece's identity after its condition (see {@link #identity}). */
	private static final int IDENTITY = 0x4F52474E;
	/** Marks what is inside a beast's head after its identity (see {@link #section}). */
	private static final int SECTION = 0x48454144;
	/** Longest organ name taken from a record. */
	private static final int MAX_NAME = 48;

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

		// Last, so a game from before conditions reads the rest and leaves this.
		p.condition.write(buf);
		// After that, what the piece is: a game from before organs stops reading at the condition.
		buf.writeInt(IDENTITY);
		string(buf, p.organ != null ? p.organ : "");
		string(buf, p.side);
		string(buf, p.owner != null ? BuiltInRegistries.ENTITY_TYPE.getKey(p.owner).toString() : "");
		string(buf, p.tissue != null ? p.tissue.name() : "");
		buf.writeByte(Math.min(p.cuts, 127));
		buf.writeLong(p.event);
		buf.writeByte(p.eventSource.ordinal());
		if (p.section != null) {
			buf.writeInt(SECTION);
			buf.writeByte(p.section.beast().ordinal());
			buf.writeBoolean(p.section.male());
			buf.writeBoolean(p.section.young());
			buf.writeByte(p.section.half());
			for (float f : p.section.box()) {
				buf.writeFloat(f);
			}
		}

		return ByteBufUtil.getBytes(buf);
	}

	/** The piece {@code data} describes, named {@code key}, with {@code lifetime} ticks to live; null if it is unreadable. */
	public static @Nullable GorePart read(byte[] data, long key, int lifetime) {
		ByteBuf buf = Unpooled.wrappedBuffer(data);
		try {
			int version = buf.readByte();
			if (version < 1 || version > VERSION) {
				return null;
			}

			Identifier texture = Identifier.tryParse(string(buf));
			Vector3f centre = vector(buf);
			Vector3f half = vector(buf);
			float scale = number(buf);
			int blood = buf.readInt();
			int tint = buf.readInt();
			int rawFaces = buf.readInt();
			ModelPart model = model(buf, 0, new int[1]);
			if (texture == null) {
				return null;
			}

			GorePart part = new GorePart(model, texture, centre, half, scale, blood, lifetime);
			part.key = key;
			part.tint = tint;
			part.rawFaces = rawFaces;
			if (buf.readBoolean()) {
				part.wounds = WoundCodec.read(buf, null, model, version);
			}

			// Sent by a game from before conditions: a fresh piece.
			if (buf.readableBytes() >= Condition.BYTES) {
				part.condition.read(buf);
			}

			// Sent by a game from before organs: a piece with no name of its own.
			if (buf.readableBytes() >= 4 && buf.readInt() == IDENTITY) {
				identity(buf, part);
				if (buf.readableBytes() >= 4 && buf.readInt() == SECTION) {
					section(buf, part);
				}
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

	/**
	 * Reads a part and its children. Bytes from the server are not trusted to be one of ours: every number must be one,
	 * every face a quad (all a model ever has, and all the renderer draws), and the tree no deeper or bigger than a
	 * real one, or the piece is dropped instead of crashing the renderer.
	 */
	private static ModelPart model(ByteBuf buf, int depth, int[] parts) {
		if (depth > MAX_DEPTH || ++parts[0] > MAX_PARTS) {
			throw new IllegalArgumentException("Fluid Gore piece too deep or too big");
		}

		float[] pose = new float[9];
		for (int i = 0; i < pose.length; i++) {
			pose[i] = number(buf);
		}

		boolean visible = buf.readBoolean();
		int cubeCount = buf.readShort();
		if (cubeCount < 0 || cubeCount > MAX_CUBES) {
			throw new IllegalArgumentException("Fluid Gore piece with " + cubeCount + " cubes");
		}

		List<ModelPart.Cube> cubes = new ArrayList<>(cubeCount);
		Direction[] directions = Direction.values();
		for (int c = 0; c < cubeCount; c++) {
			float minX = number(buf);
			float minY = number(buf);
			float minZ = number(buf);
			float maxX = number(buf);
			float maxY = number(buf);
			float maxZ = number(buf);
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
				if (count != 4) {
					throw new IllegalArgumentException("Fluid Gore face with " + count + " corners");
				}

				ModelPart.Vertex[] vertices = new ModelPart.Vertex[count];
				for (int k = 0; k < count; k++) {
					vertices[k] = new ModelPart.Vertex(number(buf), number(buf), number(buf), number(buf), number(buf));
				}

				cube.polygons[i] = new ModelPart.Polygon(vertices, new Vector3f(number(buf), number(buf), number(buf)));
			}

			cubes.add(cube);
		}

		int childCount = buf.readShort();
		Map<String, ModelPart> children = new HashMap<>();
		for (int c = 0; c < childCount; c++) {
			String name = string(buf);
			children.put(name, model(buf, depth + 1, parts));
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

	/**
	 * What the piece is, as {@link #write} put it: the organ's name and side, its owner, its tissue, how often it has been
	 * cut and the event that made it. A name that is not one of ours, an owner the registry does not know or a tissue that is
	 * not one leaves that field unset, so a hostile record can only make the piece nameless.
	 */
	private static void identity(ByteBuf buf, GorePart part) {
		String organ = string(buf);
		String side = string(buf);
		String owner = string(buf);
		String tissue = string(buf);
		int cuts = buf.readByte();
		long event = buf.readLong();
		int source = buf.readUnsignedByte();
		if (!organ.isEmpty() && organ.length() <= MAX_NAME && organ.chars().allMatch(c -> c == '_' || c >= 'a' && c <= 'z')) {
			part.organ = organ;
		}

		part.side = side.equals("left") || side.equals("right") ? side : "";
		Identifier type = owner.isEmpty() ? null : Identifier.tryParse(owner);
		if (type != null) {
			part.owner = BuiltInRegistries.ENTITY_TYPE.getOptional(type).orElse(null);
		}

		for (Anatomy.Kind kind : Anatomy.Kind.values()) {
			if (kind.name().equals(tissue)) {
				part.tissue = kind;
			}
		}

		part.cuts = Math.max(0, cuts);
		part.event = event;
		Events.Source[] sources = Events.Source.values();
		part.eventSource = sources[Math.min(source, sources.length - 1)];
		if (part.organ != null) {
			part.label = GorePart.organLabel(part.owner, part.organ, part.side, part.cuts);
		}
	}

	/**
	 * What is inside a beast's head, as {@link #write} put it: the beast, its sex and age, which half and the head's
	 * cube. A beast or half that is not one, or a cube that is not finite and in bounds, leaves the piece without it.
	 */
	private static void section(ByteBuf buf, GorePart part) {
		int beast = buf.readUnsignedByte();
		boolean male = buf.readBoolean();
		boolean young = buf.readBoolean();
		int half = buf.readByte();
		float[] box = new float[6];
		for (int i = 0; i < 6; i++) {
			box[i] = buf.readFloat();
			if (!Float.isFinite(box[i]) || Math.abs(box[i]) > MAX_NUMBER) {
				return;
			}
		}

		Anatomy.Beast[] beasts = Anatomy.Beast.values();
		if (beast < beasts.length && half >= -1 && half <= 1 && box[0] < box[3] && box[1] < box[4] && box[2] < box[5]) {
			part.section = new HeadSection(beasts[beast], male, young, box, half);
		}
	}

	private static void vector(ByteBuf buf, Vector3f v) {
		buf.writeFloat(v.x);
		buf.writeFloat(v.y);
		buf.writeFloat(v.z);
	}

	private static Vector3f vector(ByteBuf buf) {
		return new Vector3f(number(buf), number(buf), number(buf));
	}

	/** A float that is a number, and not an absurd one: pixels, radians, scales and texture coordinates all are small. */
	private static float number(ByteBuf buf) {
		float f = buf.readFloat();
		if (!(Math.abs(f) <= MAX_NUMBER)) {
			throw new IllegalArgumentException("Fluid Gore piece number " + f);
		}

		return f;
	}

	private static void string(ByteBuf buf, String s) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		buf.writeShort(bytes.length);
		buf.writeBytes(bytes);
	}

	private static String string(ByteBuf buf) {
		int length = buf.readShort();
		if (length < 0 || length > buf.readableBytes()) {
			throw new IllegalArgumentException("Fluid Gore piece string of " + length + " bytes");
		}

		byte[] bytes = new byte[length];
		buf.readBytes(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
