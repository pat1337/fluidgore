package dev.fluidgore.blood;

import dev.fluidgore.trigger.Fate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The wet sound of blood leaving a body. One short vanilla sound reads as a click, so a death is a soft wet thud
 * followed by phases like a real bleed (see {@link #play}), all low, overlapping vanilla sounds.
 */
public final class Gush {
	private static final RandomSource RANDOM = Fate.stream("gush");
	private static final int MAX_QUEUED = 96;
	private static final List<Pulse> QUEUE = new ArrayList<>();
	private static final SoundEvent[] CLICK = {
			SoundEvents.MUD_BREAK, SoundEvents.SLIME_BLOCK_BREAK, SoundEvents.HONEY_BLOCK_BREAK, SoundEvents.WART_BLOCK_BREAK
	};
	/** Everything here is layered, so each sound is kept well under full volume. */
	private static final float GAIN = 0.5F;
	private static final int[] RECENT = new int[8];
	private static int recentAt;
	private static int lastOoze = -1000;
	private static int now;

	private record Pulse(int at, ClientLevel level, Vec3 pos, SoundEvent sound, float volume, float pitch) {
	}

	private Gush() {
	}

	/**
	 * A rush of blood: a kill or a limb coming off ({@code strength} about 1). Built the way a real bleed goes, in
	 * phases: the wound opens (a tear and a thud), the heart pumps it out in jets that weaken and slow, it runs and
	 * drips as it pools, and for a heavy kill the animal drowns a little in its own blood. Each phase picks its own
	 * sounds, so no two deaths are quite alike.
	 */
	public static void play(ClientLevel level, Vec3 at, float strength) {
		strength = Math.min(1.0F, strength);
		if (!allow(3)) {
			return;
		}

		int t = opening(level, at, strength);
		t = pumping(level, at, strength, t);
		t = pooling(level, at, strength, t);
		if (strength >= 0.9F && RANDOM.nextInt(100) < 55) {
			gurgle(level, at, strength, t);
		}
	}

	/** The wound opens: a heavy wet thud and a tear. Now, and a moment after. Returns the tick it ends. */
	private static int opening(ClientLevel level, Vec3 at, float strength) {
		switch (RANDOM.nextInt(3)) {
			case 0 -> {
				now(level, at, SoundEvents.SLIME_BLOCK_BREAK, 0.7F * strength, 0.45F + RANDOM.nextFloat() * 0.1F);
				now(level, at, SoundEvents.WART_BLOCK_BREAK, 0.5F * strength, 0.5F);
			}
			case 1 -> {
				now(level, at, SoundEvents.SLIME_SQUISH, 0.7F * strength, 0.55F + RANDOM.nextFloat() * 0.15F);
				later(level, at, 2, SoundEvents.HONEY_BLOCK_BREAK, 0.5F * strength, 0.45F + RANDOM.nextFloat() * 0.1F);
			}
			default -> {
				now(level, at, SoundEvents.MUD_BREAK, 0.6F * strength, 0.55F);
				now(level, at, SoundEvents.GENERIC_SPLASH, 0.5F * strength, 0.55F + RANDOM.nextFloat() * 0.1F);
			}
		}

		if (RANDOM.nextInt(100) < 6) {
			// The old single click, now only a rare accent.
			now(level, at, CLICK[RANDOM.nextInt(CLICK.length)], 0.5F * strength, 0.55F + RANDOM.nextFloat() * 0.45F);
		}

		return 3;
	}

	/** The heart pumping it out: wet jets on the beat, each weaker and further from the last. Returns the tick it ends. */
	private static int pumping(ClientLevel level, Vec3 at, float strength, int start) {
		int beats = 4 + RANDOM.nextInt(4);
		int t = start;
		int gap = 6 + RANDOM.nextInt(3);
		for (int i = 0; i < beats; i++) {
			float fade = 1.0F - i / (float) beats;
			SoundEvent jet = RANDOM.nextInt(3) == 0 ? SoundEvents.SLIME_SQUISH_SMALL : SoundEvents.GENERIC_SPLASH;
			later(level, at, t, jet, (0.12F + 0.3F * fade) * strength, 0.6F + 0.25F * fade + RANDOM.nextFloat() * 0.1F);
			// The sound of it landing, a heartbeat's tail behind the jet.
			later(level, at, t + 2, SoundEvents.HONEY_DRINK.value(), (0.08F + 0.18F * fade) * strength, 0.6F + RANDOM.nextFloat() * 0.1F);
			t += gap;
			gap += 1 + RANDOM.nextInt(2);
		}

		return t;
	}

	/** It runs and pools: drips, fewer and fainter. Returns the tick it ends. */
	private static int pooling(ClientLevel level, Vec3 at, float strength, int start) {
		int drops = 3 + RANDOM.nextInt(4);
		int t = start;
		for (int i = 0; i < drops; i++) {
			t += 3 + RANDOM.nextInt(5) + i;
			SoundEvent sound = RANDOM.nextInt(4) == 0 ? SoundEvents.SLIME_SQUISH_SMALL : SoundEvents.POINTED_DRIPSTONE_DRIP_WATER;
			later(level, at, t, sound, (0.3F - 0.035F * i) * strength, 0.5F + RANDOM.nextFloat() * 0.25F);
		}

		return t;
	}

	/** Blood in the throat: wet bubbling and a last gulp. */
	private static void gurgle(ClientLevel level, Vec3 at, float strength, int start) {
		int bubbles = 3 + RANDOM.nextInt(3);
		int t = start;
		for (int i = 0; i < bubbles; i++) {
			t += 2 + RANDOM.nextInt(3);
			SoundEvent sound = RANDOM.nextInt(3) == 0 ? SoundEvents.HONEY_DRINK.value() : SoundEvents.BUBBLE_COLUMN_BUBBLE_POP;
			later(level, at, t, sound, (0.35F - 0.05F * i) * strength, 0.6F + RANDOM.nextFloat() * 0.25F);
		}

		later(level, at, t + 5, SoundEvents.SLIME_SQUISH_SMALL, 0.2F * strength, 0.5F);
	}

	/** A whole body going up in a blast: one low wet thud, then the pieces and blood coming back down as a soft patter. */
	public static void blast(ClientLevel level, Vec3 at, float volume) {
		volume = Math.min(1.0F, volume);
		if (!allow(2)) {
			return;
		}

		now(level, at, SoundEvents.SLIME_BLOCK_BREAK, 0.45F * volume, 0.4F + RANDOM.nextFloat() * 0.1F);
		now(level, at, SoundEvents.MUD_BREAK, 0.3F * volume, 0.5F);
		int drops = 8 + RANDOM.nextInt(7);
		int t = 6;
		for (int i = 0; i < drops; i++) {
			t += 1 + RANDOM.nextInt(5);
			SoundEvent sound = switch (RANDOM.nextInt(3)) {
				case 0 -> SoundEvents.SLIME_SQUISH_SMALL;
				case 1 -> SoundEvents.GENERIC_SPLASH;
				default -> SoundEvents.POINTED_DRIPSTONE_DRIP_WATER;
			};
			later(level, at.add(RANDOM.nextGaussian() * 1.2, 0.0, RANDOM.nextGaussian() * 1.2), t, sound, (0.2F - 0.01F * i) * volume,
					0.5F + RANDOM.nextFloat() * 0.3F);
		}
	}

	/** A carcass slowly weeping: one faint wet drip or press, no more often than every couple of seconds. */
	public static void ooze(ClientLevel level, Vec3 at) {
		if (now - lastOoze < 40) {
			return;
		}

		lastOoze = now;
		now(level, at, RANDOM.nextBoolean() ? SoundEvents.POINTED_DRIPSTONE_DRIP_WATER : SoundEvents.SLIME_SQUISH_SMALL, 0.22F, 0.5F + RANDOM.nextFloat() * 0.2F);
		if (RANDOM.nextInt(3) == 0) {
			later(level, at, 8 + RANDOM.nextInt(6), SoundEvents.POINTED_DRIPSTONE_DRIP_WATER, 0.15F, 0.6F);
		}
	}

	/** At most {@code max} big sounds in the last half second, so a crowd dying together is not a wall of noise. */
	private static boolean allow(int max) {
		int recent = 0;
		for (int t : RECENT) {
			if (now - t < 10) {
				recent++;
			}
		}

		if (recent >= max) {
			return false;
		}

		RECENT[recentAt++ % RECENT.length] = now;
		return true;
	}

	private static void now(ClientLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
		level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.NEUTRAL, volume * GAIN, pitch, false);
	}

	private static void later(ClientLevel level, Vec3 at, int delay, SoundEvent sound, float volume, float pitch) {
		if (QUEUE.size() >= MAX_QUEUED || volume <= 0.02F) {
			return;
		}

		Vec3 pos = at.add(RANDOM.nextGaussian() * 0.15, RANDOM.nextGaussian() * 0.1, RANDOM.nextGaussian() * 0.15);
		QUEUE.add(new Pulse(Gush.now + delay, level, pos, sound, volume, pitch));
	}

	public static void tick() {
		now++;
		Iterator<Pulse> it = QUEUE.iterator();
		while (it.hasNext()) {
			Pulse p = it.next();
			if (p.at() > now) {
				continue;
			}

			it.remove();
			p.level().playLocalSound(p.pos().x, p.pos().y, p.pos().z, p.sound(), SoundSource.NEUTRAL, p.volume() * GAIN, p.pitch(), false);
		}
	}

	public static void clear() {
		QUEUE.clear();
	}
}
