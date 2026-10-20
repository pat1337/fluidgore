package dev.fluidgore.ragdoll;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * Cuts a model cube into pieces across one axis, each skinned exactly as that stretch of the whole cube was, with its
 * cut faces capped by the skin of the cube's ends.
 *
 * <p>Every piece is built with vanilla's own {@link ModelPart.Cube} constructor. Sodium draws cubes from the
 * constructor's arguments and never reads the polygons, so a cube whose polygons were patched afterwards would show its
 * old skin there. The constructor lays the skin out as one box unwrap from a texture offset, which cannot match a
 * stretch of a bigger box on every face at once; so each piece is a few cubes, one per group of faces that share an
 * offset, each showing only its own faces.
 */
public final class CubeSplit {
	private static final float EPSILON = 1.0E-3F;

	/** How a cube was made: what its constructor was given. */
	private record Source(int u, int v, float growX, float growY, float growZ, boolean mirror, float texW, float texH, Set<Direction> faces) {
	}

	private CubeSplit() {
	}

	/**
	 * The pieces of {@code cube} between the boundaries {@code bounds} (ascending, along {@code axis}, in the part's
	 * pixels; the first and last may lie beyond the cube), in the cube's own coordinates; a piece the cube does not reach
	 * is empty. Null if the cube's skin cannot be reproduced (an odd texture layout, or a cut off the pixel grid of its
	 * skin).
	 */
	public static @Nullable List<List<ModelPart.Cube>> split(ModelPart.Cube cube, int axis, float[] bounds) {
		Source source = source(cube);
		if (source == null) {
			return null;
		}

		float[] min = {cube.minX, cube.minY, cube.minZ};
		float[] max = {cube.maxX, cube.maxY, cube.maxZ};
		List<List<ModelPart.Cube>> pieces = new ArrayList<>();
		for (int j = 0; j + 1 < bounds.length; j++) {
			float[] lo = min.clone();
			float[] hi = max.clone();
			lo[axis] = Math.max(min[axis], bounds[j]);
			hi[axis] = Math.min(max[axis], bounds[j + 1]);
			List<ModelPart.Cube> piece = new ArrayList<>();
			if (hi[axis] - lo[axis] > EPSILON) {
				if (!piece(cube, source, min, max, lo, hi, piece)) {
					return null;
				}
			}

			pieces.add(piece);
		}

		return pieces;
	}

	/** Builds the cubes of one piece into {@code out}. False if a face's skin could not be matched. */
	private static boolean piece(ModelPart.Cube cube, Source s, float[] min, float[] max, float[] lo, float[] hi, List<ModelPart.Cube> out) {
		Map<Long, Set<Direction>> groups = new LinkedHashMap<>();
		for (Direction face : s.faces()) {
			// The constructor's label for the face that ends up facing this way (mirroring swaps east and west).
			Direction label = s.mirror() && face.getAxis() == Direction.Axis.X ? face.getOpposite() : face;
			ModelPart.Cube probe = build(0, 0, lo, hi, s, EnumSet.of(label));
			ModelPart.Polygon wanted = polygon(cube, face);
			ModelPart.Polygon got = probe.polygons[0];
			if (wanted == null) {
				return false;
			}

			float du = Float.NaN;
			float dv = Float.NaN;
			for (ModelPart.Vertex vertex : got.vertices()) {
				float[] uv = skinAt(wanted, cube, min, max, corner(vertex, lo, hi));
				float ou = uv[0] * s.texW() - vertex.u() * s.texW();
				float ov = uv[1] * s.texH() - vertex.v() * s.texH();
				if (Float.isNaN(du)) {
					du = ou;
					dv = ov;
				} else if (Math.abs(ou - du) > EPSILON || Math.abs(ov - dv) > EPSILON) {
					return false;
				}
			}

			if (Math.abs(du - Math.round(du)) > EPSILON || Math.abs(dv - Math.round(dv)) > EPSILON) {
				return false;
			}

			groups.computeIfAbsent((long) Math.round(du) << 32 | Math.round(dv) & 0xFFFFFFFFL, k -> EnumSet.noneOf(Direction.class)).add(label);
		}

		for (Map.Entry<Long, Set<Direction>> group : groups.entrySet()) {
			int u = (int) (group.getKey() >> 32);
			int v = (int) (long) group.getKey();
			out.add(build(u, v, lo, hi, s, group.getValue()));
		}

		return true;
	}

