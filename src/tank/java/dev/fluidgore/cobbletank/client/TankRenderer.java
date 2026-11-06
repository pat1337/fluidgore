package dev.fluidgore.cobbletank.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.fluidgore.cobbletank.TankBlock;
import dev.fluidgore.cobbletank.TankEntity;
import dev.fluidgore.cobbletank.CobbleTank;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;

/** A flying tank: the block's own model, turned about its centre as it tumbles. */
public class TankRenderer extends EntityRenderer<TankEntity, TankRenderer.State> {
	public static final class State extends EntityRenderState {
		final MovingBlockRenderState block = new MovingBlockRenderState();
		final Quaternionf rotation = new Quaternionf();
	}

	public TankRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.5F;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(TankEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		BlockPos pos = BlockPos.containing(entity.getX(), entity.getBoundingBox().maxY, entity.getZ());
		state.block.randomSeedPos = entity.blockPosition();
		state.block.blockPos = pos;
		state.block.blockState = CobbleTank.BLOCK.defaultBlockState().setValue(TankBlock.FACING, entity.top()).setValue(TankBlock.SCARS, entity.scars());
		if (entity.level() instanceof ClientLevel level) {
			state.block.biome = level.getBiome(pos);
			state.block.cardinalLighting = level.cardinalLighting();
			state.block.lightEngine = level.getLightEngine();
		}

		state.rotation.set(entity.rotation());
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		poseStack.pushPose();
		poseStack.translate(0.0, 0.5, 0.0);
		poseStack.rotate(state.rotation);
		poseStack.translate(-0.5, -0.5, -0.5);
		collector.submitMovingBlock(poseStack, state.block, state.outlineColor);
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
