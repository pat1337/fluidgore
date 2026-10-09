package dev.fluidgore.suffer;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

/**
 * Where a ragdolled body is this frame, relative to where vanilla would draw the entity, plus its lean and joint
 * offsets. Only dead bodies have one.
 *
 * @param dx     offset from the entity's own interpolated position, in blocks
 * @param roll   sideways lean in radians (0 upright, +-PI/2 lying)
 * @param joints interpolated joint offsets, see {@link Ragdoll}
 */
public record BodyPose(double dx, double dy, double dz, float roll, float[] joints) {
	public static final RenderStateDataKey<BodyPose> KEY = RenderStateDataKey.create(() -> "fluidgore:body_pose");
}