	private static ModelPart.Cube build(int u, int v, float[] lo, float[] hi, Source s, Set<Direction> faces) {
		return new ModelPart.Cube(u, v, lo[0], lo[1], lo[2], hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2],
				s.growX(), s.growY(), s.growZ(), s.mirror(), s.texW(), s.texH(), faces);
	}

	/**
	 * Where a vertex of a piece sits on the whole cube, as a fraction 0..1 along each axis. The vertex is at one of the
	 * piece's grown corners; the skin is laid on the ungrown size, so the matching ungrown corner is what counts.
	 */
	private static float[] corner(ModelPart.Vertex vertex, float[] lo, float[] hi) {
		float[] p = {vertex.x(), vertex.y(), vertex.z()};
		float[] mid = {(lo[0] + hi[0]) * 0.5F, (lo[1] + hi[1]) * 0.5F, (lo[2] + hi[2]) * 0.5F};
		float[] out = new float[3];
		for (int i = 0; i < 3; i++) {
			out[i] = p[i] < mid[i] - EPSILON ? lo[i] : p[i] > mid[i] + EPSILON ? hi[i] : mid[i];
		}

		return out;
	}

	/** The skin (u, v, 0..1) the whole cube shows at an ungrown point on its face {@code face}. */
	private static float[] skinAt(ModelPart.Polygon face, ModelPart.Cube cube, float[] min, float[] max, float[] point) {
		float[] gmin = new float[3];
		float[] gmax = new float[3];
		grownBounds(cube, gmin, gmax);
		// Affine in the two directions across the face: find the corner at the low end of both, and the change along each.
		int normal = axisOf(face);
		int b = (normal + 1) % 3;
		int c = (normal + 2) % 3;
		float[] origin = null;
		float[] alongB = null;
		float[] alongC = null;
		for (ModelPart.Vertex vertex : face.vertices()) {
			float[] p = {vertex.x(), vertex.y(), vertex.z()};
			boolean lowB = Math.abs(p[b] - gmin[b]) < Math.abs(p[b] - gmax[b]);
			boolean lowC = Math.abs(p[c] - gmin[c]) < Math.abs(p[c] - gmax[c]);
			float[] uv = {vertex.u(), vertex.v()};
			if (lowB && lowC) {
				origin = uv;
			} else if (!lowB && lowC) {
				alongB = uv;
			} else if (lowB) {
				alongC = uv;
			}
		}

		if (origin == null || alongB == null || alongC == null) {
			return new float[] {Float.NaN, Float.NaN};
		}

		float fb = max[b] - min[b] > EPSILON ? (point[b] - min[b]) / (max[b] - min[b]) : 0.0F;
		float fc = max[c] - min[c] > EPSILON ? (point[c] - min[c]) / (max[c] - min[c]) : 0.0F;
		return new float[] {origin[0] + (alongB[0] - origin[0]) * fb + (alongC[0] - origin[0]) * fc,
				origin[1] + (alongB[1] - origin[1]) * fb + (alongC[1] - origin[1]) * fc};
	}

	/**
	 * Reads back what the cube's constructor was given: texture offset and size, growth, mirroring and faces. Every face
	 * of a cube rebuilt from that must land exactly on the original's skin, or the cube is left whole.
	 */
	private static @Nullable Source source(ModelPart.Cube cube) {
		if (cube.polygons.length == 0) {
			return null;
		}

		float[] gmin = new float[3];
		float[] gmax = new float[3];
		grownBounds(cube, gmin, gmax);
		float[] min = {cube.minX, cube.minY, cube.minZ};
		float[] max = {cube.maxX, cube.maxY, cube.maxZ};
		float growX = min[0] - gmin[0];
		float growY = min[1] - gmin[1];
		float growZ = min[2] - gmin[2];
		Set<Direction> faces = EnumSet.noneOf(Direction.class);
		for (ModelPart.Polygon polygon : cube.polygons) {
			Direction facing = Direction.getApproximateNearest(polygon.normal().x(), polygon.normal().y(), polygon.normal().z());
			faces.add(facing);
		}

		if (faces.size() != cube.polygons.length) {
			return null;
		}

		for (boolean mirror : new boolean[] {false, true}) {
			Set<Direction> labels = EnumSet.noneOf(Direction.class);
			for (Direction face : faces) {
				labels.add(mirror && face.getAxis() == Direction.Axis.X ? face.getOpposite() : face);
			}

			// In texture pixels: build with a 1x1 texture, so the probe's uv are pixel positions.
			ModelPart.Cube probe = new ModelPart.Cube(0, 0, min[0], min[1], min[2], max[0] - min[0], max[1] - min[1], max[2] - min[2], growX, growY,
					growZ, mirror, 1.0F, 1.0F, labels);
			float texW = Float.NaN;
			float texH = Float.NaN;
			for (ModelPart.Polygon polygon : probe.polygons) {
				Direction facing = Direction.getApproximateNearest(polygon.normal().x(), polygon.normal().y(), polygon.normal().z());
				ModelPart.Polygon original = polygon(cube, facing);
				if (original == null) {
					break;
				}

				float pu = span(polygon, true);
				float pv = span(polygon, false);
				float ou = span(original, true);
				float ov = span(original, false);
				if (Float.isNaN(texW) && ou > 1.0E-6F && pu > EPSILON) {
					texW = pu / ou;
				}

				if (Float.isNaN(texH) && ov > 1.0E-6F && pv > EPSILON) {
					texH = pv / ov;
				}
			}

			if (Float.isNaN(texW) || Float.isNaN(texH)) {
				continue;
			}

			float du = Float.NaN;
			float dv = Float.NaN;
			boolean matches = true;
			for (int i = 0; i < probe.polygons.length && matches; i++) {
				ModelPart.Polygon polygon = probe.polygons[i];
				Direction facing = Direction.getApproximateNearest(polygon.normal().x(), polygon.normal().y(), polygon.normal().z());
				ModelPart.Polygon original = polygon(cube, facing);
				if (original == null || original.vertices().length != polygon.vertices().length) {
					matches = false;
					break;
				}

				for (int k = 0; k < polygon.vertices().length; k++) {
					ModelPart.Vertex a = polygon.vertices()[k];
					ModelPart.Vertex b = original.vertices()[k];
					if (Math.abs(a.x() - b.x()) > EPSILON || Math.abs(a.y() - b.y()) > EPSILON || Math.abs(a.z() - b.z()) > EPSILON) {
						matches = false;
						break;
					}

					float ou = b.u() * texW - a.u();
					float ov = b.v() * texH - a.v();
					if (Float.isNaN(du)) {
						du = ou;
						dv = ov;
					} else if (Math.abs(ou - du) > 0.01F || Math.abs(ov - dv) > 0.01F) {
						matches = false;
						break;
					}
				}
			}

			if (matches && Math.abs(du - Math.round(du)) < 0.01F && Math.abs(dv - Math.round(dv)) < 0.01F) {
				return new Source(Math.round(du), Math.round(dv), growX, growY, growZ, mirror, texW, texH, faces);
			}
		}

		return null;
	}

	private static float span(ModelPart.Polygon polygon, boolean u) {
		float lo = Float.MAX_VALUE;
		float hi = -Float.MAX_VALUE;
		for (ModelPart.Vertex vertex : polygon.vertices()) {
			float value = u ? vertex.u() : vertex.v();
			lo = Math.min(lo, value);
			hi = Math.max(hi, value);
		}

		return hi - lo;
	}

	private static ModelPart.@Nullable Polygon polygon(ModelPart.Cube cube, Direction facing) {
		for (ModelPart.Polygon polygon : cube.polygons) {
			if (Direction.getApproximateNearest(polygon.normal().x(), polygon.normal().y(), polygon.normal().z()) == facing) {
				return polygon;
			}
		}

		return null;
	}

	private static int axisOf(ModelPart.Polygon polygon) {
		return Direction.getApproximateNearest(polygon.normal().x(), polygon.normal().y(), polygon.normal().z()).getAxis().ordinal();
	}

	private static void grownBounds(ModelPart.Cube cube, float[] min, float[] max) {
		min[0] = min[1] = min[2] = Float.MAX_VALUE;
		max[0] = max[1] = max[2] = -Float.MAX_VALUE;
		for (ModelPart.Polygon polygon : cube.polygons) {
			for (ModelPart.Vertex vertex : polygon.vertices()) {
				min[0] = Math.min(min[0], vertex.x());
				min[1] = Math.min(min[1], vertex.y());
				min[2] = Math.min(min[2], vertex.z());
				max[0] = Math.max(max[0], vertex.x());
				max[1] = Math.max(max[1], vertex.y());
				max[2] = Math.max(max[2], vertex.z());
			}
		}
	}
}
