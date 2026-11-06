package dev.fluidgore.dismember;

import dev.fluidgore.anatomy.Anatomy;
import dev.fluidgore.anatomy.MeltRenderer;
import dev.fluidgore.mixin.ModelPartAccessor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * An organ out of the body as a model of its own (see {@code Viscera}): the cubes it is made of, each skinned with its
 * own tissue's tile of anatomy.png exactly as the body draws it, with only the faces that show. And the same organ cut
 * through with a blade ({@link #split}): every cube the cut passes through is clipped at the cut, each face keeping its
 * own texels where it was, and the faces the cut opens show what the tissue is like inside ({@link Anatomy.Kind#section}).
 *
 * <p>Bounded: an organ is kept as a model only up to {@link #MOST_CUBES} cubes (one evidence record's worth, see
 * {@code PieceCodec}), held in parts of at most {@link #CHUNK}; a cut that would make a portion of more is refused, and so
 * is one that would leave a portion thinner than {@link #THINNEST} pixels.
 */
public final class OrganModel {
	/** Most cubes an organ (or a portion of one) is a model of: at every face, still one evidence record. */
	public static final int MOST_CUBES = Anatomy.Shape.INTERACTIVE_CUBES;
	/** Cubes in one model part: the codec takes 64 a part. */
	private static final int CHUNK = 48;
	/** A portion is never thinner than this across the cut, in model pixels. */
	public static final float THINNEST = 0.5F;
	/** A cube the cut passes within this of an edge of is not clipped: it goes to the side it mostly lies on. */
	private static final float SLIVER = 0.05F;
	private static final float ATLAS_W = 128.0F;
	private static final float ATLAS_H = 128.0F;

	/**
	 * One cube of an organ: its box in the organ's pixels and its six faces ({@code axis * 2}, plus one for the high side),
	 * null where a face does not show. Its tissue, for the faces a cut opens.
	 */
	public record Block(float[] lo, float[] hi, ModelPart.@Nullable Polygon[] faces, Anatomy.Kind tissue) {
		float volume() {
			return (hi[0] - lo[0]) * (hi[1] - lo[1]) * (hi[2] - lo[2]);
		}
	}

	private OrganModel() {
	}

	/**
	 * The model of an organ whose joint box is {@code box} (lo x, y, z, hi x, y, z, the body part's pixels) and whose cubes
	 * are {@code shape}'s (already mirrored the way the body part's fit runs).
	 */
	public static ModelPart build(Anatomy.Shape shape, float[] box) {
		List<Block> blocks = new ArrayList<>(shape.cubes());
		for (int c = 0; c < shape.cubes(); c++) {
			float[] lo = new float[3];
			float[] hi = new float[3];
			for (int a = 0; a < 3; a++) {
				float mid = (box[a] + box[3 + a]) * 0.5F;
				float half = (box[3 + a] - box[a]) * 0.5F;
				lo[a] = mid + shape.side(c, a, false) * half;
				hi[a] = mid + shape.side(c, a, true) * half;
			}

			ModelPart.Polygon[] faces = new ModelPart.Polygon[6];
			for (int a = 0; a < 3; a++) {
				for (int s = 0; s < 2; s++) {
					if (shape.shows(c, a, s == 1)) {
						boolean max = s == 1;
						faces[a * 2 + s] = face(lo, hi, a, max, shape.uv(c, a, max, 0), shape.uv(c, a, max, 1), shape.uv(c, a, max, 2), shape.uv(c, a, max, 3),
								shape.vAlongB(c, a, max));
					}
				}
			}

			blocks.add(new Block(lo, hi, faces, shape.kind(c)));
		}

		return assemble(blocks);
	}

	/** The cubes of {@code model} and its parts, in its own pixels (its parts are not posed: they only hold cubes). */
	public static List<Block> blocks(ModelPart model, Anatomy.Kind fallback) {
		List<Block> out = new ArrayList<>();
		collect(model, 0.0F, 0.0F, 0.0F, fallback, out);
		return out;
	}

	private static void collect(ModelPart model, float ox, float oy, float oz, Anatomy.Kind fallback, List<Block> out) {
		for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) model).fluidgore$getCubes()) {
			float[] lo = {cube.minX + ox, cube.minY + oy, cube.minZ + oz};
			float[] hi = {cube.maxX + ox, cube.maxY + oy, cube.maxZ + oz};
			ModelPart.Polygon[] faces = new ModelPart.Polygon[6];
			Anatomy.Kind tissue = null;
			for (ModelPart.Polygon polygon : cube.polygons) {
				int face = faceOf(polygon, cube.minX, cube.minY, cube.minZ, cube.maxX, cube.maxY, cube.maxZ);
				if (face >= 0) {
					faces[face] = ox == 0.0F && oy == 0.0F && oz == 0.0F ? polygon : moved(polygon, ox, oy, oz);
					if (tissue == null) {
						tissue = tissueOf(polygon);
					}
				}
			}

			out.add(new Block(lo, hi, faces, tissue != null ? tissue : fallback));
		}

		for (ModelPart child : ((ModelPartAccessor) (Object) model).fluidgore$getChildren().values()) {
			collect(child, ox + child.x, oy + child.y, oz + child.z, fallback, out);
		}
	}

	/** Blocks back into a model: its cubes in parts of {@link #CHUNK}, unposed. */
	public static ModelPart assemble(List<Block> blocks) {
		List<ModelPart.Cube> first = new ArrayList<>();
		Map<String, ModelPart> children = new HashMap<>();
		List<ModelPart.Cube> current = first;
		for (int i = 0; i < blocks.size(); i++) {
			if (i > 0 && i % CHUNK == 0) {
				current = new ArrayList<>();
				children.put("cubes" + i / CHUNK, new ModelPart(current, new HashMap<>()));
			}

			current.add(cube(blocks.get(i)));
		}

		return new ModelPart(first, children);
	}

	/** Total cubes of {@code model} and its parts. */
	public static int cubes(ModelPart model) {
		int n = ((ModelPartAccessor) (Object) model).fluidgore$getCubes().size();
		for (ModelPart child : ((ModelPartAccessor) (Object) model).fluidgore$getChildren().values()) {
			n += cubes(child);
		}

		return n;
	}

	/**
	 * The organ {@code blocks} cut through at {@code at} along {@code axis}: the cubes below the cut and those above, those
	 * the cut passes through clipped in two. Null if either side would be empty, thinner than {@link #THINNEST} or over
	 * {@link #MOST_CUBES}.
	 */
	public static @Nullable List<List<Block>> split(List<Block> blocks, int axis, float at) {
		List<Block> below = new ArrayList<>();
		List<Block> above = new ArrayList<>();
		for (Block b : blocks) {
			if (b.hi()[axis] <= at + SLIVER) {
				below.add(b);
			} else if (b.lo()[axis] >= at - SLIVER) {
				above.add(b);
			} else {
				below.add(clip(b, axis, b.lo()[axis], at));
				above.add(clip(b, axis, at, b.hi()[axis]));
			}
		}

		if (below.isEmpty() || above.isEmpty() || below.size() > MOST_CUBES || above.size() > MOST_CUBES) {
			return null;
		}

		for (List<Block> side : List.of(below, above)) {
			float lo = Float.MAX_VALUE;
			float hi = -Float.MAX_VALUE;
			for (Block b : side) {
				lo = Math.min(lo, b.lo()[axis]);
				hi = Math.max(hi, b.hi()[axis]);
			}

			if (hi - lo < THINNEST) {
				return null;
			}
		}

		open(below);
		open(above);
		return List.of(below, above);
	}

	/** The joint box of {@code blocks}: lo x, y, z, hi x, y, z. */
	public static float[] bounds(List<Block> blocks) {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (Block block : blocks) {
			for (int a = 0; a < 3; a++) {
				b[a] = Math.min(b[a], block.lo()[a]);
				b[3 + a] = Math.max(b[3 + a], block.hi()[a]);
			}
		}

		return b;
	}

	public static float volume(List<Block> blocks) {
		float v = 0.0F;
		for (Block b : blocks) {
			v += b.volume();
		}

		return v;
	}

	/** {@code b} between {@code from} and {@code to} along {@code axis}, each face keeping its texels where they were. */
	private static Block clip(Block b, int axis, float from, float to) {
		float[] lo = b.lo().clone();
		float[] hi = b.hi().clone();
		lo[axis] = from;
		hi[axis] = to;
		ModelPart.Polygon[] faces = new ModelPart.Polygon[6];
		for (int f = 0; f < 6; f++) {
			ModelPart.Polygon old = b.faces()[f];
			int a = f >> 1;
			boolean max = (f & 1) == 1;
			if (old == null) {
				continue;
			}

			if (a == axis) {
				// The end faces: only the one still at its place is kept (the cut face is made by open).
				if (max ? to == b.hi()[axis] : from == b.lo()[axis]) {
					faces[f] = old;
				}

				continue;
			}

			faces[f] = refit(old, b.lo(), b.hi(), lo, hi, a, max);
		}

		return new Block(lo, hi, faces, b.tissue());
	}

	/**
	 * Gives every face of {@code side} that shows now and has no skin (it was inside the organ, against a cube that went to
	 * the other side, or the cut went through it) the inside of its tissue.
	 */
	private static void open(List<Block> side) {
		for (int i = 0; i < side.size(); i++) {
			Block b = side.get(i);
			for (int f = 0; f < 6; f++) {
				if (b.faces()[f] != null || covered(side, i, f >> 1, (f & 1) == 1)) {
					continue;
				}

				int a = f >> 1;
				boolean max = (f & 1) == 1;
				int bb = (a + 1) % 3;
				int cc = (a + 2) % 3;
				boolean along = bb == 1;
				float spanU = Math.min((along ? b.hi()[cc] - b.lo()[cc] : b.hi()[bb] - b.lo()[bb]), 16.0F);
				float spanV = Math.min((along ? b.hi()[bb] - b.lo()[bb] : b.hi()[cc] - b.lo()[cc]), 16.0F);
				Anatomy.Kind inside = b.tissue().section();
				int seed = Math.round(b.lo()[0] * 4.0F) * 961 + Math.round(b.lo()[1] * 4.0F) * 31 + Math.round(b.lo()[2] * 4.0F);
				float[] w = MeltRenderer.windowOf(inside.tile, seed, f, Math.max(spanU, 1.0F), Math.max(spanV, 1.0F));
				b.faces()[f] = face(b.lo(), b.hi(), a, max, w[0], w[1], w[2], w[3], along);
			}
		}
	}

	/** True if face {@code axis}/{@code max} of block {@code index} lies flat against another block of {@code side} that covers it. */
	private static boolean covered(List<Block> side, int index, int axis, boolean max) {
		Block p = side.get(index);
		float plane = max ? p.hi()[axis] : p.lo()[axis];
		int b = (axis + 1) % 3;
		int c = (axis + 2) % 3;
		for (int j = 0; j < side.size(); j++) {
			Block q = side.get(j);
			if (j == index || Math.abs((max ? q.lo()[axis] : q.hi()[axis]) - plane) > 1.0E-3F) {
				continue;
			}

			if (q.lo()[b] <= p.lo()[b] + 1.0E-3F && q.hi()[b] >= p.hi()[b] - 1.0E-3F && q.lo()[c] <= p.lo()[c] + 1.0E-3F && q.hi()[c] >= p.hi()[c] - 1.0E-3F) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Face {@code axis}/{@code max} of the box {@code lo}..{@code hi}, wound as the game winds a cube's, showing the atlas
	 * window u0, v0 to u1, v1: v along the face's first axis ({@code (axis + 1) % 3}) if {@code vAlongB}, else u.
	 */
	private static ModelPart.Polygon face(float[] lo, float[] hi, int axis, boolean max, float u0, float v0, float u1, float v1, boolean vAlongB) {
		ModelPart.Polygon shape = reference(lo, hi, axis, max);
		int b = (axis + 1) % 3;
		int c = (axis + 2) % 3;
		ModelPart.Vertex[] vertices = new ModelPart.Vertex[4];
		for (int k = 0; k < 4; k++) {
			ModelPart.Vertex v = shape.vertices()[k];
			float[] p = {v.x(), v.y(), v.z()};
			float tb = along(p[b], lo[b], hi[b]);
			float tc = along(p[c], lo[c], hi[c]);
			float u = vAlongB ? u0 + (u1 - u0) * tc : u0 + (u1 - u0) * tb;
			float w = vAlongB ? v0 + (v1 - v0) * tb : v0 + (v1 - v0) * tc;
			vertices[k] = new ModelPart.Vertex(v.x(), v.y(), v.z(), u, w);
		}

		return new ModelPart.Polygon(vertices, shape.normal());
	}

	/**
	 * Face {@code axis}/{@code max} of {@code old}'s box {@code oldLo}..{@code oldHi}, cut down to the box {@code lo}..{@code hi}:
	 * the same texels at the same places (its texture runs straight across the face, so it is found from three corners).
	 */
	private static ModelPart.Polygon refit(ModelPart.Polygon old, float[] oldLo, float[] oldHi, float[] lo, float[] hi, int axis, boolean max) {
		int b = (axis + 1) % 3;
		int c = (axis + 2) % 3;
		// The texels at the face's (b0, c0), (b1, c0) and (b0, c1) corners.
		float[][] at = new float[4][];
		for (ModelPart.Vertex v : old.vertices()) {
			float[] p = {v.x(), v.y(), v.z()};
			int corner = (along(p[b], oldLo[b], oldHi[b]) > 0.5F ? 1 : 0) | (along(p[c], oldLo[c], oldHi[c]) > 0.5F ? 2 : 0);
			at[corner] = new float[]{v.u(), v.v()};
		}

		ModelPart.Polygon shape = reference(lo, hi, axis, max);
		ModelPart.Vertex[] vertices = new ModelPart.Vertex[4];
		for (int k = 0; k < 4; k++) {
			ModelPart.Vertex v = shape.vertices()[k];
			float[] p = {v.x(), v.y(), v.z()};
			float tb = along(p[b], oldLo[b], oldHi[b]);
			float tc = along(p[c], oldLo[c], oldHi[c]);
			float u = at[0][0] + (at[1][0] - at[0][0]) * tb + (at[2][0] - at[0][0]) * tc;
			float w = at[0][1] + (at[1][1] - at[0][1]) * tb + (at[2][1] - at[0][1]) * tc;
			vertices[k] = new ModelPart.Vertex(v.x(), v.y(), v.z(), u, w);
		}

		return new ModelPart.Polygon(vertices, shape.normal());
	}

	/** The game's own face of a box, for its corners and winding (its texels are replaced). */
	private static ModelPart.Polygon reference(float[] lo, float[] hi, int axis, boolean max) {
		Direction dir = Direction.fromAxisAndDirection(Direction.Axis.values()[axis], max ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
		ModelPart.Cube cube = new ModelPart.Cube(0, 0, lo[0], lo[1], lo[2], hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2], 0.0F, 0.0F, 0.0F, false, 1.0F, 1.0F,
				EnumSet.of(dir));
		return cube.polygons[0];
	}

	private static ModelPart.Cube cube(Block b) {
		int n = 0;
		for (ModelPart.Polygon face : b.faces()) {
			n += face != null ? 1 : 0;
		}

		// A cube with that many faces, whose faces are then replaced by the block's own (see PieceCodec).
		EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);
		Direction[] directions = Direction.values();
		for (int i = 0; i < n; i++) {
			faces.add(directions[i]);
		}

		ModelPart.Cube cube = new ModelPart.Cube(0, 0, b.lo()[0], b.lo()[1], b.lo()[2], b.hi()[0] - b.lo()[0], b.hi()[1] - b.lo()[1], b.hi()[2] - b.lo()[2], 0.0F,
				0.0F, 0.0F, false, 1.0F, 1.0F, faces);
		int i = 0;
		for (ModelPart.Polygon face : b.faces()) {
			if (face != null) {
				cube.polygons[i++] = face;
			}
		}

		return cube;
	}

	/** Which face of the box a polygon is ({@code axis * 2}, plus one for the high side), or -1 if it is none of them. */
	private static int faceOf(ModelPart.Polygon polygon, float x0, float y0, float z0, float x1, float y1, float z1) {
		float[] lo = {x0, y0, z0};
		float[] hi = {x1, y1, z1};
		for (int a = 0; a < 3; a++) {
			boolean allLo = true;
			boolean allHi = true;
			for (ModelPart.Vertex v : polygon.vertices()) {
				float p = a == 0 ? v.x() : a == 1 ? v.y() : v.z();
				allLo &= Math.abs(p - lo[a]) < 1.0E-4F;
				allHi &= Math.abs(p - hi[a]) < 1.0E-4F;
			}

			if (allLo && !allHi) {
				return a * 2;
			}

			if (allHi && !allLo) {
				return a * 2 + 1;
			}
		}

		return -1;
	}

	/** The tissue whose tile of anatomy.png a face shows, read from where its texels lie; null if it is not on one. */
	private static Anatomy.@Nullable Kind tissueOf(ModelPart.Polygon polygon) {
		float u = 0.0F;
		float v = 0.0F;
		for (ModelPart.Vertex vertex : polygon.vertices()) {
			u += vertex.u() * 0.25F;
			v += vertex.v() * 0.25F;
		}

		int tile = (int) Math.floor(u * ATLAS_W / 16.0F) + (int) Math.floor(v * ATLAS_H / 16.0F) * 8;
		for (Anatomy.Kind kind : Anatomy.Kind.values()) {
			if (kind.tile == tile) {
				return kind;
			}
		}

		return null;
	}

	private static ModelPart.Polygon moved(ModelPart.Polygon polygon, float ox, float oy, float oz) {
		ModelPart.Vertex[] vertices = new ModelPart.Vertex[polygon.vertices().length];
		for (int i = 0; i < vertices.length; i++) {
			ModelPart.Vertex v = polygon.vertices()[i];
			vertices[i] = new ModelPart.Vertex(v.x() + ox, v.y() + oy, v.z() + oz, v.u(), v.v());
		}

		return new ModelPart.Polygon(vertices, polygon.normal());
	}

	private static float along(float p, float lo, float hi) {
		return hi - lo < 1.0E-6F ? 0.0F : (p - lo) / (hi - lo);
	}
}
