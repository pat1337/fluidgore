package dev.fluidgore.suffer;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.util.Mth;

/**
 * Animation amplitudes for one suffering animal this frame. The oscillations themselves are computed at draw time
 * from a clock ({@link #CLOCK} for a dying body, otherwise the render state's {@code ageInTicks}), so they stay
 * smooth between ticks.
 *
 * @param tremble   whole-body shivering, 0..1
 * @param thrash    violent head throwing, 0..1
 * @param kick      leg kicking / paddling, 0..1
 * @param kickRate  leg oscillation speed in radians per tick
 * @param tiltX     forward (+) / backward (-) body tilt in degrees, e.g. from a missing front leg
 * @param tiltZ     sideways body tilt in degrees
 * @param drop      how far the body sags towards the ground, in blocks
 * @param stagger   amplitude of a drunken side-to-side wobble in degrees
 * @param headDip   head pitch offset in radians: positive lowers the head (cowering, mourning), negative lifts it (alert)
 * @param lookYaw   head turn offset in radians, to face a body or a smell of blood (or back against its own flank)
 * @param foldFront front legs folded back under the chest, 0..1 (kneeling, lying on the brisket)
 * @param foldHind  hind legs folded forward under the belly, 0..1 (a sitting or lying hind end)
 * @param stiff     legs locked straight out, front forward and hind back, 0..1 (the rigid phase after a stun)
 * @param headTilt  head roll in radians, the cocked head of a brain injury
 * @param hindLimp  hind legs hang dead, 0..1: they neither stiffen nor kick (a severed spine)
 * @param legSeed   gives each body its own leg rhythm: every leg kicks with its own phase and strength
 * @param gaspAt    clock time the last agonal gasp began (very negative for none)
 * @param gasp      strength of that gasp, 0..1: the chest heaves and the head lifts, then sinks back
 */
public record SufferPose(float tremble, float thrash, float kick, float kickRate, float tiltX, float tiltZ, float drop, float stagger,
		float headDip, float lookYaw, float foldFront, float foldHind, float stiff, float headTilt, float hindLimp, float legSeed,
		float gaspAt, float gasp) {
	public static final RenderStateDataKey<SufferPose> KEY = RenderStateDataKey.create(() -> "fluidgore:suffer_pose");
	/**
	 * Ticks since death, with the partial tick, for a dying body. A body the server has removed is no longer ticked by
	 * the game, so its own age stops; this clock keeps going, and is the same on every client.
	 */
	public static final RenderStateDataKey<Float> CLOCK = RenderStateDataKey.create(() -> "fluidgore:death_clock");
	public static final SufferPose IDLE = new SufferPose(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	/** Length of one gasp in ticks. */
	private static final float GASP_TICKS = 16.0F;

	public SufferPose(float tremble, float thrash, float kick, float kickRate, float tiltX, float tiltZ, float drop, float stagger,
			float headDip, float lookYaw) {
		this(tremble, thrash, kick, kickRate, tiltX, tiltZ, drop, stagger, headDip, lookYaw, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -1000.0F, 0.0F);
	}

	public boolean isIdle() {
		return tremble < 0.01F && thrash < 0.01F && kick < 0.01F && tiltX == 0.0F && tiltZ == 0.0F && drop == 0.0F && stagger == 0.0F
				&& headDip == 0.0F && lookYaw == 0.0F && foldFront == 0.0F && foldHind == 0.0F && stiff == 0.0F && headTilt == 0.0F
				&& gasp == 0.0F;
	}

	/** How far into its gasp the body is at clock time {@code t}, 0..1: a quick heave in, a slow sag out. */
	public float gaspAmount(float t) {
		float since = t - gaspAt;
		if (gasp <= 0.0F || since < 0.0F || since > GASP_TICKS) {
			return 0.0F;
		}

		float shape = since < 3.0F ? since / 3.0F : (float) Math.exp(-(since - 3.0F) / 3.5F);
		return gasp * shape;
	}

	/** This leg's share of the kicking, 0.55..1.0: no two legs of a dying body paddle alike. */
	public float legStrength(int leg) {
		return 0.775F + 0.225F * Mth.sin(legSeed * 1.7F + leg * 2.3F);
	}

	/** This leg's phase in the paddling cycle. */
	public float legPhase(int leg) {
		return leg * 1.9F + legSeed * (0.6F + leg * 0.37F);
	}

	/** Smooth pseudo-random signal in about [-1, 1]; a sum of incommensurate sines. */
	public static float noise(float t, float seed) {
		return (float) (Math.sin(t * 1.7 + seed) * 0.5 + Math.sin(t * 3.1 + seed * 1.3 + 1.3) * 0.3 + Math.sin(t * 5.3 + seed * 0.7 + 2.1) * 0.2);
	}
}
