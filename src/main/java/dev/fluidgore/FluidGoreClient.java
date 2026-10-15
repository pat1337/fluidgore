package dev.fluidgore;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.demo.DemoPlayer;
import dev.fluidgore.net.Evidence;
import dev.fluidgore.net.GoreSync;
import dev.fluidgore.net.PieceSync;
import dev.fluidgore.player.HurtScreen;
import dev.fluidgore.render.GoreRenderer;
import dev.fluidgore.ui.GoreCommand;
import dev.fluidgore.ui.OptionsScreenButton;
import dev.fluidgore.ui.SortButtons;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import org.slf4j.Logger;

/**
 * Client entrypoint: all of the gore. It adds no blocks, items, entities or registry entries; its one packet
 * ({@link GoreSync}) is only used on a server that runs Fluid Gore too (see {@link FluidGore}).
 */
public class FluidGoreClient implements ClientModInitializer {
	public static final Logger LOGGER = FluidGore.LOGGER;

	@Override
	public void onInitializeClient() {
		GoreSync.register();
		PieceSync.register();
		Evidence.register();

		ClientTickEvents.END_CLIENT_TICK.register(GoreSystems::tick);
		ClientTickEvents.END_CLIENT_TICK.register(DemoPlayer::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> GoreCommand.register(dispatcher));
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || DemoPlayer.allowMessage(message));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> GoreSystems.onChunkUnload(chunk.getPos().x(), chunk.getPos().z()));
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> GoreSystems.onEntityUnload(entity.getId()));

		GoreRenderer.register();
		HurtScreen.register();
		OptionsScreenButton.register();
		SortButtons.register();
		LOGGER.info("Fluid Gore loaded (gore level {})", GoreConfig.get().goreLevel);
	}
}
