package dev.fluidgore;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.server.EvidenceStore;
import dev.fluidgore.server.HitReports;
import dev.fluidgore.server.KeptBodies;
import dev.fluidgore.tank.Tanks;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint, run on the server and the client. Everything you see is client-side ({@link FluidGoreClient});
 * this sets up the server half ({@link KeptBodies}): bodies that stay, and butchery every player sees. A client on a
 * server without Fluid Gore works as before, on its own, except in a world with the pressure vessel block
 * ({@link Tanks}), which needs the mod on both sides.
 */
public class FluidGore implements ModInitializer {
	public static final String MOD_ID = "fluidgore";
	public static final Logger LOGGER = LoggerFactory.getLogger("Fluid Gore");

	@Override
	public void onInitialize() {
		GoreConfig.load();
		KeptBodies.register();
		EvidenceStore.register();
		HitReports.register();
		Tanks.register();
	}
}
