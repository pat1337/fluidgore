package dev.fluidgore.suffer;

import dev.fluidgore.config.GoreConfig;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Builds screams out of vanilla animal sounds. A scream is a schedule of short layers: the animal's hurt sound
 * pushed to the top of the pitch range, an octave-down copy under it, and a low growl from its idle sound, re-
 * triggered a few ticks apart with the pitch bending. Played this way the familiar "moo" turns into something that
 * sounds like the animal is in agony. All sounds are local to this client.
 *
 * <p>Concurrent layers are capped by {@link GoreConfig#maxScreams}. Background sounds (whimpers, herd bellows,
 * gurgles) are dropped first; pain and death screams get a little headroom above the cap.
 */
public final class ScreamManager {
	private static final int LOW = 0;
	private static final int HIGH = 1;

	private record Clip(int playAt, int priority, SoundEvent sound, Supplier<Vec3> position, float startPitch, float endPitch,
			float wobble, float wobbleRate, float volume, float fade, int life) {
	}

	private final List<Clip> scheduled = new ArrayList<>();
	private final List<ScreamSound> playing = new ArrayList<>();
	private final RandomSource random = RandomSource.create();
	private int tick;
	private int totalPlayed;

	/** Number of scream layers started since launch (used by the automated test). */
	public int totalPlayed() {
		return totalPlayed;
	}

	public int playingCount() {
		return playing.size();
	}

	/** A sharp cry when hit. Higher {@code fear} pushes it higher and longer. */
	public void pain(Supplier<Vec3> at, Voice voice, float intensity, float fear) {
		float top = 1.7F + 0.25F * Math.min(1.0F, fear + intensity * 0.2F);
		clip(0, HIGH, voice.hurt(), at, top, top + 0.15F, 0.06F, 1.3F, 1.0F, 0.3F, 12);
		clip(1, HIGH, voice.hurt(), at, top * 0.62F, top * 0.6F, 0.04F, 1.1F, 0.75F, 0.4F, 12);
		clip(2, LOW, voice.ambient(), at, 0.62F, 0.55F, 0.05F, 0.9F, 0.5F, 0.6F, 16);
		int repeats = Math.round(intensity);
		for (int i = 0; i < repeats; i++) {
			int delay = 6 + i * 7 + random.nextInt(3);
			clip(delay, HIGH, voice.hurt(), at, top + 0.05F, top - 0.35F, 0.1F, 1.6F, 0.85F - i * 0.15F, 0.5F, 14);
		}
	}

	/**
	 * Death scream. {@code length} 0..1: a quick kill gets a single cut-off cry, a drawn-out death gets a long
	 * wail of repeated cries that sag in pitch and strength as the animal gives out.
	 */
	public void death(Supplier<Vec3> at, Voice voice, float intensity, float length) {
		int cries = 1 + Math.round(length * 6.0F * Math.min(1.5F, intensity));
		int delay = 0;
		for (int i = 0; i < cries; i++) {
			float progress = cries == 1 ? 0.0F : i / (float) (cries - 1);
			float pitch = 1.95F - progress * 0.95F;
			float volume = 1.25F - progress * 0.65F;
			clip(delay, HIGH, voice.hurt(), at, pitch, pitch - 0.25F, 0.08F + progress * 0.08F, 1.4F, volume, 0.5F, 16);
			clip(delay + 1, HIGH, voice.hurt(), at, pitch * 0.55F, pitch * 0.5F, 0.05F, 1.0F, volume * 0.7F, 0.5F, 16);
			delay += 5 + random.nextInt(4) + Math.round(progress * 5.0F);
		}

		clip(delay, HIGH, voice.death(), at, 0.75F, 0.6F, 0.04F, 0.7F, 0.9F, 0.7F, 30);
	}

	/** Wet, low, failing sounds from a dying animal. */
	public void gurgle(Supplier<Vec3> at, Voice voice, float strength) {
		clip(0, LOW, voice.hurt(), at, 0.55F + random.nextFloat() * 0.08F, 0.5F, 0.06F, 2.2F, 0.25F + strength * 0.35F, 0.8F, 18);
	}

	/** Quiet, shaky crying of a badly wounded animal. */
	public void whimper(Supplier<Vec3> at, Voice voice) {
		float pitch = 1.55F + random.nextFloat() * 0.25F;
		clip(0, LOW, voice.ambient(), at, pitch, pitch - 0.2F, 0.09F, 0.9F, 0.35F, 0.6F, 20);
	}

	/** A panicked call from an animal watching others being slaughtered. */
	public void bellow(Supplier<Vec3> at, Voice voice, float fear) {
		float pitch = 1.4F + fear * 0.5F + random.nextFloat() * 0.1F;
		float volume = 0.55F + fear * 0.45F;
		clip(0, LOW, voice.ambient(), at, pitch, pitch + 0.1F, 0.12F, 1.2F, volume, 0.3F, 20);
		if (fear > 0.6F && random.nextBoolean()) {
			clip(7 + random.nextInt(5), LOW, voice.ambient(), at, pitch + 0.1F, pitch - 0.3F, 0.15F, 1.5F, volume * 0.8F, 0.5F, 20);
		}
	}

	/** The weak last sounds of a severed head. */
	public void moan(Supplier<Vec3> at, Voice voice, float strength) {
		clip(0, LOW, voice.ambient(), at, 0.5F + random.nextFloat() * 0.1F, 0.5F, 0.05F, 0.6F, 0.2F + 0.35F * strength, 0.8F, 24);
	}

	private void clip(int delay, int priority, SoundEvent sound, Supplier<Vec3> position, float startPitch, float endPitch,
			float wobble, float wobbleRate, float volume, float fade, int life) {
		scheduled.add(new Clip(tick + delay, priority, sound, position, startPitch, endPitch, wobble, wobbleRate, volume, fade, life));
	}

	public void tick() {
		tick++;
		var sounds = Minecraft.getInstance().getSoundManager();
		playing.removeIf(s -> s.isStopped() || !sounds.isActive(s));

		GoreConfig config = GoreConfig.get();
		float master = config.screamVolume / 100.0F;
		Iterator<Clip> it = scheduled.iterator();
		while (it.hasNext()) {
			Clip clip = it.next();
			if (clip.playAt() > tick) {
				continue;
			}

			it.remove();
			int limit = clip.priority() == HIGH ? config.maxScreams + 4 : config.maxScreams;
			if (master <= 0.0F || playing.size() >= limit) {
				continue;
			}

			ScreamSound sound = new ScreamSound(clip.sound(), clip.position(), clip.startPitch(), clip.endPitch(), clip.wobble(),
					clip.wobbleRate(), clip.volume() * master, clip.fade(), clip.life(), random);
			sounds.play(sound);
			playing.add(sound);
			totalPlayed++;
		}
	}

	public void clear() {
		scheduled.clear();
		var sounds = Minecraft.getInstance().getSoundManager();
		for (ScreamSound sound : playing) {
			sounds.stop(sound);
		}

		playing.clear();
	}
}
