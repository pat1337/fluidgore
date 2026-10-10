package dev.fluidgore.suffer;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

/**
 * Animation amplitudes for one suffering animal this frame. The oscillations themselves are computed from the
 * render state's {@code ageInTicks} at draw time, so they stay smooth between ticks.
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
 */
public record SufferPose(float tremble, float thrash, float kick, float kickRate, float tiltX, float tiltZ, float drop, float stagger,
		float headDip, float lookYaw, float foldFront, float foldHind, float stiff, float headTilt) {
	public static final RenderStateDataKey<SufferPose> KEY = RenderStateDataKey.create(() -> "fluidgore:suffer_pose");
	public static final SufferPose IDLE = new SufferPose(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

	public SufferPose(float tremble, float thrash, float kick, float kickRate, float tiltX, float tiltZ, float drop, float stagger,
			float headDip, float lookYaw) {
		this(tremble, thrash, kick, kickRate, tiltX, tiltZ, drop, stagger, headDip, lookYaw, 0.0F, 0.0F, 0.0F, 0.0F);
	}

	public boolean isIdle() {
		return tremble < 0.01F && thrash < 0.01F && kick < 0.01F && tiltX == 0.0F && tiltZ == 0.0F && drop == 0.0F && stagger == 0.0F
				&& headDip == 0.0F && lookYaw == 0.0F && foldFront == 0.0F && foldHind == 0.0F && stiff == 0.0F && headTilt == 0.0F;
	}

	/** Smooth pseudo-random signal in about [-1, 1]; a sum of incommensurate sines. */
	public static float noise(float t, float seed) {
		return (float) (Math.sin(t * 1.7 + seed) * 0.5 + Math.sin(t * 3.1 + seed * 1.3 + 1.3) * 0.3 + Math.sin(t * 5.3 + seed * 0.7 + 2.1) * 0.2);
	}
}
