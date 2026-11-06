package dev.fluidgore;

import dev.fluidgore.config.GoreConfig;
import dev.fluidgore.server.BurningBodies;
import dev.fluidgore.server.EvidenceStore;
import dev.fluidgore.server.HitReports;
import dev.fluidgore.server.KeptBodies;
import dev.fluidgore.net.VesselBurstPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint, run on the server and the client. Everything you see is client-side ({@link FluidGoreClient});
 * this sets up the server half ({@link KeptBodies}): bodies that stay, and butchery every player sees. A client on a
 * server without Fluid Gore works as before, on its own. Fluid Gore registers no blocks, items or entities, so it never
 * forces the mod on anyone who joins; registered content (the Cobble Tank) lives in separate add-ons.
 */
public class FluidGore implements ModInitializer {
	public static final String MOD_ID = "fluidgore";
	public static final Logger LOGGER = LoggerFactory.getLogger("Fluid Gore");

	@Override
	public void onInitialize() {
		GoreConfig.load();
		KeptBodies.register();
		BurningBodies.register();
		EvidenceStore.register();
		HitReports.register();
		// Sent by add-ons that make pressure vessels; registered here so any of them can use it.
		PayloadTypeRegistry.clientboundPlay().register(VesselBurstPayload.TYPE, VesselBurstPayload.CODEC);
	}
}
