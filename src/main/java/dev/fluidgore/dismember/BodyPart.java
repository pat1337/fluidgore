package dev.fluidgore.dismember;

/**
 * Severable parts. Each maps to the vanilla model-part names used across humanoid, quadruped and bird models;
 * the first name that exists in a given model is used, so most vanilla and many modded mobs work unchanged.
 */
public enum BodyPart {
	HEAD(0.95F, "head"),
	RIGHT_ARM(0.7F, "right_arm", "right_front_leg", "right_wing"),
	LEFT_ARM(0.7F, "left_arm", "left_front_leg", "left_wing"),
	RIGHT_LEG(0.3F, "right_leg", "right_hind_leg"),
	LEFT_LEG(0.3F, "left_leg", "left_hind_leg");

	/** Rough height of the stump as a fraction of the entity's height, used for stump bleeding. */
	public final float stumpHeight;
	public final String[] modelNames;

	BodyPart(float stumpHeight, String... modelNames) {
		this.stumpHeight = stumpHeight;
		this.modelNames = modelNames;
	}

	public int bit() {
		return 1 << ordinal();
	}
}
