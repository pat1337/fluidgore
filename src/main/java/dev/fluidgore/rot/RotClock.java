package dev.fluidgore.rot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * How far one body has rotted, counted up as it goes. Common code: the server keeps one on every kept body (saved with
 * it, {@code server/KeptBodies.ROTTED}) and the client counts on from it ({@link Decay}). No client classes here.
 *
 * <p>Rot is counted in decay-days (see {@link Rot}): each stretch of time adds its own days at the heat and wet of
 * where the body lay through it, so a body carried from the plains into the snow stops where it got to, and one pulled
 * out of a river keeps the rot it gained there. Nothing ever takes days away.
 *
 * <p>Whether a body mummifies ({@link #arid}) is settled while it is fresh: until it starts to bloat it follows where
 * it lies, and from then on it is too late to change course. A body that was swelling in the plains bursts in the
 * desert all the same, and a mummy carried into the rain stays a mummy.
 *
 * @param days  decay-days so far
 * @param arid  drying into a mummy rather than liquefying
 * @param game  the game time it was counted up to
 * @param clock the overworld's day clock then
 */
public record RotClock(float days, boolean arid, long game, long clock) {
	public static final float DAY = 24000.0F;
	public static final Codec<RotClock> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.FLOAT.fieldOf("days").forGetter(RotClock::days),
			Codec.BOOL.fieldOf("arid").forGetter(RotClock::arid),
			Codec.LONG.fieldOf("game").forGetter(RotClock::game),
			Codec.LONG.fieldOf("clock").forGetter(RotClock::clock)).apply(i, RotClock::new));
	private static final float PIG_VOLUME = 0.9F * 0.9F * 0.9F;

	/** A body that died at this game time and day clock, not counted yet. */
	public static RotClock died(long game, long clock) {
		return new RotClock(0.0F, false, game, clock);
	}

	/**
	 * Counted on to {@code game} / {@code clock}: the ticks since it was last counted, by the game clock or the day
	 * clock, whichever has gone further (sleeping and {@code /time add} move only the day clock; a day clock turned
	 * back adds nothing), at {@code rate} decay-days a day, for a body {@code size} times as slow as a pig.
	 * {@code aridHere} is whether where it lies now is dry heat.
	 */
	public RotClock to(long game, long clock, float rate, float size, boolean aridHere) {
		long ticks = Math.max(0L, Math.max(game - this.game, clock - this.clock));
		return new RotClock(days + ticks / DAY * Math.max(0.0F, rate) / size, arid(arid, days, aridHere), game, clock);
	}

	/** Whether a body {@code days} rotten that was {@code arid} is arid now, lying somewhere {@code aridHere}. */
	public static boolean arid(boolean arid, float days, boolean aridHere) {
		// A compile-time constant, so a dedicated server never loads Rot (it holds a client render key).
		return days < Rot.BLOAT ? aridHere : arid;
	}

	/**
	 * How swollen the trunk of a body {@code days} rotten is: up to 1 at the height of the bloat, then it bursts and goes
	 * down at once, and sinks below its living size as the flesh goes (negative). See {@link Rot#swell}.
	 */
	public static float swell(float days, boolean arid) {
		if (arid) {
			return 0.35F * ramp(days, Rot.BLOAT, 3.0F) - 0.65F * ramp(days, 3.0F, Rot.ADVANCED);
		}

		if (days < Rot.ACTIVE) {
			return ramp(days, Rot.BLOAT, Rot.ACTIVE - 0.5F);
		}

		return 1.0F - 1.2F * ramp(days, Rot.ACTIVE, Rot.ACTIVE + 1.5F) - 0.15F * ramp(days, Rot.ADVANCED, Rot.DRY);
	}

	/** Smoothstep of {@code x} from {@code from} to {@code to} (as Rot's; Rot itself is not loaded on a server). */
	private static float ramp(float x, float from, float to) {
		float t = Mth.clamp((x - from) / (to - from), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	/** Decay-days per day at {@code pos}: degree-days over 20, from the biome's temperature, halved in water. */
	public static float rate(Level level, BlockPos pos, boolean wet) {
		float rate = Mth.clamp(celsius(level, pos), 0.0F, 40.0F) / 20.0F;
		return wet ? rate * 0.5F : rate;
	}

	/** Dry heat: no rain and warm. Bodies there mummify. */
	public static boolean arid(Level level, BlockPos pos, boolean wet) {
		Biome biome = level.getBiome(pos).value();
		return !wet && !biome.hasPrecipitation() && celsius(level, pos) > 20.0F;
	}

	private static float celsius(Level level, BlockPos pos) {
		return level.getBiome(pos).value().getBaseTemperature() * 25.0F - 5.0F;
	}

	/** How much longer than a pig a body of {@code volume} cubic blocks takes to rot. */
	public static float size(float volume) {
		return Mth.clamp(Mth.sqrt(volume / PIG_VOLUME), 0.3F, 3.0F);
	}
}
