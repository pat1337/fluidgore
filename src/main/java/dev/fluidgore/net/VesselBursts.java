package dev.fluidgore.net;

import dev.fluidgore.GoreSystems;
import dev.fluidgore.event.Events;
import dev.fluidgore.fragment.FragmentEffects;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * The client half of a pressure-vessel burst reported by the server ({@link VesselBurstPayload}): the fragments are
 * played on the drawn anatomy and the burst is handed to the vessel gore ({@code trigger/PressureVessel}), so a burst
 * looks and feels the same whatever made it. An add-on that sends bursts can listen first (the Cobble Tank clears its
 * block, which would otherwise shade every body from the wave).
 */
public final class VesselBursts {
	/** Beyond this the server sends no explosion packet: the burst is played from its message alone. */
	private static final double NO_EXPLOSION_PACKET = 60.0;
	private static final List<Consumer<VesselBurstPayload>> LISTENERS = new CopyOnWriteArrayList<>();

	private VesselBursts() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(VesselBurstPayload.TYPE, (payload, context) -> burst(payload));
	}

	/** Runs before the gore of every accepted burst. */
	public static void listen(Consumer<VesselBurstPayload> listener) {
		LISTENERS.add(listener);
	}

	private static void burst(VesselBurstPayload payload) {
		Minecraft mc = Minecraft.getInstance();
		if (!payload.finite() || !payload.validWave() || mc.player == null) {
			return;
		}

		Int2IntOpenHashMap hits = new Int2IntOpenHashMap();
		for (var path : payload.paths()) if (path.contact() != null) hits.addTo(path.contact().entityId(), 1);

		Vec3 centre = new Vec3(payload.x(), payload.y(), payload.z());
		long event = Events.id(Events.Source.COMPRESSED_GAS, centre, payload.litres() * payload.psi());
		if (!FragmentEffects.accept(centre, payload.fragmentSeed(), payload.occurrence(), event, Events.Source.COMPRESSED_GAS, payload.paths())) {
			return;
		}

		for (Consumer<VesselBurstPayload> listener : LISTENERS) {
			listener.accept(payload);
		}

		GoreSystems.tracker().vessels().told(centre, payload.litres(), payload.psi(), payload.waveKg(), hits);
		if (mc.player.position().distanceTo(centre) >= NO_EXPLOSION_PACKET) {
			GoreSystems.tracker().onExplosion(centre, payload.radius());
		}
	}
}
