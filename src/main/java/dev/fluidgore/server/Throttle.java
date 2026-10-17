package dev.fluidgore.server;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * How much one player may send of one kind of packet: a bucket that holds {@code burst} and fills by {@code perSecond}.
 * A player's own game stays far under it (a carcass diced into twenty pieces, each streamed as it falls, is a few
 * hundred packets); what goes over is dropped, so one game cannot flood the server or every player around it.
 * Server thread only.
 */
final class Throttle {
	private final double burst;
	private final double perTick;
	private final Map<ServerPlayer, double[]> buckets = new WeakHashMap<>();

	Throttle(double burst, double perSecond) {
		this.burst = burst;
		this.perTick = perSecond / 20.0;
	}

	/** True if {@code player} may send {@code cost} more now (it is then taken off), false to drop it. */
	boolean take(ServerPlayer player, double cost) {
		long now = player.level().getGameTime();
		// [tokens, game time last filled]
		double[] bucket = buckets.computeIfAbsent(player, p -> new double[]{burst, now});
		bucket[0] = Math.min(burst, bucket[0] + Math.max(0L, now - (long) bucket[1]) * perTick);
		bucket[1] = now;
		if (bucket[0] < cost) {
			return false;
		}

		bucket[0] -= cost;
		return true;
	}

	void clear() {
		buckets.clear();
	}
}
