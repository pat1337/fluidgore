package dev.fluidgore.trigger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * Shared dice for multiplayer, with no server mod and no packets. Every client on a server is told the same facts
 * about a hit: who was hit (UUID), who hit them, and the victim's exact health afterwards. Each hit's dice are seeded
 * from those facts, so every player running the mod rolls the same numbers and sees the same wound, the same severed
 * part and the same death, without the clients ever talking to each other.
 * <p>
 * Each system that decides what a hit does keeps its own {@link #stream}: all streams are reseeded at the start of
 * every hit, so one system drawing more numbers than usual on one client (a ray that just missed, say) cannot shift
 * the dice of another. Between hits the streams run on unseeded, which is fine for drips and particles. Anything that
 * plays out over time on a body (death throes) uses that body's own dice from {@link #body}.
 */
public final class Fate {
	private static final List<Stream> STREAMS = new ArrayList<>();
	private static long seed;
	private static boolean active;

	private record Stream(RandomSource random, long salt) {
	}

	private Fate() {
	}

	/** A dice stream for one system, named so its numbers differ from every other system's. */
	public static RandomSource stream(String name) {
		Stream stream = new Stream(RandomSource.create(), name.hashCode() * 0x9E3779B97F4A7C15L);
		if (active) {
			// First used in the middle of a hit: seeded now, so it matches clients that made it earlier.
			stream.random().setSeed(scramble(seed ^ stream.salt()));
		}

		STREAMS.add(stream);
		return stream.random();
	}

	/** Seeds every stream for a hit on {@code victim}. Call {@link #end} when the hit has been handled. */
	public static void begin(LivingEntity victim, @Nullable Entity attacker) {
		long s = mix(victim.getUUID());
		s = s * 31 + Float.floatToIntBits(victim.getHealth());
		s = s * 31 + (attacker != null ? mix(attacker.getUUID()) : 0L);
		begin(s);
	}

	/** Seeds every stream from a value that every client knows the same way. */
	public static void begin(long hitSeed) {
		seed = scramble(hitSeed);
		active = true;
		for (Stream stream : STREAMS) {
			stream.random().setSeed(scramble(seed ^ stream.salt()));
		}
	}

	public static void end() {
		active = false;
	}

	/**
	 * Dice for everything a body does from now until it is gone. Inside a hit they come from the hit's seed, so the
	 * killing blow decides the throes the same way on every client; otherwise from the body's UUID.
	 */
	public static RandomSource body(LivingEntity entity) {
		long base = active ? seed : scramble(mix(entity.getUUID()));
		return RandomSource.create(scramble(base ^ 0x5DEECE66DL));
	}

	private static long mix(UUID id) {
		return id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 29);
	}

	/** SplitMix64's finaliser: nearby inputs give unrelated outputs. */
	private static long scramble(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
