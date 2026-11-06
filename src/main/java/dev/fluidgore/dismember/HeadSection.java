package dev.fluidgore.dismember;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fluidgore.anatomy.Anatomy;
import dev.fluidgore.anatomy.Anatomy.Kind;
import dev.fluidgore.anatomy.Anatomy.Piece;
import dev.fluidgore.anatomy.Anatomy.Region;
import dev.fluidgore.anatomy.MeltRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * What is inside a beast's severed head, so that a blade down its middle can show it (see {@code Butchery#cut}): the
 * beast, its sex and whether it is young (which skull, which teeth), and the head's own cube ({@code box}: lo x, y, z, hi
 * x, y, z, the piece's pixels), onto which the head's anatomy is fitted as the body draws it.
 *
 * <p>{@code half} is 0 for the whole head, -1 for the half on the low x side of the cut (the animal's right) and 1 for the
 * other. A half draws the head's anatomy where the cut went through it: the skull, the jaw and the nasal bones open to
 * their marrow, the brain to its grey and white matter, the tongue, the larynx and the throat, each on the cut face where
 * it lies; between them the raw face of the piece shows the muscle of the head. Where an organ only meets the middle (the
 * inner face of each half of the brain, which a butcher's cut runs between) it shows its own surface.
 */
public record HeadSection(Anatomy.Beast beast, boolean male, boolean young, float[] box, int half) {
	/** How far in front of the raw cut face the section is drawn, in pixels: over every layer of the wound there (see {@code WoundRenderer}). */
	private static final float PROUD = 0.15F;
	/** How close to the middle a piece's side must be to count as meeting it, in canonical pixels. */
	private static final float AT_MIDDLE = 0.01F;

	public HeadSection halved(int side) {
		return new HeadSection(beast, male, young, box, side);
	}

	/** The middle of the head across, in the piece's pixels: where the blade goes. */
	public float middle() {
		return (box[0] + box[3]) * 0.5F;
	}

	/** The head's pieces, as the body draws them (a lamb's milk teeth, a grown skull). */
	public Piece[] pieces() {
		return Anatomy.pieces(Region.BEAST_HEAD, false, beast, false, Anatomy.Detail.MIXED, male, young);
	}

	/**
	 * Draws this half's cut face ({@code pose} at the piece's pixels, as its model is drawn) into {@code buffer}, a
	 * {@link MeltRenderer#renderType()} buffer. Nothing for a whole head.
	 */
	public void draw(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int tint) {
		if (half == 0) {
			return;
		}

		float[] min = Region.BEAST_HEAD.min(false, false);
		float[] max = Region.BEAST_HEAD.max(false, false);
		float[] scale = new float[3];
		float[] offset = new float[3];
		for (int i = 0; i < 3; i++) {
			scale[i] = (box[3 + i] - box[i]) / (max[i] - min[i]);
			offset[i] = box[i] - min[i] * scale[i];
		}

		float plane = offset[0] - half * PROUD;
		Matrix4f m = pose.pose();
		Vector3f normal = new Vector3f();
		pose.transformNormal(-half, 0.0F, 0.0F, normal);
		int argb = 0xFF000000 | tint;
		Piece[] pieces = pieces();
		for (int n = 0; n < pieces.length; n++) {
			Piece p = pieces[n];
			boolean through = p.x0() < -AT_MIDDLE && p.x1() > AT_MIDDLE;
			boolean meets = half < 0 ? p.x0() < 0.0F && Math.abs(p.x1()) <= AT_MIDDLE : p.x1() > 0.0F && Math.abs(p.x0()) <= AT_MIDDLE;
			if (!through && !meets) {
				continue;
			}

			Kind kind = through ? p.kind().section() : p.kind();
			float y0 = clamp(p.y0() * scale[1] + offset[1], box[1], box[4]);
			float y1 = clamp(p.y1() * scale[1] + offset[1], box[1], box[4]);
			float z0 = clamp(p.z0() * scale[2] + offset[2], box[2], box[5]);
			float z1 = clamp(p.z1() * scale[2] + offset[2], box[2], box[5]);
			if (y1 - y0 < 1.0E-3F || z1 - z0 < 1.0E-3F) {
				continue;
			}

			// v down the head, u along it, as the body's blocks are skinned; a small block shows its own place in its tile.
			float spanU = Math.min(Math.max((z1 - z0) * p.texels(), 1.0F), 16.0F);
			float spanV = Math.min(Math.max((y1 - y0) * p.texels(), 1.0F), 16.0F);
			float[] w = MeltRenderer.windowOf(kind.tile, n, half < 0 ? 1 : 0, spanU, spanV);
			// Facing +x (the right half's cut) the corners go round one way, facing -x the other.
			if (half < 0) {
				vertex(buffer, m, plane, y0, z0, w[0], w[1], argb, light, overlay, normal);
				vertex(buffer, m, plane, y1, z0, w[0], w[3], argb, light, overlay, normal);
				vertex(buffer, m, plane, y1, z1, w[2], w[3], argb, light, overlay, normal);
				vertex(buffer, m, plane, y0, z1, w[2], w[1], argb, light, overlay, normal);
			} else {
				vertex(buffer, m, plane, y0, z1, w[2], w[1], argb, light, overlay, normal);
				vertex(buffer, m, plane, y1, z1, w[2], w[3], argb, light, overlay, normal);
				vertex(buffer, m, plane, y1, z0, w[0], w[3], argb, light, overlay, normal);
				vertex(buffer, m, plane, y0, z0, w[0], w[1], argb, light, overlay, normal);
			}
		}
	}

	/** The kinds of tissue this half's cut face shows, for tests: each piece the cut goes through, as it is cut. */
	public java.util.Set<Kind> shows() {
		java.util.Set<Kind> out = java.util.EnumSet.noneOf(Kind.class);
		for (Piece p : pieces()) {
			if (p.x0() < -AT_MIDDLE && p.x1() > AT_MIDDLE) {
				out.add(p.kind().section());
			}
		}

		return out;
	}

	private static void vertex(VertexConsumer buffer, Matrix4f m, float x, float y, float z, float u, float v, int argb, int light, int overlay,
			Vector3f normal) {
		Vector3f at = m.transformPosition(x / 16.0F, y / 16.0F, z / 16.0F, new Vector3f());
		buffer.addVertex(at.x, at.y, at.z, argb, u, v, overlay, light, normal.x, normal.y, normal.z);
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
