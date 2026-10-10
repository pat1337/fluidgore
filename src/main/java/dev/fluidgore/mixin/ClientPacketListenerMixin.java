package dev.fluidgore.mixin;

import dev.fluidgore.GoreSystems;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The explosion packet is the only place the client learns how big a blast was (TNT, a creeper, a bed). The damage
 * events it causes arrive in the same tick, so each body can be torn by how close it was and how much of the blast
 * reached it. TAIL: the first call on the network thread bails out before it, so this runs once, on the main thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleExplosion", at = @At("TAIL"))
	private void fluidgore$onExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
		GoreSystems.tracker().onExplosion(packet.center(), packet.radius());
	}
}
