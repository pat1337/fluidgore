package dev.fluidgore.suffer;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

/**
 * Animation amplitudes for one suffering animal this frame. The oscillations themselves are computed from the
 * render state's {@code ageInTicks} at draw time, so they stay smooth between ticks.
 *
 * @param tremble whole-body shivering, 0..1
 * @param thrash  violent head throwing, 0..1
 * @param kick    leg kicking / paddling, 0..1
 * @param kickRate leg oscillation speed in radians per tick
 * @param tiltX   forward (+) / backward (-) body tilt in degrees, e.g. from a missing front leg
 * @param tiltZ   sideways body tilt in degrees
 * @param drop    how far the body sags towards the ground, in blocks
 * @param stagger amplitude of a drunken side-to-side wobble in degrees
 */
public record SufferPose(float tremble, float thrash, float kick, float kickRate, float tiltX, float tiltZ, float drop, float stagger) {
	public static final RenderStateDataKey<SufferPose> KEY = RenderStateDataKey.create(() -> "fluidgore:suffer_pose");

	public boolean isIdle() {
		return tremble < 0.01F && thrash < 0.01F && kick < 0.01F && tiltX == 0.0F && tiltZ == 0.0F && drop == 0.0F && stagger == 0.0F;
	}

	/** Smooth pseudo-random signal in about [-1, 1]; a sum of incommensurate sines. */
	public static float noise(float t, float seed) {
		return (float) (Math.sin(t * 1.7 + seed) * 0.5 + Math.sin(t * 3.1 + seed * 1.3 + 1.3) * 0.3 + Math.sin(t * 5.3 + seed * 0.7 + 2.1) * 0.2);
	}
}
