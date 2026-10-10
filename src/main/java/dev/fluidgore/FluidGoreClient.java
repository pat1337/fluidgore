package dev.fluidgore;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.demo.DemoPlayer;
import dev.fluidgore.render.GoreRenderer;
import dev.fluidgore.ui.GoreCommand;
import dev.fluidgore.ui.OptionsScreenButton;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client entrypoint. Fluid Gore is client-only: it adds no blocks, items, entities, packets or registry entries. */
public class FluidGoreClient implements ClientModInitializer {
	public static final String MOD_ID = "fluidgore";
	public static final Logger LOGGER = LoggerFactory.getLogger("Fluid Gore");

	@Override
	public void onInitializeClient() {
		GoreConfig.load();

		ClientTickEvents.END_CLIENT_TICK.register(GoreSystems::tick);
		ClientTickEvents.END_CLIENT_TICK.register(DemoPlayer::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> GoreCommand.register(dispatcher));
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || DemoPlayer.allowMessage(message));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> GoreSystems.onChunkUnload(chunk.getPos().x(), chunk.getPos().z()));
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> GoreSystems.onEntityUnload(entity.getId()));

		GoreRenderer.register();
		OptionsScreenButton.register();
		LOGGER.info("Fluid Gore loaded (gore level {})", GoreConfig.get().goreLevel);
	}
}
