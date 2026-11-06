package dev.fluidgore.wound;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * Vanilla's on-fire flames, moved onto a body lying where its ragdoll fell instead of hanging in the air at the
 * entity's own spot, and sized to it: as wide as the body lies long, as tall as the fire on it is fierce.
 *
 * @param dx     from where vanilla would draw the flames to the middle of the body, in blocks
 * @param width  how wide the sheet of flames is, in blocks
 * @param height how tall, in blocks
 */
public record BodyFlames(double dx, double dy, double dz, float width, float height) {
	public static final RenderStateDataKey<BodyFlames> KEY = RenderStateDataKey.create(() -> "fluidgore:body_flames");

	/**
	 * A render state to give vanilla's flame renderer: it reads only the box and the light, and keeps the state until it
	 * draws, so the body's own state cannot be changed for it.
	 */
	public EntityRenderState state(EntityRenderState of) {
		EntityRenderState state = new EntityRenderState();
		state.entityType = of.entityType;
		state.boundingBoxWidth = width;
		// One layer of flames: the sheet is squashed to {@link #height} where it is drawn (see {@link #sheetHeight}).
		state.boundingBoxHeight = 0.01F;
		state.lightCoords = of.lightCoords;
		return state;
	}

	/** How tall vanilla draws one layer of its flame sheet for a box {@code width} wide: 1.4 times its scale, 1.4 times the width. */
	public static float sheetHeight(float width) {
		return Math.max(1.0E-3F, width * 1.4F * 1.4F);
	}
}
