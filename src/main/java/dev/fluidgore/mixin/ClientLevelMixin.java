package dev.fluidgore.mixin;

import dev.fluidgore.blood.BrokenBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change the client applies (from the server, or predicted while mining) ends up here. A block that
 * loses its collision is recorded so blood, flesh and parts resting on it fall straight away.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void fluidgore$onBlockChanged(BlockPos pos, BlockState old, BlockState current, int flags, CallbackInfo ci) {
		if (old != current && !old.isAir()) {
			ClientLevel self = (ClientLevel) (Object) this;
			if (!old.getCollisionShape(self, pos).isEmpty() && current.getCollisionShape(self, pos) != old.getCollisionShape(self, pos)) {
				BrokenBlocks.add(pos);
			}
		}
	}
}
