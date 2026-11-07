package dev.fluidgore.net;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * This game's side of leading kept bodies' falls (see {@code server/FallLeads}): which bodies the server has asked it to
 * lead, and the reports of where their ragdolls got to ({@code ragdoll/Rigging.lead}).
 */
public final class FallSync {
	/** Entity ids of the kept bodies whose falls this game leads. */
	private static final IntOpenHashSet LEADS = new IntOpenHashSet();

	private FallSync() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(BodyLead.TYPE, (lead, context) -> {
			if (lead.lead()) {
				LEADS.add(lead.entityId());
			} else {
				LEADS.remove(lead.entityId());
			}
		});
	}

	/** True if this game leads the fall of the kept body {@code entityId}. */
	public static boolean leads(int entityId) {
		return LEADS.contains(entityId);
	}

	/** Tells the server where the ragdoll of a body this game leads is. False if it could not be sent. */
	public static boolean report(int entityId, double x, double y, double z, boolean resting) {
		if (!ClientPlayNetworking.canSend(BodyFall.TYPE)) {
			return false;
		}

		ClientPlayNetworking.send(new BodyFall(entityId, x, y, z, resting));
		return true;
	}

	public static void clear() {
		LEADS.clear();
	}
}
