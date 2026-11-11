package dev.fluidgore.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fluidgore.blood.BloodCell;
import dev.fluidgore.blood.BloodFluidSim;
import dev.fluidgore.blood.BloodTextures;
import dev.fluidgore.blood.Surface;
import dev.fluidgore.blood.SurfaceProbe;
import dev.fluidgore.config.BloodColor;
import dev.fluidgore.config.GoreConfig;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the blood on the ground ({@link BloodFluidSim}) as one surface rather than a mark per block.
 *
 * <p>Each cell's sub-cells are joined into an outline: where a wet sub-cell meets a dry one the edge falls between
 * them, nearer the wet one the less blood it holds, so a pool has a rounded edge that creeps outwards as it fills
 * instead of a step along the block grid. How full a place counts as eases from one sub-cell to the next rather than
 * running straight between them, which is what rounds the outline: a single wet sub-cell is a round spot, not a
 * diamond. Against a wall or the lip of a step the blood goes right up to it. A cell's mesh is built once and kept
 * until its blood or a neighbour's changes.
 *
 * <p>The outline is drawn as finely as {@link GoreConfig#bloodResolution} says. At 16 it is cut into the same pixels
 * the game's block textures have, laid on the same grid, so blood looks like part of the block it lies on; at 32 and
 * 64 the pixels are smaller; Smooth cuts the outline as a clean line through the sub-cells (marching squares). The
 * surface is textured to match, from tiles drawn at that resolution.
 *
 * <p>How it looks follows what it is. A thin film is scarlet and a pool darkens to crimson the deeper it stands; it
 * mirrors the sky more the flatter you look across it, as any wet floor does, and small highlights come and go on it
 * as you move. As it sets it loses that shine, and what has soaked in or dried on is a flat, dark stain that lets the
 * floor show through.
 */
public final class FluidRenderer {
	private static final float ISO = 0.5F;
	/** Floats a vertex: x, y, z from the cell's low corner, u, v, how much it mirrors, and which layer it is in. */
	private static final int STRIDE = 7;
	private static final float BODY_LIFT = 0.003F;
	private static final float OVER_LIFT = 0.0038F;
	private static final float LAYER_BODY = 0.0F;
	/** The cracks of a dried pool, over the body. */
	private static final float LAYER_CRUST = 1.0F;
	/** Highlights on wet blood, over the body. */
	private static final float LAYER_SPARK = 2.0F;
	/** Blood pouring down a step: this plus the side it pours from. */
	private static final float LAYER_FALL = 3.0F;
	/** Depth, mm, up to which a film is drawn flat on its floor; deeper, its surface rises with it. */
	private static final float FLAT_MM = 3.0F;
	/**
	 * How dry (see {@link GoreRenderer#shade}) blood that has dried onto a hard floor looks, and blood that has soaked
	 * into a soft one, however fresh the pool beside it is.
	 */
	private static final float DRIED_ON = 0.85F;
	private static final float SOAKED_IN = 0.55F;
	/** Most meshes rebuilt in one frame; the rest keep last frame's for one more. */
	private static final int MAX_BUILDS = 40;
	private static final double MAX_DISTANCE_SQ = 96.0 * 96.0;
	private static final int OVERLAY = OverlayTexture.NO_OVERLAY;
	private static final byte FLOOR = 0;
	private static final byte SOLID = 1;
	private static final byte OPEN = 2;
	private static final byte UNKNOWN = 3;
	/** Corners sampled for one cell: its own 8 by 8 sub-cell centres and one ring of the neighbours'. */
	private static final int SPAN = BloodCell.N + 2;
	private static final int[] DX = {-1, 1, 0, 0};
	private static final int[] DZ = {0, 0, -1, 1};

	/** What the sky gives a wet surface to mirror, this frame. */
	public record View(float skyR, float skyG, float skyB, float sunX, float sunY, float sunZ, float sun, boolean packs) {
	}

	private static final class Mesh {
		float[] data = new float[0];
		int[] argb = new int[0];
		int vertices;
		long stamp = Long.MIN_VALUE;
	}

	// Scratch for building one mesh.
	private static float[] data = new float[STRIDE * 2048];
	private static int[] argb = new int[2048];
	private static int count;
	private static final BloodCell[] AROUND = new BloodCell[9];
	private static final float[][] BASE = new float[9][3];
	private static final float[][] DRY = new float[9][3];
	/** How far towards white a highlight is, for the mesh being built: all the way drawn finely, a lighter red in coarse pixels. */
	private static float whiten;
	private static final byte[] KIND = new byte[SPAN * SPAN];
	private static final float[] FILL = new float[SPAN * SPAN];
	private static final float[] DEEP = new float[SPAN * SPAN];
	private static final float[] GROUND = new float[SPAN * SPAN];
	private static final float[] SHINE = new float[SPAN * SPAN];
	private static final int[] TINT = new int[SPAN * SPAN];
	// One polygon out of a square: up to six corners.
	private static final float[] PX = new float[8];
	private static final float[] PY = new float[8];
	private static final float[] PZ = new float[8];
	private static final float[] PG = new float[8];
	private static final int[] PC = new int[8];
	/** Steps along each side that a partly covered square is cut into when it is drawn smooth. */
	private static final int FINE = 3;
	// The four corners of the square being cut, in the order the outline is walked: how full each counts as on the floor
	// level in hand, how full it really is there (NaN if it is not that floor), the corner it takes its look from, and
	// how far its blood stands over the floor.
	private static final int[] QC = new int[4];
	private static final float[] QV = new float[4];
	private static final float[] QR = new float[4];
	private static final int[] QS = new int[4];
	private static final float[] QL = new float[4];
	private static final float[] FIELD = new float[(FINE + 1) * (FINE + 1)];
	private static int builds;
	/** Heights of the wet floors drawn this frame, for the sun's glint on them. */
	private static final double[] PLANES = new double[6];
	private static int planes;

	private FluidRenderer() {
	}

	/** Every cell in view: its stain and its liquid. */
	static void emit(PoseStack.Pose pose, VertexConsumer buffer, BloodFluidSim sim, Vec3 cam, Frustum frustum, View view) {
		builds = 0;
		planes = 0;
		int texels = GoreConfig.get().bloodResolution.texels;
		for (BloodCell cell : sim.cells()) {
			double cx = cell.bx + 0.5;
			double cz = cell.bz + 0.5;
			double distance = cam.distanceToSqr(cx, cell.surfaceY, cz);
			if (distance > MAX_DISTANCE_SQ) {
				continue;
			}

			double top = cell.by + 1.0 + cell.deepest() / 1000.0;
			if (!frustum.isVisible(new AABB(cell.bx - 0.13, cell.by - 1.0, cell.bz - 0.13, cell.bx + 1.13, top, cell.bz + 1.13))) {
				continue;
			}

			float fade = cell.fade();
			if (fade <= 0.01F) {
				continue;
			}

			Mesh mesh = (Mesh) cell.mesh;
			if (mesh == null) {
				mesh = new Mesh();
				cell.mesh = mesh;
			}

			long stamp = stamp(sim, cell, texels);
			if (stamp != mesh.stamp && (builds < MAX_BUILDS || mesh.vertices == 0)) {
				builds++;
				build(sim, cell, mesh, texels);
				mesh.stamp = stamp;
			}

			if (mesh.vertices == 0) {
				continue;
			}

			if (planes < PLANES.length && cell.litres > 0.02F && cell.surface.gloss > 0.4F && BloodFluidSim.gel(cell) < 0.7F
					&& LightCoordsUtil.sky(cell.light) >= 13) {
				plane(cell.surfaceY);
			}

			draw(pose, buffer, cell, mesh, cam, view, fade, (float) Math.sqrt(distance), texels);
		}
	}

	private static void plane(double y) {
		for (int i = 0; i < planes; i++) {
			if (Math.abs(PLANES[i] - y) < 0.02) {
				return;
			}
		}

		PLANES[planes++] = y;
	}

	/** A number that changes whenever what the cell's mesh shows does: its own blood, its neighbours' along its edges, its age. */
	private static long stamp(BloodFluidSim sim, BloodCell cell, int texels) {
		long s = cell.version * 31L + cell.age / 40;
		for (int oz = -1; oz <= 1; oz++) {
			for (int ox = -1; ox <= 1; ox++) {
				if (ox == 0 && oz == 0) {
					continue;
				}

				BloodCell n = sim.cellAt(cell.bx + ox, cell.by, cell.bz + oz);
				s = s * 31L + (n == null ? -1 : n.version);
			}
		}

		return s * 131L + texels;
	}

	private static void draw(PoseStack.Pose pose, VertexConsumer buffer, BloodCell cell, Mesh mesh, Vec3 cam, View view, float fade, float distance,
			int texels) {
		float ox = (float) (cell.bx - cam.x);
		float oy = (float) (cell.by - cam.y);
		float oz = (float) (cell.bz - cam.z);
		// Further off, the depth buffer is coarser: lift the film a little more so it does not flicker against the floor.
		float spread = Math.max(1.0F, distance / 12.0F);
		int light = BloodColor.glows(cell.rgb) ? LightCoordsUtil.FULL_BRIGHT : cell.light;
		// What a wet surface mirrors here: the sky as far as it sees it, and a little of whatever lights the place.
		float sky = LightCoordsUtil.sky(cell.light) / 15.0F;
		float lamp = LightCoordsUtil.block(cell.light) / 15.0F * 0.45F;
		// Across a floor it is the sky near the horizon that is mirrored, which is paler than the sky overhead.
		float mr = Math.min(1.0F, (view.skyR * 0.5F + 0.5F) * sky + 0.95F * lamp);
		float mg = Math.min(1.0F, (view.skyG * 0.5F + 0.5F) * sky + 0.8F * lamp);
		float mb = Math.min(1.0F, (view.skyB * 0.5F + 0.5F) * sky + 0.6F * lamp);
		// Drawn in the game's own pixels, blood keeps more of its own colour: a block texture does not turn to sky. Even
		// drawn smooth the sky is only a sheen on it: mixed in any further, dark crimson turns a flat mauve pink.
		float most = texels == 16 ? 0.14F : texels == 32 ? 0.2F : 0.35F;
		float lit = Math.min(1.0F, sky + lamp * 2.0F);
		float[] d = mesh.data;
		for (int i = 0; i < mesh.vertices; i++) {
			int o = i * STRIDE;
			float layer = d[o + 6];
			float lift = layer == LAYER_BODY ? BODY_LIFT * spread : layer < LAYER_FALL ? OVER_LIFT * spread : 0.0F;
			float x = ox + d[o];
			float y = oy + d[o + 1] + lift;
			float z = oz + d[o + 2];
			int color = mesh.argb[i];
			float a = (color >>> 24) * fade;
			float gloss = d[o + 5];
			if (view.packs && layer == LAYER_BODY) {
				// A shader pack lights the world brighter than the game does: blood keeps its depth of colour under it.
				color = color & 0xFF000000 | (color >> 16 & 0xFF) * 4 / 5 << 16 | (color >> 8 & 0xFF) * 4 / 5 << 8 | (color & 0xFF) * 4 / 5;
			}

			if (layer == LAYER_SPARK) {
				// Highlights come and go as the eye moves across them, each in its own time.
				float length = (float) Math.sqrt(x * x + y * y + z * z);
				float turn = length > 1.0E-4F ? (x * 13.0F + z * 11.0F) / length : 0.0F;
				float phase = (cell.bx + d[o]) * 2.3F + (cell.bz + d[o + 2]) * 3.1F;
				a *= (0.55F + 0.45F * Mth.sin(turn + phase)) * lit;
			} else if (gloss > 0.01F) {
				// Fresnel: looked at steeply, a liquid shows its own colour; across it, what is above it.
				float length = (float) Math.sqrt(x * x + y * y + z * z);
				float cos = length > 1.0E-4F ? Math.abs(y) / length : 1.0F;
				float one = 1.0F - cos;
				float mirror = Math.min(most, (0.07F + 0.93F * one * one * one * one * one) * gloss);
				float r = (color >> 16 & 0xFF) / 255.0F;
				float g = (color >> 8 & 0xFF) / 255.0F;
				float b = (color & 0xFF) / 255.0F;
				r += (mr - r) * mirror;
				g += (mg - g) * mirror;
				b += (mb - b) * mirror;
				a += (255.0F * fade - a) * mirror;
				color = (int) (r * 255.0F) << 16 | (int) (g * 255.0F) << 8 | (int) (b * 255.0F);
			}

			float nx = 0.0F;
			float ny = 1.0F;
			float nz = 0.0F;
			if (layer >= LAYER_FALL) {
				int k = (int) (layer - LAYER_FALL);
				nx = DX[k];
				ny = 0.0F;
				nz = DZ[k];
			}

			buffer.addVertex(pose, x, y, z)
					.setColor(Math.clamp((int) a, 0, 255) << 24 | color & 0xFFFFFF)
					.setUv(d[o + 3], d[o + 4])
					.setOverlay(OVERLAY)
					.setLight(light)
					.setNormal(pose, nx, ny, nz);
		}
	}

	private static int corner(int ix, int iz) {
		return (iz + 1) * SPAN + ix + 1;
	}

	/** Builds the mesh of one cell from its sub-cells and the ring of its neighbours' round them. */
	private static void build(BloodFluidSim sim, BloodCell c, Mesh mesh, int texels) {
		count = 0;
		for (int oz = -1; oz <= 1; oz++) {
			for (int ox = -1; ox <= 1; ox++) {
				int a = (oz + 1) * 3 + ox + 1;
				BloodCell n = ox == 0 && oz == 0 ? c : sim.known(c.bx + ox, c.by, c.bz + oz);
				AROUND[a] = n;
				// A neighbour with blood of its own gives its colour to the edge between; one without takes this cell's.
				BloodCell tinted = n != null && n.serial >= 0L ? n : c;
				int shade = GoreRenderer.shade(tinted.rgb, tinted.dryness());
				BASE[a][0] = shade >> 16 & 0xFF;
				BASE[a][1] = shade >> 8 & 0xFF;
				BASE[a][2] = shade & 0xFF;
				// What has dried on or soaked in is dry, whatever the age of the liquid beside it.
				int dry = GoreRenderer.shade(tinted.rgb, Math.max(tinted.dryness(), tinted.surface.porous() ? SOAKED_IN : DRIED_ON));
				DRY[a][0] = dry >> 16 & 0xFF;
				DRY[a][1] = dry >> 8 & 0xFF;
				DRY[a][2] = dry & 0xFF;
			}
		}

		// A highlight a block pixel across is too big to be a glint of white: there it is the blood's own red, lighter.
		whiten = texels == 16 ? 0.4F : texels == 32 ? 0.6F : 1.0F;

		for (int iz = -1; iz <= BloodCell.N; iz++) {
			for (int ix = -1; ix <= BloodCell.N; ix++) {
				sample(ix, iz);
			}
		}

		boolean west = sim.cellAt(c.bx - 1, c.by, c.bz) != null;
		boolean north = sim.cellAt(c.bx, c.by, c.bz - 1) != null;
		boolean both = sim.cellAt(c.bx - 1, c.by, c.bz - 1) != null;
		float dryness = c.dryness();
		// A floor that drinks shows its own grain through the blood in it.
		int body = BloodTextures.fine(c.surface.porous() ? BloodTextures.STAIN : BloodTextures.FILM, texels);
		int crust = dryness > 0.6F && !c.surface.porous() ? crustTint(dryness) : 0;
		for (int iz = -1; iz < BloodCell.N; iz++) {
			int ix = -1;
			while (ix < BloodCell.N) {
				if (!owned(ix, iz, west, north, both)) {
					ix++;
					continue;
				}

				int shape = shape(ix, iz);
				if (shape == 0) {
					ix++;
					continue;
				}

				if (shape == 2) {
					if (texels == 0) {
						polygons(ix, iz, body, crust);
					} else {
						pixels(ix, iz, texels, body, crust);
					}

					ix++;
					continue;
				}

				// A row of squares that are covered corner to corner and look alike is one quad.
				int end = ix + 1;
				if (ix >= 0 && alike(ix, end, iz)) {
					while (end < BloodCell.N && shape(end, iz) == 1 && alike(ix, end + 1, iz)) {
						end++;
					}
				}

				quad(ix, end, iz, body, LAYER_BODY, 0);
				if (crust != 0) {
					quad(ix, end, iz, BloodTextures.fine(BloodTextures.CRUST, texels), LAYER_CRUST, crust);
				}

				if (SHINE[corner(ix, iz)] + SHINE[corner(end, iz + 1)] > 0.5F) {
					quad(ix, end, iz, BloodTextures.fine(BloodTextures.SPARK, texels), LAYER_SPARK, -1);
				}

				ix = end;
			}
		}

		falls(c, texels);
		for (int i = 0; i < 9; i++) {
			AROUND[i] = null;
		}

		mesh.vertices = count;
		if (mesh.argb.length < count || mesh.argb.length > count * 4 + 64) {
			mesh.data = new float[count * STRIDE];
			mesh.argb = new int[count];
		}

		System.arraycopy(data, 0, mesh.data, 0, count * STRIDE);
		System.arraycopy(argb, 0, mesh.argb, 0, count);
	}

	/** True if this cell draws the square whose low corner is (ix, iz): its own, and those on its low sides nobody else draws. */
	private static boolean owned(int ix, int iz, boolean west, boolean north, boolean both) {
		if (ix >= 0 && iz >= 0) {
			return true;
		}

		if (ix < 0 && iz < 0) {
			return !west && !north && !both;
		}

		return ix < 0 ? !west : !north;
	}

	/** Reads one corner: what kind of place it is, how full of blood, how deep, and the colour blood has there. */
	private static void sample(int ix, int iz) {
		int at = corner(ix, iz);
		int ox = ix < 0 ? -1 : ix >= BloodCell.N ? 1 : 0;
		int oz = iz < 0 ? -1 : iz >= BloodCell.N ? 1 : 0;
		BloodCell n = AROUND[(oz + 1) * 3 + ox + 1];
		FILL[at] = 0.0F;
		DEEP[at] = 0.0F;
		SHINE[at] = 0.0F;
		if (n == null) {
			KIND[at] = UNKNOWN;
			GROUND[at] = Float.NaN;
			return;
		}

		int j = BloodCell.index(ix & BloodCell.N - 1, iz & BloodCell.N - 1);
		float ground = n.ground[j];
		if (ground != ground) {
			KIND[at] = OPEN;
			GROUND[at] = Float.NaN;
			return;
		}

		if (ground == BloodCell.SOLID) {
			KIND[at] = SOLID;
			GROUND[at] = Float.NaN;
			return;
		}

		KIND[at] = FLOOR;
		GROUND[at] = ground;
		float mm = n.mm[j];
		float all = mm + n.soak[j];
		if (all <= 0.0F) {
			return;
		}

		Surface surface = n.surface;
		FILL[at] = Math.min(1.0F, all / surface.cover());
		DEEP[at] = mm;
		float wet = mm / all;
		float gel = BloodFluidSim.gel(n);
		SHINE[at] = surface.gloss * wet * (1.0F - 0.75F * gel) * Math.min(1.0F, mm / 0.6F);

		// The colour between this cell and its neighbours, so a pool has no seams along the block grid.
		float lx = (ix + 0.5F) * BloodCell.SIZE + 0.5F;
		float lz = (iz + 0.5F) * BloodCell.SIZE + 0.5F;
		int cx = lx < 1.0F ? 0 : 1;
		int cz = lz < 1.0F ? 0 : 1;
		float fx = lx - cx;
		float fz = lz - cz;
		// Light passes through a thin film and comes back scarlet; a few millimetres of blood let almost none back, so
		// a pool is dark crimson, darker the deeper it stands. What has soaked in or dried on is dry: dull and brown.
		float t = Mth.clamp((mm - 0.3F) / 5.5F, 0.0F, 1.0F);
		float deep = (1.0F - 0.52F * t * (2.0F - t)) * (surface.porous() ? 0.8F : 1.0F);
		float stain = surface.porous() ? 0.78F : 0.92F;
		float r = blend(DRY, cx, cz, fx, fz, 0) * stain;
		float g = blend(DRY, cx, cz, fx, fz, 1) * stain;
		float b = blend(DRY, cx, cz, fx, fz, 2) * stain;
		r += (blend(BASE, cx, cz, fx, fz, 0) * deep - r) * wet;
		g += (blend(BASE, cx, cz, fx, fz, 1) * deep - g) * wet;
		b += (blend(BASE, cx, cz, fx, fz, 2) * deep - b) * wet;
		float stainAlpha = surface.porous() ? 0.62F + 0.3F * Math.min(1.0F, all / 4.0F) : 0.66F + 0.28F * Math.min(1.0F, all / 2.0F);
		// Liquid hides a hard floor; on turf, sand or cloth the floor's own texture still shows through it.
		float wetAlpha = (surface.porous() ? 0.9F : 0.97F) - 0.2F * Mth.clamp(1.0F - mm / 0.6F, 0.0F, 1.0F);
		float alpha = stainAlpha + (wetAlpha - stainAlpha) * wet;
		TINT[at] = Math.clamp((int) (alpha * 255.0F), 0, 255) << 24 | Math.clamp((int) r, 0, 255) << 16 | Math.clamp((int) g, 0, 255) << 8
				| Math.clamp((int) b, 0, 255);
	}

	/** One channel of the colour of the cells round a corner, weighed by how near each is. */
	private static float blend(float[][] from, int cx, int cz, float fx, float fz, int channel) {
		float a = from[cz * 3 + cx][channel];
		float b = from[cz * 3 + cx + 1][channel];
		float d = from[(cz + 1) * 3 + cx][channel];
		float e = from[(cz + 1) * 3 + cx + 1][channel];
		float top = a + (b - a) * fx;
		float bottom = d + (e - d) * fx;
		return top + (bottom - top) * fz;
	}

	private static boolean in(int at) {
		return KIND[at] == FLOOR && FILL[at] >= ISO;
	}

	/** 0: nothing of this square is covered. 1: all of it is, on one level. 2: part of it, or on more than one level. */
	private static int shape(int ix, int iz) {
		int a = corner(ix, iz);
		int b = corner(ix + 1, iz);
		int d = corner(ix, iz + 1);
		int e = corner(ix + 1, iz + 1);
		boolean ia = in(a);
		boolean ib = in(b);
		boolean id = in(d);
		boolean ie = in(e);
		if (!ia && !ib && !id && !ie) {
			return 0;
		}

		if (ia && ib && id && ie && level(a, b) && level(a, d) && level(a, e)) {
			return 1;
		}

		return 2;
	}

	private static boolean level(int a, int b) {
		return Math.abs(GROUND[a] - GROUND[b]) < 0.02F;
	}

	/** True if the corners at {@code ix} on rows iz and iz + 1 look enough like those at {@code start} to share a quad. */
	private static boolean alike(int start, int ix, int iz) {
		return alike(corner(start, iz), corner(ix, iz)) && alike(corner(start, iz + 1), corner(ix, iz + 1));
	}

	private static boolean alike(int a, int b) {
		int ca = TINT[a];
		int cb = TINT[b];
		return Math.abs(height(a) - height(b)) < 1.0E-4F && Math.abs(SHINE[a] - SHINE[b]) < 0.05F && Math.abs((ca >>> 24) - (cb >>> 24)) <= 8
				&& Math.abs((ca >> 16 & 0xFF) - (cb >> 16 & 0xFF)) <= 6 && Math.abs((ca >> 8 & 0xFF) - (cb >> 8 & 0xFF)) <= 6
				&& Math.abs((ca & 0xFF) - (cb & 0xFF)) <= 6;
	}

	/** Height of the blood's surface over the cell's floor level at a corner: flat while it is a film, rising with a pool. */
	private static float height(int at) {
		return GROUND[at] + Math.max(0.0F, DEEP[at] - FLAT_MM) / 1000.0F;
	}

	private static float local(int i) {
		return (i + 0.5F) * BloodCell.SIZE;
	}

	/** Where along a tile corner {@code i} falls, so the tile repeats once a block without a quad ever leaving it. */
	private static float tile(int i) {
		return (i + BloodCell.N & BloodCell.N - 1) * BloodCell.SIZE;
	}

	/**
	 * One quad from corner column {@code x0} to {@code x1} on rows iz and iz + 1, every corner covered. {@code tint} 0
	 * takes each corner's own colour, -1 its shine as a white highlight, anything else is the colour itself.
	 */
	private static void quad(int x0, int x1, int iz, int tile, float layer, int tint) {
		float u0 = tile(x0);
		float u1 = u0 + (x1 - x0) * BloodCell.SIZE;
		float v0 = tile(iz);
		float v1 = v0 + BloodCell.SIZE;
		vertex(corner(x0, iz), local(x0), local(iz), u0, v0, tile, layer, tint);
		vertex(corner(x0, iz + 1), local(x0), local(iz + 1), u0, v1, tile, layer, tint);
		vertex(corner(x1, iz + 1), local(x1), local(iz + 1), u1, v1, tile, layer, tint);
		vertex(corner(x1, iz), local(x1), local(iz), u1, v0, tile, layer, tint);
	}

	private static void vertex(int at, float x, float z, float u, float v, int tile, float layer, int tint) {
		put(x, height(at), z, u, v, tile, SHINE[at], layer, tint == 0 ? TINT[at] : tint == -1 ? spark(at) : tint);
	}

	/** A highlight on the blood at a corner, as bright as the blood there is shiny: its own colour taken towards white. */
	private static int spark(int at) {
		int color = TINT[at];
		int r = color >> 16 & 0xFF;
		int g = color >> 8 & 0xFF;
		int b = color & 0xFF;
		r += (int) ((255 - r) * whiten);
		g += (int) ((244 - g) * whiten);
		b += (int) ((236 - b) * whiten);
		return Math.clamp((int) (SHINE[at] * 235.0F), 0, 235) << 24 | r << 16 | g << 8 | b;
	}

	private static void put(float x, float y, float z, float u, float v, int tile, float gloss, float layer, int color) {
		if (count == argb.length) {
			argb = java.util.Arrays.copyOf(argb, count * 2);
			data = java.util.Arrays.copyOf(data, count * 2 * STRIDE);
		}

		int o = count * STRIDE;
		data[o] = x;
		data[o + 1] = y;
		data[o + 2] = z;
		data[o + 3] = BloodTextures.u0(tile) + u * BloodTextures.span();
		data[o + 4] = BloodTextures.v0(tile) + v * BloodTextures.span();
		data[o + 5] = gloss;
		data[o + 6] = layer;
		argb[count++] = color;
	}

	/** The dark of a dried pool's cracks, stronger the drier it is. */
	private static int crustTint(float dryness) {
		int alpha = Math.clamp((int) ((dryness - 0.6F) / 0.4F * 200.0F), 1, 200);
		return alpha << 24 | 0x120806;
	}

	/** Loads the corners of square (ix, iz) into {@link #QC} in the order its outline is walked. */
	private static void square(int ix, int iz) {
		QC[0] = corner(ix, iz);
		QC[1] = corner(ix, iz + 1);
		QC[2] = corner(ix + 1, iz + 1);
		QC[3] = corner(ix + 1, iz);
	}

	/**
	 * Works out one floor level of the square loaded by {@link #square}: how full each corner counts as on it (into QV),
	 * the covered corner each takes its look from (QS) and how far the blood stands over the floor there (QL). A corner
	 * that is this floor counts as full as it is. One that is a wall, a drop or a step counts as the mirror of its covered
	 * neighbours, which puts the edge of the blood on the line between them. Returns the corners covered on this level,
	 * as bits.
	 */
	private static int level(float ground, int first) {
		int covered = 0;
		for (int k = 0; k < 4; k++) {
			int at = QC[k];
			if (KIND[at] == UNKNOWN) {
				QR[k] = 0.0F;
			} else if (KIND[at] == FLOOR && Math.abs(GROUND[at] - ground) < 0.02F) {
				QR[k] = FILL[at];
				if (in(at)) {
					covered |= 1 << k;
				}
			} else {
				QR[k] = Float.NaN;
			}
		}

		for (int k = 0; k < 4; k++) {
			int before = k + 3 & 3;
			int after = k + 1 & 3;
			boolean b = (covered & 1 << before) != 0;
			boolean a = (covered & 1 << after) != 0;
			if (QR[k] != QR[k]) {
				QV[k] = b && a ? 2.0F * ISO - (QR[before] + QR[after]) * 0.5F : b ? 2.0F * ISO - QR[before] : a ? 2.0F * ISO - QR[after] : 0.0F;
			} else {
				QV[k] = QR[k];
			}

			QS[k] = (covered & 1 << k) != 0 ? k : a ? after : b ? before : first;
			// Bare floor has nothing standing on it; against a wall the blood stands as high as it does beside it.
			QL[k] = (covered & 1 << k) != 0 || QR[k] != QR[k] ? height(QC[QS[k]]) - ground : 0.0F;
		}

		return covered;
	}

	/** Eases from 0 to 1 with no slope at either end, so the field has no crease along the lines between sub-cells. */
	private static float ease(float t) {
		return t * t * (3.0F - 2.0F * t);
	}

	/** How full the place (x, z) of the square counts as, 0 to 1 across it, from its corners' QV. */
	private static float field(float x, float z) {
		float ex = ease(x);
		float ez = ease(z);
		float low = QV[0] + (QV[3] - QV[0]) * ex;
		float high = QV[1] + (QV[2] - QV[1]) * ex;
		return low + (high - low) * ez;
	}

	/**
	 * The covered part of a square that is not covered corner to corner, cut as a clean line, once for each floor level
	 * it has blood on. The square is cut into {@link #FINE} steps a side and the outline drawn through those, on a field
	 * that eases between the corners, so the edge of the blood is a curve.
	 */
	private static void polygons(int ix, int iz, int body, int crust) {
		square(ix, iz);
		int done = 0;
		for (int first = 0; first < 4; first++) {
			if (!in(QC[first]) || (done & 1 << first) != 0) {
				continue;
			}

			float ground = GROUND[QC[first]];
			done |= level(ground, first);
			for (int j = 0; j <= FINE; j++) {
				for (int i = 0; i <= FINE; i++) {
					FIELD[j * (FINE + 1) + i] = field(i / (float) FINE, j / (float) FINE);
				}
			}

			for (int j = 0; j < FINE; j++) {
				for (int i = 0; i < FINE; i++) {
					int n = 0;
					for (int k = 0; k < 4; k++) {
						int next = k + 1 & 3;
						int ax = i + (k >= 2 ? 1 : 0);
						int az = j + (k == 1 || k == 2 ? 1 : 0);
						int bx = i + (next >= 2 ? 1 : 0);
						int bz = j + (next == 1 || next == 2 ? 1 : 0);
						float fa = FIELD[az * (FINE + 1) + ax];
						float fb = FIELD[bz * (FINE + 1) + bx];
						if (fa >= ISO) {
							n = place(n, ix, iz, ax / (float) FINE, az / (float) FINE, ground, false);
						}

						if (fa >= ISO != fb >= ISO) {
							// The edge crosses this side.
							float t = (ISO - fa) / (fb - fa);
							n = place(n, ix, iz, (ax + (bx - ax) * t) / FINE, (az + (bz - az) * t) / FINE, ground, true);
						}
					}

					polygon(n, ix, iz, body, LAYER_BODY, 0);
					if (crust != 0) {
						polygon(n, ix, iz, BloodTextures.CRUST, LAYER_CRUST, crust);
					}
				}
			}
		}
	}

	/**
	 * Puts a corner of the polygon being gathered at (x, z) of square (ix, iz), 0 to 1 across it: its height, shine and
	 * colour from the square's corners, weighed as the field is. {@code edge} is a corner on the outline, where the film
	 * thins out. Returns the count with it.
	 */
	private static int place(int n, int ix, int iz, float x, float z, float ground, boolean edge) {
		float ex = ease(x);
		float ez = ease(z);
		float w0 = (1.0F - ex) * (1.0F - ez);
		float w1 = (1.0F - ex) * ez;
		float w2 = ex * ez;
		float w3 = ex * (1.0F - ez);
		int c0 = TINT[QC[QS[0]]];
		int c1 = TINT[QC[QS[1]]];
		int c2 = TINT[QC[QS[2]]];
		int c3 = TINT[QC[QS[3]]];
		int color = mix(c0, c1, c2, c3, w0, w1, w2, w3, 24) << 24 | mix(c0, c1, c2, c3, w0, w1, w2, w3, 16) << 16 | mix(c0, c1, c2, c3, w0, w1, w2, w3, 8) << 8
				| mix(c0, c1, c2, c3, w0, w1, w2, w3, 0);
		float shine = SHINE[QC[QS[0]]] * w0 + SHINE[QC[QS[1]]] * w1 + SHINE[QC[QS[2]]] * w2 + SHINE[QC[QS[3]]] * w3;
		PX[n] = local(ix) + x * BloodCell.SIZE;
		PZ[n] = local(iz) + z * BloodCell.SIZE;
		PY[n] = ground + QL[0] * w0 + QL[1] * w1 + QL[2] * w2 + QL[3] * w3;
		PG[n] = edge ? shine * 0.7F : shine;
		PC[n] = edge ? rim(color) : color;
		return n + 1;
	}

	/** One channel (the bits from {@code shift}) of four colours mixed by weight. */
	private static int mix(int c0, int c1, int c2, int c3, float w0, float w1, float w2, float w3, int shift) {
		return Math.clamp((int) ((c0 >>> shift & 0xFF) * w0 + (c1 >>> shift & 0xFF) * w1 + (c2 >>> shift & 0xFF) * w2 + (c3 >>> shift & 0xFF) * w3 + 0.5F), 0, 255);
	}

	/** The colour at a pool's edge: the film thins out there and the floor shows through it a little. */
	private static int rim(int color) {
		int a = (color >>> 24) * 9 / 10;
		int r = (color >> 16 & 0xFF) * 88 / 100;
		int g = (color >> 8 & 0xFF) * 88 / 100;
		int b = (color & 0xFF) * 88 / 100;
		return a << 24 | r << 16 | g << 8 | b;
	}

	/** Emits the polygon gathered in the P arrays as quads fanned from its first corner. */
	private static void polygon(int n, int ix, int iz, int tile, float layer, int tint) {
		if (n < 3) {
			return;
		}

		float u0 = tile(ix) - local(ix);
		float v0 = tile(iz) - local(iz);
		for (int i = 1; i < n - 1; i += 2) {
			int third = Math.min(i + 2, n - 1);
			point(0, u0, v0, tile, layer, tint);
			point(i, u0, v0, tile, layer, tint);
			point(i + 1, u0, v0, tile, layer, tint);
			point(third, u0, v0, tile, layer, tint);
		}
	}

	private static void point(int i, float u0, float v0, int tile, float layer, int tint) {
		put(PX[i], PY[i], PZ[i], u0 + PX[i], v0 + PZ[i], tile, PG[i], layer, tint != 0 ? tint : PC[i]);
	}

	/**
	 * The covered part of a square that is not covered corner to corner, cut into pixels, {@code texels} to a block, on
	 * the grid the block textures use. A pixel is drawn if the middle of it is covered, on the same field the smooth
	 * outline is drawn through, and takes the colour and height of the covered corner it is nearest. Rows of pixels from
	 * one corner are one quad.
	 */
	private static void pixels(int ix, int iz, int texels, int body, int crust) {
		square(ix, iz);
		int n = texels / BloodCell.N;
		float size = BloodCell.SIZE / n;
		int done = 0;
		for (int first = 0; first < 4; first++) {
			if (!in(QC[first]) || (done & 1 << first) != 0) {
				continue;
			}

			done |= level(GROUND[QC[first]], first);
			for (int tz = 0; tz < n; tz++) {
				float fz = (tz + 0.5F) / n;
				int start = -1;
				int from = -1;
				for (int tx = 0; tx <= n; tx++) {
					int source = -1;
					if (tx < n) {
						float fx = (tx + 0.5F) / n;
						if (field(fx, fz) >= ISO) {
							// The corner this pixel is nearest, or the covered one that corner takes its look from.
							source = QS[fx < 0.5F ? (fz < 0.5F ? 0 : 1) : (fz < 0.5F ? 3 : 2)];
						}
					}

					if (source == from) {
						continue;
					}

					if (from >= 0) {
						int at = QC[from];
						float x0 = local(ix) + start * size;
						float x1 = local(ix) + tx * size;
						float z0 = local(iz) + tz * size;
						float u0 = tile(ix) + start * size;
						float u1 = tile(ix) + tx * size;
						float w0 = tile(iz) + tz * size;
						rect(x0, x1, z0, z0 + size, height(at), u0, u1, w0, w0 + size, body, SHINE[at], LAYER_BODY, TINT[at]);
						if (crust != 0) {
							rect(x0, x1, z0, z0 + size, height(at), u0, u1, w0, w0 + size, BloodTextures.fine(BloodTextures.CRUST, texels), 0.0F,
									LAYER_CRUST, crust);
						}
					}

					from = source;
					start = tx;
				}
			}
		}
	}

	private static void rect(float x0, float x1, float z0, float z1, float y, float u0, float u1, float v0, float v1, int tile, float gloss,
			float layer, int color) {
		put(x0, y, z0, u0, v0, tile, gloss, layer, color);
		put(x0, y, z1, u0, v1, tile, gloss, layer, color);
		put(x1, y, z1, u1, v1, tile, gloss, layer, color);
		put(x1, y, z0, u1, v0, tile, gloss, layer, color);
	}

	/** Blood pouring down a step from a sub-cell's edge: a ribbon down the face of the step while it pours. */
	private static void falls(BloodCell c, int texels) {
		float[] drops = c.pourDrop;
		if (drops == null || c.poured == 0) {
			return;
		}

		int ribbon = BloodTextures.fine(BloodTextures.RIBBON, texels);
		for (int i = 0; i < BloodCell.COUNT; i++) {
			for (int k = 0; k < 4; k++) {
				float drop = drops[i * 4 + k];
				if (drop <= 0.0F || !c.floored(i)) {
					continue;
				}

				int sx = i % BloodCell.N;
				int sz = i / BloodCell.N;
				int at = corner(sx, sz);
				float top = height(at) + BODY_LIFT;
				float bottom = top - drop;
				float out = 0.006F;
				float x0;
				float x1;
				float z0;
				float z1;
				if (DX[k] != 0) {
					x0 = x1 = (sx + (DX[k] > 0 ? 1 : 0)) * BloodCell.SIZE + DX[k] * out;
					z0 = sz * BloodCell.SIZE;
					z1 = z0 + BloodCell.SIZE;
				} else {
					z0 = z1 = (sz + (DZ[k] > 0 ? 1 : 0)) * BloodCell.SIZE + DZ[k] * out;
					x0 = sx * BloodCell.SIZE;
					x1 = x0 + BloodCell.SIZE;
				}

				float u0 = (DX[k] != 0 ? sz : sx) * BloodCell.SIZE;
				float u1 = u0 + BloodCell.SIZE;
				float v1 = Math.min(1.0F, drop);
				int color = TINT[at];
				float layer = LAYER_FALL + k;
				put(x0, top, z0, u0, 0.0F, ribbon, 0.3F, layer, color);
				put(x0, bottom, z0, u0, v1, ribbon, 0.3F, layer, color);
				put(x1, bottom, z1, u1, v1, ribbon, 0.3F, layer, color);
				put(x1, top, z1, u1, 0.0F, ribbon, 0.3F, layer, color);
			}
		}
	}

	/**
	 * The sun (or the moon) on wet blood: where its mirror image falls on each wet floor in view, a bright patch drawn
	 * out towards it, only over what is wet and shiny there. Drawn at full brightness on top of everything else, and only
	 * where blood is drawn finely enough for a soft light to belong.
	 */
	static void emitGlint(PoseStack.Pose pose, VertexConsumer buffer, BloodFluidSim sim, Vec3 cam, View view) {
		int texels = GoreConfig.get().bloodResolution.texels;
		if (view.sun <= 0.02F || view.sunY < 0.1F || view.packs || (texels != 0 && texels < 64)) {
			return;
		}

		for (int p = 0; p < planes; p++) {
			double y = PLANES[p];
			double height = cam.y - y;
			if (height < 0.1) {
				continue;
			}

			// Along the sun's mirror image from the eye down to the floor.
			double t = height / view.sunY;
			double px = cam.x + view.sunX * t;
			double pz = cam.z + view.sunZ * t;
			double range = Math.sqrt(t * t * (view.sunX * view.sunX + view.sunZ * view.sunZ) + height * height);
			if (range > 40.0) {
				continue;
			}

			// A round highlight seen across a floor is drawn out along the line of sight.
			float across = 0.2F + 0.035F * (float) range;
			float along = across * Mth.clamp(1.0F / view.sunY, 1.0F, 3.5F);
			double flat = Math.sqrt(view.sunX * view.sunX + view.sunZ * view.sunZ);
			double ax = flat > 1.0E-4 ? view.sunX / flat : 1.0;
			double az = flat > 1.0E-4 ? view.sunZ / flat : 0.0;
			glint(pose, buffer, sim, cam, px, y, pz, ax, az, along * 1.8F, across * 1.8F, view.sun);
		}
	}

	private static void glint(PoseStack.Pose pose, VertexConsumer buffer, BloodFluidSim sim, Vec3 cam, double px, double y, double pz, double ax,
			double az, float along, float across, float strength) {
		double reach = Math.max(along, across);
		int by = SurfaceProbe.airBlockY(y);
		for (int bx = Mth.floor(px - reach); bx <= Mth.floor(px + reach); bx++) {
			for (int bz = Mth.floor(pz - reach); bz <= Mth.floor(pz + reach); bz++) {
				BloodCell cell = sim.cellAt(bx, by, bz);
				if (cell == null || cell.litres <= 0.0F || Math.abs(cell.surfaceY - y) > 0.02) {
					continue;
				}

				float shine = cell.surface.gloss * (1.0F - 0.75F * BloodFluidSim.gel(cell)) * cell.fade();
				if (shine <= 0.05F) {
					continue;
				}

				for (int i = 0; i < BloodCell.COUNT; i++) {
					if (!cell.floored(i) || cell.mm[i] < 0.5F) {
						continue;
					}

					double x0 = bx + (i % BloodCell.N) * BloodCell.SIZE;
					double z0 = bz + (i / BloodCell.N) * BloodCell.SIZE;
					float a00 = glow(x0, z0, px, pz, ax, az, along, across);
					float a10 = glow(x0 + BloodCell.SIZE, z0, px, pz, ax, az, along, across);
					float a01 = glow(x0, z0 + BloodCell.SIZE, px, pz, ax, az, along, across);
					float a11 = glow(x0 + BloodCell.SIZE, z0 + BloodCell.SIZE, px, pz, ax, az, along, across);
					if (a00 + a10 + a01 + a11 < 0.02F) {
						continue;
					}

					float k = strength * shine;
					float wy = (float) (cell.by + cell.ground[i] + Math.max(0.0F, cell.mm[i] - FLAT_MM) / 1000.0F + OVER_LIFT * 1.5F - cam.y);
					glintVertex(pose, buffer, x0 - cam.x, wy, z0 - cam.z, a00 * k);
					glintVertex(pose, buffer, x0 - cam.x, wy, z0 + BloodCell.SIZE - cam.z, a01 * k);
					glintVertex(pose, buffer, x0 + BloodCell.SIZE - cam.x, wy, z0 + BloodCell.SIZE - cam.z, a11 * k);
					glintVertex(pose, buffer, x0 + BloodCell.SIZE - cam.x, wy, z0 - cam.z, a10 * k);
				}
			}
		}
	}

	/** Brightness of the highlight at (x, z): a soft spot round (px, pz), long along (ax, az). */
	private static float glow(double x, double z, double px, double pz, double ax, double az, float along, float across) {
		double dx = x - px;
		double dz = z - pz;
		double u = (dx * ax + dz * az) / along;
		double v = (dz * ax - dx * az) / across;
		return (float) Math.exp(-3.0 * (u * u + v * v));
	}

	private static void glintVertex(PoseStack.Pose pose, VertexConsumer buffer, double x, float y, double z, float alpha) {
		buffer.addVertex(pose, (float) x, y, (float) z)
				.setColor(Math.clamp((int) (alpha * 255.0F), 0, 255) << 24 | 0xFFF6E0)
				.setUv(BloodTextures.u0(BloodTextures.FLAT) + 0.5F * BloodTextures.span(), BloodTextures.v0(BloodTextures.FLAT) + 0.5F * BloodTextures.span())
				.setOverlay(OVERLAY)
				.setLight(LightCoordsUtil.FULL_BRIGHT)
				.setNormal(pose, 0.0F, 1.0F, 0.0F);
	}
}
